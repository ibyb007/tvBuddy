package com.example

import com.example.data.epg.EpgMatcher
import com.example.data.local.dao.ChannelEpgRef
import com.example.data.local.entity.EpgChannelEntity
import com.example.data.util.LogoUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EpgMatcherTest {

    private fun guide(id: String, vararg names: String) = EpgChannelEntity(
        channelId = id, lowerId = id.lowercase(), displayName = names.firstOrNull() ?: id,
        normNames = EpgMatcher.normNamesOf(names.toList(), id)
    )

    @Test
    fun normalizeIgnoresQualityTagsPrefixesAndPunctuation() {
        assertEquals("skysports1", EpgMatcher.normalize("UK: Sky Sports 1 HD"))
        assertEquals("skysports1", EpgMatcher.normalize("sky-sports-1"))
        assertEquals("skysports1", EpgMatcher.normalize("|UK| SKY SPORTS 1 FHD"))
        assertEquals("unitednews", EpgMatcher.normalize("Ünited News (HD)"))
    }

    @Test
    fun timeshiftChannelsStayDistinct() {
        assertEquals("bbconeplus1", EpgMatcher.normalize("BBC One +1"))
    }

    @Test
    fun resolveByIdThenByName() {
        val index = EpgMatcher.Index(
            listOf(guide("BBCOne.uk", "BBC One"), guide("SkySports1.uk", "Sky Sports 1")),
            setOf("BBCOne.uk", "SkySports1.uk")
        )
        // exact id, different case
        assertEquals("BBCOne.uk", EpgMatcher.resolve(ChannelEpgRef(1, "x", "Whatever", "bbcone.UK", null, null), index))
        // no id: matched by display name
        assertEquals("SkySports1.uk", EpgMatcher.resolve(ChannelEpgRef(2, "9", "UK: Sky Sports 1 HD", null, null, null), index))
        // nothing fits
        assertNull(EpgMatcher.resolve(ChannelEpgRef(3, "10", "Unknown Channel", "nope", null, null), index))
    }

    @Test
    fun guideChannelsWithoutProgrammesAreIgnored() {
        val index = EpgMatcher.Index(listOf(guide("Empty.uk", "Some Channel")), emptySet())
        assertNull(EpgMatcher.resolve(ChannelEpgRef(1, "1", "Some Channel", null, null, null), index))
    }

    @Test
    fun logoUrlNormalisation() {
        assertEquals("https://x.com/a.png", LogoUrl.normalize("//x.com/a.png"))
        assertEquals("http://h:8080/img/a%20b.png", LogoUrl.normalize("/img/a b.png", "http://h:8080/portal/c"))
        assertEquals("http://x.com/a.png", LogoUrl.normalize("http://x.com/a.png|User-Agent=foo"))
        assertNull(LogoUrl.normalize("null"))
        assertNull(LogoUrl.normalize("  "))
    }
}
