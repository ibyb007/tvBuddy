package com.example

import android.content.Context
import androidx.media3.common.C
import androidx.test.core.app.ApplicationProvider
import com.example.data.parser.M3uParser
import com.example.player.drm.ClearKeyHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.json.JSONObject

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleRobolectricTest {

    @Test
    fun `read string from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("tvBuddy", appName)
    }

    @Test
    fun `test clearkey parser with hex key`() {
        val rawKey = "40f019b86241d23ef075633fd7f1e927:058dec845bd340178a388edd104a015e"
        val parsed = ClearKeyHelper.parseClearKeys(rawKey)
        assertEquals(1, parsed.size)
        val entry = parsed[0]
        assertEquals("QPAZuGJB0j7wdWM_1_HpJw", entry.kidBase64Url)
        assertEquals("BY3shFvTQBeKOI7dEEoBXg", entry.keyBase64Url)

        val jwkBytes = ClearKeyHelper.buildJwkBytes(parsed)
        val jwkStr = String(jwkBytes, Charsets.UTF_8)
        val json = JSONObject(jwkStr)
        assertTrue(json.has("keys"))
        val keys = json.getJSONArray("keys")
        assertEquals(1, keys.length())
        assertEquals("BY3shFvTQBeKOI7dEEoBXg", keys.getJSONObject(0).getString("k"))
        assertEquals("QPAZuGJB0j7wdWM_1_HpJw", keys.getJSONObject(0).getString("kid"))
    }

    @Test
    fun `test clearkey pssh atom generation`() {
        val rawKey = "40f019b86241d23ef075633fd7f1e927:058dec845bd340178a388edd104a015e"
        val parsed = ClearKeyHelper.parseClearKeys(rawKey)
        val pssh = ClearKeyHelper.buildPsshAtom(C.CLEARKEY_UUID, parsed.map { it.kidBytes })
        assertEquals(52, pssh.size)
        assertEquals('p'.code.toByte(), pssh[4])
        assertEquals('s'.code.toByte(), pssh[5])
        assertEquals('s'.code.toByte(), pssh[6])
        assertEquals('h'.code.toByte(), pssh[7])
    }

    @Test
    fun `test m3u normalizer with squished tags`() {
        val squished = "#EXTINF:-1 tvg-id=\"MN+.HD.in\",MN+ HD#KODIPROP:inputstream.adaptive.license_type=org.w3.clearkey#KODIPROP:inputstream.adaptive.license_key=40f019b86241d23ef075633fd7f1e927:058dec845bd340178a388edd104a015ehttps://times-ott-live.akamaized.net/mnplus_wv_drm/index.mpd"
        val normalized = M3uParser.normalizeM3uContent(squished)
        assertTrue(normalized.contains("\n#KODIPROP:"))
        assertTrue(normalized.contains("\nhttps://"))

        val channels = M3uParser.parse(squished, playlistId = 1)
        assertEquals(1, channels.size)
        val ch = channels[0]
        assertEquals("MN+ HD", ch.name)
        assertEquals("CLEARKEY", ch.drmType)
        assertEquals("40f019b86241d23ef075633fd7f1e927:058dec845bd340178a388edd104a015e", ch.drmKeyIdKey)
        assertEquals("https://times-ott-live.akamaized.net/mnplus_wv_drm/index.mpd", ch.streamUrl)
    }

    @Test
    fun `test clearkey session manager patches format with common pssh and valid mime`() {
        val rawKey = "40f019b86241d23ef075633fd7f1e927:058dec845bd340178a388edd104a015e"
        val parsed = ClearKeyHelper.parseClearKeys(rawKey)
        val dummyManager = androidx.media3.exoplayer.drm.DrmSessionManager.DRM_UNSUPPORTED
        val sessionManager = com.example.player.drm.ClearKeyDrmSessionManager(dummyManager, parsed)

        // 1. Video format with video/avc
        val videoFormat = androidx.media3.common.Format.Builder()
            .setSampleMimeType(androidx.media3.common.MimeTypes.VIDEO_H264)
            .build()
        val patchedVideo = sessionManager.patchFormat(videoFormat)
        assertNotNull(patchedVideo.drmInitData)
        val videoInitData = patchedVideo.drmInitData!!
        assertEquals(2, videoInitData.schemeDataCount)
        assertEquals(C.COMMON_PSSH_UUID, videoInitData[0].uuid)
        assertEquals(androidx.media3.common.MimeTypes.VIDEO_MP4, videoInitData[0].mimeType)

        // 2. Audio format with audio/mp4a-latm
        val audioFormat = androidx.media3.common.Format.Builder()
            .setSampleMimeType(androidx.media3.common.MimeTypes.AUDIO_AAC)
            .build()
        val patchedAudio = sessionManager.patchFormat(audioFormat)
        assertNotNull(patchedAudio.drmInitData)
        val audioInitData = patchedAudio.drmInitData!!
        assertEquals(C.COMMON_PSSH_UUID, audioInitData[0].uuid)
        assertEquals(androidx.media3.common.MimeTypes.AUDIO_MP4, audioInitData[0].mimeType)

        // 3. Subtitle format should not be modified
        val subFormat = androidx.media3.common.Format.Builder()
            .setSampleMimeType(androidx.media3.common.MimeTypes.TEXT_VTT)
            .build()
        val unpatchedSub = sessionManager.patchFormat(subFormat)
        assertEquals(null, unpatchedSub.drmInitData)
    }
}
