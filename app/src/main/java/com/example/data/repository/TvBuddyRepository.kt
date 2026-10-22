package com.example.data.repository

import android.content.Context
import androidx.room.withTransaction
import com.example.data.local.TvBuddyDatabase
import com.example.data.local.entity.ChannelEntity
import com.example.data.local.entity.ChannelGroupEntity
import com.example.data.local.entity.EpgChannelEntity
import com.example.data.epg.EpgMatcher
import com.example.data.util.LogoUrl
import kotlinx.coroutines.sync.Mutex
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
import com.example.data.network.AdaptiveGate
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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import com.example.data.network.VodCategory
import com.example.data.network.VodPage
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.BufferedInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
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
    private val epgChannelDao = database.epgChannelDao()
    private val remapMutex = Mutex()
    /** Channels whose portal EPG was asked for lately (id -> time), so zapping back and forth does not hammer the portal. */
    private val portalEpgAsked = java.util.concurrent.ConcurrentHashMap<Long, Long>()

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
                val stale = now - epgPrefs.getLong("ts_${p.id}", 0L) > 12L * 60 * 60 * 1000
                if (p.type == PlaylistType.STALKER) {
                    if (p.includeTv && stale) refreshPortalEpg(p.id)
                    continue
                }
                val urls = epgPrefs.getStringSet("urls_${p.id}", null)?.toList() ?: continue
                if (stale) fetchEpgUrls(p.id, urls, userAgent)
            }
        }
    }

    /**
     * Stalker portals deliver their guide themselves (get_epg_info, IPTVnator style): one bulk request for the
     * whole portal, then a per-channel get_short_epg for the channels it left out. Stored under the portal keys the
     * matcher knows, next to whatever XMLTV guides were imported.
     */
    suspend fun refreshPortalEpg(playlistId: Long): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val pl = playlistDao.getPlaylistById(playlistId) ?: throw Exception("Playlist not found")
            if (pl.type != PlaylistType.STALKER) return@withContext Result.success(0)
            val ua = pl.toSpoofProfile().getUserAgent()
            val auth = stalkerSession(pl)
            if (!auth.success) throw Exception(auth.message)
            val programs = stalkerClient.fetchBulkEpg(auth, pl.toStalkerParams(), pl.id, ua)
            val minTime = System.currentTimeMillis() - 24L * 3_600_000L
            val keep = programs.filter { it.endTimeMs >= minTime }
            if (keep.isNotEmpty()) {
                keep.map { it.epgChannelId }.distinct().chunked(500).forEach { programDao.deleteProgramsForChannels(it) }
                keep.chunked(2000).forEach { programDao.insertPrograms(it) }
            }
            epgPrefs.edit().putLong("ts_$playlistId", System.currentTimeMillis()).apply()
            remapEpg(playlistId)
            Result.success(keep.size)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Makes sure the channel that is being watched has a guide: when no imported XMLTV and no earlier portal answer
     * has upcoming programmes for it, asks the portal itself (Xtream get_simple_data_table / get_short_epg, Stalker
     * get_short_epg). Asked at most once per five minutes per channel.
     */
    suspend fun ensureChannelEpg(channel: ChannelEntity) = withContext(Dispatchers.IO) {
        if (channel.contentType != ContentType.TV || channel.streamId.isBlank()) return@withContext
        try {
            val now = System.currentTimeMillis()
            val last = portalEpgAsked[channel.id]
            if (last != null && now - last < 5 * 60_000L) return@withContext
            val pl = playlistDao.getPlaylistById(channel.playlistId) ?: return@withContext
            if (pl.type != PlaylistType.XTREAM && pl.type != PlaylistType.STALKER) return@withContext
            val keys = listOfNotNull(channel.epgChannelId, EpgMatcher.portalKey(pl.id, channel.streamId)).distinct()
            if (keys.any { programDao.countUpcoming(it, now) > 1 }) return@withContext
            portalEpgAsked[channel.id] = now
            val ua = pl.toSpoofProfile().getUserAgent()
            val programs = when (pl.type) {
                PlaylistType.XTREAM -> {
                    val full = xtreamClient.fetchFullEpg(pl.sourceUrl, pl.username.orEmpty(), pl.password.orEmpty(), pl.id, channel.streamId, ua)
                    full.ifEmpty { xtreamClient.fetchShortEpg(pl.sourceUrl, pl.username.orEmpty(), pl.password.orEmpty(), pl.id, channel.streamId, 12, ua) }
                }
                else -> {
                    val auth = stalkerSession(pl)
                    if (auth.success) stalkerClient.fetchShortEpg(auth, pl.toStalkerParams(), pl.id, ua, channel.streamId, 12) else emptyList()
                }
            }
            val fresh = programs.filter { it.endTimeMs >= now - 6 * 3_600_000L }
            if (fresh.isNotEmpty()) {
                programDao.deleteProgramsForChannel(fresh.first().epgChannelId)
                programDao.insertPrograms(fresh)
            }
        } catch (_: Exception) {
        }
    }

    /**
     * Resolves, for every live channel of [playlistId] (0 = all playlists), which guide channel its programmes are
     * stored under, and fills in logos from the guide's channel icons where the playlist had none. Run after every
     * guide import and every playlist import / update. See [EpgMatcher].
     */
    suspend fun remapEpg(playlistId: Long = 0L) = withContext(Dispatchers.IO) {
        remapMutex.withLock {
            try {
                val ids = programDao.getDistinctChannelIds().toHashSet()
                if (ids.isEmpty()) return@withLock
                val index = EpgMatcher.Index(epgChannelDao.getAll(), ids)
                val refs = channelDao.getTvEpgRefs(playlistId)
                database.withTransaction {
                    for (r in refs) {
                        val resolved = EpgMatcher.resolve(r, index)
                        if (resolved != null && resolved != r.epgChannelId) channelDao.setEpgChannelId(r.id, resolved)
                        if (r.logoUrl.isNullOrBlank() && resolved != null) {
                            LogoUrl.normalize(index.icons[resolved])?.let { channelDao.setLogoUrl(r.id, it) }
                        }
                    }
                }
            } catch (_: Exception) {
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
            val now = System.currentTimeMillis()
            val minTime = if (pastDaysToKeep > 0) now - pastDaysToKeep.toLong() * 24L * 60L * 60L * 1000L else 0L
            // a guide reaching months ahead is dead weight on a TV: a bit over a week is plenty
            val maxStart = now + 10L * 24L * 60L * 60L * 1000L

            // Streamed: the document is read, and written to the database in batches, while it downloads
            // (the old code held every programme of the guide in memory first).
            val clearedChannels = HashSet<String>()
            var total = 0
            var channelCount = 0
            val onChannels: (List<com.example.data.parser.XmltvChannel>) -> Unit = { list ->
                channelCount += list.size
                val rows = list.map { c ->
                    EpgChannelEntity(
                        channelId = c.id,
                        lowerId = c.id.lowercase(),
                        displayName = c.displayNames.firstOrNull() ?: c.id,
                        normNames = EpgMatcher.normNamesOf(c.displayNames, c.id),
                        iconUrl = LogoUrl.normalize(c.iconUrl)
                    )
                }
                kotlinx.coroutines.runBlocking { rows.chunked(500).forEach { epgChannelDao.insertAll(it) } }
            }
            val onPrograms: (List<ProgramEntity>) -> Unit = { batch ->
                total += batch.size
                kotlinx.coroutines.runBlocking {
                    // the old rows of a channel go when its first new programme arrives (never after, or the batch would delete itself)
                    val fresh = batch.map { it.epgChannelId }.distinct().filter { clearedChannels.add(it) }
                    fresh.chunked(500).forEach { programDao.deleteProgramsForChannels(it) }
                    programDao.insertPrograms(batch)
                }
            }

            if (isRawText) {
                XmltvParser.parseStreaming(
                    java.io.ByteArrayInputStream(urlOrContent.toByteArray(Charsets.UTF_8)),
                    storeDescriptions, minTime, maxStart, onChannels = onChannels, onPrograms = onPrograms
                )
            } else {
                val req = Request.Builder().url(urlOrContent)
                    .header("User-Agent", userAgent)
                    .header("Accept-Encoding", "gzip")
                    .build()
                // the guide of a big provider is hundreds of MB: no total-call timeout, a generous read timeout
                val client = okHttpClient.newBuilder().readTimeout(120, TimeUnit.SECONDS).callTimeout(0, TimeUnit.SECONDS).build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) throw Exception("HTTP ${resp.code}: ${resp.message}")
                    val rawStream = resp.body?.byteStream() ?: throw Exception("Empty response body")
                    val bodyStream = BufferedInputStream(rawStream, 64 * 1024)
                    bodyStream.mark(2)
                    val b0 = bodyStream.read()
                    val b1 = bodyStream.read()
                    bodyStream.reset()
                    // gzip is recognised by its signature, not by the URL or headers: ".xml" links that serve gzip
                    // and ".gz" links that serve plain XML both exist
                    val stream = if (b0 == 0x1f && b1 == 0x8b) GZIPInputStream(bodyStream, 64 * 1024) else bodyStream
                    stream.use {
                        XmltvParser.parseStreaming(it, storeDescriptions, minTime, maxStart, onChannels = onChannels, onPrograms = onPrograms)
                    }
                }
            }

            if (total == 0 && channelCount == 0) throw Exception("The guide contains no channels or programmes")

            if (minTime > 0L) {
                programDao.purgeOldPrograms(minTime)
            }

            // link the playlists' channels to the new guide and fill in missing logos from its icons
            remapEpg()

            val count = programDao.getDistinctUpdatedChannelsCount()
            val timeFormatted = SimpleDateFormat("dd/MM/yy hh:mm a", Locale.US).format(Date())
            val statusMsg = "EPG is successfully updated for $count channels on $timeFormatted"
            epgPrefs.edit().putString("latest_status", statusMsg).apply()

            Result.success(total)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun getLatestEpgStatus(): String {
        return epgPrefs.getString("latest_status", "No EPG updates yet") ?: "No EPG updates yet"
    }

    suspend fun clearAllEpg() = withContext(Dispatchers.IO) {
        programDao.clearAllPrograms()
        epgChannelDao.clear()
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
        forgetVodState(playlist.id, ContentType.MOVIE)
        forgetVodState(playlist.id, ContentType.SHOW)
        stalkerSessions.remove(playlist.id)
        channelDao.deleteChannelsByPlaylist(playlist.id)
        channelGroupDao.deleteGroupsByPlaylist(playlist.id)
        epgSourceDao.clearAssignedSourcesForPlaylist(playlist.id)
        programDao.deleteProgramsByPrefix("sp${playlist.id}:")
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
                    // the playlist may have gained / changed its guide link
                    val urls = M3uParser.extractEpgUrls(content)
                    if (urls.isNotEmpty() && urls.toSet() != epgPrefs.getStringSet("urls_${pl.id}", null)) {
                        startPlaylistEpgImport(pl.id, urls, ua)
                    }
                    M3uParser.parse(content, pl.id, ua, headers)
                }
                PlaylistType.XTREAM -> {
                    val auth = xtreamClient.authenticate(pl.sourceUrl, pl.username.orEmpty(), pl.password.orEmpty(), ua)
                    if (!auth.success) throw Exception(auth.message)
                    expiration = formatExpiration(auth.expDate)
                    // playlists added before the panel guide was wired in get it now; the rest follow the 12 h refresh
                    if (pl.includeTv && epgPrefs.getStringSet("urls_${pl.id}", null).isNullOrEmpty()) {
                        startPlaylistEpgImport(pl.id, listOf(xtreamClient.xmltvUrl(pl.sourceUrl, pl.username.orEmpty(), pl.password.orEmpty())), ua)
                    }
                    xtreamClient.fetchChannels(
                        pl.sourceUrl, pl.username.orEmpty(), pl.password.orEmpty(), pl.id, ua,
                        includeTv = pl.includeTv, includeVod = pl.includeVod, liveExtension = auth.liveExtension
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
                if (match == null) ch else {
                    // a panel that dropped the category request answers with everything in "Live TV": keep the group it had
                    val keepGroup = ch.contentType == ContentType.TV && ch.groupTitle == "Live TV" &&
                        match.groupTitle.isNotBlank() && match.groupTitle != "Live TV"
                    ch.copy(
                        id = match.id,
                        isFavorite = match.isFavorite,
                        groupTitle = if (keepGroup) match.groupTitle else ch.groupTitle,
                        // what the guide taught us (mapping, icon) survives a refresh that brings nothing better
                        epgChannelId = ch.epgChannelId ?: match.epgChannelId,
                        logoUrl = ch.logoUrl ?: match.logoUrl
                    )
                }
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
            if (counts.tv > 0) epgScope.launch { remapEpg(pl.id) }
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

    // ---------------------------------------------------------------------------------------------
    // Movies and shows of a Stalker portal, loaded lazily (IPTVnator style): the categories are known from the
    // import, the items of a category are fetched when it is opened, "All movies" fills while it is scrolled.
    // ---------------------------------------------------------------------------------------------

    private class VodSource(val categories: List<VodCategory>) {
        val idByTitle: Map<String, String> = categories.associate { it.title to it.id }
        val titleById: Map<String, String> = categories.associate { it.id to it.title }
        fun indexOf(id: String) = categories.indexOfFirst { it.id == id }.coerceAtLeast(0)
    }

    private val vodScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val vodInsertLock = kotlinx.coroutines.sync.Mutex()
    private val vodSources = java.util.concurrent.ConcurrentHashMap<String, VodSource>()
    /** Pages already downloaded per "playlist|type|category" and the number of pages the category has. */
    private val vodPages = java.util.concurrent.ConcurrentHashMap<String, MutableSet<Int>>()
    private val vodPageCount = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private val vodTotals = java.util.concurrent.ConcurrentHashMap<Pair<Long, String>, Int>()
    private val stalkerGates = java.util.concurrent.ConcurrentHashMap<Long, AdaptiveGate>()
    private val stalkerSessions = java.util.concurrent.ConcurrentHashMap<Long, Pair<StalkerAuthResult, Long>>()

    private fun forgetVodState(playlistId: Long, type: String) {
        val prefix = "$playlistId|$type|"
        vodSources.remove("$playlistId|$type")
        vodPages.keys.removeAll { it.startsWith(prefix) }
        vodPageCount.keys.removeAll { it.startsWith(prefix) }
    }

    /** Logged-in session of a portal, kept for a while so opening a group costs no handshake. */
    private suspend fun stalkerSession(pl: PlaylistEntity, force: Boolean = false): StalkerAuthResult {
        val cached = stalkerSessions[pl.id]
        if (!force && cached != null && System.currentTimeMillis() - cached.second < 15 * 60_000L) return cached.first
        val auth = stalkerClient.login(pl.sourceUrl, pl.toStalkerParams(), pl.toSpoofProfile().getUserAgent())
        if (auth.success) stalkerSessions[pl.id] = auth to System.currentTimeMillis()
        return auth
    }

    private suspend fun vodSource(pl: PlaylistEntity, type: String): VodSource? {
        val key = "${pl.id}|$type"
        vodSources[key]?.let { return it }
        val auth = stalkerSession(pl)
        if (!auth.success) return null
        val catalog = stalkerClient.fetchVodCatalog(type, auth, pl.toStalkerParams(), pl.toSpoofProfile().getUserAgent()) ?: return null
        return VodSource(catalog.categories).also { vodSources[key] = it }
    }

    /** Stores items that are not stored yet (the same movie arrives through its category and through "All"). */
    private suspend fun storeVodItems(playlistId: Long, type: String, items: List<ChannelEntity>) {
        if (items.isEmpty()) return
        vodInsertLock.withLock {
            val existing = HashSet<String>()
            items.map { it.streamId }.distinct().chunked(500).forEach { existing += channelDao.existingStreamIds(playlistId, type, it) }
            val fresh = items.filter { it.streamId !in existing }.distinctBy { it.streamId }
            insertChannelsBulk(fresh)
        }
    }

    /**
     * Downloads what is missing of one movie / show group: its first page at once (posters show up in a moment),
     * then the other pages in parallel waves. Safe to call again and again; cancelling it (another group got the
     * focus) just leaves the rest for next time.
     */
    suspend fun loadVodGroup(playlistId: Long, type: String, groupTitle: String) = withContext(Dispatchers.IO) {
        val pl = playlistDao.getPlaylistById(playlistId) ?: return@withContext
        if (pl.type != PlaylistType.STALKER || !pl.includeVod) return@withContext
        val src = vodSource(pl, type) ?: return@withContext
        val catId = src.idByTitle[groupTitle] ?: return@withContext
        loadStalkerPages(pl, type, src, catId, wave = Int.MAX_VALUE)
        Unit
    }

    /**
     * Downloads pages of [catId] ("*" = everything): all missing ones, or - with a small [wave] - just the next
     * few. Returns true while there are more pages to come.
     */
    private suspend fun loadStalkerPages(pl: PlaylistEntity, type: String, src: VodSource, catId: String, wave: Int): Boolean {
        val params = pl.toStalkerParams()
        val ua = pl.toSpoofProfile().getUserAgent()
        var auth = stalkerSession(pl)
        if (!auth.success) return false
        val gate = stalkerGates.getOrPut(pl.id) { AdaptiveGate() }
        val key = "${pl.id}|$type|$catId"
        val done = vodPages.getOrPut(key) { java.util.concurrent.ConcurrentHashMap.newKeySet() }
        val catIndex = if (catId == "*") 0 else src.indexOf(catId)
        val fallbackGroup = if (type == ContentType.MOVIE) "Movies" else "Shows"

        suspend fun page(p: Int): VodPage? =
            stalkerClient.fetchVodPage(type, auth, params, pl.id, ua, catId, catIndex, p, { src.titleById[it] }, fallbackGroup, gate)

        var pages = vodPageCount[key]
        if (1 !in done || pages == null) {
            var first = page(1)
            if (first == null) {            // session gone? log in again once
                auth = stalkerSession(pl, force = true)
                if (auth.success) first = page(1)
            }
            if (first == null) return false
            storeVodItems(pl.id, type, first.items)
            done += 1
            pages = if (first.perPage > 0 && first.totalItems > 0) minOf((first.totalItems + first.perPage - 1) / first.perPage, 2000) else 1
            vodPageCount[key] = pages
        }
        val totalPages = pages ?: 1
        val missing = (2..totalPages).filter { it !in done }
        var left = missing.size
        var budget = wave
        for (chunk in missing.chunked(24)) {
            if (budget <= 0) break
            val take = if (budget >= chunk.size) chunk else chunk.take(budget)
            val results = coroutineScope { take.map { p -> async { p to page(p) } }.awaitAll() }
            storeVodItems(pl.id, type, results.flatMap { it.second?.items.orEmpty() })
            results.forEach { (p, r) -> if (r != null) { done += p; left-- } }
            budget -= take.size
        }
        return left > 0
    }

    /**
     * "All movies" / "All shows": downloads the next few pages of every active portal's complete listing, so the
     * list grows while the user scrolls. Returns true while any portal has more.
     */
    suspend fun loadMoreVodAll(type: String): Boolean = withContext(Dispatchers.IO) {
        var more = false
        val list = playlistDao.getAllPlaylists().first().filter { it.isActive && it.type == PlaylistType.STALKER && it.includeVod }
        for (pl in list) {
            if ((type == ContentType.MOVIE && pl.movieCount <= 0) || (type == ContentType.SHOW && pl.showCount <= 0)) continue
            val src = vodSource(pl, type) ?: continue
            if (loadStalkerPages(pl, type, src, "*", wave = 24)) more = true
        }
        more
    }

    fun vodChannels(contentType: String): Flow<List<ChannelEntity>> = channelDao.getVodListByType(contentType)

    /** Movies / shows that were started, newest first. */
    fun vodHistory(contentType: String): Flow<List<ChannelEntity>> = historyDao.getVodHistory(contentType)

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
        // a guide imported earlier may already cover these channels (and carry their logos)
        if (counts.tv > 0) epgScope.launch { remapEpg(playlistId) }
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
                includeTv = includeTv, includeVod = includeVod, liveExtension = auth.liveExtension
            )
            val counts = storeImported(playlistId, channels, formatExpiration(auth.expDate))
            // the panel's own XMLTV guide: its ids are the channels' epg_channel_id values
            if (includeTv && counts.tv > 0) {
                startPlaylistEpgImport(playlistId, listOf(xtreamClient.xmltvUrl(serverUrl, username, password)), spoof.getUserAgent())
            }
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
            // the portal's own guide (get_epg_info), fetched in the background
            if (includeTv && counts.tv > 0) {
                val id = playlistId
                epgScope.launch { refreshPortalEpg(id); remapEpg(id) }
            }
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
     * Adds a Stalker portal the IPTVnator way: live TV in full (one request), but of movies and shows only the
     * CATEGORIES (a few requests) - the items of a category are fetched when it is opened (see [loadVodGroup]).
     * A portal with 25,000 movies is therefore usable within seconds instead of after thousands of page requests.
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
        val expiration = formatExpiration(auth.expiration)

        // live TV first and on its own: groups come from a second request that must not compete with anything else
        if (includeTv) {
            onProgress("Loading live channels...")
            val live = stalkerClient.fetchLiveChannels(auth, params, playlistId, userAgent)
            if (live.isNotEmpty()) {
                insertChannelsBulk(live)
                syncGroupsForPlaylist(playlistId, live, true)
            }
            tv = live.size
            playlistDao.updateContentCounts(playlistId, tv, 0, 0, expiration, System.currentTimeMillis())
        }

        if (includeVod) {
            for (type in listOf(ContentType.MOVIE, ContentType.SHOW)) {
                onProgress(if (type == ContentType.MOVIE) "Loading movie categories..." else "Loading show categories...")
                val catalog = stalkerClient.fetchVodCatalog(type, auth, params, userAgent) ?: continue
                syncGroupTitles(playlistId, mapOf(type to catalog.categories.map { it.title }), true)
                val count = catalog.total.takeIf { it > 0 } ?: catalog.categories.size
                if (type == ContentType.MOVIE) movies = count else shows = count
            }
        }
        return ImportCounts(tv = tv, movies = movies, shows = shows, incomplete = false)
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
        stalkerSessions[pl.id] = auth to System.currentTimeMillis()
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
        val oldTvGroups = if (pl.includeTv) channelDao.getTvGroupsByStreamId(pl.id).associate { it.streamId to it.groupTitle } else emptyMap()

        if (pl.includeTv) {
            onProgress("Loading live channels...")
            val fetched = stalkerClient.fetchLiveChannels(auth, params, pl.id, ua)
            // a portal that dropped the genre request answers with everything in "Live TV": keep the groups it had
            val live = fetched.map { ch ->
                val before = oldTvGroups[ch.streamId]
                if (before != null && ch.groupTitle == "Live TV" && before != "Live TV") ch.copy(groupTitle = before) else ch
            }
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
                onProgress(if (type == ContentType.MOVIE) "Loading movie categories..." else "Loading show categories...")
                val catalog = stalkerClient.fetchVodCatalog(type, auth, params, ua) ?: continue
                fetchedAnything = true
                // what was downloaded before is stale now (it comes back category by category when opened); My list stays
                channelDao.deleteNonFavoritesByType(pl.id, type)
                forgetVodState(pl.id, type)
                syncGroupTitles(pl.id, mapOf(type to catalog.categories.map { it.title }), pl.showNewlyAddedGroups)
                vodTotals[pl.id to type] = catalog.total.takeIf { it > 0 } ?: catalog.categories.size
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
        // movies / shows are not downloaded up front: their numbers are the portal's totals
        val movies = if (pl.includeVod) vodTotals[pl.id to ContentType.MOVIE] ?: pl.movieCount else 0
        val shows = if (pl.includeVod) vodTotals[pl.id to ContentType.SHOW] ?: pl.showCount else 0
        // movies / shows switched off: their groups go with them (live TV groups are never touched here)
        if (!pl.includeVod) {
            channelGroupDao.deleteGroupsByPlaylistAndType(pl.id, ContentType.MOVIE)
            channelGroupDao.deleteGroupsByPlaylistAndType(pl.id, ContentType.SHOW)
        }
        if (tv == 0 && !pl.includeTv) channelGroupDao.deleteGroupsByPlaylistAndType(pl.id, ContentType.TV)
        playlistDao.updateContentCounts(pl.id, tv, movies, shows, expiration, System.currentTimeMillis())
        if (tv > 0) epgScope.launch { refreshPortalEpg(pl.id); remapEpg(pl.id) }
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
