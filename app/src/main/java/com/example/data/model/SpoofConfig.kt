package com.example.data.model

import android.os.Build
import com.example.data.local.entity.PlaylistEntity
import org.json.JSONObject
import java.util.Locale

enum class SpoofPreset(val displayName: String, val description: String) {
    TIVIMATE("TiviMate", "Spoofs TiviMate IPTV Player on Android TV"),
    OTT_NAVIGATOR("OTT Navigator", "Spoofs OTT Navigator / Studio Scillarium"),
    VLC("VLC Media Player", "Standard LibVLC Player signature"),
    SPARKLE_TV("Sparkle TV", "Spoofs the Sparkle TV player"),
    EXOPLAYER("ExoPlayer Standard", "Default ExoPlayer client"),
    CUSTOM("Custom Signature", "Custom User-Agent and request headers")
}

data class SpoofProfile(
    val preset: SpoofPreset = SpoofPreset.TIVIMATE,
    val customUserAgent: String = "",
    val customHeaders: Map<String, String> = emptyMap()
) {
    /** Short stable per-device id of the shape OTT Navigator puts in its user agent (e.g. "1q2vzy"). */
    private fun deviceTag(): String =
        java.lang.Long.toString((Build.MODEL + Build.DEVICE).hashCode().toLong() and 0xffffffffL, 36).padEnd(6, '0').take(6)

    fun getUserAgent(): String {
        return when (preset) {
            // Same shapes the real apps send (providers that check the client reject anything else)
            // TiviMate, OTT Navigator and Sparkle TV strings were captured from real requests (ua_logger.py)
            SpoofPreset.TIVIMATE -> "TiviMate/5.3.3 (Android ${Build.VERSION.RELEASE})"
            SpoofPreset.OTT_NAVIGATOR -> "OTT Navigator/1.7.0.2.4 (Linux;Android ${Build.VERSION.RELEASE}; ${Locale.getDefault().language}; ${deviceTag()})"
            SpoofPreset.VLC -> "VLC/3.5.4 (Linux; Android 12; LibVLC/3.5.4)"
            SpoofPreset.SPARKLE_TV -> "SparkleTV/2.3.0 (${Build.MODEL}, Android ${Build.VERSION.RELEASE})"
            SpoofPreset.EXOPLAYER -> "ExoPlayerLib/2.19.1 (tvBuddy IPTV)"
            SpoofPreset.CUSTOM -> customUserAgent.ifBlank { "tvBuddy/1.0.0" }
        }
    }

    fun getEffectiveHeaders(): Map<String, String> {
        val headers = mutableMapOf<String, String>()
        headers["User-Agent"] = getUserAgent()
        // the real TiviMate / OTT Navigator / Sparkle TV send nothing but User-Agent (the HTTP stack adds
        // Accept-Encoding and Connection), so don't add an Accept header for them
        val uaOnly = preset == SpoofPreset.TIVIMATE || preset == SpoofPreset.OTT_NAVIGATOR || preset == SpoofPreset.SPARKLE_TV
        if (!uaOnly) headers["Accept"] = "*/*"

        headers.putAll(customHeaders)
        return headers
    }
}

/** Parses a {"Header":"value"} JSON object; anything invalid gives an empty map. */
fun parseHeadersJson(json: String?): Map<String, String> {
    if (json.isNullOrBlank()) return emptyMap()
    return try {
        val o = JSONObject(json)
        buildMap { o.keys().forEach { k -> put(k, o.getString(k)) } }
    } catch (_: Exception) {
        emptyMap()
    }
}

/** The player spoofing engine saved for this playlist. */
fun PlaylistEntity.toSpoofProfile(): SpoofProfile {
    val preset = runCatching { SpoofPreset.valueOf(spoofPreset) }.getOrDefault(SpoofPreset.TIVIMATE)
    return SpoofProfile(preset, customUserAgent.orEmpty(), parseHeadersJson(customHeadersJson))
}
