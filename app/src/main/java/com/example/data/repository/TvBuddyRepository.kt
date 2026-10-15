package com.example.data.repository

import android.content.Context
import com.example.data.local.TvBuddyDatabase
import com.example.data.local.entity.ChannelEntity
import com.example.data.local.entity.ChannelGroupEntity
import com.example.data.local.entity.EpgSourceEntity
import com.example.data.local.entity.HistoryEntity
import com.example.data.local.entity.PlaylistEpgCrossRef
import com.example.data.local.entity.PlaylistEntity
import com.example.data.model.PlaylistType
import com.example.data.model.SpoofPreset
import com.example.data.model.SpoofProfile
import com.example.data.network.StalkerClient
import com.example.data.network.XtreamClient
import com.example.data.parser.JsonPlaylistParser
import com.example.data.parser.M3uParser
import com.example.data.parser.XmltvParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
    val allCurrentPrograms: Flow<com.example.data.local.entity.ProgramEntity> // fixed type import
    val allEpgSources: Flow<List<EpgSourceEntity>> = epgSourceDao.getAllEpgSources()
    val allPlaylistEpgCrossRefs: Flow<List<PlaylistEpgCrossRef>> = epgSourceDao.getAllPlaylistEpgCrossRefs()
    val allChannelGroups: Flow<List<ChannelGroupEntity>> = channelGroupDao.getAllGroups()

    init {
        allCurrentPrograms = programDao.getAllCurrentPrograms() as Flow<com.example.data.local.entity.ProgramEntity>
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
    suspend fun syncGroupsForPlaylist(playlistId: Long, channels: List<ChannelEntity>, showNewlyAdded: Boolean) = withContext(Dispatchers.IO) {
        val existingGroups = channelGroupDao.getGroupsByPlaylistOnce(playlistId).associateBy { it.groupTitle }
        val distinctTitles = channels.map { it.groupTitle }.distinct().filter { it.isNotBlank() }
        
        val newGroupEntities = distinctTitles.mapIndexed { index, title ->
            val existing = existingGroups[title]
            if (existing != null) {
                existing.copy(originalOrder = index)
            } else {
                ChannelGroupEntity(
                    playlistId = playlistId,
                    groupTitle = title,
                    isVisible = showNewlyAdded,
                    originalOrder = index,
                    sortOrder = index
                )
            }
        }
        channelGroupDao.insertGroups(newGroupEntities)
    }

    // ---- EPG Source & Import management ----
    suspend fun addEpgSource(url: String, name: String = url.substringAfterLast("/").ifBlank { "EPG Source" }): Long = withContext(Dispatchers.IO) {
        val existing = epgSourceDao.getEpgSourceByUrl(url)
        if (existing != null) return@withContext existing.id
        val isFirst = epgSourceDao.getAllEpgSources().first().isEmpty()
        val src = EpgSourceEntity(name = name, url = url, isDefault = isFirst)
        val id = epgSourceDao.insertEpgSource(src)
        id
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

            // Purge anything older than retention threshold
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
    suspend fun deletePlaylist(playlist: PlaylistEntity) = withContext(Dispatchers.IO) {
        channelDao.deleteChannelsByPlaylist(playlist.id)
        channelGroupDao.deleteGroupsByPlaylist(playlist.id)
        epgSourceDao.clearAssignedSourcesForPlaylist(playlist.id)
        playlistDao.deletePlaylist(playlist)
    }

    suspend fun updatePlaylist(playlist: PlaylistEntity) = withContext(Dispatchers.IO) {
        playlistDao.updatePlaylist(playlist)
    }

    suspend fun reorderPlaylists(orderedIds: List<Long>) = withContext(Dispatchers.IO) {
        playlistDao.updatePlaylistOrders(orderedIds)
    }

    suspend fun setGroupVisibility(playlistId: Long, groupTitle: String, isVisible: Boolean) = withContext(Dispatchers.IO) {
        channelGroupDao.setGroupVisibility(playlistId, groupTitle, isVisible)
    }

    suspend fun setAllGroupsVisibility(playlistId: Long, isVisible: Boolean) = withContext(Dispatchers.IO) {
        channelGroupDao.setAllGroupsVisibility(playlistId, isVisible)
    }

    suspend fun reorderGroups(playlistId: Long, orderedTitles: List<String>) = withContext(Dispatchers.IO) {
        channelGroupDao.updateGroupOrders(playlistId, orderedTitles)
    }

    suspend fun addM3uPlaylist(
        name: String,
        sourceUrlOrContent: String,
        isRawContent: Boolean = false,
        spoofPreset: SpoofPreset = SpoofPreset.TIVIMATE,
        customUserAgent: String = "",
        customHeaders: Map<String, String> = emptyMap()
    ): Result<Int> = withContext(Dispatchers.IO) {
        try {
            var spoof = SpoofProfile(spoofPreset, customUserAgent, customHeaders)
            val content = if (isRawContent) sourceUrlOrContent else {
                val reqBuilder = Request.Builder().url(sourceUrlOrContent)
                spoof.getEffectiveHeaders().forEach { (k, v) -> reqBuilder.header(k, v) }
                okHttpClient.newCall(reqBuilder.build()).execute().use { resp ->
                    if (!resp.isSuccessful) throw Exception("HTTP ${resp.code}: ${resp.message}")
                    resp.body?.string() ?: ""
                }
            }

            val playlistId = playlistDao.insertPlaylist(
                PlaylistEntity(
                    name = name,
                    type = PlaylistType.M3U,
                    sourceUrl = if (isRawContent) "Pasted M3U" else sourceUrlOrContent,
                    spoofPreset = spoof.preset.name,
                    customUserAgent = customUserAgent.ifBlank { null }
                )
            )

            val channels = M3uParser.parse(
                content = content,
                playlistId = playlistId,
                defaultUserAgent = spoof.getUserAgent(),
                defaultHeaders = spoof.getEffectiveHeaders()
            )

            if (channels.isNotEmpty()) {
                channelDao.insertChannels(channels)
                playlistDao.updateChannelCount(playlistId, channels.size)
                syncGroupsForPlaylist(playlistId, channels, true)
            }

            // Extract url-tvg from M3U header and auto-assign
            val extEpgUrls = M3uParser.extractEpgUrls(content)
            for (url in extEpgUrls) {
                val srcId = addEpgSource(url)
                togglePlaylistEpgAssignment(playlistId, srcId, true)
            }

            Result.success(channels.size)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun addXtreamPlaylist(
        name: String,
        serverUrl: String,
        username: String,
        password: String,
        spoofPreset: SpoofPreset = SpoofPreset.TIVIMATE
    ): Result<Int> = withContext(Dispatchers.IO) {
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
                    spoofPreset = spoofPreset.name
                )
            )

            val channels = xtreamClient.fetchChannels(serverUrl, username, password, playlistId, spoof.getUserAgent())
            if (channels.isNotEmpty()) {
                channelDao.insertChannels(channels)
                playlistDao.updateChannelCount(playlistId, channels.size)
                syncGroupsForPlaylist(playlistId, channels, true)
            }
            Result.success(channels.size)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun addStalkerPlaylist(
        name: String,
        portalUrl: String,
        macAddress: String,
        spoofPreset: SpoofPreset = SpoofPreset.TIVIMATE
    ): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val spoof = SpoofProfile(spoofPreset)
            val auth = stalkerClient.handshake(portalUrl, macAddress, spoof.getUserAgent())
            if (!auth.success) return@withContext Result.failure(Exception(auth.message))

            val playlistId = playlistDao.insertPlaylist(
                PlaylistEntity(
                    name = name,
                    type = PlaylistType.STALKER,
                    sourceUrl = portalUrl,
                    macAddress = macAddress,
                    spoofPreset = spoofPreset.name
                )
            )

            val channels = stalkerClient.fetchChannels(portalUrl, macAddress, playlistId, spoof.getUserAgent())
            if (channels.isNotEmpty()) {
                channelDao.insertChannels(channels)
                playlistDao.updateChannelCount(playlistId, channels.size)
                syncGroupsForPlaylist(playlistId, channels, true)
            }
            Result.success(channels.size)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun addJsonPlaylist(
        name: String,
        sourceUrlOrContent: String,
        isRawContent: Boolean = false,
        spoofPreset: SpoofPreset = SpoofPreset.TIVIMATE
    ): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val spoof = SpoofProfile(spoofPreset)
            val content = if (isRawContent) sourceUrlOrContent else {
                val req = Request.Builder().url(sourceUrlOrContent).header("User-Agent", spoof.getUserAgent()).build()
                okHttpClient.newCall(req).execute().use { resp -> resp.body?.string() ?: "" }
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
            if (channels.isNotEmpty()) {
                channelDao.insertChannels(channels)
                playlistDao.updateChannelCount(playlistId, channels.size)
                syncGroupsForPlaylist(playlistId, channels, true)
            }
            Result.success(channels.size)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun getScheduleForChannel(epgChannelId: String): Flow<List<com.example.data.local.entity.ProgramEntity>> {
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

    suspend fun clearHistory() = withContext(Dispatchers.IO) {
        historyDao.clearHistory()
    }
}
