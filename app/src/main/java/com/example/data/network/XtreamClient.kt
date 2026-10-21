package com.example.data.network

import android.util.JsonReader
import com.example.data.local.entity.ChannelEntity
import com.example.data.model.ContentType
import com.example.data.model.VodInfo
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
    val maxConnections: String? = null
)

class XtreamClient(
    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
) {

    private fun enc(v: String) = URLEncoder.encode(v, "UTF-8")

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
                        return@withContext XtreamAuthResult(
                            success = true,
                            serverUrl = cleanUrl,
                            expDate = exp,
                            maxConnections = maxCons
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
        includeVod: Boolean = false
    ): List<ChannelEntity> = withContext(Dispatchers.IO) {
        val cleanUrl = cleanServerUrl(serverUrl)
        val out = ArrayList<ChannelEntity>()

        if (includeTv) {
            val cats = fetchCategories(cleanUrl, username, password, "get_live_categories", userAgent)
            streamArray(api(cleanUrl, username, password, "get_live_streams"), userAgent) { item ->
                val streamId = item["stream_id"].orEmpty()
                if (streamId.isBlank()) return@streamArray
                out.add(
                    ChannelEntity(
                        playlistId = playlistId,
                        streamId = streamId,
                        name = item["name"]?.ifBlank { null } ?: "Channel $streamId",
                        streamUrl = "$cleanUrl/live/$username/$password/$streamId.m3u8",
                        logoUrl = item["stream_icon"]?.ifBlank { null },
                        groupTitle = cats[item["category_id"].orEmpty()] ?: "Live TV",
                        channelNumber = item["num"]?.toIntOrNull() ?: (out.size + 1),
                        contentType = ContentType.TV,
                        httpUserAgent = userAgent
                    )
                )
            }
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
                        logoUrl = item["stream_icon"]?.ifBlank { null },
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
                        logoUrl = item["cover"]?.ifBlank { null },
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

    private fun fetchCategories(
        cleanUrl: String,
        username: String,
        password: String,
        action: String,
        userAgent: String
    ): Map<String, String> {
        val map = HashMap<String, String>()
        try {
            streamArray(api(cleanUrl, username, password, action), userAgent) { obj ->
                val id = obj["category_id"].orEmpty()
                val name = obj["category_name"].orEmpty()
                if (id.isNotBlank() && name.isNotBlank()) map[id] = name
            }
        } catch (_: Exception) {
        }
        return map
    }

    /** Streams the array behind [url] item by item. A failing request just yields no items. */
    private fun streamArray(url: String, userAgent: String, onItem: (Map<String, String>) -> Unit) {
        val request = Request.Builder().url(url).header("User-Agent", userAgent).build()
        try {
            okHttpClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return
                val stream = resp.body?.byteStream() ?: return
                JsonReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
                    JsonStream.forEachObject(reader, onItem)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
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
