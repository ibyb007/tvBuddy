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
data class EpgChannelMatch(val name: String, val manualMatch: String?, val matched: Boolean)

/**
 * The one guide the TVBuddy EPG engine uses: a merged XMLTV file (UK, India, Australia, Willow ...) kept on GitHub.
 * Channels it does not cover can be matched by hand on the "Unmatched channels" page.
 */
object TvBuddyEpg {
    const val NAME_PREFIX = "TVBuddy EPG"
    const val SOURCE_NAME = "TVBuddy EPG"
    const val SOURCE_URL = "https://raw.githubusercontent.com/ibyb007/myepg/main/epg.xml.gz"

    /** Sources made by older versions, one per region ("TVBuddy EPG · India" ...). They are replaced by [SOURCE_URL]. */
    fun isLegacyName(name: String) = name.startsWith("$NAME_PREFIX ·")
}
