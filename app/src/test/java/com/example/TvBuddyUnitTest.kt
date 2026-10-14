package com.example

import com.example.data.model.DrmType
import com.example.data.model.SpoofPreset
import com.example.data.model.SpoofProfile
import com.example.data.parser.M3uParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TvBuddyUnitTest {

    @Test
    fun testSpoofProfiles() {
        val tivimateSpoof = SpoofProfile(SpoofPreset.TIVIMATE)
        assertTrue(tivimateSpoof.getUserAgent().contains("TiviMate"))

        val ottSpoof = SpoofProfile(SpoofPreset.OTT_NAVIGATOR)
        assertTrue(ottSpoof.getUserAgent().contains("OTT Navigator"))

        val customSpoof = SpoofProfile(SpoofPreset.CUSTOM, customUserAgent = "MyCustomPlayer/2.0")
        assertEquals("MyCustomPlayer/2.0", customSpoof.getUserAgent())
    }

    @Test
    fun testM3uParserWithDrmAndHeaders() {
        val sampleM3u = """
            #EXTM3U
            #EXTINF:-1 tvg-id="test1" tvg-name="Sports HD" group-title="Sports" tvg-logo="http://logo.png",Sports HD
            #KODIPROP:inputstream.adaptive.license_type=com.widevine.alpha
            #KODIPROP:inputstream.adaptive.license_key=https://license.server/widevine
            http://stream.domain.com/live.mpd|User-Agent=TiviMate/5.0&Referer=http://portal.com
        """.trimIndent()

        val channels = M3uParser.parse(sampleM3u, playlistId = 1)
        assertEquals(1, channels.size)

        val channel = channels[0]
        assertEquals("Sports HD", channel.name)
        assertEquals("Sports", channel.groupTitle)
        assertEquals("http://stream.domain.com/live.mpd", channel.streamUrl)
        assertEquals(DrmType.WIDEVINE.name, channel.drmType)
        assertEquals("https://license.server/widevine", channel.drmLicenseUrl)
        assertEquals("TiviMate/5.0", channel.httpUserAgent)
        assertNotNull(channel.httpHeadersJson)
        assertTrue(channel.httpHeadersJson!!.contains("Referer"))
    }
}
