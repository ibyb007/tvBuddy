package com.example.data.network

import com.example.data.epg.EpgMatcher
import com.example.data.local.entity.ChannelEntity
import com.example.data.local.entity.ProgramEntity
import com.example.data.util.LogoUrl
import com.example.data.model.ContentType
import com.example.data.model.VodInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

data class StalkerAuthResult(
    val success: Boolean,
    val message: String = "",
    val token: String = "",
    val portalUrl: String = "",
    /** Account expiry as the portal reports it (e.g. "2026-10-29"), when it tells. */
    val expiration: String? = null
)

data class VodCategory(val id: String, val title: String)

/** The portal's movie / show categories (in its own order) and its grand total of items. */
data class VodCatalog(val categories: List<VodCategory>, val total: Int)

/** One page of a category: its items, the category's total and the portal's page size. */
data class VodPage(val items: List<ChannelEntity>, val totalItems: Int, val perPage: Int)

/** What a streamed movie / show download came back with. */
data class VodStreamResult(
    /** The portal's categories in its own order (some may have produced no items). */
    val categoryTitles: List<String>,
    /** Pages that still failed after the retries: when > 0 the download is incomplete. */
    val failedPages: Int,
    val total: Int
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

/**
 * Concurrency limiter that finds the portal's sweet spot by itself. A fixed "40 at once" is too much for portals that
 * queue / throttle / answer 5xx under load (every page then crawls or fails and is retried), and too little for the
 * ones that happily serve 60. It starts modestly, grows quickly while the answers stay fast and clean, and backs off
 * as soon as pages fail or get much slower than the best the portal has shown.
 */
class AdaptiveGate(private val min: Int = 4, start: Int = 12, private val max: Int = 64) {
    private val sem = Semaphore(max, acquiredPermits = max - start)
    private val lock = Any()
    private var limit = start
    private var debt = 0
    private var n = 0
    private var fails = 0
    private var latencySum = 0L
    private var best = Long.MAX_VALUE

    /** Runs the blocking [block] (a null result counts as a failed request) inside the current limit. */
    suspend fun <T> run(block: () -> T?): T? {
        sem.acquire()
        val t0 = System.nanoTime()
        var result: T? = null
        try {
            result = block()
        } finally {
            record(result != null, (System.nanoTime() - t0) / 1_000_000)
            giveBack()
        }
        return result
    }

    private fun giveBack() {
        synchronized(lock) { if (debt > 0) { debt--; return } }
        sem.release()
    }

    private fun record(ok: Boolean, ms: Long): Unit = synchronized(lock) {
        n++
        if (!ok) fails++
        latencySum += ms
        if (n < maxOf(12, limit)) return@synchronized
        val avg = latencySum / n
        if (fails == 0) best = minOf(best, avg)
        val target = when {
            fails * 100 > n * 15 || (best != Long.MAX_VALUE && avg > best * 3) -> maxOf(min, limit * 3 / 4)
            fails == 0 && avg <= best * 16 / 10 -> minOf(max, limit + maxOf(4, limit / 2))
            else -> limit
        }
        if (target > limit) {
            var inc = target - limit
            val repay = minOf(debt, inc)
            debt -= repay
            inc -= repay
            repeat(inc) { sem.release() }
        } else if (target < limit) {
            debt += limit - target
        }
        limit = target
        n = 0; fails = 0; latencySum = 0
    }
}

class StalkerClient(
    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
) {

    /**
     * Client for the big downloads (channel list, thousands of movie pages): a connection pool large enough to
     * keep every parallel request on a kept-alive connection, and a longer read timeout for the big replies.
     */
    private val bulkHttp: OkHttpClient by lazy {
        okHttpClient.newBuilder()
            .connectionPool(ConnectionPool(128, 5, TimeUnit.MINUTES))
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    /** Movie / show pages are small: a request stuck for 25 s is dead, better to retry it than to hold a slot. */
    private val pageHttp: OkHttpClient by lazy {
        bulkHttp.newBuilder()
            .readTimeout(25, TimeUnit.SECONDS)
            .callTimeout(40, TimeUnit.SECONDS)
            .build()
    }

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

    private fun digestHex(algorithm: String, text: String): String =
        MessageDigest.getInstance(algorithm).digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun randomHex(n: Int): String {
        val chars = "abcdef0123456789"
        return buildString { repeat(n) { append(chars[kotlin.random.Random.nextInt(chars.length)]) } }
    }

    private fun Request.Builder.stalkerHeaders(userAgent: String, mac: String, token: String? = null): Request.Builder {
        header("User-Agent", userAgent)
        header("Cookie", "mac=$mac; stb_lang=en; timezone=GMT")
        header("X-User-Agent", "Model: MAG250; Link: Ethernet")
        if (!token.isNullOrBlank()) header("Authorization", "Bearer $token")
        return this
    }

    private fun getJson(
        url: String,
        userAgent: String,
        mac: String,
        token: String?,
        client: OkHttpClient = okHttpClient
    ): JSONObject? {
        val req = Request.Builder().url(url).stalkerHeaders(userAgent, mac, token).build()
        return try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) null else JSONObject(resp.body?.string().orEmpty())
            }
        } catch (_: Exception) {
            null
        }
    }

    /** [getJson] with a few retries: a portal that is busy drops the odd request, and a dropped one must not look like "empty". */
    private fun getJsonRetry(
        url: String, userAgent: String, mac: String, token: String?,
        client: OkHttpClient = okHttpClient, attempts: Int = 3
    ): JSONObject? {
        for (i in 0 until attempts) {
            getJson(url, userAgent, mac, token, client)?.let { return it }
            if (i < attempts - 1) try { Thread.sleep(400L * (i + 1)) } catch (_: InterruptedException) { return null }
        }
        return null
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
            // a real MAG box also sends the SHA-1 of its MAC ("prehash"); portals that check it refuse boxes without it
            val prehash = digestHex("SHA-1", mac).uppercase()
            val hsReq = Request.Builder()
                .url("$loadPhp?type=stb&action=handshake&token=&prehash=$prehash&JsHttpRequest=1-xml&mac=$mac")
                .stalkerHeaders(userAgent, mac)
                .build()

            var token = ""
            var random = ""
            try {
                okHttpClient.newCall(hsReq).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@withContext StalkerAuthResult(false, "Portal HTTP Error ${response.code}")
                    }
                    val json = JSONObject(response.body?.string().orEmpty())
                    val js = json.optJSONObject("js") ?: json
                    token = js.optString("token")
                    random = js.optString("random")
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

            // STB profile: portals that bind a box to serial / device ids need it before they list anything.
            // IPTVnator's coherent MAG250 identity (firmware banner, hardware revision, metrics) is sent as well.
            val metrics = JSONObject()
                .put("mac", mac).put("model", "MAG250").put("type", "STB")
                .put("random", random.ifBlank { randomHex(40) }).put("sn", params.effectiveSerial)
                .toString()
            fun profileUrl(secondStep: Boolean) = "$loadPhp?type=stb&action=get_profile&hd=1&num_banks=2&stb_type=MAG250&client_type=STB" +
                "&image_version=218&video_out=hdmi&auth_second_step=${if (secondStep) 1 else 0}&not_valid_token=0&api_signature=262&JsHttpRequest=1-xml" +
                "&hw_version=${enc("1.7-BD-00")}" +
                "&ver=${enc("ImageDescription: 0.2.18-r14-pub-250; ImageDate: Fri Jan 15 15:20:44 EET 2016; PORTAL version: 5.6.0; API Version: JS API version: 328; STB API version: 134; Player Engine version: 0x566")}" +
                "&metrics=${enc(metrics)}&prehash=$prehash" +
                "&sn=${enc(params.effectiveSerial)}&device_id=${enc(params.effectiveDeviceId)}" +
                "&device_id2=${enc(params.effectiveDeviceId2)}&mac=$mac"
            var profile = getJson(profileUrl(params.username.isNotBlank()), userAgent, mac, token)
                // portals that only know the "second step" form (what this client always sent before) still get it
                ?: getJson(profileUrl(true), userAgent, mac, token)
            // status 2 = "login / password required" (do_auth was not sent above because none were given... or the portal wants it after the profile)
            val status = profile?.optJSONObject("js")?.optInt("status", 0) ?: 0
            if (status == 2 && params.username.isNotBlank()) {
                val url = "$loadPhp?type=stb&action=do_auth&login=${enc(params.username)}&password=${enc(params.password)}" +
                    "&device_id=${enc(params.effectiveDeviceId)}&device_id2=${enc(params.effectiveDeviceId2)}&mac=$mac"
                getJson(url, userAgent, mac, token)
                profile = getJson(profileUrl(true), userAgent, mac, token) ?: profile
            }
            if (status == 1) {
                val msg = profile?.optJSONObject("js")?.let { it.optString("block_msg") }.orEmpty()
                if (msg.isNotBlank() && msg != "null") return@withContext StalkerAuthResult(false, "Portal blocked this device: $msg")
            }
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

    /** Live TV and / or movies and shows (VOD) of a Stalker portal, as one list (used where the result is small enough). */
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
        if (includeTv) out += fetchLiveChannels(auth, params, playlistId, userAgent)
        if (includeVod) {
            for (type in listOf(ContentType.MOVIE, ContentType.SHOW)) {
                streamVod(type, auth, params, playlistId, userAgent, onBatch = { batch -> out.addAll(batch) })
            }
        }
        out
    }

    /**
     * Live TV channels with their genre as group. Like IPTVnator: the whole list in one `get_all_channels`
     * request first; a portal that cannot answer that (empty / error) is crawled page by page through
     * `get_ordered_list` (genre=*, sorted by number) instead.
     */
    suspend fun fetchLiveChannels(
        auth: StalkerAuthResult,
        params: StalkerParams,
        playlistId: Long,
        userAgent: String
    ): List<ChannelEntity> = withContext(Dispatchers.IO) { fetchLive(auth, params, playlistId, userAgent) }

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

    /** First non-blank text of the given keys ("null" counts as blank). */
    private fun firstText(o: JSONObject, vararg keys: String): String =
        keys.map { o.optString(it).trim() }.firstOrNull { it.isNotEmpty() && it != "null" }.orEmpty()

    /** Base the portal's channel logos hang off: "<portal root>/misc/logos/320/". */
    private fun logoBase(loadPhp: String): String =
        loadPhp.substringBefore("/server/load.php").trimEnd('/') + "/misc/logos/320/"

    /**
     * A live channel's logo. Portals send an absolute URL, a path from the host root ("/stalker_portal/misc/..."),
     * or - the usual case on Stalker / Ministra - just the file name ("1234.png"), which lives in the portal's
     * logo directory.
     */
    private fun liveLogo(loadPhp: String, raw: String): String? {
        val v = raw.trim()
        if (v.isEmpty() || v.equals("null", true)) return null
        LogoUrl.normalize(v)?.let { return it }               // already absolute
        if (v.startsWith("/")) return LogoUrl.normalize(v, loadPhp)
        return LogoUrl.normalize(logoBase(loadPhp) + v)
    }

    private fun mapLive(
        o: JSONObject, index: Int, genreMap: Map<String, String>, loadPhp: String, playlistId: Long, userAgent: String
    ): ChannelEntity? {
        // first NON-blank of id / stream_id: a blank id beside a valid stream_id must not make the row unplayable
        val id = firstText(o, "id", "stream_id")
        if (id.isEmpty()) return null
        val cmd = cleanCmd(o.optString("cmd"))
        return ChannelEntity(
            playlistId = playlistId,
            streamId = id,
            name = firstText(o, "name", "o_name").ifEmpty { "Channel $id" },
            streamUrl = cmd,
            logoUrl = liveLogo(loadPhp, firstText(o, "logo", "logo_url", "icon")),
            groupTitle = genreMap[o.optString("tv_genre_id")] ?: "Live TV",
            channelNumber = o.optInt("number", index + 1),
            contentType = ContentType.TV,
            httpUserAgent = userAgent,
            epgChannelId = firstText(o, "xmltv_id").ifEmpty { null }
        )
    }

    private suspend fun fetchLive(
        auth: StalkerAuthResult,
        params: StalkerParams,
        playlistId: Long,
        userAgent: String
    ): List<ChannelEntity> {
        val loadPhp = auth.portalUrl
        val mac = params.normalizedMac
        val token = auth.token

        val genreMap = mutableMapOf<String, String>()
        val genres = itemsOf(getJsonRetry("$loadPhp?type=itv&action=get_genres&mac=$mac", userAgent, mac, token))
        for (i in 0 until genres.length()) {
            val g = genres.optJSONObject(i) ?: continue
            val id = g.optString("id")
            val title = g.optString("title")
            if (id.isNotBlank() && title.isNotBlank()) genreMap[id] = title
        }

        val seen = HashSet<String>()
        val channels = ArrayList<ChannelEntity>()
        fun add(arr: JSONArray) {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val ch = mapLive(o, channels.size, genreMap, loadPhp, playlistId, userAgent) ?: continue
                if (seen.add(ch.streamId)) channels.add(ch)
            }
        }

        // 1) the whole list in one request (what a STB does)
        try {
            add(itemsOf(getJsonRetry("$loadPhp?type=itv&action=get_all_channels&mac=$mac", userAgent, mac, token, bulkHttp)))
        } catch (e: Exception) {
            e.printStackTrace()
        }
        if (channels.isNotEmpty()) return channels

        // 2) crawl get_ordered_list: page 1 tells the total and the page size, the rest runs a few pages at a time
        fun pageUrl(p: Int) = "$loadPhp?type=itv&action=get_ordered_list&category=*&genre=*&sortby=number&p=$p&mac=$mac"
        suspend fun page(p: Int): JSONObject? = withContext(Dispatchers.IO) {
            getJsonRetry(pageUrl(p), userAgent, mac, token, pageHttp, attempts = 2)
        }
        val first = page(1) ?: return channels
        val js = first.optJSONObject("js") ?: return channels
        val firstData = js.optJSONArray("data") ?: return channels
        add(firstData)
        val total = minOf(js.optInt("total_items", firstData.length()), 30_000)
        val perPage = js.optInt("max_page_items", 0).takeIf { it > 0 } ?: firstData.length().takeIf { it > 0 } ?: 14
        val pages = maxOf(1, (total + perPage - 1) / perPage)
        var p = 2
        while (p <= pages && channels.size < total) {
            val batch = (p until minOf(p + 4, pages + 1)).toList()
            val results = coroutineScope { batch.map { n -> async { page(n) } }.awaitAll() }
            var added = 0
            for (r in results) {
                // a page that failed twice: stop with what we have rather than hang on a dying portal
                val data = r?.optJSONObject("js")?.optJSONArray("data") ?: return channels
                val before = channels.size
                add(data)
                added += channels.size - before
            }
            // a portal that ignores "p" keeps returning the same channels: that is the end of the list
            if (added == 0) break
            p += 4
        }
        return channels
    }

    // ---------------------------------------------------------------------------------------------
    // Portal EPG (IPTVnator's Stalker flow): one bulk get_epg_info for the whole portal, and get_short_epg
    // per channel for the ones the bulk answer lacks (some portals only list future programmes in it).
    // ---------------------------------------------------------------------------------------------

    private fun epgEntries(value: Any?): JSONArray? = when (value) {
        is JSONArray -> value
        is JSONObject -> value.optJSONArray("data") ?: value.optJSONArray("epg") ?: value.optJSONArray("items")
        else -> null
    }

    private fun epgTime(o: JSONObject, tsKey: String, vararg textKeys: String): Long {
        o.optString(tsKey).toLongOrNull()?.takeIf { it > 0 }?.let { return it * 1000L }
        val text = textKeys.map { o.optString(it).trim() }.firstOrNull { it.isNotEmpty() && it != "null" } ?: return 0L
        text.toLongOrNull()?.takeIf { it > 100_000_000 }?.let { return it * 1000L }
        val m = Regex("^(\\d{4})-(\\d{2})-(\\d{2})[ T](\\d{2}):(\\d{2})(?::(\\d{2}))?").find(text) ?: return 0L
        return try {
            // no timestamps: the text is the portal's wall clock, read it in the device's zone like IPTVnator's Date.parse
            java.util.GregorianCalendar(
                m.groupValues[1].toInt(), m.groupValues[2].toInt() - 1, m.groupValues[3].toInt(),
                m.groupValues[4].toInt(), m.groupValues[5].toInt(), m.groupValues[6].ifEmpty { "0" }.toInt()
            ).timeInMillis
        } catch (_: Exception) { 0L }
    }

    private fun toProgram(o: JSONObject, channelKeyId: String, playlistId: Long): ProgramEntity? {
        val start = epgTime(o, "start_timestamp", "time", "start")
        val stop = epgTime(o, "stop_timestamp", "time_to", "stop")
        val title = firstText(o, "name", "title")
        if (start <= 0L || stop <= start || title.isEmpty()) return null
        return ProgramEntity(
            epgChannelId = EpgMatcher.portalKey(playlistId, channelKeyId),
            title = title,
            description = firstText(o, "descr", "description").ifEmpty { null },
            category = firstText(o, "category").ifEmpty { null },
            startTimeMs = start,
            endTimeMs = stop
        )
    }

    /** Programmes of every channel for the next [periodHours] hours; empty when the portal has no bulk EPG. */
    suspend fun fetchBulkEpg(
        auth: StalkerAuthResult, params: StalkerParams, playlistId: Long, userAgent: String, periodHours: Int = 48
    ): List<ProgramEntity> = withContext(Dispatchers.IO) {
        val mac = params.normalizedMac
        val root = getJsonRetry(
            "${auth.portalUrl}?type=itv&action=get_epg_info&period=$periodHours&mac=$mac",
            userAgent, mac, auth.token, bulkHttp, attempts = 2
        ) ?: return@withContext emptyList()
        val out = ArrayList<ProgramEntity>()
        val raw = root.opt("js")
        val data: Any? = if (raw is JSONObject && raw.has("data")) raw.opt("data") else raw
        when (data) {
            // a flat list: every entry names its channel in ch_id
            is JSONArray -> for (i in 0 until data.length()) {
                val o = data.optJSONObject(i) ?: continue
                val ch = firstText(o, "ch_id", "channel_id")
                if (ch.isNotEmpty()) toProgram(o, ch, playlistId)?.let(out::add)
            }
            // keyed by channel id
            is JSONObject -> data.keys().forEach { chId ->
                val arr = epgEntries(data.opt(chId)) ?: return@forEach
                for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { o -> toProgram(o, chId, playlistId)?.let(out::add) }
            }
        }
        out
    }

    /** `get_short_epg` of one channel: the programme on air and the next few. */
    suspend fun fetchShortEpg(
        auth: StalkerAuthResult, params: StalkerParams, playlistId: Long, userAgent: String, channelId: String, size: Int = 10
    ): List<ProgramEntity> = withContext(Dispatchers.IO) {
        val mac = params.normalizedMac
        val root = getJsonRetry(
            "${auth.portalUrl}?type=itv&action=get_short_epg&ch_id=${enc(channelId)}&size=$size&mac=$mac",
            userAgent, mac, auth.token, attempts = 2
        ) ?: return@withContext emptyList()
        val arr = epgEntries(root.opt("js")) ?: return@withContext emptyList()
        val out = ArrayList<ProgramEntity>()
        for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { o -> toProgram(o, channelId, playlistId)?.let(out::add) }
        out.sortedBy { it.startTimeMs }
    }

    /**
     * Movies or shows. The portal lists them category by category in small pages (often 14 items), so a big
     * catalogue is thousands of requests: they run in parallel (as many as the portal tolerates, see [AdaptiveGate]) on kept-alive connections, and
     * every page is handed to [onBatch] in batches as it arrives (one consumer, never concurrently) instead of
     * being collected first. Pages that fail are retried, then once more slowly at the end.
     */
    suspend fun streamVod(
        contentType: String,
        auth: StalkerAuthResult,
        params: StalkerParams,
        playlistId: Long,
        userAgent: String,
        onBatch: suspend (List<ChannelEntity>) -> Unit,
        onProgress: (Int) -> Unit = {},
        /** Share one gate between movies and shows when they download at the same time. */
        gate: AdaptiveGate = AdaptiveGate()
    ): VodStreamResult = coroutineScope {
        val kind = if (contentType == ContentType.MOVIE) "vod" else "series"
        val loadPhp = auth.portalUrl
        val mac = params.normalizedMac
        val token = auth.token

        val categories = LinkedHashMap<String, String>()
        val catItems = withContext(Dispatchers.IO) {
            itemsOf(getJson("$loadPhp?type=$kind&action=get_categories&mac=$mac", userAgent, mac, token, bulkHttp))
        }
        for (i in 0 until catItems.length()) {
            val c = catItems.optJSONObject(i) ?: continue
            val id = c.optString("id")
            val title = c.optString("title")
            if (id.isNotBlank() && id != "*" && title.isNotBlank()) categories[id] = title
        }
        if (categories.isEmpty()) categories["*"] = if (contentType == ContentType.MOVIE) "Movies" else "Shows"
        val catList = categories.entries.toList()

        val seen = ConcurrentHashMap.newKeySet<String>()
        val loaded = AtomicInteger(0)
        val failedPages = ConcurrentLinkedQueue<IntArray>()
        val buffer = ArrayList<ChannelEntity>()
        val lock = Any()
        // first flush early so posters show up within seconds, then bigger and bigger writes
        var flushAt = 400
        val out = Channel<List<ChannelEntity>>(6)
        val consumer = launch { for (batch in out) onBatch(batch) }

        suspend fun page(category: String, p: Int, g: AdaptiveGate): JSONObject? {
            val url = "$loadPhp?type=$kind&action=get_ordered_list&category=${enc(category)}&p=$p&sortby=added&mac=$mac"
            // portals drop the odd request under load: a few retries (outside the gate, so they don't hog a slot)
            for (attempt in 0 until 3) {
                val root = g.run { getJson(url, userAgent, mac, token, pageHttp) }
                if (root != null) return root
                if (attempt < 2) delay(400L * (attempt + 1))
            }
            return null
        }

        fun parse(root: JSONObject, groupTitle: String, catIndex: Int, p: Int): List<ChannelEntity> {
            val data = root.optJSONObject("js")?.optJSONArray("data") ?: return emptyList()
            val list = ArrayList<ChannelEntity>(data.length())
            for (i in 0 until data.length()) {
                val obj = data.optJSONObject(i) ?: continue
                val id = obj.optString("id")
                if (id.isBlank() || !seen.add(id)) continue
                list += ChannelEntity(
                    playlistId = playlistId,
                    streamId = id,
                    name = obj.optString("name").ifBlank { "${ContentType.label(contentType).removeSuffix("s")} $id" },
                    streamUrl = cleanCmd(obj.optString("cmd")),
                    logoUrl = absolute(
                        loadPhp,
                        listOf("screenshot_uri", "logo", "cover").map { k -> obj.optString(k) }.firstOrNull { v -> v.isNotBlank() }
                    ),
                    groupTitle = groupTitle,
                    // portal order: category by category, page by page (stable however the parallel pages arrive)
                    channelNumber = catIndex * 500_000 + (p - 1) * 100 + i,
                    contentType = contentType,
                    rating = VodInfo.cleanRating(
                        listOf("rating_imdb", "rate_imdb", "rating_kinopoisk").map { k -> obj.optString(k) }.firstOrNull { v -> VodInfo.cleanRating(v).isNotBlank() }
                    ).ifBlank { null },
                    metaJson = metaOf(obj, loadPhp),
                    httpUserAgent = userAgent
                )
            }
            return list
        }

        suspend fun emit(items: List<ChannelEntity>) {
            if (items.isEmpty()) return
            val full: List<ChannelEntity>? = synchronized(lock) {
                buffer.addAll(items)
                if (buffer.size >= flushAt) {
                    val copy = ArrayList(buffer)
                    buffer.clear()
                    flushAt = minOf(flushAt * 2, VOD_BATCH)
                    copy
                } else null
            }
            onProgress(loaded.addAndGet(items.size))
            if (full != null) out.send(full)
        }

        suspend fun load(catIndex: Int, p: Int, gate: AdaptiveGate): JSONObject? {
            val entry = catList[catIndex]
            val root = page(entry.key, p, gate)
            if (root == null) {
                failedPages.add(intArrayOf(catIndex, p))
                return null
            }
            emit(parse(root, entry.value, catIndex, p))
            return root
        }

        suspend fun runCategory(catIndex: Int, gate: AdaptiveGate) {
            val first = load(catIndex, 1, gate) ?: return
            val js = first.optJSONObject("js")
            val total = js?.optInt("total_items", 0) ?: 0
            val perPage = (js?.optInt("max_page_items", 0) ?: 0).takeIf { it > 0 } ?: (js?.optJSONArray("data")?.length() ?: 0)
            if (total > 0 && perPage > 0) {
                val pages = minOf((total + perPage - 1) / perPage, 2000)
                coroutineScope {
                    for (p in 2..pages) launch(Dispatchers.IO) { load(catIndex, p, gate) }
                }
            }
        }

        try {
            coroutineScope {
                catList.indices.map { i -> async(Dispatchers.IO) { runCategory(i, gate) } }.awaitAll()
            }
            // whatever failed: one more go, gently (a portal that throttled the fast pass usually answers now)
            val retry = failedPages.toList()
            failedPages.clear()
            if (retry.isNotEmpty()) {
                delay(1500)
                val slow = AdaptiveGate(min = 2, start = 4, max = 6)
                coroutineScope {
                    retry.map { f ->
                        async(Dispatchers.IO) {
                            if (f[1] == 1) runCategory(f[0], slow) else { load(f[0], f[1], slow) }
                            Unit
                        }
                    }.awaitAll()
                }
            }
            val rest = synchronized(lock) {
                val copy = ArrayList(buffer)
                buffer.clear()
                copy
            }
            if (rest.isNotEmpty()) out.send(rest)
        } finally {
            out.close()
        }
        consumer.join()
        VodStreamResult(categories.values.toList(), failedPages.size, loaded.get())
    }


    // ---------------------------------------------------------------------------------------------
    // Lazy movies / shows (the way IPTVnator does it): categories first, the items of a category only
    // when somebody opens it.
    // ---------------------------------------------------------------------------------------------

    /** Categories of the portal's movies or shows, and how many items it says it has in total. */
    suspend fun fetchVodCatalog(
        contentType: String,
        auth: StalkerAuthResult,
        params: StalkerParams,
        userAgent: String
    ): VodCatalog? = withContext(Dispatchers.IO) {
        val kind = if (contentType == ContentType.MOVIE) "vod" else "series"
        val loadPhp = auth.portalUrl
        val mac = params.normalizedMac
        val root = getJsonRetry("$loadPhp?type=$kind&action=get_categories&mac=$mac", userAgent, mac, auth.token, bulkHttp)
            ?: return@withContext null
        val cats = ArrayList<VodCategory>()
        val arr = itemsOf(root)
        for (i in 0 until arr.length()) {
            val c = arr.optJSONObject(i) ?: continue
            val id = c.optString("id")
            val title = c.optString("title")
            if (id.isNotBlank() && id != "*" && title.isNotBlank()) cats += VodCategory(id, title)
        }
        if (cats.isEmpty()) cats += VodCategory("*", if (contentType == ContentType.MOVIE) "Movies" else "Shows")
        // the "All" listing's first page carries the grand total
        val first = getJsonRetry(
            "$loadPhp?type=$kind&action=get_ordered_list&category=*&p=1&sortby=added&mac=$mac",
            userAgent, mac, auth.token, bulkHttp, attempts = 2
        )
        val total = first?.optJSONObject("js")?.optInt("total_items", 0) ?: 0
        VodCatalog(cats, total)
    }

    /**
     * One page of a category ("*" = everything). Items of the "*" listing are put in the group of their own
     * category ([groupOf] maps a category id to its title). Retried; null when the portal still says no.
     */
    suspend fun fetchVodPage(
        contentType: String,
        auth: StalkerAuthResult,
        params: StalkerParams,
        playlistId: Long,
        userAgent: String,
        categoryId: String,
        catIndex: Int,
        page: Int,
        groupOf: (String) -> String?,
        defaultGroup: String,
        gate: AdaptiveGate
    ): VodPage? {
        val kind = if (contentType == ContentType.MOVIE) "vod" else "series"
        val loadPhp = auth.portalUrl
        val mac = params.normalizedMac
        val url = "$loadPhp?type=$kind&action=get_ordered_list&category=${enc(categoryId)}&p=$page&sortby=added&mac=$mac"
        var root: JSONObject? = null
        for (attempt in 0 until 3) {
            root = gate.run { getJson(url, userAgent, mac, auth.token, pageHttp) }
            if (root != null) break
            if (attempt < 2) delay(400L * (attempt + 1))
        }
        val js = root?.optJSONObject("js") ?: return null
        val data = js.optJSONArray("data") ?: JSONArray()
        val items = ArrayList<ChannelEntity>(data.length())
        for (i in 0 until data.length()) {
            val obj = data.optJSONObject(i) ?: continue
            val id = obj.optString("id")
            if (id.isBlank()) continue
            val group = if (categoryId != "*") groupOf(categoryId) ?: defaultGroup
            else groupOf(obj.optString("category_id")) ?: defaultGroup
            items += ChannelEntity(
                playlistId = playlistId,
                streamId = id,
                name = obj.optString("name").ifBlank { "${ContentType.label(contentType).removeSuffix("s")} $id" },
                streamUrl = cleanCmd(obj.optString("cmd")),
                logoUrl = absolute(
                    loadPhp,
                    listOf("screenshot_uri", "logo", "cover").map { k -> obj.optString(k) }.firstOrNull { v -> v.isNotBlank() }
                ),
                groupTitle = group,
                // portal order inside a category; "All" pages come after everything numbered per category
                channelNumber = if (categoryId != "*") catIndex * 500_000 + (page - 1) * 100 + i else 250_000_000 + (page - 1) * 100 + i,
                contentType = contentType,
                rating = VodInfo.cleanRating(
                    listOf("rating_imdb", "rate_imdb", "rating_kinopoisk").map { k -> obj.optString(k) }.firstOrNull { v -> VodInfo.cleanRating(v).isNotBlank() }
                ).ifBlank { null },
                metaJson = metaOf(obj, loadPhp),
                httpUserAgent = userAgent
            )
        }
        val perPage = js.optInt("max_page_items", 0).takeIf { it > 0 } ?: data.length()
        return VodPage(items, js.optInt("total_items", 0), perPage)
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

    private companion object {
        /** Largest number of items per database write while a catalogue streams in (writes ramp up to it). */
        const val VOD_BATCH = 3000
    }
}
