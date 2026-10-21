package com.example.data.network

import com.example.data.local.entity.ChannelEntity
import com.example.data.model.ContentType
import com.example.data.model.VodInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

data class StalkerAuthResult(
    val success: Boolean,
    val message: String = "",
    val token: String = "",
    val portalUrl: String = "",
    /** Account expiry as the portal reports it (e.g. "2026-10-29"), when it tells. */
    val expiration: String? = null
)

/**
 * Everything a Stalker portal may ask the box to identify itself with. Only the MAC address is required;
 * the rest is optional. Blank device ids / serial number are derived from the MAC like a real MAG box does.
 */
data class StalkerParams(
    val mac: String,
    val username: String = "",
    val password: String = "",
    val deviceId: String = "",
    val deviceId2: String = "",
    val serial: String = ""
) {
    val normalizedMac: String get() = mac.trim().uppercase()
    val effectiveSerial: String get() = serial.trim().ifBlank { digest("MD5", normalizedMac).take(13).uppercase() }
    val effectiveDeviceId: String get() = deviceId.trim().ifBlank { digest("SHA-256", normalizedMac).uppercase() }
    val effectiveDeviceId2: String get() = deviceId2.trim().ifBlank { effectiveDeviceId }

    private fun digest(algorithm: String, text: String): String =
        MessageDigest.getInstance(algorithm).digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
}

class StalkerClient(
    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
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

    private fun enc(v: String) = URLEncoder.encode(v, "UTF-8")

    private fun Request.Builder.stalkerHeaders(userAgent: String, mac: String, token: String? = null): Request.Builder {
        header("User-Agent", userAgent)
        header("Cookie", "mac=$mac; stb_lang=en; timezone=GMT")
        header("X-User-Agent", "Model: MAG250; Link: Ethernet")
        if (!token.isNullOrBlank()) header("Authorization", "Bearer $token")
        return this
    }

    private fun getJson(url: String, userAgent: String, mac: String, token: String?): JSONObject? {
        val req = Request.Builder().url(url).stalkerHeaders(userAgent, mac, token).build()
        return try {
            okHttpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) null else JSONObject(resp.body?.string().orEmpty())
            }
        } catch (_: Exception) {
            null
        }
    }

    /** Handshake only (kept for callers that just need a token). */
    suspend fun handshake(portalUrl: String, macAddress: String, userAgent: String): StalkerAuthResult =
        login(portalUrl, StalkerParams(macAddress), userAgent)

    /**
     * Full portal login: handshake, then (when given) username / password authentication, then the STB profile
     * with serial number and device ids, then the account's expiry date.
     */
    suspend fun login(portalUrl: String, params: StalkerParams, userAgent: String): StalkerAuthResult =
        withContext(Dispatchers.IO) {
            val loadPhp = getLoadPhpUrl(portalUrl)
            val mac = params.normalizedMac
            val hsReq = Request.Builder()
                .url("$loadPhp?type=stb&action=handshake&token=&mac=$mac")
                .stalkerHeaders(userAgent, mac)
                .build()

            var token = ""
            try {
                okHttpClient.newCall(hsReq).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@withContext StalkerAuthResult(false, "Portal HTTP Error ${response.code}")
                    }
                    val json = JSONObject(response.body?.string().orEmpty())
                    val js = json.optJSONObject("js") ?: json
                    token = js.optString("token")
                }
            } catch (e: Exception) {
                return@withContext StalkerAuthResult(false, "Handshake failed: ${e.localizedMessage}")
            }
            if (token.isBlank()) return@withContext StalkerAuthResult(false, "No handshake token returned from portal")

            // Optional username / password authentication
            if (params.username.isNotBlank()) {
                val url = "$loadPhp?type=stb&action=do_auth&login=${enc(params.username)}&password=${enc(params.password)}" +
                    "&device_id=${enc(params.effectiveDeviceId)}&device_id2=${enc(params.effectiveDeviceId2)}&mac=$mac"
                val auth = getJson(url, userAgent, mac, token)
                val ok = auth?.opt("js")?.let { it == true || (it is JSONObject) || it.toString() == "true" } ?: false
                if (!ok) return@withContext StalkerAuthResult(false, "Portal rejected the username / password")
            }

            // STB profile: portals that bind a box to serial / device ids need it before they list anything
            val profileUrl = "$loadPhp?type=stb&action=get_profile&hd=1&num_banks=2&stb_type=MAG250&client_type=STB" +
                "&image_version=218&video_out=hdmi&auth_second_step=1&not_valid_token=0&api_signature=262&JsHttpRequest=1-xml" +
                "&sn=${enc(params.effectiveSerial)}&device_id=${enc(params.effectiveDeviceId)}" +
                "&device_id2=${enc(params.effectiveDeviceId2)}&mac=$mac"
            val profile = getJson(profileUrl, userAgent, mac, token)
            // some portals hand out a fresh token with the profile
            val newToken = profile?.optJSONObject("js")?.optString("token").orEmpty()
            if (newToken.isNotBlank()) token = newToken

            // Expiry date (best effort): portals keep it in the profile or in the account info, under different names
            val expiryKeys = listOf("end_date", "expire_billing_date", "expiry_date", "expire_date", "phone")
            fun expiryOf(js: JSONObject?): String? = js?.let { o ->
                val dateLike = Regex("\\d{1,4}[-/.]\\d{1,2}[-/.]\\d{1,4}|\\d{1,2}\\s+[A-Za-z]{3,}\\s+\\d{2,4}|[A-Za-z]{3,}\\s+\\d{1,2},?\\s+\\d{4}")
                expiryKeys.firstNotNullOfOrNull { key ->
                    val v = o.optString(key).trim()
                    // "phone" is only an expiry text on some portals, elsewhere a real phone number: demand a date shape
                    v.takeIf {
                        it.isNotBlank() && it != "null" && it != "0" && !it.startsWith("0000") &&
                            (if (key == "phone") dateLike.containsMatchIn(it) else it.any(Char::isDigit))
                    }
                }
            }
            var expiration: String? = expiryOf(profile?.optJSONObject("js"))
            if (expiration == null) {
                val info = getJson("$loadPhp?type=account_info&action=get_main_info&JsHttpRequest=1-xml&mac=$mac", userAgent, mac, token)
                expiration = expiryOf(info?.optJSONObject("js"))
            }

            StalkerAuthResult(success = true, token = token, portalUrl = loadPhp, expiration = expiration)
        }

    suspend fun fetchChannels(
        portalUrl: String,
        macAddress: String,
        playlistId: Long,
        userAgent: String
    ): List<ChannelEntity> =
        fetchContent(portalUrl, StalkerParams(macAddress), playlistId, userAgent, includeTv = true, includeVod = false)

    /** Live TV and / or movies and shows (VOD) of a Stalker portal. */
    suspend fun fetchContent(
        portalUrl: String,
        params: StalkerParams,
        playlistId: Long,
        userAgent: String,
        includeTv: Boolean,
        includeVod: Boolean,
        session: StalkerAuthResult? = null
    ): List<ChannelEntity> = withContext(Dispatchers.IO) {
        val auth = session ?: login(portalUrl, params, userAgent)
        if (!auth.success) return@withContext emptyList()
        val out = ArrayList<ChannelEntity>()
        if (includeTv) out += fetchLive(auth, params, playlistId, userAgent)
        if (includeVod) {
            out += fetchVodKind("vod", ContentType.MOVIE, auth, params, playlistId, userAgent)
            out += fetchVodKind("series", ContentType.SHOW, auth, params, playlistId, userAgent)
        }
        out
    }

    private fun cleanCmd(cmd: String): String {
        var streamUrl = cmd.trim()
        for (prefix in listOf("ffmpeg ", "ffrt ", "auto ")) {
            if (streamUrl.startsWith(prefix, ignoreCase = true)) {
                streamUrl = streamUrl.substring(prefix.length).trim()
                break
            }
        }
        return streamUrl
    }

    private fun absolute(portalLoadPhp: String, path: String?): String? {
        if (path.isNullOrBlank() || path == "null") return null
        if (path.startsWith("http://") || path.startsWith("https://")) return path
        return try {
            val u = URI(portalLoadPhp)
            val port = if (u.port > 0) ":${u.port}" else ""
            "${u.scheme}://${u.host}$port/" + path.trimStart('/')
        } catch (_: Exception) {
            null
        }
    }

    /** The details a Stalker list item carries (plot, cast, ...) packed small; plots are cut to keep the database lean. */
    private fun metaOf(o: JSONObject, loadPhp: String): String? {
        fun v(vararg keys: String) = keys.map { o.optString(it).trim() }.firstOrNull { it.isNotBlank() && it != "null" }.orEmpty()
        val j = JSONObject()
        v("description").takeIf { it.isNotBlank() }?.let { j.put("p", it.take(700)) }
        v("actors").takeIf { it.isNotBlank() }?.let { j.put("c", it.take(200)) }
        v("director").takeIf { it.isNotBlank() }?.let { j.put("d", it.take(100)) }
        v("genres_str", "genre").takeIf { it.isNotBlank() }?.let { j.put("g", it.take(100)) }
        v("year").takeIf { it.isNotBlank() }?.let { j.put("y", it.take(4)) }
        v("time").toIntOrNull()?.let { VodInfo.formatMinutes(it) }?.takeIf { it.isNotBlank() }?.let { j.put("t", it) }
        absolute(loadPhp, v("backdrop", "pic", "backdrop_path"))?.let { j.put("b", it) }
        return if (j.length() == 0) null else j.toString()
    }

    private fun itemsOf(root: JSONObject?): JSONArray {
        val js = root?.optJSONObject("js")
        return js?.optJSONArray("data") ?: root?.optJSONArray("js") ?: JSONArray()
    }

    private fun fetchLive(
        auth: StalkerAuthResult,
        params: StalkerParams,
        playlistId: Long,
        userAgent: String
    ): List<ChannelEntity> {
        val loadPhp = auth.portalUrl
        val mac = params.normalizedMac
        val token = auth.token

        val genreMap = mutableMapOf<String, String>()
        val genres = itemsOf(getJson("$loadPhp?type=itv&action=get_genres&mac=$mac", userAgent, mac, token))
        for (i in 0 until genres.length()) {
            val g = genres.optJSONObject(i) ?: continue
            val id = g.optString("id")
            val title = g.optString("title")
            if (id.isNotBlank() && title.isNotBlank()) genreMap[id] = title
        }

        val channels = mutableListOf<ChannelEntity>()
        try {
            val items = itemsOf(getJson("$loadPhp?type=itv&action=get_all_channels&mac=$mac", userAgent, mac, token))
            for (i in 0 until items.length()) {
                val ch = items.optJSONObject(i) ?: continue
                val id = ch.optString("id", "${i + 1}")
                channels.add(
                    ChannelEntity(
                        playlistId = playlistId,
                        streamId = id,
                        name = ch.optString("name", "Channel $id"),
                        streamUrl = cleanCmd(ch.optString("cmd")),
                        logoUrl = ch.optString("logo").ifBlank { null },
                        groupTitle = genreMap[ch.optString("tv_genre_id")] ?: "Live TV",
                        channelNumber = ch.optInt("number", i + 1),
                        contentType = ContentType.TV,
                        httpUserAgent = userAgent
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return channels
    }

    /**
     * Movies ("vod") or shows ("series"): the portal lists them category by category in pages, so every
     * category is read page by page (several pages in parallel, the portal is only hit a few requests at a time).
     */
    private suspend fun fetchVodKind(
        kind: String,
        contentType: String,
        auth: StalkerAuthResult,
        params: StalkerParams,
        playlistId: Long,
        userAgent: String
    ): List<ChannelEntity> = coroutineScope {
        val loadPhp = auth.portalUrl
        val mac = params.normalizedMac
        val token = auth.token

        val categories = LinkedHashMap<String, String>()
        val catItems = itemsOf(getJson("$loadPhp?type=$kind&action=get_categories&mac=$mac", userAgent, mac, token))
        for (i in 0 until catItems.length()) {
            val c = catItems.optJSONObject(i) ?: continue
            val id = c.optString("id")
            val title = c.optString("title")
            if (id.isNotBlank() && id != "*" && title.isNotBlank()) categories[id] = title
        }
        if (categories.isEmpty()) categories["*"] = if (contentType == ContentType.MOVIE) "Movies" else "Shows"

        val gate = Semaphore(6)
        val byId = LinkedHashMap<String, ChannelEntity>()
        val lock = Any()
        var counter = 0

        fun page(category: String, p: Int): JSONObject? {
            val url = "$loadPhp?type=$kind&action=get_ordered_list&category=${enc(category)}&p=$p&sortby=added&mac=$mac"
            // one retry: portals drop the odd request under load
            return getJson(url, userAgent, mac, token) ?: getJson(url, userAgent, mac, token)
        }

        fun absorb(root: JSONObject?, groupTitle: String) {
            val js = root?.optJSONObject("js") ?: return
            val data = js.optJSONArray("data") ?: return
            synchronized(lock) {
                for (i in 0 until data.length()) {
                    val obj = data.optJSONObject(i) ?: continue
                    val id = obj.optString("id")
                    if (id.isBlank() || byId.containsKey(id)) continue
                    counter++
                    byId[id] = ChannelEntity(
                        playlistId = playlistId,
                        streamId = id,
                        name = obj.optString("name").ifBlank { "${ContentType.label(contentType).removeSuffix("s")} $id" },
                        streamUrl = cleanCmd(obj.optString("cmd")),
                        logoUrl = absolute(
                            loadPhp,
                            listOf("screenshot_uri", "logo", "cover").map { k -> obj.optString(k) }.firstOrNull { v -> v.isNotBlank() }
                        ),
                        groupTitle = groupTitle,
                        channelNumber = counter,
                        contentType = contentType,
                        rating = VodInfo.cleanRating(
                            listOf("rating_imdb", "rate_imdb", "rating_kinopoisk").map { k -> obj.optString(k) }.firstOrNull { v -> VodInfo.cleanRating(v).isNotBlank() }
                        ).ifBlank { null },
                        metaJson = metaOf(obj, loadPhp),
                        httpUserAgent = userAgent
                    )
                }
            }
        }

        categories.map { (catId, catTitle) ->
            async(Dispatchers.IO) {
                val first = gate.withPermit { page(catId, 1) }
                absorb(first, catTitle)
                val js = first?.optJSONObject("js")
                val total = js?.optInt("total_items", 0) ?: 0
                val perPage = (js?.optInt("max_page_items", 0) ?: 0).takeIf { it > 0 } ?: (js?.optJSONArray("data")?.length() ?: 0)
                if (total > 0 && perPage > 0) {
                    val pages = minOf((total + perPage - 1) / perPage, 2000)
                    (2..pages).map { p ->
                        async(Dispatchers.IO) { absorb(gate.withPermit { page(catId, p) }, catTitle) }
                    }.awaitAll()
                }
            }
        }.awaitAll()

        byId.values.toList()
    }

    suspend fun resolveStreamLink(
        portalUrl: String,
        macAddress: String,
        cmd: String,
        token: String,
        userAgent: String,
        kind: String = "itv"
    ): String = withContext(Dispatchers.IO) {
        val loadPhp = getLoadPhpUrl(portalUrl)
        val mac = macAddress.trim().uppercase()
        val url = "$loadPhp?type=$kind&action=create_link&cmd=${enc(cmd)}&mac=$mac"
        val root = getJson(url, userAgent, mac, token)
        val cmdLink = root?.optJSONObject("js")?.optString("cmd").orEmpty()
        if (cmdLink.isNotBlank()) cleanCmd(cmdLink) else cmd
    }
}
