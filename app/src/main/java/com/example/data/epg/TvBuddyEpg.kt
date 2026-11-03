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
 * Catalog of the public guides the TVBuddy EPG engine tries. Each region lists candidate XMLTV links in priority order:
 * the engine downloads them one by one, keeps the first one that gives the playlist more channels with a guide, and
 * drops the ones that fail or add nothing. A region is only tried when the playlist has channels that look like it
 * ([trigger]); India is always tried.
 *
 * The links are community guides and can move or disappear: edit this list when one stops working.
 */
object TvBuddyEpg {
    const val NAME_PREFIX = "TVBuddy EPG"

    /** [targets]: names the region is about; its sources keep being tried until all of them have a guide. */
    class Region(val title: String, val trigger: Regex?, val targets: Regex, val candidates: List<String>)

    private const val GLOBE = "https://raw.githubusercontent.com/globetvapp/epg/main"
    private const val OPEN = "https://www.open-epg.com/files"
    private const val IPTVEPG = "https://iptv-epg.org/files"

    val REGIONS: List<Region> = listOf(
        // Indian channels: Star Sports, Sony Sports Ten ...
        Region(
            "India", null,
            Regex("star\\s*sports?|sony|\\bten\\s*[1-5]\\b|sports\\s*ten"),
            listOf(
                "https://epg.pw/xmltv/epg_IN.xml.gz",
                "$IPTVEPG/epg-in.xml.gz",
                "$OPEN/india1.xml.gz",
                "$OPEN/india2.xml.gz",
                "$GLOBE/India/india1.xml",
                "$GLOBE/India/india1.xml.gz"
            )
        ),
        // Sky Sports, TNT Sports (UK)
        Region(
            "UK sports",
            Regex("sky\\s*sports?|tnt\\s*sports?|bt\\s*sport|premier\\s*sports?"),
            Regex("sky\\s*sports?|tnt\\s*sports?|bt\\s*sport|premier\\s*sports?"),
            listOf(
                "https://epg.pw/xmltv/epg_GB.xml.gz",
                "$IPTVEPG/epg-gb.xml.gz",
                "$OPEN/unitedkingdom1.xml.gz",
                "$OPEN/unitedkingdom2.xml.gz",
                "$OPEN/unitedkingdom3.xml.gz",
                "$GLOBE/Unitedkingdom/unitedkingdom1.xml",
                "$GLOBE/Sports/sports1.xml"
            )
        ),
        // Fox Sports 501 - 507 (Australia)
        Region(
            "Australia",
            Regex("fox\\s*(sports?|cricket|league|footy|premier)|\\b50[1-7]\\b"),
            Regex("fox\\s*(sports?|cricket|league|footy|premier)|\\b50[1-7]\\b"),
            listOf(
                "$GLOBE/Australia/australia1.xml",
                "https://epg.pw/xmltv/epg_AU.xml.gz",
                "$IPTVEPG/epg-au.xml.gz",
                "$OPEN/australia1.xml.gz",
                "$OPEN/australia2.xml.gz",
                "$GLOBE/Australia/australia2.xml"
            )
        ),
        // Willow Sports
        Region(
            "USA / Willow",
            Regex("willow"),
            Regex("willow"),
            listOf(
                "https://epg.pw/xmltv/epg_US.xml.gz",
                "$IPTVEPG/epg-us.xml.gz",
                "$OPEN/usa1.xml.gz",
                "$GLOBE/Sports/sports1.xml"
            )
        )
    )
}
