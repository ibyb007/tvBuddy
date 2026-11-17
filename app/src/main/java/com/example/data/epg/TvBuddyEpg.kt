package com.example.data.epg

/** State of the "TVBuddy EPG" engine of one M3U playlist. */
data class TvBuddyEpgStatus(
    val enabled: Boolean = false,
    val running: Boolean = false,
    val message: String = "",
    val matched: Int = 0,
    val total: Int = 0,
    val updatedAt: Long = 0L
)

/**
 * One row of the "Unmatched channels" page: a live channel of the playlist, and the guide channel name / tvg-id the
 * user typed in for it, if any. [matched] is true when the channel has a guide now (only rows with a manual match
 * can be matched; the page also lists those so a wrong manual match can be changed).
 */
data class EpgChannelMatch(val name: String, val manualMatch: String?, val matched: Boolean, val autoMatch: String? = null)

/**
 * The one guide the TVBuddy EPG engine uses: a merged XMLTV file (UK, India, Australia, Willow ...) kept on GitHub.
 * Channels it does not cover can be matched by hand on the "Unmatched channels" page.
 */
object TvBuddyEpg {
    const val NAME_PREFIX = "TVBuddy EPG"
    const val SOURCE_NAME = "TVBuddy EPG"
    const val SOURCE_URL = "https://raw.githubusercontent.com/ibyb007/myepg/main/epg.xml.gz"

    /** One guide the TVBuddy EPG engine can use. Each can be turned on / off (Playlist > TVBuddy EPG > Configure sources). */
    data class Source(val key: String, val label: String, val url: String, val defaultOn: Boolean)

    val SOURCES: List<Source> = listOf(
        Source("default", "TVBuddy merged guide (India, UK sports, Fox Sports AU, Willow)", SOURCE_URL, true),
        Source("iptvepg_in", "India (iptv-epg.org)", "https://iptv-epg.org/files/epg-in.xml.gz", false),
        Source("epgpw_in", "India (epg.pw)", "https://epg.pw/xmltv/epg_IN.xml.gz", false),
        Source("iptvepg_us", "USA (iptv-epg.org)", "https://iptv-epg.org/files/epg-us.xml.gz", false),
        Source("iptvepg_gb", "UK (iptv-epg.org)", "https://iptv-epg.org/files/epg-gb.xml.gz", false),
        Source("iptvepg_au", "Australia (iptv-epg.org)", "https://iptv-epg.org/files/epg-au.xml.gz", false),
        Source("epgpw_us", "USA (epg.pw)", "https://epg.pw/xmltv/epg_US.xml.gz", false),
        Source("epgpw_gb", "UK (epg.pw)", "https://epg.pw/xmltv/epg_GB.xml.gz", false),
        Source("epgpw_au", "Australia (epg.pw)", "https://epg.pw/xmltv/epg_AU.xml.gz", false)
    )

    /** Name of the guide source entry; the default keeps the plain name. */
    fun sourceName(s: Source) = if (s.key == "default") SOURCE_NAME else "$NAME_PREFIX - ${s.label}"

    /** Sources made by older versions, one per region ("TVBuddy EPG · India" ...). They are replaced by [SOURCE_URL]. */
    fun isLegacyName(name: String) = name.startsWith("$NAME_PREFIX ·")
}
