package com.example.data.repository

import android.content.Context
import androidx.room.withTransaction
import com.example.data.local.TvBuddyDatabase
import com.example.data.local.entity.ChannelEntity
import com.example.data.local.entity.ChannelGroupEntity
import com.example.data.local.entity.EpgSourceEntity
import com.example.data.local.entity.HistoryEntity
import com.example.data.local.entity.PlaylistEpgCrossRef
import com.example.data.local.entity.PlaylistEntity
import com.example.data.local.entity.ProgramEntity
import kotlinx.coroutines.flow.flatMapLatest
import com.example.data.model.ContentType
import com.example.data.model.ImportCounts
import com.example.data.model.VodInfo
import com.example.data.model.PlaylistType
import com.example.data.model.SpoofPreset
import com.example.data.model.SpoofProfile
import com.example.data.model.parseHeadersJson
import com.example.data.model.toSpoofProfile
import com.example.data.network.StalkerAuthResult
import com.example.data.network.StalkerClient
import com.example.data.network.StalkerParams
import com.example.data.network.XtreamClient
import com.example.data.parser.JsonPlaylistParser
import com.example.data.parser.M3uParser
import com.example.data.parser.XmltvParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.BufferedInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

class TvBuddyRepository(
    private val context: Context,
    private val database: TvBuddyDatabase = TvBuddyDatabase.getDatabase(context),
    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build(),
    private val xtreamClient: XtreamClient = XtreamClient(okHttpClient),
    private val stalkerClient: StalkerClient = StalkerClient(okHttpClient)
) {
    private val playlistDao = database.playlistDao()
    private val channelDao = database.channelDao()
    private val historyDao = database.historyDao()
    private val programDao = database.programDao()
    private val epgSourceDao = database.epgSourceDao()
    private val channelGroupDao = database.channelGroupDao()

    private val epgScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val epgPrefs by lazy { context.getSharedPreferences("tvbuddy_epg", Context.MODE_PRIVATE) }

    val allPlaylists: Flow<List<PlaylistEntity>> = playlistDao.getAllPlaylists()
    val allChannels: Flow<List<ChannelEntity>> = channelDao.getAllChannels()
    val favoriteChannels: Flow<List<ChannelEntity>> = channelDao.getFavoriteChannels()
    val allCategories: Flow<List<String>> = channelDao.getAllCategories()
    val playbackHistory: Flow<List<HistoryEntity>> = historyDao.getRecentHistory()
    val allCurrentPrograms: Flow<List<ProgramEntity>> = programDao.getAllCurrentPrograms()

    /** Programs from a few hours ago up to ~a day ahead (TV guide / schedule panels). Window is re-evaluated every 30 min. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val epgWindowPrograms: Flow<List<ProgramEntity>> =
        kotlinx.coroutines.flow.flow {
            while (true) { emit(Unit); kotlinx.coroutines.delay(30 * 60_000L) }
        }.flatMapLatest {
            val now = System.currentTimeMillis()
            programDao.getProgramsInRange(now - 4 * 3_600_000L, now + 30 * 3_600_000L)
        }
    val allEpgSources: Flow<List<EpgSourceEntity>> = epgSourceDao.getAllEpgSources()
    val allPlaylistEpgCrossRefs: Flow<List<PlaylistEpgCrossRef>> = epgSourceDao.getAllPlaylistEpgCrossRefs()
    val allChannelGroups: Flow<List<ChannelGroupEntity>> = channelGroupDao.getAllGroups()
    /** Groups of live TV only: movie / show groups never take part in the channel list. */
    val liveChannelGroups: Flow<List<ChannelGroupEntity>> = channelGroupDao.getLiveGroups()

    private fun startPlaylistEpgImport(playlistId: Long, urls: List<String>, userAgent: String) {
        if (urls.isEmpty()) return
        epgPrefs.edit().putStringSet("urls_$playlistId", urls.toSet()).apply()
        epgScope.launch {
            val plName = playlistDao.getPlaylistById(playlistId)?.name
            urls.forEachIndexed { i, url ->
                // Name the source after the playlist it came from so it is recognisable when assigning it to other playlists
                val label = when {
                    plName.isNullOrBlank() -> url.substringAfterLast("/").ifBlank { "EPG Source" }
                    urls.size > 1 -> "$plName EPG ${i + 1}"
                    else -> "$plName EPG"
                }
                val srcId = addEpgSource(url, label)
                togglePlaylistEpgAssignment(playlistId, srcId, true)
            }
            fetchEpgUrls(playlistId, urls, userAgent)
        }
    }

    private suspend fun fetchEpgUrls(playlistId: Long, urls: List<String>, userAgent: String) {
        var anyOk = false
        for (u in urls) {
            if (importXmltv(u, false, userAgent).isSuccess) anyOk = true
        }
        if (anyOk) epgPrefs.edit().putLong("ts_$playlistId", System.currentTimeMillis()).apply()
    }

    fun refreshStaleEpgs(userAgent: String) {
        epgScope.launch {
            val now = System.currentTimeMillis()
            for (p in playlistDao.getAllPlaylists().first()) {
                val urls = epgPrefs.getStringSet("urls_${p.id}", null)?.toList() ?: continue
                if (now - epgPrefs.getLong("ts_${p.id}", 0L) > 12L * 60 * 60 * 1000) {
                    fetchEpgUrls(p.id, urls, userAgent)
                }
            }
        }
    }

    suspend fun initializeDefaultsIfNeeded() = withContext(Dispatchers.IO) {
        playlistDao.getAllPlaylists().first()
            .filter { it.sourceUrl == "local://curated_demo.m3u" }
            .forEach { demo ->
                channelDao.deleteChannelsByPlaylist(demo.id)
                channelGroupDao.deleteGroupsByPlaylist(demo.id)
                playlistDao.deletePlaylist(demo)
            }
    }

    // ---- Group Management helpers ----
    /**
     * Makes sure every group of [channels] has a row (per content type: TV, movies, shows). Existing rows keep
     * their visibility and manual order; new groups follow "Show newly added groups".
     */
    suspend fun syncGroupsForPlaylist(playlistId: Long, channels: List<ChannelEntity>, showNewlyAdded: Boolean) = withContext(Dispatchers.IO) {
        syncGroupTitles(
            playlistId,
            channels.groupBy { it.contentType }.mapValues { (_, list) -> list.map { it.groupTitle } },
            showNewlyAdded
        )
    }

    /** Same as [syncGroupsForPlaylist] from group titles per content type, in the order they should appear. */
    suspend fun syncGroupTitles(playlistId: Long, titlesByType: Map<String, List<String>>, showNewlyAdded: Boolean) = withContext(Dispatchers.IO) {
        val existing = channelGroupDao.getGroupsByPlaylistOnce(playlistId).associateBy { it.contentType to it.groupTitle }
        val rows = ArrayList<ChannelGroupEntity>()
        titlesByType.forEach { (type, titles) ->
            titles.filter { it.isNotBlank() }.distinct().forEachIndexed { index, title ->
                val old = existing[type to title]
                rows += old?.copy(originalOrder = index) ?: ChannelGroupEntity(
                    playlistId = playlistId,
                    groupTitle = title,
                    contentType = type,
                    isVisible = showNewlyAdded,
                    originalOrder = index,
                    sortOrder = index
                )
            }
        }
        rows.chunked(1000).forEach { channelGroupDao.insertGroups(it) }
    }

    /** Inserts many channels in one transaction so the channel list behind the UI is rebuilt once, not per chunk. */
    private suspend fun insertChannelsBulk(channels: List<ChannelEntity>) {
        if (channels.isEmpty()) return
        database.withTransaction { channels.chunked(1000).forEach { channelDao.insertChannels(it) } }
    }

    private fun countsOf(channels: List<ChannelEntity>) = ImportCounts(
        tv = channels.count { it.contentType == ContentType.TV },
        movies = channels.count { it.contentType == ContentType.MOVIE },
        shows = channels.count { it.contentType == ContentType.SHOW }
    )

    /** Epoch seconds (Xtream) and yyyy-MM-dd dates (Stalker) become dd/MM/yy; any other text is shown as reported. */
    private fun formatExpiration(raw: String?): String? {
        val v = raw?.trim().orEmpty()
        if (v.isEmpty() || v.equals("null", true) || v.equals("unknown", true) || v == "0") return null
        val epoch = v.toLongOrNull()
        if (epoch != null) {
            return if (v.length >= 9) SimpleDateFormat("dd/MM/yy", Locale.US).format(Date(epoch * 1000L)) else null
        }
        Regex("^(\\d{4})-(\\d{2})-(\\d{2})").find(v)?.let { m ->
            return "${m.groupValues[3]}/${m.groupValues[2]}/${m.groupValues[1].takeLast(2)}"
        }
        return v
    }

    private fun PlaylistEntity.toStalkerParams() = StalkerParams(
        mac = macAddress.orEmpty(),
        username = username.orEmpty(),
        password = password.orEmpty(),
        deviceId = deviceId.orEmpty(),
        deviceId2 = deviceId2.orEmpty(),
        serial = serialNumber.orEmpty()
    )

    /** Result of adding a playlist: what the "Playlist is processed" screen shows. */
    data class ImportResult(val playlistId: Long, val name: String, val counts: ImportCounts)

    // ---- EPG Source & Import management ----
    suspend fun addEpgSource(url: String, name: String = url.substringAfterLast("/").ifBlank { "EPG Source" }): Long = withContext(Dispatchers.IO) {
        val existing = epgSourceDao.getEpgSourceByUrl(url)
        if (existing != null) return@withContext existing.id
        val isFirst = epgSourceDao.getAllEpgSources().first().isEmpty()
        val src = EpgSourceEntity(name = name, url = url, isDefault = isFirst)
        epgSourceDao.insertEpgSource(src)
    }

    suspend fun deleteEpgSource(source: EpgSourceEntity) = withContext(Dispatchers.IO) {
        epgSourceDao.deleteEpgSource(source)
    }

    suspend fun setDefaultEpgSource(id: Long) = withContext(Dispatchers.IO) {
        epgSourceDao.setDefaultSource(id)
    }

    suspend fun togglePlaylistEpgAssignment(playlistId: Long, epgSourceId: Long, assign: Boolean) = withContext(Dispatchers.IO) {
        if (assign) {
            val count = epgSourceDao.getCrossRefsForPlaylist(playlistId).first().size
            epgSourceDao.insertPlaylistEpgCrossRef(PlaylistEpgCrossRef(playlistId, epgSourceId, priority = count))
        } else {
            epgSourceDao.removeEpgSourceFromPlaylist(playlistId, epgSourceId)
        }
    }

    suspend fun reorderPlaylistEpgPriority(playlistId: Long, orderedSourceIds: List<Long>) = withContext(Dispatchers.IO) {
        epgSourceDao.clearAssignedSourcesForPlaylist(playlistId)
        orderedSourceIds.forEachIndexed { index, sourceId ->
            epgSourceDao.insertPlaylistEpgCrossRef(PlaylistEpgCrossRef(playlistId, sourceId, priority = index))
        }
    }

    suspend fun importXmltv(
        urlOrContent: String,
        isRawText: Boolean,
        userAgent: String,
        pastDaysToKeep: Int = 3,
        storeDescriptions: Boolean = true
    ): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val minTime = if (pastDaysToKeep > 0) {
                System.currentTimeMillis() - (pastDaysToKeep.toLong() * 24L * 60L * 60L * 1000L)
            } else 0L

            val programs = if (isRawText) {
                XmltvParser.parseString(urlOrContent, storeDescriptions, minTime)
            } else {
                val req = Request.Builder().url(urlOrContent).header("User-Agent", userAgent).build()
                okHttpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) throw Exception("HTTP ${resp.code}: ${resp.message}")
                    val rawStream = resp.body?.byteStream() ?: throw Exception("Empty response body")
                    val bodyStream = BufferedInputStream(rawStream)
                    bodyStream.mark(2)
                    val b0 = bodyStream.read()
                    val b1 = bodyStream.read()
                    bodyStream.reset()
                    val isGzip = b0 == 0x1f && b1 == 0x8b
                    if (isGzip) {
                        GZIPInputStream(bodyStream).use { gzStream ->
                            XmltvParser.parse(gzStream, storeDescriptions, minTime)
                        }
                    } else {
                        XmltvParser.parse(bodyStream, storeDescriptions, minTime)
                    }
                }
            }

            if (programs.isNotEmpty()) {
                programs.map { it.epgChannelId }.distinct().chunked(500).forEach {
                    programDao.deleteProgramsForChannels(it)
                }
                programDao.insertPrograms(programs)
            }

            if (minTime > 0L) {
                programDao.purgeOldPrograms(minTime)
            }

            val count = programDao.getDistinctUpdatedChannelsCount()
            val timeFormatted = SimpleDateFormat("dd/MM/yy hh:mm a", Locale.US).format(Date())
            val statusMsg = "EPG is successfully updated for $count channels on $timeFormatted"
            epgPrefs.edit().putString("latest_status", statusMsg).apply()

            Result.success(programs.size)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun getLatestEpgStatus(): String {
        return epgPrefs.getString("latest_status", "No EPG updates yet") ?: "No EPG updates yet"
    }

    suspend fun clearAllEpg() = withContext(Dispatchers.IO) {
        programDao.clearAllPrograms()
        epgPrefs.edit().putString("latest_status", "EPG cleared").apply()
    }

    // ---- Playlist operations ----
    fun getChannelsByPlaylist(playlistId: Long): Flow<List<ChannelEntity>> {
        return channelDao.getChannelsByPlaylist(playlistId)
    }

    fun searchChannels(query: String): Flow<List<ChannelEntity>> {
        return channelDao.searchChannels(query)
    }

    suspend fun deletePlaylist(playlist: PlaylistEntity) = withContext(Dispatchers.IO) {
        channelDao.deleteChannelsByPlaylist(playlist.id)
        channelGroupDao.deleteGroupsByPlaylist(playlist.id)
        epgSourceDao.clearAssignedSourcesForPlaylist(playlist.id)
        playlistDao.deletePlaylist(playlist)
        epgPrefs.edit().remove("urls_${playlist.id}").remove("ts_${playlist.id}").apply()
    }

    suspend fun updatePlaylist(playlist: PlaylistEntity) = withContext(Dispatchers.IO) {
        playlistDao.updatePlaylist(playlist)
    }

    suspend fun getChannel(id: Long): ChannelEntity? = withContext(Dispatchers.IO) {
        channelDao.getChannelById(id)
    }

    // ---- Per-playlist player spoofing ----

    /** Saves the spoofing engine of one playlist and re-signs the channels that still carry its old defaults. */
    suspend fun setPlaylistSpoof(
        playlistId: Long,
        preset: SpoofPreset,
        customUserAgent: String,
        customHeaders: Map<String, String>
    ) = withContext(Dispatchers.IO) {
        val pl = playlistDao.getPlaylistById(playlistId) ?: return@withContext
        applySpoof(pl, SpoofProfile(preset, customUserAgent, customHeaders))
    }

    suspend fun setAllPlaylistsSpoof(
        preset: SpoofPreset,
        customUserAgent: String,
        customHeaders: Map<String, String>
    ) = withContext(Dispatchers.IO) {
        val profile = SpoofProfile(preset, customUserAgent, customHeaders)
        playlistDao.getAllPlaylists().first().forEach { applySpoof(it, profile) }
    }

    private suspend fun applySpoof(pl: PlaylistEntity, profile: SpoofProfile) {
        val old = pl.toSpoofProfile()
        val oldUa = old.getUserAgent()
        val oldHeaders = old.getEffectiveHeaders()
        val newUa = profile.getUserAgent()
        val newHeaders = profile.getEffectiveHeaders()

        // The signature is baked into every channel at import time, so rewrite it. Values a channel
        // sets itself (e.g. #EXTVLCOPT user-agent) are different from the old defaults and are kept.
        val updated = channelDao.getChannelsByPlaylist(pl.id).first().mapNotNull { ch ->
            val ua = if (ch.httpUserAgent.isNullOrBlank() || ch.httpUserAgent == oldUa) newUa else ch.httpUserAgent
            val own = parseHeadersJson(ch.httpHeadersJson).filter { (k, v) -> oldHeaders[k] != v }
            val json = JSONObject(newHeaders + own).toString()
            if (ua == ch.httpUserAgent && json == ch.httpHeadersJson) null
            else ch.copy(httpUserAgent = ua, httpHeadersJson = json)
        }
        updated.chunked(500).forEach { channelDao.insertChannels(it) }

        val latest = playlistDao.getPlaylistById(pl.id) ?: pl
        playlistDao.updatePlaylist(
            latest.copy(
                spoofPreset = profile.preset.name,
                customUserAgent = if (profile.preset == SpoofPreset.CUSTOM) profile.customUserAgent.ifBlank { null } else latest.customUserAgent,
                customHeadersJson = if (profile.customHeaders.isNotEmpty()) JSONObject(profile.customHeaders).toString() else null
            )
        )
    }

    // ---- Playlist refresh ("Update playlist") ----

    private fun downloadText(url: String, headers: Map<String, String>): String {
        val req = Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.build()
        return okHttpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw Exception("HTTP ${resp.code}: ${resp.message}")
            resp.body?.string().orEmpty()
        }
    }

    /**
     * Downloads an M3U playlist with the given player signature first and, when [allowFallback] is set, then
     * (only for the download) with the other built-in signatures, because providers answer unknown clients
     * with a Telegram redirect or a non-playlist page. Returns the text and the signature that worked.
     */
    private fun fetchM3uText(url: String, primary: SpoofProfile, allowFallback: Boolean = true): Pair<String, SpoofProfile> {
        val candidates = buildList {
            add(primary)
            if (allowFallback) SpoofPreset.entries
                    .filter { it != primary.preset && it != SpoofPreset.CUSTOM && it != SpoofPreset.EXOPLAYER }
                    .forEach { add(SpoofProfile(it, "", primary.customHeaders)) }
        }
        var lastProblem = "No valid M3U returned"
        for (candidate in candidates) {
            try {
                val reqBuilder = Request.Builder().url(url)
                candidate.getEffectiveHeaders().forEach { (k, v) -> reqBuilder.header(k, v) }
                val body = okHttpClient.newCall(reqBuilder.build()).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        lastProblem = "HTTP ${resp.code}: ${resp.message}"
                        null
                    } else {
                        val finalHost = resp.request.url.host
                        val text = resp.body?.string() ?: ""
                        if (finalHost.contains("t.me") || finalHost.contains("telegram")) {
                            lastProblem = "Provider redirected to Telegram (player signature '${candidate.preset.displayName}' rejected)"
                            null
                        } else if (!text.contains("#EXTM3U", ignoreCase = true) &&
                            !text.contains("#EXTINF", ignoreCase = true)) {
                            lastProblem = "Provider returned a non-playlist page for '${candidate.preset.displayName}'"
                            null
                        } else text
                    }
                }
                if (body != null) return body to candidate
            } catch (e: Exception) {
                lastProblem = e.message ?: e.javaClass.simpleName
            }
        }
        throw Exception(lastProblem)
    }

    /**
     * Downloads the playlist again and replaces its channels. Channels that still exist keep their
     * id (and therefore favorites / resume position); vanished ones are removed, new ones added.
     */
    suspend fun refreshPlaylist(playlistId: Long, onProgress: (String) -> Unit = {}): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val pl = playlistDao.getPlaylistById(playlistId) ?: throw Exception("Playlist not found")
            if (pl.type == PlaylistType.STALKER) return@withContext Result.success(refreshStalker(pl, onProgress))
            val spoof = pl.toSpoofProfile()
            val ua = spoof.getUserAgent()
            val headers = spoof.getEffectiveHeaders()

            var expiration: String? = null
            val fresh: List<ChannelEntity> = when (pl.type) {
                PlaylistType.M3U -> {
                    if (!pl.sourceUrl.startsWith("http", ignoreCase = true)) {
                        throw Exception("This playlist was pasted, it has no URL to update from")
                    }
                    // one attempt with the playlist's own signature: no extra requests against a provider that may rate-limit / ban
                    val content = fetchM3uText(pl.sourceUrl, spoof, allowFallback = false).first
                    M3uParser.parse(content, pl.id, ua, headers)
                }
                PlaylistType.XTREAM -> {
                    val auth = xtreamClient.authenticate(pl.sourceUrl, pl.username.orEmpty(), pl.password.orEmpty(), ua)
                    if (!auth.success) throw Exception(auth.message)
                    expiration = formatExpiration(auth.expDate)
                    xtreamClient.fetchChannels(
                        pl.sourceUrl, pl.username.orEmpty(), pl.password.orEmpty(), pl.id, ua,
                        includeTv = pl.includeTv, includeVod = pl.includeVod
                    )
                }
                PlaylistType.STALKER -> {
                    val params = pl.toStalkerParams()
                    val auth = stalkerClient.login(pl.sourceUrl, params, ua)
                    if (!auth.success) throw Exception(auth.message)
                    expiration = formatExpiration(auth.expiration)
                    stalkerClient.fetchContent(pl.sourceUrl, params, pl.id, ua, pl.includeTv, pl.includeVod, auth)
                }
                PlaylistType.RAW_JSON -> {
                    if (!pl.sourceUrl.startsWith("http", ignoreCase = true)) {
                        throw Exception("This playlist was pasted, it has no URL to update from")
                    }
                    val content = downloadText(pl.sourceUrl, mapOf("User-Agent" to ua))
                    JsonPlaylistParser.parse(content, pl.id, ua, headers)
                }
            }
            if (fresh.isEmpty()) throw Exception("The provider returned no channels")
            if (pl.type == PlaylistType.STALKER || pl.type == PlaylistType.XTREAM) {
                if (!pl.includeTv && !pl.includeVod) throw Exception("Enable TV channels or VOD first")
            }

            val old = channelDao.getChannelsByPlaylist(pl.id).first()
            val used = HashSet<Long>()
            fun take(list: List<ChannelEntity>?) = list?.firstOrNull { used.add(it.id) }
            // ids are only comparable within one content type: live channel 123 and movie 123 are different things
            val byUrl = old.groupBy { it.contentType to it.streamUrl }
            val byStreamId = old.filter { it.streamId.isNotBlank() }.groupBy { it.contentType to it.streamId }
            val merged = fresh.map { ch ->
                val match = take(byUrl[ch.contentType to ch.streamUrl])
                    ?: ch.streamId.takeIf { it.isNotBlank() }?.let { take(byStreamId[ch.contentType to it]) }
                if (match != null) ch.copy(id = match.id, isFavorite = match.isFavorite) else ch
            }

            insertChannelsBulk(merged)
            val keptIds = merged.map { it.id }.filter { it != 0L }.toSet()
            old.filter { it.id !in keptIds }.map { it.id }.chunked(500).forEach { channelDao.deleteChannelsByIds(it) }

            // movies / shows switched off: their groups go with them
            val counts = countsOf(merged)
            if (counts.movies == 0) channelGroupDao.deleteGroupsByPlaylistAndType(pl.id, ContentType.MOVIE)
            if (counts.shows == 0) channelGroupDao.deleteGroupsByPlaylistAndType(pl.id, ContentType.SHOW)
            if (counts.tv == 0 && !pl.includeTv) channelGroupDao.deleteGroupsByPlaylistAndType(pl.id, ContentType.TV)

            syncGroupsForPlaylist(pl.id, merged, pl.showNewlyAddedGroups)
            playlistDao.updateContentCounts(pl.id, counts.tv, counts.movies, counts.shows, expiration, System.currentTimeMillis())
            Result.success(merged.size)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun reorderPlaylists(orderedIds: List<Long>) = withContext(Dispatchers.IO) {
        playlistDao.updatePlaylistOrders(orderedIds)
    }

    suspend fun setGroupVisibility(playlistId: Long, groupTitle: String, isVisible: Boolean, contentType: String = ContentType.TV) = withContext(Dispatchers.IO) {
        channelGroupDao.setGroupVisibility(playlistId, contentType, groupTitle, isVisible)
    }

    suspend fun setGroupsVisibility(playlistId: Long, changes: Map<String, Boolean>, contentType: String = ContentType.TV) = withContext(Dispatchers.IO) {
        channelGroupDao.setGroupsVisibility(playlistId, contentType, changes)
    }

    suspend fun setAllGroupsVisibility(playlistId: Long, isVisible: Boolean, contentType: String = ContentType.TV) = withContext(Dispatchers.IO) {
        channelGroupDao.setAllGroupsVisibility(playlistId, contentType, isVisible)
    }

    suspend fun reorderGroups(playlistId: Long, orderedTitles: List<String>, contentType: String = ContentType.TV) = withContext(Dispatchers.IO) {
        channelGroupDao.updateGroupOrders(playlistId, contentType, orderedTitles)
    }

    private val vodInfoCache = object : LinkedHashMap<Long, VodInfo>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, VodInfo>?) = size > 300
    }

    /**
     * Details for the movie / show page. Xtream asks the server for them (get_vod_info / get_series_info) the
     * moment a poster is highlighted; Stalker already sent them with the list item.
     */
    suspend fun loadVodInfo(channel: ChannelEntity): VodInfo? = withContext(Dispatchers.IO) {
        synchronized(vodInfoCache) { vodInfoCache[channel.id] }?.let { return@withContext it }
        val pl = playlistDao.getPlaylistById(channel.playlistId) ?: return@withContext null
        val info: VodInfo? = when (pl.type) {
            PlaylistType.XTREAM -> xtreamClient.fetchInfo(
                pl.sourceUrl, pl.username.orEmpty(), pl.password.orEmpty(), channel.streamId,
                isSeries = channel.contentType == ContentType.SHOW, userAgent = pl.toSpoofProfile().getUserAgent()
            )
            // the list pages load rows without the details blob: read it for this one item
            PlaylistType.STALKER -> (channel.metaJson ?: channelDao.getChannelById(channel.id)?.metaJson)?.let { json ->
                try {
                    val j = JSONObject(json)
                    VodInfo(
                        plot = j.optString("p"), cast = j.optString("c"), director = j.optString("d"),
                        genre = j.optString("g"), year = j.optString("y"), duration = j.optString("t"),
                        backdropUrl = j.optString("b")
                    )
                } catch (_: Exception) { null }
            }
            else -> null
        }
        if (info != null) synchronized(vodInfoCache) { vodInfoCache[channel.id] = info }
        info
    }

    /** Re-reads the account's expiry date (login only, no channel download) so it is known for older playlists too. */
    suspend fun refreshExpiration(playlistId: Long) = withContext(Dispatchers.IO) {
        try {
            val pl = playlistDao.getPlaylistById(playlistId) ?: return@withContext
            val ua = pl.toSpoofProfile().getUserAgent()
            val exp = when (pl.type) {
                PlaylistType.XTREAM -> formatExpiration(xtreamClient.authenticate(pl.sourceUrl, pl.username.orEmpty(), pl.password.orEmpty(), ua).expDate)
                PlaylistType.STALKER -> formatExpiration(stalkerClient.login(pl.sourceUrl, pl.toStalkerParams(), ua).expiration)
                else -> null
            }
            if (exp != null && exp != pl.expirationDate) playlistDao.updateExpiration(playlistId, exp)
        } catch (_: Exception) {
        }
    }

    fun vodChannels(contentType: String): Flow<List<ChannelEntity>> = channelDao.getVodListByType(contentType)

    /**
     * Turns a movie into something the player can open. Xtream movies are direct links already; Stalker movies
     * carry a portal command that has to be exchanged for a real link first. Shows are not playable yet
     * (their episodes are not fetched).
     */
    suspend fun resolvePlayable(channel: ChannelEntity): Result<ChannelEntity> = withContext(Dispatchers.IO) {
        try {
            if (channel.contentType == ContentType.SHOW) throw Exception("Episodes of shows can't be played yet")
            val pl = playlistDao.getPlaylistById(channel.playlistId) ?: throw Exception("Playlist not found")
            if (pl.type != PlaylistType.STALKER || channel.contentType != ContentType.MOVIE) return@withContext Result.success(channel)
            val ua = pl.toSpoofProfile().getUserAgent()
            val params = pl.toStalkerParams()
            val auth = stalkerClient.login(pl.sourceUrl, params, ua)
            if (!auth.success) throw Exception(auth.message)
            val link = stalkerClient.resolveStreamLink(pl.sourceUrl, params.normalizedMac, channel.streamUrl, auth.token, ua, "vod")
            Result.success(channel.copy(streamUrl = link))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getPlaylist(id: Long): PlaylistEntity? = withContext(Dispatchers.IO) { playlistDao.getPlaylistById(id) }

    suspend fun addM3uPlaylist(
        name: String,
        sourceUrlOrContent: String,
        isRawContent: Boolean = false,
        spoofPreset: SpoofPreset = SpoofPreset.TIVIMATE,
        customUserAgent: String = "",
        customHeaders: Map<String, String> = emptyMap()
    ): Result<ImportResult> = withContext(Dispatchers.IO) {
        try {
            val spoof = SpoofProfile(spoofPreset, customUserAgent, customHeaders)
            val effectiveHeaders = spoof.getEffectiveHeaders()

            val content = if (isRawContent) {
                sourceUrlOrContent
            } else {
                // Other player signatures may be tried to DOWNLOAD the playlist, but the signature the user
                // picked stays the one saved for playback (it used to be silently replaced by the one that worked).
                fetchM3uText(sourceUrlOrContent, spoof).first
            }

            val playlistId = playlistDao.insertPlaylist(
                PlaylistEntity(
                    name = name,
                    type = PlaylistType.M3U,
                    sourceUrl = if (isRawContent) "Pasted M3U" else sourceUrlOrContent,
                    spoofPreset = spoof.preset.name,
                    customUserAgent = customUserAgent.ifBlank { null },
                    customHeadersJson = if (customHeaders.isNotEmpty()) JSONObject(customHeaders).toString() else null
                )
            )

            val channels = M3uParser.parse(
                content = content,
                playlistId = playlistId,
                defaultUserAgent = spoof.getUserAgent(),
                defaultHeaders = effectiveHeaders
            )

            val counts = storeImported(playlistId, channels, null)
            startPlaylistEpgImport(playlistId, M3uParser.extractEpgUrls(content), spoof.getUserAgent())

            Result.success(ImportResult(playlistId, name, counts))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Saves the channels of a freshly added playlist, its groups and its per-type counts. */
    private suspend fun storeImported(playlistId: Long, channels: List<ChannelEntity>, expiration: String?): ImportCounts {
        val counts = countsOf(channels)
        if (channels.isNotEmpty()) {
            insertChannelsBulk(channels)
            syncGroupsForPlaylist(playlistId, channels, true)
        }
        playlistDao.updateContentCounts(playlistId, counts.tv, counts.movies, counts.shows, expiration, System.currentTimeMillis())
        return counts
    }

    suspend fun addXtreamPlaylist(
        name: String,
        serverUrl: String,
        username: String,
        password: String,
        spoofPreset: SpoofPreset = SpoofPreset.TIVIMATE,
        includeTv: Boolean = true,
        includeVod: Boolean = false
    ): Result<ImportResult> = withContext(Dispatchers.IO) {
        try {
            val spoof = SpoofProfile(spoofPreset)
            val auth = xtreamClient.authenticate(serverUrl, username, password, spoof.getUserAgent())
            if (!auth.success) return@withContext Result.failure(Exception(auth.message))

            val playlistId = playlistDao.insertPlaylist(
                PlaylistEntity(
                    name = name,
                    type = PlaylistType.XTREAM,
                    sourceUrl = serverUrl,
                    username = username,
                    password = password,
                    spoofPreset = spoofPreset.name,
                    includeTv = includeTv,
                    includeVod = includeVod
                )
            )

            val channels = xtreamClient.fetchChannels(
                serverUrl, username, password, playlistId, spoof.getUserAgent(),
                includeTv = includeTv, includeVod = includeVod
            )
            val counts = storeImported(playlistId, channels, formatExpiration(auth.expDate))
            Result.success(ImportResult(playlistId, name, counts))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun addStalkerPlaylist(
        name: String,
        portalUrl: String,
        macAddress: String,
        spoofPreset: SpoofPreset = SpoofPreset.TIVIMATE,
        username: String = "",
        password: String = "",
        deviceId: String = "",
        deviceId2: String = "",
        serialNumber: String = "",
        includeTv: Boolean = true,
        includeVod: Boolean = false,
        onProgress: (String) -> Unit = {}
    ): Result<ImportResult> = withContext(Dispatchers.IO) {
        var playlistId = -1L
        try {
            val spoof = SpoofProfile(spoofPreset)
            val params = StalkerParams(macAddress, username, password, deviceId, deviceId2, serialNumber)
            onProgress("Connecting to the portal...")
            val auth = stalkerClient.login(portalUrl, params, spoof.getUserAgent())
            if (!auth.success) return@withContext Result.failure(Exception(auth.message))

            playlistId = playlistDao.insertPlaylist(
                PlaylistEntity(
                    name = name,
                    type = PlaylistType.STALKER,
                    sourceUrl = portalUrl,
                    macAddress = macAddress,
                    username = username.ifBlank { null },
                    password = password.ifBlank { null },
                    deviceId = deviceId.ifBlank { null },
                    deviceId2 = deviceId2.ifBlank { null },
                    serialNumber = serialNumber.ifBlank { null },
                    spoofPreset = spoofPreset.name,
                    includeTv = includeTv,
                    includeVod = includeVod
                )
            )

            val counts = importStalkerContent(playlistId, params, auth, spoof.getUserAgent(), includeTv, includeVod, onProgress)
            if (counts.tv + counts.movies + counts.shows == 0) throw Exception("The portal returned no channels or movies")
            playlistDao.updateContentCounts(playlistId, counts.tv, counts.movies, counts.shows, formatExpiration(auth.expiration), System.currentTimeMillis())
            Result.success(ImportResult(playlistId, name, counts))
        } catch (e: Exception) {
            // don't leave an empty, half-added playlist behind
            if (playlistId > 0) {
                try { playlistDao.getPlaylistById(playlistId)?.let { deletePlaylist(it) } } catch (_: Exception) {}
            }
            Result.failure(e)
        }
    }

    /**
     * Downloads a Stalker portal into the database: live TV first (usable at once), then movies and shows,
     * which are written in batches while they stream in. A portal with 100k movies is thousands of small
     * requests, so nothing is collected in memory and a failure part-way keeps what already arrived.
     */
    private suspend fun importStalkerContent(
        playlistId: Long,
        params: StalkerParams,
        auth: StalkerAuthResult,
        userAgent: String,
        includeTv: Boolean,
        includeVod: Boolean,
        onProgress: (String) -> Unit
    ): ImportCounts {
        var tv = 0
        var movies = 0
        var shows = 0
        var incomplete = false
        val expiration = formatExpiration(auth.expiration)

        if (includeTv) {
            onProgress("Loading live channels...")
            val live = stalkerClient.fetchLiveChannels(auth, params, playlistId, userAgent)
            if (live.isNotEmpty()) {
                insertChannelsBulk(live)
                syncGroupsForPlaylist(playlistId, live, true)
            }
            tv = live.size
            // live TV can be used from here on, even while the (much bigger) movie / show catalogue still comes in
            playlistDao.updateContentCounts(playlistId, tv, 0, 0, expiration, System.currentTimeMillis())
        }

        if (includeVod) {
            for (type in listOf(ContentType.MOVIE, ContentType.SHOW)) {
                val label = if (type == ContentType.MOVIE) "Movies" else "Shows"
                onProgress("$label: starting...")
                val titles = LinkedHashSet<String>()
                var stored = 0
                var lastShown = 0
                var portalOrder: List<String> = emptyList()
                try {
                    val result = stalkerClient.streamVod(
                        type, auth, params, playlistId, userAgent,
                        onBatch = { batch ->
                            insertChannelsBulk(batch)
                            for (ch in batch) titles += ch.groupTitle
                            stored += batch.size
                        },
                        onProgress = { n ->
                            if (n - lastShown >= 250) {
                                lastShown = n
                                onProgress("$label: ${String.format(Locale.US, "%,d", n)}")
                            }
                        }
                    )
                    portalOrder = result.categoryTitles
                    if (result.failedPages > 0) incomplete = true
                } finally {
                    // groups of whatever arrived, in the portal's own order (also when the download broke off)
                    val ordered = portalOrder.filter { it in titles } + titles.filter { it !in portalOrder }
                    withContext(NonCancellable) { syncGroupTitles(playlistId, mapOf(type to ordered), true) }
                }
                if (type == ContentType.MOVIE) movies = stored else shows = stored
            }
        }
        return ImportCounts(tv = tv, movies = movies, shows = shows, incomplete = incomplete)
    }

    /**
     * Processes a Stalker playlist again (update, or VOD switched on / off in the portal settings). Same streaming
     * download as adding it; channels that still exist keep their id (favorites, resume), vanished ones are removed,
     * but only for a kind that was downloaded completely.
     */
    private suspend fun refreshStalker(pl: PlaylistEntity, onProgress: (String) -> Unit): Int {
        if (!pl.includeTv && !pl.includeVod) throw Exception("Enable TV channels or VOD first")
        val ua = pl.toSpoofProfile().getUserAgent()
        val params = pl.toStalkerParams()
        onProgress("Connecting to the portal...")
        val auth = stalkerClient.login(pl.sourceUrl, params, ua)
        if (!auth.success) throw Exception(auth.message)
        val expiration = formatExpiration(auth.expiration)

        val old = channelDao.getIdentities(pl.id)
        val used = HashSet<Long>()
        val byStreamId = old.filter { it.streamId.isNotBlank() }.groupBy { it.contentType to it.streamId }
        val byUrl = old.groupBy { it.contentType to it.streamUrl }
        fun reuse(batch: List<ChannelEntity>): List<ChannelEntity> = batch.map { ch ->
            val match = ch.streamId.takeIf { it.isNotBlank() }
                ?.let { id -> byStreamId[ch.contentType to id]?.firstOrNull { used.add(it.id) } }
                ?: byUrl[ch.contentType to ch.streamUrl]?.firstOrNull { used.add(it.id) }
            if (match != null) ch.copy(id = match.id, isFavorite = match.isFavorite) else ch
        }

        // content types whose old rows that were not seen again may be removed
        val deletable = HashSet<String>()
        var fetchedAnything = false
        val titlesByType = LinkedHashMap<String, List<String>>()

        if (pl.includeTv) {
            onProgress("Loading live channels...")
            val live = stalkerClient.fetchLiveChannels(auth, params, pl.id, ua)
            if (live.isNotEmpty()) {
                fetchedAnything = true
                insertChannelsBulk(reuse(live))
                titlesByType[ContentType.TV] = live.map { it.groupTitle }
                deletable += ContentType.TV
            }
        } else {
            deletable += ContentType.TV
        }

        if (pl.includeVod) {
            for (type in listOf(ContentType.MOVIE, ContentType.SHOW)) {
                val label = if (type == ContentType.MOVIE) "Movies" else "Shows"
                onProgress("$label: starting...")
                val titles = LinkedHashSet<String>()
                var stored = 0
                var lastShown = 0
                var portalOrder: List<String> = emptyList()
                var complete = false
                try {
                    val result = stalkerClient.streamVod(
                        type, auth, params, pl.id, ua,
                        onBatch = { batch ->
                            insertChannelsBulk(reuse(batch))
                            for (ch in batch) titles += ch.groupTitle
                            stored += batch.size
                        },
                        onProgress = { n ->
                            if (n - lastShown >= 250) {
                                lastShown = n
                                onProgress("$label: ${String.format(Locale.US, "%,d", n)}")
                            }
                        }
                    )
                    portalOrder = result.categoryTitles
                    complete = result.failedPages == 0 && stored > 0
                } finally {
                    if (stored > 0) {
                        // groups of whatever arrived, in the portal's own order (also when the download broke off)
                        val ordered = portalOrder.filter { it in titles } + titles.filter { it !in portalOrder }
                        withContext(NonCancellable) { syncGroupTitles(pl.id, mapOf(type to ordered), pl.showNewlyAddedGroups) }
                        fetchedAnything = true
                        if (complete) deletable += type
                    }
                }
            }
        } else {
            deletable += ContentType.MOVIE
            deletable += ContentType.SHOW
        }

        if (!fetchedAnything) throw Exception("The provider returned no channels")

        old.filter { it.id !in used && it.contentType in deletable }
            .map { it.id }
            .chunked(500)
            .forEach { channelDao.deleteChannelsByIds(it) }

        syncGroupTitles(pl.id, titlesByType, pl.showNewlyAddedGroups)

        val tv = channelDao.countByType(pl.id, ContentType.TV)
        val movies = channelDao.countByType(pl.id, ContentType.MOVIE)
        val shows = channelDao.countByType(pl.id, ContentType.SHOW)
        // movies / shows switched off: their groups go with them
        if (movies == 0) channelGroupDao.deleteGroupsByPlaylistAndType(pl.id, ContentType.MOVIE)
        if (shows == 0) channelGroupDao.deleteGroupsByPlaylistAndType(pl.id, ContentType.SHOW)
        if (tv == 0 && !pl.includeTv) channelGroupDao.deleteGroupsByPlaylistAndType(pl.id, ContentType.TV)
        playlistDao.updateContentCounts(pl.id, tv, movies, shows, expiration, System.currentTimeMillis())
        return tv + movies + shows
    }

    suspend fun addJsonPlaylist(
        name: String,
        sourceUrlOrContent: String,
        isRawContent: Boolean = false,
        spoofPreset: SpoofPreset = SpoofPreset.TIVIMATE
    ): Result<ImportResult> = withContext(Dispatchers.IO) {
        try {
            val spoof = SpoofProfile(spoofPreset)
            val content = if (isRawContent) sourceUrlOrContent else {
                val req = Request.Builder().url(sourceUrlOrContent).header("User-Agent", spoof.getUserAgent()).build()
                okHttpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) throw Exception("HTTP ${resp.code}: ${resp.message}")
                    resp.body?.string() ?: ""
                }
            }

            val playlistId = playlistDao.insertPlaylist(
                PlaylistEntity(
                    name = name,
                    type = PlaylistType.RAW_JSON,
                    sourceUrl = if (isRawContent) "Pasted JSON" else sourceUrlOrContent,
                    spoofPreset = spoofPreset.name
                )
            )

            val channels = JsonPlaylistParser.parse(content, playlistId, spoof.getUserAgent(), spoof.getEffectiveHeaders())
            val counts = storeImported(playlistId, channels, null)
            Result.success(ImportResult(playlistId, name, counts))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun getScheduleForChannel(epgChannelId: String): Flow<List<ProgramEntity>> {
        return programDao.getScheduleForChannel(epgChannelId)
    }

    suspend fun toggleFavorite(channelId: Long, currentFavorite: Boolean) = withContext(Dispatchers.IO) {
        channelDao.updateFavorite(channelId, !currentFavorite)
    }

    suspend fun recordHistory(channel: ChannelEntity) = withContext(Dispatchers.IO) {
        historyDao.deleteHistoryByChannel(channel.id)
        historyDao.insertHistory(
            HistoryEntity(
                channelId = channel.id,
                channelName = channel.name,
                streamUrl = channel.streamUrl,
                logoUrl = channel.logoUrl,
                groupTitle = channel.groupTitle
            )
        )
    }

    suspend fun removeHistory(channelId: Long) = withContext(Dispatchers.IO) {
        historyDao.deleteHistoryByChannel(channelId)
    }

    suspend fun clearHistory() = withContext(Dispatchers.IO) {
        historyDao.clearHistory()
    }
}
