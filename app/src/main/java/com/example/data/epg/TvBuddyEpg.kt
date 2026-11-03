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

    class Region(val title: String, val trigger: Regex?, val candidates: List<String>)

    private const val GLOBE = "https://raw.githubusercontent.com/globetvapp/epg/main"

    val REGIONS: List<Region> = listOf(
        // Indian channels
        Region(
            "India", null,
            listOf(
                "https://epg.pw/xmltv/epg_IN.xml.gz",
                "$GLOBE/India/india1.xml",
                "$GLOBE/India/india1.xml.gz"
            )
        ),
        // Sky Sports, TNT Sports (UK)
        Region(
            "UK sports",
            Regex("sky\\s*sports?|tnt\\s*sports?|bt\\s*sport|premier\\s*sports?"),
            listOf(
                "https://epg.pw/xmltv/epg_GB.xml.gz",
                "$GLOBE/Unitedkingdom/unitedkingdom1.xml",
                "$GLOBE/Sports/sports1.xml"
            )
        ),
        // Fox Sports 501 - 507 (Australia)
        Region(
            "Australia",
            Regex("fox\\s*(sports?|cricket|league|footy|premier)|\\b50[1-7]\\b"),
            listOf(
                "https://epg.pw/xmltv/epg_AU.xml.gz",
                "$GLOBE/Australia/australia1.xml",
                "$GLOBE/Australia/australia2.xml"
            )
        ),
        // Willow Sports
        Region(
            "USA / Willow",
            Regex("willow"),
            listOf(
                "https://epg.pw/xmltv/epg_US.xml.gz",
                "$GLOBE/Sports/sports1.xml"
            )
        )
    )
}
