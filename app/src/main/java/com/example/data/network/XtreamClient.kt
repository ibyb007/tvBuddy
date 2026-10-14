package com.example.data.network

import com.example.data.local.entity.ChannelEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
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
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
) {

    suspend fun authenticate(
        serverUrl: String,
        username: String,
        password: String,
        userAgent: String
    ): XtreamAuthResult = withContext(Dispatchers.IO) {
        val cleanUrl = cleanServerUrl(serverUrl)
        val url = "$cleanUrl/player_api.php?username=$username&password=$password"

        val request = Request.Builder()
            .url(url)
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

    suspend fun fetchChannels(
        serverUrl: String,
        username: String,
        password: String,
        playlistId: Long,
        userAgent: String
    ): List<ChannelEntity> = withContext(Dispatchers.IO) {
        val cleanUrl = cleanServerUrl(serverUrl)

        // 1. Fetch categories
        val categoriesMap = mutableMapOf<String, String>()
        try {
            val catUrl = "$cleanUrl/player_api.php?username=$username&password=$password&action=get_live_categories"
            val catRequest = Request.Builder()
                .url(catUrl)
                .header("User-Agent", userAgent)
                .build()

            okHttpClient.newCall(catRequest).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: ""
                    val array = JSONArray(body)
                    for (i in 0 until array.length()) {
                        val obj = array.optJSONObject(i) ?: continue
                        val id = obj.optString("category_id")
                        val name = obj.optString("category_name")
                        if (id.isNotBlank() && name.isNotBlank()) {
                            categoriesMap[id] = name
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        // 2. Fetch live streams
        val channels = mutableListOf<ChannelEntity>()
        try {
            val streamsUrl = "$cleanUrl/player_api.php?username=$username&password=$password&action=get_live_streams"
            val streamsRequest = Request.Builder()
                .url(streamsUrl)
                .header("User-Agent", userAgent)
                .build()

            okHttpClient.newCall(streamsRequest).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: ""
                    val array = JSONArray(body)
                    for (i in 0 until array.length()) {
                        val item = array.optJSONObject(i) ?: continue
                        val streamId = item.optString("stream_id")
                        val name = item.optString("name", "Channel $streamId")
                        val streamIcon = item.optString("stream_icon").ifBlank { null }
                        val categoryId = item.optString("category_id")
                        val groupTitle = categoriesMap[categoryId] ?: "Live TV"
                        val num = item.optInt("num", i + 1)

                        // Standard Xtream live stream URL format:
                        val streamUrl = "$cleanUrl/live/$username/$password/$streamId.m3u8"

                        channels.add(
                            ChannelEntity(
                                playlistId = playlistId,
                                streamId = streamId,
                                name = name,
                                streamUrl = streamUrl,
                                logoUrl = streamIcon,
                                groupTitle = groupTitle,
                                channelNumber = num,
                                httpUserAgent = userAgent
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        channels
    }

    private fun cleanServerUrl(url: String): String {
        var clean = url.trim()
        if (!clean.startsWith("http://") && !clean.startsWith("https://")) {
            clean = "http://$clean"
        }
        return clean.removeSuffix("/")
    }
}
