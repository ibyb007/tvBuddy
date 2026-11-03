package com.example.data.epg

import com.example.data.local.dao.ChannelEpgRef
import com.example.data.local.entity.ChannelEntity
import com.example.data.local.entity.EpgChannelEntity
import com.example.data.local.entity.ProgramEntity
import java.text.Normalizer

/**
 * Matching of playlist channels to XMLTV channels.
 *
 * IPTVnator looks a channel up by tvg-id, then tvg-name, then the channel name. This does the same, but exactly
 * and loosely: ids are compared case-insensitively, and when no id fits, the channel's names are compared with the
 * display names of the guide's `<channel>` elements after normalising both (case, accents, "HD / FHD / 4K" quality
 * tags, "UK:" / "|US|" country prefixes, punctuation). Only guide channels that really have programmes take part,
 * so a channel never gets mapped to an empty guide entry.
 */
object EpgMatcher {

    private val bracketed = Regex("[\\[(][^\\])]*[\\])]")
    private val prefix = Regex("^\\s*(?:[|\\[(]\\s*)?[A-Za-z]{2,3}\\s*(?:[|\\])]|:|-\\s)\\s*")
    private val qualityTokens = setOf(
        "hd", "fhd", "uhd", "sd", "4k", "8k", "hevc", "h265", "h264", "1080p", "1080i", "720p", "576p", "480p",
        "50fps", "60fps", "raw", "vip", "backup", "multisub", "orig", "original"
    )
    private val domainSuffix = Regex("\\.(?:[a-z]{2,3}|com|net|tv)$", RegexOption.IGNORE_CASE)

    /** "Sky Sports 1 HD" / "UK: SKY SPORTS 1 FHD" / "sky-sports-1" -> "skysports1". "+1" timeshift stays distinct ("plus1"). */
    fun normalize(name: String?): String {
        if (name.isNullOrBlank()) return ""
        var s = Normalizer.normalize(name, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        s = s.replace(bracketed, " ")
        // a leading country / provider tag: "UK: ", "US | ", "|UK|", "[DE] "
        val m = prefix.find(s)
        if (m != null && m.range.first == 0 && s.length - m.value.length >= 2) s = s.substring(m.value.length)
        s = s.lowercase().replace("&", " and ").replace(Regex("\\+\\s*(\\d)"), " plus$1 ")
        val tokens = s.split(Regex("[^a-z0-9\\p{L}]+")).filter { it.isNotEmpty() && it !in qualityTokens }
        return tokens.joinToString("")
    }

    /** Names joined for storage in the epg_channels table. */
    fun normNamesOf(displayNames: List<String>, id: String): String =
        (displayNames + id.replace(domainSuffix, "")).map { normalize(it) }.filter { it.isNotEmpty() }.distinct().joinToString("\t")

    // ---- Smart matching for playlists without tvg-id (Sony Ten, Star Sports, Fox 501-507, Sky Sports ...) ----

    /** A name that is only a brand says nothing about the channel ("Star 4K" is not the entertainment channel "Star"). */
    private val genericNames = setOf(
        "star", "sky", "fox", "sony", "zee", "colors", "sun", "tnt", "bt", "etv", "sports", "sport", "news",
        "movies", "kids", "music", "ten", "tv"
    )
    private val noiseTokens = qualityTokens + setOf(
        "hindi", "english", "tamil", "telugu", "bangla", "bengali", "marathi", "kannada", "malayalam", "punjabi",
        "gujarati", "urdu", "channel", "live", "in", "uk", "us", "au", "ind", "india"
    )
    /** Foxtel Australia: channel number -> the name the guides use for it. */
    private val foxAu = mapOf(501 to "cricket", 502 to "league", 503 to "footy")

    fun isGeneric(norm: String) = norm in genericNames

    private fun tokensOf(name: String?): List<String> {
        if (name.isNullOrBlank()) return emptyList()
        var s = Normalizer.normalize(name, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        s = s.replace(bracketed, " ")
        val m = prefix.find(s)
        if (m != null && m.range.first == 0 && s.length - m.value.length >= 2) s = s.substring(m.value.length)
        s = s.lowercase().replace("&", " and ")
            .replace(Regex("sony\\s*sports?\\s*ten"), "sony ten")
            .replace(Regex("(?<=[a-z])(?=\\d)"), " ")
        return s.split(Regex("[^a-z0-9\\p{L}]+")).filter { it.isNotEmpty() && it !in noiseTokens }
    }

    /** Order-free key of a name: "Ten 1 Sony" and "Sony Sports Ten 1 HD" give the same key. */
    private fun tokenKey(tokens: List<String>) = tokens.sorted().joinToString(" ")

    /** Alternative keys for a channel name, most specific first. */
    fun smartKeys(name: String?): List<String> {
        val t = tokensOf(name)
        if (t.isEmpty()) return emptyList()
        val out = LinkedHashSet<String>()
        out += tokenKey(t)
        // Foxtel "501" - "507": guides list them by number or by their name (Fox Cricket / League / Footy)
        val num = t.firstNotNullOfOrNull { it.toIntOrNull()?.takeIf { n -> n in 501..507 } }
        if (num != null && "fox" in t) {
            out += tokenKey(listOf("fox", "sports", num.toString()))
            out += tokenKey(listOf("fox", num.toString()))
            foxAu[num]?.let { out += tokenKey(listOf("fox", it)) }
        }
        // a trailing channel number (>= 100) that the guide does not repeat: "Fox Cricket 501" -> "Fox Cricket"
        val noBigNumber = t.filter { it.toIntOrNull()?.let { n -> n >= 100 } != true }
        if (noBigNumber.isNotEmpty() && noBigNumber.size < t.size && !(num != null && foxAu[num] == null)) out += tokenKey(noBigNumber)
        // "Sports" is often dropped or added between brand and number
        val noSports = t.filter { it != "sports" && it != "sport" }
        if (noSports.isNotEmpty() && noSports.size < t.size) out += tokenKey(noSports)
        return out.filter { k -> k.split(' ').let { parts -> !(parts.size == 1 && parts[0] in genericNames) } }
    }

    /** True when [id] is a generic brand id that does not fit the channel's own name (a mapping from an older, looser matcher). */
    fun isWrongGenericId(ch: ChannelEpgRef): Boolean {
        val id = ch.epgChannelId?.takeIf { it.isNotBlank() } ?: return false
        val nId = normalize(id.replace(domainSuffix, ""))
        return nId in genericNames && normalize(ch.name) != nId
    }

    /** Built once per mapping run from the guide's channel list and the ids that have programmes. */
    class Index(guide: List<EpgChannelEntity>, idsWithPrograms: Set<String>) {
        private val byLowerId = HashMap<String, String>()
        private val byNorm = HashMap<String, MutableList<String>>()
        private val byTok = HashMap<String, MutableList<String>>()
        val icons = HashMap<String, String>()

        init {
            for (g in guide) {
                if (g.channelId !in idsWithPrograms) continue
                byLowerId.putIfAbsent(g.lowerId, g.channelId)
                g.iconUrl?.takeIf { it.isNotBlank() }?.let { icons[g.channelId] = it }
                for (n in g.normNames.split('\t')) if (n.isNotEmpty()) byNorm.getOrPut(n) { ArrayList(2) }.add(g.channelId)
                for (k in smartKeys(g.displayName).take(1)) byTok.getOrPut(k) { ArrayList(2) }.add(g.channelId)
            }
            // ids that have programmes but no <channel> element in the guide still match by id
            for (id in idsWithPrograms) byLowerId.putIfAbsent(id.lowercase(), id)
            for (id in idsWithPrograms) {
                val n = normalize(id.replace(domainSuffix, ""))
                if (n.isNotEmpty()) {
                    val list = byNorm.getOrPut(n) { ArrayList(2) }
                    if (id !in list) list.add(id)
                }
            }
        }

        fun byTokens(key: String): String? = byTok[key]?.firstOrNull()

        fun byId(id: String?): String? = id?.trim()?.takeIf { it.isNotEmpty() }?.let { byLowerId[it.lowercase()] }

        /** Best guide channel for [norm]; when several share the name, the one whose id also resembles [hint]. */
        fun byName(norm: String, hint: String?): String? {
            if (norm.isEmpty()) return null
            val hits = byNorm[norm] ?: return null
            if (hits.size == 1) return hits[0]
            val h = hint?.lowercase().orEmpty()
            return hits.firstOrNull { h.isNotEmpty() && it.lowercase().contains(h) } ?: hits[0]
        }
    }

    /** The guide channel [ch] belongs to, or null. */
    fun resolve(ch: ChannelEpgRef, index: Index): String? {
        val wrong = isWrongGenericId(ch)
        if (!wrong) index.byId(ch.epgChannelId)?.let { return it }
        index.byId(ch.epgName)?.let { if (!isGeneric(normalize(it))) return it }
        index.byId(ch.name)?.let { return it }
        val nEpg = normalize(ch.epgName)
        if (!isGeneric(nEpg)) index.byName(nEpg, ch.epgChannelId)?.let { return it }
        val nName = normalize(ch.name)
        if (!isGeneric(nName)) index.byName(nName, ch.epgChannelId)?.let { return it }
        // no id and no exact name: word-order free / number-aware match
        for (k in smartKeys(ch.name) + smartKeys(ch.epgName)) index.byTokens(k)?.let { return it }
        return null
    }

    /**
     * Keys a channel's programmes may be stored under, best first. The persisted [ChannelEntity.epgChannelId]
     * (set by [resolve]) comes first; the rest cover guides that were imported after the last mapping run and
     * Stalker portal guides (stored under "sp<playlistId>:<channelId>").
     */
    fun candidateKeys(ch: ChannelEntity): List<String> {
        val out = ArrayList<String>(6)
        ch.epgChannelId?.takeIf { it.isNotBlank() }?.let { out += it.lowercase() }
        if (ch.streamId.isNotBlank()) {
            out += portalKey(ch.playlistId, ch.streamId)
            out += ch.streamId.lowercase()
        }
        ch.epgName?.takeIf { it.isNotBlank() }?.let { out += it.lowercase() }
        out += ch.name.lowercase()
        return out
    }

    fun portalKey(playlistId: Long, channelId: String) = "sp$playlistId:${channelId.lowercase()}"

    /** Programmes of [ch] from a lowercase-id -> programmes map; the first candidate key that has any wins. */
    fun programsOf(ch: ChannelEntity, byEpgId: Map<String, List<ProgramEntity>>): List<ProgramEntity> {
        for (k in candidateKeys(ch)) byEpgId[k]?.let { if (it.isNotEmpty()) return it }
        return emptyList()
    }

    /** The programme on air for [ch] out of a list of "current" programmes (any candidate key may match). */
    fun currentProgramOf(ch: ChannelEntity, programs: List<ProgramEntity>): ProgramEntity? {
        val keys = candidateKeys(ch).toHashSet()
        return programs.firstOrNull { it.epgChannelId.lowercase() in keys }
    }

    /** All programmes of [ch] out of a mixed list (same matching as [currentProgramOf]). */
    fun programsOfList(ch: ChannelEntity, programs: List<ProgramEntity>): List<ProgramEntity> {
        val keys = candidateKeys(ch).toHashSet()
        return programs.filter { it.epgChannelId.lowercase() in keys }
    }
}
