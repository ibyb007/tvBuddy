package com.example.data.network

import android.util.JsonReader
import com.example.data.local.entity.ChannelEntity
import com.example.data.model.ContentType
import com.example.data.local.entity.ProgramEntity
import com.example.data.model.VodInfo
import com.example.data.epg.EpgMatcher
import com.example.data.util.LogoUrl
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.InputStreamReader
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class XtreamAuthResult(
    val success: Boolean,
    val message: String = "",
    val serverUrl: String = "",
    val expDate: String? = null,
    val maxConnections: String? = null,
    /** Container the live streams are requested in: m3u8 when the account allows it, else ts. */
    val liveExtension: String = "m3u8"
)

class XtreamClient(
    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
) {

    /** The channel list of a big panel can take a minute: longer read timeout, and a few retries (see [loadLive]). */
    private val bulkHttp: OkHttpClient by lazy {
        okHttpClient.newBuilder().readTimeout(90, TimeUnit.SECONDS).build()
    }

    private fun enc(v: String) = URLEncoder.encode(v, "UTF-8")

    /** The panel's own XMLTV guide: its channel ids are exactly the `epg_channel_id`s of the live streams. */
    fun xmltvUrl(serverUrl: String, username: String, password: String): String =
        "${cleanServerUrl(serverUrl)}/xmltv.php?username=${enc(username)}&password=${enc(password)}"

    private fun api(cleanUrl: String, username: String, password: String, action: String? = null): String {
        val base = "$cleanUrl/player_api.php?username=${enc(username)}&password=${enc(password)}"
        return if (action == null) base else "$base&action=$action"
    }

    suspend fun authenticate(
        serverUrl: String,
        username: String,
        password: String,
        userAgent: String
    ): XtreamAuthResult = withContext(Dispatchers.IO) {
        val cleanUrl = cleanServerUrl(serverUrl)
        val request = Request.Builder()
            .url(api(cleanUrl, username, password))
            .header("User-Agent", userAgent)
            .build()

        try {
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext XtreamAuthResult(false, "Server returned HTTP ${response.code}")
                }
                val body = response.body?.string() ?: ""
                val json = JSONObject(body)
                val userInfo = json.optJSONObject("user_info")
                if (userInfo != null) {
                    val auth = userInfo.optInt("auth", 0)
                    val status = userInfo.optString("status", "")
                    if (auth == 1 || status.equals("Active", ignoreCase = true)) {
                        val exp = userInfo.optString("exp_date", "Unknown")
                        val maxCons = userInfo.optString("max_connections", "1")
                        // allowed_output_formats: ["m3u8","ts"] - an account that only allows ts must not get .m3u8 links
                        val formats = userInfo.optJSONArray("allowed_output_formats")
                        val allowed = (0 until (formats?.length() ?: 0)).map { formats!!.optString(it).lowercase() }
                        val ext = if (allowed.isNotEmpty() && "m3u8" !in allowed && "ts" in allowed) "ts" else "m3u8"
                        return@withContext XtreamAuthResult(
                            success = true,
                            serverUrl = cleanUrl,
                            expDate = exp,
                            maxConnections = maxCons,
                            liveExtension = ext
                        )
                    } else {
                        val msg = userInfo.optString("message", "Authentication denied: $status")
                        return@withContext XtreamAuthResult(false, msg)
                    }
                }
                XtreamAuthResult(false, "Invalid Xtream response format")
            }
        } catch (e: Exception) {
            XtreamAuthResult(false, "Network error: ${e.localizedMessage}")
        }
    }

    /**
     * Live channels and / or VOD (movies and shows) of an Xtream Codes account.
     * Movies and shows are returned as [ContentType.MOVIE] / [ContentType.SHOW] entries; a show is one entry
     * per series (its episodes are not expanded here).
     */
    suspend fun fetchChannels(
        serverUrl: String,
        username: String,
        password: String,
        playlistId: Long,
        userAgent: String,
        includeTv: Boolean = true,
        includeVod: Boolean = false,
        liveExtension: String = "m3u8"
    ): List<ChannelEntity> = withContext(Dispatchers.IO) {
        val cleanUrl = cleanServerUrl(serverUrl)
        val out = ArrayList<ChannelEntity>()

        if (includeTv) {
            val cats = fetchCategories(cleanUrl, username, password, "get_live_categories", userAgent)
            fun liveOf(item: Map<String, String>, no: Int): ChannelEntity? {
                val streamId = item["stream_id"].orEmpty()
                if (streamId.isBlank()) return null
                return ChannelEntity(
                    playlistId = playlistId,
                    streamId = streamId,
                    name = item["name"]?.trim()?.ifBlank { null } ?: "Channel $streamId",
                    streamUrl = "$cleanUrl/live/$username/$password/$streamId.$liveExtension",
                    logoUrl = LogoUrl.normalize(item["stream_icon"], cleanUrl),
                    groupTitle = cats[item["category_id"].orEmpty()] ?: "Live TV",
                    channelNumber = item["num"]?.toIntOrNull() ?: no,
                    contentType = ContentType.TV,
                    httpUserAgent = userAgent,
                    epgChannelId = item["epg_channel_id"]?.trim()?.ifBlank { null }
                )
            }
            // one attempt = a fresh list, so a request that broke half way never leaves half a channel list behind
            fun loadLive(url: String): List<ChannelEntity>? {
                val list = ArrayList<ChannelEntity>()
                val ok = streamArray(url, userAgent, bulkHttp) { item -> liveOf(item, list.size + 1)?.let(list::add) }
                return if (ok) list else null
            }
            var live: List<ChannelEntity>? = null
            for (attempt in 0 until 3) {
                live = loadLive(api(cleanUrl, username, password, "get_live_streams"))
                if (!live.isNullOrEmpty()) break
                if (attempt < 2) try { Thread.sleep(600L * (attempt + 1)) } catch (_: InterruptedException) { break }
            }
            // a panel that cannot serve everything in one go (timeouts on 20k channels) can still serve it category by category
            if (live.isNullOrEmpty() && cats.isNotEmpty()) {
                val merged = LinkedHashMap<String, ChannelEntity>()
                for (catId in cats.keys) {
                    val part = loadLive(api(cleanUrl, username, password, "get_live_streams") + "&category_id=${enc(catId)}") ?: continue
                    part.forEach { merged.putIfAbsent(it.streamId, it) }
                }
                live = merged.values.toList()
            }
            // the same stream id can be listed twice by sloppy panels
            live?.let { out.addAll(it.distinctBy { c -> c.streamId }) }
        }

        if (includeVod) {
            val movieCats = fetchCategories(cleanUrl, username, password, "get_vod_categories", userAgent)
            var movieNo = 0
            streamArray(api(cleanUrl, username, password, "get_vod_streams"), userAgent) { item ->
                val streamId = item["stream_id"].orEmpty()
                if (streamId.isBlank()) return@streamArray
                val ext = item["container_extension"]?.ifBlank { null } ?: "mp4"
                movieNo++
                out.add(
                    ChannelEntity(
                        playlistId = playlistId,
                        streamId = streamId,
                        name = item["name"]?.ifBlank { null } ?: "Movie $streamId",
                        streamUrl = "$cleanUrl/movie/$username/$password/$streamId.$ext",
                        logoUrl = LogoUrl.normalize(item["stream_icon"], cleanUrl),
                        groupTitle = movieCats[item["category_id"].orEmpty()] ?: "Movies",
                        channelNumber = movieNo,
                        contentType = ContentType.MOVIE,
                        rating = VodInfo.cleanRating(item["rating"]).ifBlank { null },
                        httpUserAgent = userAgent
                    )
                )
            }

            val showCats = fetchCategories(cleanUrl, username, password, "get_series_categories", userAgent)
            var showNo = 0
            streamArray(api(cleanUrl, username, password, "get_series"), userAgent) { item ->
                val seriesId = item["series_id"].orEmpty()
                if (seriesId.isBlank()) return@streamArray
                showNo++
                out.add(
                    ChannelEntity(
                        playlistId = playlistId,
                        streamId = seriesId,
                        name = item["name"]?.ifBlank { null } ?: "Show $seriesId",
                        // a show is not directly playable: its episodes come from get_series_info
                        streamUrl = "xtream-series://$seriesId",
                        logoUrl = LogoUrl.normalize(item["cover"], cleanUrl),
                        groupTitle = showCats[item["category_id"].orEmpty()] ?: "Shows",
                        channelNumber = showNo,
                        contentType = ContentType.SHOW,
                        rating = VodInfo.cleanRating(item["rating"]).ifBlank { null },
                        httpUserAgent = userAgent
                    )
                )
            }
        }
        out
    }

    /**
     * The on-demand details call (get_vod_info / get_series_info): plot, cast, director, genre, release date,
     * duration and the 16:9 backdrop. Returns null when the server has nothing for this id.
     */
    suspend fun fetchInfo(
        serverUrl: String,
        username: String,
        password: String,
        id: String,
        isSeries: Boolean,
        userAgent: String
    ): VodInfo? = withContext(Dispatchers.IO) {
        val cleanUrl = cleanServerUrl(serverUrl)
        val url = if (isSeries) api(cleanUrl, username, password, "get_series_info") + "&series_id=${enc(id)}"
        else api(cleanUrl, username, password, "get_vod_info") + "&vod_id=${enc(id)}"
        try {
            val req = Request.Builder().url(url).header("User-Agent", userAgent).build()
            okHttpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val root = JSONObject(resp.body?.string().orEmpty())
                val info = root.optJSONObject("info") ?: return@withContext null
                // backdrop_path is normally an array of urls, some panels send a single string
                val backdrop = info.optJSONArray("backdrop_path")?.optString(0).orEmpty()
                    .ifBlank { info.optString("backdrop_path").takeIf { it.startsWith("http") }.orEmpty() }
                val minutes = info.optInt("duration_secs", 0) / 60
                val duration = when {
                    minutes > 0 -> VodInfo.formatMinutes(minutes)
                    info.optString("episode_run_time").toIntOrNull() != null -> VodInfo.formatMinutes(info.optString("episode_run_time").toInt())
                    else -> info.optString("duration").takeIf { it != "null" && !it.startsWith("00:00") }.orEmpty()
                }
                val date = listOf("releasedate", "releaseDate", "release_date").map { info.optString(it) }
                    .firstOrNull { it.isNotBlank() && it != "null" }.orEmpty()
                fun s(vararg keys: String) = keys.map { info.optString(it) }.firstOrNull { it.isNotBlank() && it != "null" }.orEmpty()
                VodInfo(
                    plot = s("plot", "description"),
                    cast = s("cast", "actors"),
                    director = s("director"),
                    genre = s("genre"),
                    year = date.take(4).takeIf { it.length == 4 && it.all(Char::isDigit) }.orEmpty(),
                    duration = duration,
                    rating = VodInfo.cleanRating(s("rating")),
                    backdropUrl = backdrop,
                    trailer = s("youtube_trailer")
                )
            }
        } catch (_: Exception) {
            null
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Portal EPG (IPTVnator's Xtream flow): get_short_epg for the "now / next" of one channel, the full
    // get_simple_data_table (with the old misspelled get_simple_date_table as fallback) for its whole schedule.
    // Titles and descriptions come base64 encoded.
    // ---------------------------------------------------------------------------------------------

    /** Next [limit] programmes of one live stream, stored under the portal key of ([playlistId], [streamId]). */
    suspend fun fetchShortEpg(
        serverUrl: String, username: String, password: String,
        playlistId: Long, streamId: String, limit: Int, userAgent: String
    ): List<ProgramEntity> = withContext(Dispatchers.IO) {
        val url = api(cleanServerUrl(serverUrl), username, password, "get_short_epg") +
            "&stream_id=${enc(streamId)}&limit=$limit"
        parseEpgListings(getJsonOrNull(url, userAgent), EpgMatcher.portalKey(playlistId, streamId))
    }

    /** Whole schedule of one live stream (what the panel keeps: usually a few days). */
    suspend fun fetchFullEpg(
        serverUrl: String, username: String, password: String,
        playlistId: Long, streamId: String, userAgent: String
    ): List<ProgramEntity> = withContext(Dispatchers.IO) {
        val base = api(cleanServerUrl(serverUrl), username, password, "get_simple_data_table") + "&stream_id=${enc(streamId)}"
        val key = EpgMatcher.portalKey(playlistId, streamId)
        val first = parseEpgListings(getJsonOrNull(base, userAgent), key)
        if (first.isNotEmpty()) return@withContext first
        val legacy = base.replace("get_simple_data_table", "get_simple_date_table")
        parseEpgListings(getJsonOrNull(legacy, userAgent), key)
    }

    private fun getJsonOrNull(url: String, userAgent: String): JSONObject? = try {
        val req = Request.Builder().url(url).header("User-Agent", userAgent).build()
        okHttpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) null else {
                val text = resp.body?.string().orEmpty().trim()
                if (text.startsWith("{")) JSONObject(text) else null
            }
        }
    } catch (_: Exception) {
        null
    }

    /** epg_listings is an array on most panels and an object keyed by index on some. */
    private fun parseEpgListings(root: JSONObject?, key: String): List<ProgramEntity> {
        val raw = root?.opt("epg_listings") ?: return emptyList()
        val items = ArrayList<JSONObject>()
        when (raw) {
            is org.json.JSONArray -> for (i in 0 until raw.length()) raw.optJSONObject(i)?.let(items::add)
            is JSONObject -> raw.keys().forEach { k -> raw.optJSONObject(k)?.let(items::add) }
        }
        val out = ArrayList<ProgramEntity>(items.size)
        for (o in items) {
            var start = o.optString("start_timestamp").toLongOrNull()?.times(1000L) ?: 0L
            var stop = o.optString("stop_timestamp").toLongOrNull()?.times(1000L) ?: 0L
            if (start <= 0L) start = parsePanelTime(o.optString("start"))
            if (stop <= 0L) stop = parsePanelTime(o.optString("end").ifBlank { o.optString("stop") })
            val title = decodeEpgText(o.optString("title")).trim()
            if (start <= 0L || stop <= start || title.isEmpty()) continue
            out += ProgramEntity(
                epgChannelId = key,
                title = title,
                description = decodeEpgText(o.optString("description")).trim().ifBlank { null },
                startTimeMs = start,
                endTimeMs = stop
            )
        }
        return out.sortedBy { it.startTimeMs }
    }

    /** "yyyy-MM-dd HH:mm:ss" in the panel's own clock; only used when a listing carries no unix timestamps. */
    private fun parsePanelTime(v: String): Long {
        val m = Regex("^(\\d{4})-(\\d{2})-(\\d{2})[ T](\\d{2}):(\\d{2})(?::(\\d{2}))?").find(v.trim()) ?: return 0L
        return try {
            val cal = java.util.GregorianCalendar(
                m.groupValues[1].toInt(), m.groupValues[2].toInt() - 1, m.groupValues[3].toInt(),
                m.groupValues[4].toInt(), m.groupValues[5].toInt(), m.groupValues[6].ifEmpty { "0" }.toInt()
            )
            cal.timeInMillis
        } catch (_: Exception) { 0L }
    }

    /**
     * Panels send EPG text base64 encoded, a few send it plain: decode only when the text really is base64 of
     * valid UTF-8 (a plain word like "News" is also valid base64 and must not be turned into garbage).
     */
    private fun decodeEpgText(s: String): String {
        val t = s.trim()
        if (t.length < 4 || t.length % 4 != 0 || !t.matches(Regex("[A-Za-z0-9+/]+={0,2}"))) return s
        return try {
            val bytes = Base64.decode(t, Base64.DEFAULT)
            val text = String(bytes, Charsets.UTF_8)
            if (text.any { it == '\uFFFD' || (it.isISOControl() && it != '\n' && it != '\r' && it != '\t') }) s else text
        } catch (_: Exception) { s }
    }

    /**
     * Category id -> name. Retried: a panel that is busy drops the odd request, and a dropped one used to leave
     * every channel in a single "Live TV" group.
     */
    private fun fetchCategories(
        cleanUrl: String,
        username: String,
        password: String,
        action: String,
        userAgent: String
    ): Map<String, String> {
        for (attempt in 0 until 3) {
            val map = HashMap<String, String>()
            val ok = streamArray(api(cleanUrl, username, password, action), userAgent) { obj ->
                val id = obj["category_id"].orEmpty()
                val name = obj["category_name"].orEmpty()
                if (id.isNotBlank() && name.isNotBlank()) map[id] = name
            }
            if (ok) return map
            if (attempt < 2) try { Thread.sleep(400L * (attempt + 1)) } catch (_: InterruptedException) { break }
        }
        return emptyMap()
    }

    /** Streams the array behind [url] item by item. A failing request just yields no items. */
    private fun streamArray(
        url: String,
        userAgent: String,
        client: OkHttpClient = okHttpClient,
        onItem: (Map<String, String>) -> Unit
    ): Boolean {
        val request = Request.Builder().url(url).header("User-Agent", userAgent).build()
        return try {
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@use false
                val stream = resp.body?.byteStream() ?: return@use false
                JsonReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
                    JsonStream.forEachObject(reader, onItem)
                }
                true
            }
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    private fun cleanServerUrl(url: String): String {
        var clean = url.trim()
        if (!clean.startsWith("http://") && !clean.startsWith("https://")) {
            clean = "http://$clean"
        }
        return clean.removeSuffix("/")
    }
}
