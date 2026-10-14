package com.example.data.network

import com.example.data.local.entity.ChannelEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class StalkerAuthResult(
    val success: Boolean,
    val message: String = "",
    val token: String = "",
    val portalUrl: String = ""
)

class StalkerClient(
    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
) {

    private fun getLoadPhpUrl(portalUrl: String): String {
        var clean = portalUrl.trim().removeSuffix("/")
        if (!clean.startsWith("http://") && !clean.startsWith("https://")) {
            clean = "http://$clean"
        }
        return if (clean.endsWith("/server/load.php")) {
            clean
        } else if (clean.endsWith("/c")) {
            clean.removeSuffix("/c") + "/server/load.php"
        } else {
            "$clean/server/load.php"
        }
    }

    private fun formatMac(mac: String): String {
        return mac.trim().uppercase()
    }

    suspend fun handshake(
        portalUrl: String,
        macAddress: String,
        userAgent: String
    ): StalkerAuthResult = withContext(Dispatchers.IO) {
        val loadPhp = getLoadPhpUrl(portalUrl)
        val mac = formatMac(macAddress)
        val url = "$loadPhp?type=stb&action=handshake&token=&mac=$mac"

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .header("Cookie", "mac=$mac; stb_lang=en; timezone=GMT")
            .header("X-User-Agent", "Model: MAG250; Link: Ethernet")
            .build()

        try {
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext StalkerAuthResult(false, "Portal HTTP Error ${response.code}")
                }
                val body = response.body?.string() ?: ""
                val json = JSONObject(body)
                val js = json.optJSONObject("js") ?: json
                val token = js.optString("token")
                if (token.isNotBlank()) {
                    return@withContext StalkerAuthResult(
                        success = true,
                        token = token,
                        portalUrl = loadPhp
                    )
                }
                StalkerAuthResult(false, "No handshake token returned from portal")
            }
        } catch (e: Exception) {
            StalkerAuthResult(false, "Handshake failed: ${e.localizedMessage}")
        }
    }

    suspend fun fetchChannels(
        portalUrl: String,
        macAddress: String,
        playlistId: Long,
        userAgent: String
    ): List<ChannelEntity> = withContext(Dispatchers.IO) {
        val auth = handshake(portalUrl, macAddress, userAgent)
        if (!auth.success) return@withContext emptyList()

        val loadPhp = auth.portalUrl
        val mac = formatMac(macAddress)
        val token = auth.token

        // Fetch genres
        val genreMap = mutableMapOf<String, String>()
        try {
            val genreUrl = "$loadPhp?type=itv&action=get_genres&mac=$mac"
            val genreReq = Request.Builder()
                .url(genreUrl)
                .header("User-Agent", userAgent)
                .header("Authorization", "Bearer $token")
                .header("Cookie", "mac=$mac; stb_lang=en; timezone=GMT")
                .build()

            okHttpClient.newCall(genreReq).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string() ?: ""
                    val root = JSONObject(body)
                    val js = root.optJSONObject("js")
                    val items = js?.optJSONArray("data") ?: root.optJSONArray("js") ?: JSONArray()
                    for (i in 0 until items.length()) {
                        val g = items.optJSONObject(i) ?: continue
                        val id = g.optString("id")
                        val title = g.optString("title")
                        if (id.isNotBlank() && title.isNotBlank()) {
                            genreMap[id] = title
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        // Fetch channels
        val channels = mutableListOf<ChannelEntity>()
        try {
            val chanUrl = "$loadPhp?type=itv&action=get_all_channels&mac=$mac"
            val chanReq = Request.Builder()
                .url(chanUrl)
                .header("User-Agent", userAgent)
                .header("Authorization", "Bearer $token")
                .header("Cookie", "mac=$mac; stb_lang=en; timezone=GMT")
                .build()

            okHttpClient.newCall(chanReq).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string() ?: ""
                    val root = JSONObject(body)
                    val js = root.optJSONObject("js")
                    val items = js?.optJSONArray("data") ?: root.optJSONArray("js") ?: JSONArray()

                    for (i in 0 until items.length()) {
                        val ch = items.optJSONObject(i) ?: continue
                        val id = ch.optString("id", "${i + 1}")
                        val name = ch.optString("name", "Channel $id")
                        val number = ch.optInt("number", i + 1)
                        val genreId = ch.optString("tv_genre_id")
                        val groupTitle = genreMap[genreId] ?: "Live TV"
                        val logo = ch.optString("logo").ifBlank { null }
                        val cmd = ch.optString("cmd")

                        // Extract clean stream url or cmd
                        var streamUrl = cmd
                        if (streamUrl.startsWith("ffmpeg ", ignoreCase = true)) {
                            streamUrl = streamUrl.substring(7).trim()
                        } else if (streamUrl.startsWith("ffrt ", ignoreCase = true)) {
                            streamUrl = streamUrl.substring(5).trim()
                        } else if (streamUrl.startsWith("auto ", ignoreCase = true)) {
                            streamUrl = streamUrl.substring(5).trim()
                        }

                        channels.add(
                            ChannelEntity(
                                playlistId = playlistId,
                                streamId = id,
                                name = name,
                                streamUrl = streamUrl,
                                logoUrl = logo,
                                groupTitle = groupTitle,
                                channelNumber = number,
                                httpUserAgent = null // spoof is for API/playlist requests only, not playback
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

    suspend fun resolveStreamLink(
        portalUrl: String,
        macAddress: String,
        cmd: String,
        token: String,
        userAgent: String
    ): String = withContext(Dispatchers.IO) {
        val loadPhp = getLoadPhpUrl(portalUrl)
        val mac = formatMac(macAddress)
        val encodedCmd = URLEncoder.encode(cmd, "UTF-8")
        val url = "$loadPhp?type=itv&action=create_link&type=itv&cmd=$encodedCmd&mac=$mac"

        val req = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .header("Authorization", "Bearer $token")
            .header("Cookie", "mac=$mac; stb_lang=en; timezone=GMT")
            .build()

        try {
            okHttpClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string() ?: ""
                    val root = JSONObject(body)
                    val js = root.optJSONObject("js")
                    val cmdLink = js?.optString("cmd") ?: ""
                    if (cmdLink.isNotBlank()) {
                        return@withContext cmdLink.removePrefix("ffmpeg ").removePrefix("ffrt ").removePrefix("auto ")
                    }
                }
            }
        } catch (_: Exception) {}

        cmd
    }
}
