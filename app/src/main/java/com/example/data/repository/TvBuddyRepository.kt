package com.example.data.repository

import android.content.Context
import com.example.data.local.TvBuddyDatabase
import com.example.data.local.entity.ChannelEntity
import com.example.data.local.entity.HistoryEntity
import com.example.data.local.entity.PlaylistEntity
import com.example.data.model.DrmConfig
import com.example.data.model.DrmType
import com.example.data.model.PlaylistType
import com.example.data.model.SpoofPreset
import com.example.data.model.SpoofProfile
import com.example.data.network.StalkerClient
import com.example.data.network.XtreamClient
import com.example.data.parser.JsonPlaylistParser
import com.example.data.parser.M3uParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

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

    // EPG sources declared by playlists (#EXTM3U url-tvg="...") are fetched in the background
    private val epgScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val epgPrefs by lazy { context.getSharedPreferences("tvbuddy_epg", Context.MODE_PRIVATE) }

    private fun startPlaylistEpgImport(playlistId: Long, urls: List<String>, userAgent: String) {
        if (urls.isEmpty()) return
        epgPrefs.edit().putStringSet("urls_$playlistId", urls.toSet()).apply()
        epgScope.launch { fetchEpgUrls(playlistId, urls, userAgent) }
    }

    private suspend fun fetchEpgUrls(playlistId: Long, urls: List<String>, userAgent: String) {
        var anyOk = false
        for (u in urls) {
            if (importXmltv(u, false, userAgent).isSuccess) anyOk = true
        }
        if (anyOk) epgPrefs.edit().putLong("ts_$playlistId", System.currentTimeMillis()).apply()
    }

    /** Re-downloads playlist EPG sources that have not been refreshed for 12 hours. */
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

    val allPlaylists: Flow<List<PlaylistEntity>> = playlistDao.getAllPlaylists()
    val allChannels: Flow<List<ChannelEntity>> = channelDao.getAllChannels()
    val favoriteChannels: Flow<List<ChannelEntity>> = channelDao.getFavoriteChannels()
    val allCategories: Flow<List<String>> = channelDao.getAllCategories()
    val playbackHistory: Flow<List<HistoryEntity>> = historyDao.getRecentHistory()
    val allCurrentPrograms: Flow<List<com.example.data.local.entity.ProgramEntity>> = programDao.getAllCurrentPrograms()

    /** No bundled demo content: the app starts empty and removes the old demo playlist from earlier installs. */
    suspend fun initializeDefaultsIfNeeded() = withContext(Dispatchers.IO) {
        playlistDao.getAllPlaylists().first()
            .filter { it.sourceUrl == "local://curated_demo.m3u" }
            .forEach { demo ->
                channelDao.deleteChannelsByPlaylist(demo.id)
                playlistDao.deletePlaylist(demo)
            }
    }

    fun getScheduleForChannel(epgChannelId: String): Flow<List<com.example.data.local.entity.ProgramEntity>> {
        return programDao.getScheduleForChannel(epgChannelId)
    }

    suspend fun importXmltv(
        urlOrContent: String,
        isRawText: Boolean,
        userAgent: String
    ): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val programs = if (isRawText) {
                com.example.data.parser.XmltvParser.parseString(urlOrContent)
            } else {
                val req = Request.Builder().url(urlOrContent).header("User-Agent", userAgent).build()
                okHttpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) throw Exception("HTTP ${resp.code}: ${resp.message}")
                    val rawStream = resp.body?.byteStream() ?: throw Exception("Empty response body")
                    val bodyStream = java.io.BufferedInputStream(rawStream)
                    bodyStream.mark(2)
                    val b0 = bodyStream.read()
                    val b1 = bodyStream.read()
                    bodyStream.reset()
                    val isGzip = b0 == 0x1f && b1 == 0x8b
                    if (isGzip) {
                        java.util.zip.GZIPInputStream(bodyStream).use { gzStream ->
                            com.example.data.parser.XmltvParser.parse(gzStream)
                        }
                    } else {
                        com.example.data.parser.XmltvParser.parse(bodyStream)
                    }
                }
            }

            if (programs.isNotEmpty()) {
                // Replace (not duplicate) the schedule of every channel contained in this import
                programs.map { it.epgChannelId }.distinct().chunked(500).forEach {
                    programDao.deleteProgramsForChannels(it)
                }
                programDao.insertPrograms(programs)
            }
            Result.success(programs.size)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun getChannelsByPlaylist(playlistId: Long): Flow<List<ChannelEntity>> {
        return channelDao.getChannelsByPlaylist(playlistId)
    }

    fun searchChannels(query: String): Flow<List<ChannelEntity>> {
        return channelDao.searchChannels(query)
    }

    suspend fun toggleFavorite(channelId: Long, currentFavorite: Boolean) = withContext(Dispatchers.IO) {
        channelDao.updateFavorite(channelId, !currentFavorite)
    }

    suspend fun recordHistory(channel: ChannelEntity) = withContext(Dispatchers.IO) {
        // keep one row per channel so "recent channels" stays unique, newest first
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

    suspend fun deletePlaylist(playlist: PlaylistEntity) = withContext(Dispatchers.IO) {
        channelDao.deleteChannelsByPlaylist(playlist.id)
        playlistDao.deletePlaylist(playlist)
        epgPrefs.edit().remove("urls_${playlist.id}").remove("ts_${playlist.id}").apply()
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
            var effectiveHeaders = spoof.getEffectiveHeaders()

            val content = if (isRawContent) {
                sourceUrlOrContent
            } else {
                // Some providers answer unknown players with an HTML page / a redirect to Telegram
                // instead of the playlist. Try the chosen signature first, then fall back through
                // the other player signatures until a real M3U comes back.
                val candidates = buildList {
                    add(spoof)
                    SpoofPreset.entries
                        .filter { it != spoofPreset && it != SpoofPreset.CUSTOM && it != SpoofPreset.EXOPLAYER }
                        .forEach { add(SpoofProfile(it, "", customHeaders)) }
                }
                var found: String? = null
                var lastProblem = "No valid M3U returned"
                for (candidate in candidates) {
                    val headers = candidate.getEffectiveHeaders()
                    try {
                        val reqBuilder = Request.Builder().url(sourceUrlOrContent)
                        headers.forEach { (k, v) -> reqBuilder.header(k, v) }
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
                        if (body != null) {
                            found = body
                            spoof = candidate
                            effectiveHeaders = headers
                            break
                        }
                    } catch (e: Exception) {
                        lastProblem = e.message ?: e.javaClass.simpleName
                    }
                }
                found ?: throw Exception(lastProblem)
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

            if (channels.isNotEmpty()) {
                channelDao.insertChannels(channels)
                playlistDao.updateChannelCount(playlistId, channels.size)
            }

            // url-tvg="..." in the #EXTM3U header = this playlist's EPG source
            startPlaylistEpgImport(playlistId, M3uParser.extractEpgUrls(content), spoof.getUserAgent())

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
            if (!auth.success) {
                return@withContext Result.failure(Exception(auth.message))
            }

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

            val channels = xtreamClient.fetchChannels(
                serverUrl = serverUrl,
                username = username,
                password = password,
                playlistId = playlistId,
                userAgent = spoof.getUserAgent()
            )

            if (channels.isNotEmpty()) {
                channelDao.insertChannels(channels)
                playlistDao.updateChannelCount(playlistId, channels.size)
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
            if (!auth.success) {
                return@withContext Result.failure(Exception(auth.message))
            }

            val playlistId = playlistDao.insertPlaylist(
                PlaylistEntity(
                    name = name,
                    type = PlaylistType.STALKER,
                    sourceUrl = portalUrl,
                    macAddress = macAddress,
                    spoofPreset = spoofPreset.name
                )
            )

            val channels = stalkerClient.fetchChannels(
                portalUrl = portalUrl,
                macAddress = macAddress,
                playlistId = playlistId,
                userAgent = spoof.getUserAgent()
            )

            if (channels.isNotEmpty()) {
                channelDao.insertChannels(channels)
                playlistDao.updateChannelCount(playlistId, channels.size)
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
            val content = if (isRawContent) {
                sourceUrlOrContent
            } else {
                val req = Request.Builder()
                    .url(sourceUrlOrContent)
                    .header("User-Agent", spoof.getUserAgent())
                    .build()
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

            val channels = JsonPlaylistParser.parse(
                content = content,
                playlistId = playlistId,
                defaultUserAgent = spoof.getUserAgent(),
                defaultHeaders = spoof.getEffectiveHeaders()
            )

            if (channels.isNotEmpty()) {
                channelDao.insertChannels(channels)
                playlistDao.updateChannelCount(playlistId, channels.size)
            }

            Result.success(channels.size)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
