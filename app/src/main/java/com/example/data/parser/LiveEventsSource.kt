package com.example.data.parser

import com.example.data.local.entity.ChannelEntity
import com.example.player.PlaybackLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder

/**
 * Adds dynamic live rows (live sports events, ...) that a playlist's static M3U does not contain.
 *
 * Some playlist sites publish a static M3U but keep their changing rows only in a JSON API:
 * a channel list (with a `category` per channel) and a per-channel detail call with the
 * sources, headers and ClearKey DRM. When a playlist URL belongs to a known [Site], every API
 * channel the M3U does not already contain (same name in the same category) is fetched and added
 * as a normal channel in its category. Any failure is swallowed: the M3U import always wins.
 *
 * To support another site with the same API shape, add one entry to [SITES].
 */
object LiveEventsSource {
    /** [detailUrl] gets the URL-encoded channel id appended. */
    class Site(
        val host: String,
        val siteUrl: String,
        val channelsUrl: String,
        val detailUrl: String,
        val idPrefix: String,
        /**
         * Only API channels whose category matches this are added. The API lists every channel the site
         * has (1000+), most of which the curated M3U deliberately leaves out; the dynamic rows are the
         * ones we want here.
         */
        val eventCategory: Regex = DEFAULT_EVENT_CATEGORY
    )

    // must be declared before SITES: object properties initialize in order
    private val DEFAULT_EVENT_CATEGORY = Regex("event|sport|match|fixture", RegexOption.IGNORE_CASE)

    private val SITES = listOf(
        // LiveTG TV (same data the Vega "LiveTG TV" provider reads)
        Site(
            host = "livetgtv.lovable.app",
            siteUrl = "https://livetgtv.lovable.app",
            channelsUrl = "https://livetgtv.lovable.app/api/public/channels",
            detailUrl = "https://livetgtv.lovable.app/api/public/channels/",
            idPrefix = "livetg"
        )
    )

    private const val MAX_DETAIL_FETCHES = 150
    private const val MAX_SOURCES_PER_CHANNEL = 3
    private const val PARALLEL_REQUESTS = 6

    private const val PLAYLIST_PATH = "/api/public/playlist.m3u"

    private fun siteFor(url: String): Site? = try {
        val uri = URI(url.trim())
        val host = uri.host
        SITES.firstOrNull { it.host.equals(host, ignoreCase = true) } ?: run {
            // same site behind another domain / mirror: the API sits next to the playlist
            val path = uri.path.orEmpty()
            if (host != null && path.endsWith(PLAYLIST_PATH, ignoreCase = true)) {
                val origin = "${uri.scheme ?: "https"}://${uri.authority}"
                val base = origin + path.dropLast(PLAYLIST_PATH.length) + "/api/public/channels"
                Site(host, origin, base, "$base/", "live")
            } else null
        }
    } catch (_: Exception) {
        null
    }

    private fun bust(url: String) = url + (if (url.contains('?')) "&" else "?") + "_t=" + System.currentTimeMillis()

    /** True when [url] is a playlist of a site this source knows how to add live events for. */
    fun isSupportedPlaylist(url: String): Boolean = siteFor(url) != null

    private fun norm(s: String) = s.trim().lowercase().replace(Regex("\\s+"), " ")

    private fun absoluteLogo(site: Site, raw: String?): String? {
        val v = raw?.trim().orEmpty()
        return when {
            v.isEmpty() -> null
            v.startsWith("http", ignoreCase = true) -> v
            v.startsWith("/") -> site.siteUrl + v
            else -> null
        }
    }

    private class ApiChannel(val id: String, val name: String, val category: String, val logo: String?)

    private fun parseList(site: Site, json: String): List<ApiChannel> {
        val root = JSONObject(json)
        val out = ArrayList<ApiChannel>()
        // "channels" is the documented list; event-style lists are accepted too, defaulting to an events category
        for ((key, defaultCategory) in listOf(
            "channels" to "Other",
            "events" to "Live Events",
            "liveEvents" to "Live Events",
            "live_events" to "Live Events",
            "matches" to "Live Events"
        )) {
            val arr = root.optJSONArray(key) ?: continue
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.opt("id")?.toString()?.trim().orEmpty()
                val name = (o.optString("name", "").ifBlank { o.optString("title", "") }).trim()
                if (id.isEmpty() || name.isEmpty() || id == "null") continue
                val category = o.optString("category", "").trim().ifEmpty { defaultCategory }
                out.add(ApiChannel(id, name, category, absoluteLogo(site, o.optString("logo", o.optString("image", "")))))
            }
        }
        return out
    }

    private data class Drm(val keyIdKey: String)

    /** `{type:"clearkey", clearKeys:{kid:key}}` or `{keyId, key}` -> "kid:key,kid:key"; null = no DRM; throws if unsupported. */
    private fun parseDrm(drm: Any?): Drm? {
        if (drm == null || drm == JSONObject.NULL) return null
        val o = drm as? JSONObject ?: throw IllegalArgumentException("unsupported drm")
        val type = o.optString("type", "").trim().lowercase()
        if (type != "clearkey" && type != "org.w3.clearkey") throw IllegalArgumentException("unsupported drm")
        val pairs = mutableListOf<String>()
        val keys = o.optJSONObject("clearKeys")
        if (keys != null) {
            val kidIter = keys.keys()
            while (kidIter.hasNext()) {
                val kid = kidIter.next()
                pairs.add("${kid.trim()}:${keys.optString(kid).trim()}")
            }
        } else if (o.has("keyId") || o.has("key")) {
            pairs.add("${o.optString("keyId").trim()}:${o.optString("key").trim()}")
        }
        if (pairs.isEmpty() || pairs.any { !Regex("^[0-9a-fA-F]{32}:[0-9a-fA-F]{32}$").matches(it) }) {
            throw IllegalArgumentException("invalid clearkey")
        }
        return Drm(pairs.joinToString(","))
    }

    private fun putHeader(headers: MutableMap<String, String>, name: String, value: String) {
        if (name.isBlank() || value.isBlank() || name.any { it == '\r' || it == '\n' } || value.any { it == '\r' || it == '\n' }) return
        val key = when (name.lowercase()) {
            "cookie" -> "Cookie"
            "origin" -> "Origin"
            "referer", "referrer" -> "Referer"
            "user-agent", "useragent" -> "User-Agent"
            else -> name
        }
        headers.keys.firstOrNull { it.equals(key, ignoreCase = true) }?.let { headers.remove(it) }
        headers[key] = value
    }

    private fun readHeaders(obj: JSONObject?, into: MutableMap<String, String>) {
        if (obj == null) return
        val names = obj.keys()
        while (names.hasNext()) {
            val k = names.next()
            val v = obj.opt(k)
            if (v is String) putHeader(into, k, v)
        }
    }

    private class Source(val url: String, val headers: Map<String, String>, val drm: Drm?)

    private fun parseDetail(json: String): List<Source> {
        val root = JSONObject(json)
        val sources = root.optJSONArray("sources") ?: return emptyList()
        val baseHeaders = LinkedHashMap<String, String>()
        readHeaders(root.optJSONObject("headers"), baseHeaders)
        val baseDrm = runCatching { parseDrm(root.opt("drm")) }
        val out = ArrayList<Source>()
        for (i in 0 until sources.length()) {
            val item = sources.opt(i)
            var rawUrl = ""
            val headers = LinkedHashMap(baseHeaders)
            var drm: Result<Drm?> = baseDrm
            if (item is String) {
                rawUrl = item.trim()
            } else if (item is JSONObject) {
                rawUrl = item.optString("url", "").trim()
                readHeaders(item.optJSONObject("headers"), headers)
                if (item.has("drm")) drm = runCatching { parseDrm(item.opt("drm")) }
            }
            if (!rawUrl.startsWith("http", ignoreCase = true)) continue
            if (drm.isFailure) continue // unsupported / invalid DRM: skip that source only
            val parsedDrm = drm.getOrNull()

            // "url|User-Agent=..&Referer=.." pipe syntax
            var url = rawUrl
            val pipe = rawUrl.indexOf('|')
            if (pipe >= 0) {
                url = rawUrl.substring(0, pipe)
                for (part in rawUrl.substring(pipe + 1).split('&')) {
                    val eq = part.indexOf('=')
                    if (eq > 0) {
                        val v = runCatching { URLDecoder.decode(part.substring(eq + 1), "UTF-8") }.getOrDefault(part.substring(eq + 1))
                        putHeader(headers, part.substring(0, eq).trim(), v)
                    }
                }
            }
            out.add(Source(url, headers, parsedDrm))
        }
        return out
    }

    /** Channels of the site's API that [existing] (the parsed M3U) lacks, as ready-to-store entities. */
    suspend fun fetchMissingChannels(
        playlistUrl: String,
        playlistId: Long,
        existing: List<ChannelEntity>,
        startNumber: Int,
        download: (String) -> String
    ): List<ChannelEntity> {
        val site = siteFor(playlistUrl) ?: return emptyList()
        return try {
            val have = existing.mapTo(HashSet()) { norm(it.name) + "|" + norm(it.groupTitle) }
            val list = withContext(Dispatchers.IO) { parseList(site, download(bust(site.channelsUrl))) }
            val missing = list
                .filter { site.eventCategory.containsMatchIn(it.category) }
                .filter { (norm(it.name) + "|" + norm(it.category)) !in have }
                .distinctBy { norm(it.name) + "|" + norm(it.category) }
                .take(MAX_DETAIL_FETCHES)
            PlaybackLog.info(
                "LIVE-EVENTS",
                "${site.host}: ${list.size} API channels, categories: " +
                    list.groupingBy { it.category }.eachCount().entries.sortedByDescending { it.value }
                        .take(40).joinToString { "${it.key} (${it.value})" }
            )
            PlaybackLog.info(
                "LIVE-EVENTS",
                "${missing.size} event channels not in the M3U: " +
                    missing.groupingBy { it.category }.eachCount().entries.joinToString { "${it.key} (${it.value})" }
            )
            if (missing.isEmpty()) return emptyList()

            val gate = Semaphore(PARALLEL_REQUESTS)
            val details: List<Pair<ApiChannel, List<Source>>> = coroutineScope {
                missing.map { ch ->
                    async(Dispatchers.IO) {
                        gate.withPermit {
                            val sources = try {
                                parseDetail(download(bust(site.detailUrl + java.net.URLEncoder.encode(ch.id, "UTF-8").replace("+", "%20"))))
                            } catch (e: Exception) {
                                PlaybackLog.warn("LIVE-EVENTS", "'${ch.name}' (${ch.category}) detail failed: ${e.message}")
                                emptyList()
                            }
                            if (sources.isEmpty()) PlaybackLog.warn("LIVE-EVENTS", "'${ch.name}' (${ch.category}) has no usable source")
                            ch to sources
                        }
                    }
                }.awaitAll()
            }

            val result = ArrayList<ChannelEntity>()
            var number = startNumber
            for ((ch, sources) in details) {
                val usable = sources.distinctBy { it.url }.take(MAX_SOURCES_PER_CHANNEL)
                usable.forEachIndexed { index, s ->
                    val headers = LinkedHashMap(s.headers)
                    val ua = headers.keys.firstOrNull { it.equals("User-Agent", ignoreCase = true) }?.let { headers.remove(it) }
                    result.add(
                        ChannelEntity(
                            playlistId = playlistId,
                            streamId = "${site.idPrefix}:${ch.id}" + if (index > 0) ":${index + 1}" else "",
                            name = if (index > 0) "${ch.name} (Source ${index + 1})" else ch.name,
                            streamUrl = s.url,
                            logoUrl = ch.logo,
                            groupTitle = ch.category,
                            channelNumber = number++,
                            drmType = if (s.drm != null) "CLEARKEY" else null,
                            drmKeyIdKey = s.drm?.keyIdKey,
                            httpUserAgent = ua,
                            httpHeadersJson = if (headers.isNotEmpty()) JSONObject(headers as Map<*, *>).toString() else null
                        )
                    )
                }
            }
            PlaybackLog.info("LIVE-EVENTS", "added ${result.size} channels in ${result.map { it.groupTitle }.distinct().size} groups")
            result
        } catch (e: Exception) {
            PlaybackLog.warn("LIVE-EVENTS", "skipped: ${e.javaClass.simpleName}: ${e.message}")
            emptyList()
        }
    }
}
