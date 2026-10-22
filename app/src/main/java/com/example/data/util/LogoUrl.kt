package com.example.data.util

import java.net.URI

/**
 * One place that turns whatever a playlist / panel / portal / guide puts in a logo field into a URL an image
 * loader can fetch (or null when there is nothing usable). Handles the shapes seen in the wild:
 * quotes and whitespace, "//host/x.png", "null" / "n/a" placeholders, "url|User-Agent=..." header suffixes,
 * relative paths ("/images/x.png", "images/x.png") and bare host names without a scheme.
 */
object LogoUrl {
    private val placeholders = setOf("null", "none", "n/a", "na", "undefined", "false", "0", "-", "no")

    /**
     * @param base the server / portal URL relative paths are resolved against (scheme + host are taken from it,
     *   and for paths without a leading slash also its directory), or null when none is known.
     */
    fun normalize(raw: String?, base: String? = null): String? {
        var u = raw?.trim().orEmpty().trim('"', '\'', ' ', '\t')
        if (u.isEmpty() || u.lowercase() in placeholders) return null
        // "url|Header=Value" (Kodi style)
        u = u.substringBefore('|').trim()
        if (u.isEmpty()) return null
        if (u.startsWith("data:image", ignoreCase = true)) return u
        if (u.startsWith("//")) u = "https:$u"
        val lower = u.lowercase()
        if (lower.startsWith("http://") || lower.startsWith("https://")) return encodeSpaces(u)
        if (lower.contains("://")) return null // file://, rtmp:// ... are not images we can load

        // looks like "host.tld/path.png" (no scheme)
        val firstSeg = u.substringBefore('/')
        if (!u.startsWith("/") && firstSeg.contains('.') && !firstSeg.contains(' ') &&
            u.contains('/') && !firstSeg.matches(Regex(".*\\.(png|jpe?g|webp|gif|svg|bmp)$", RegexOption.IGNORE_CASE))
        ) return encodeSpaces("http://$u")

        // relative: needs a base
        val b = base?.trim().orEmpty()
        if (b.isEmpty()) return null
        return try {
            val withScheme = if (b.startsWith("http", true)) b else "http://$b"
            val uri = URI(withScheme)
            val host = uri.host ?: return null
            val port = if (uri.port > 0) ":${uri.port}" else ""
            val origin = "${uri.scheme}://$host$port"
            val resolved = if (u.startsWith("/")) origin + u else {
                val dir = (uri.path ?: "").substringBeforeLast('/', "")
                "$origin$dir/$u"
            }
            encodeSpaces(resolved)
        } catch (_: Exception) {
            null
        }
    }

    private fun encodeSpaces(u: String) = u.replace(" ", "%20")
}
