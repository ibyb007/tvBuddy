package com.example.data.model

import android.os.Build
import com.example.data.local.entity.PlaylistEntity
import org.json.JSONObject

enum class SpoofPreset(val displayName: String, val description: String) {
    TIVIMATE("TiviMate", "Spoofs TiviMate IPTV Player on Android TV"),
    OTT_NAVIGATOR("OTT Navigator", "Spoofs OTT Navigator / Studio Scillarium"),
    VLC("VLC Media Player", "Standard LibVLC Player signature"),
    APPLE_TV("Apple TV / CoreMedia", "AppleCoreMedia streaming signature"),
    EXOPLAYER("ExoPlayer Standard", "Default ExoPlayer client"),
    CUSTOM("Custom Signature", "Custom User-Agent and request headers")
}

data class SpoofProfile(
    val preset: SpoofPreset = SpoofPreset.TIVIMATE,
    val customUserAgent: String = "",
    val customHeaders: Map<String, String> = emptyMap()
) {
    fun getUserAgent(): String {
        return when (preset) {
            // Same shapes the real apps send (providers that check the client reject anything else)
            // captured from a real TiviMate 5.3.3 request: only User-Agent (+ Accept-Encoding/Connection from the HTTP stack)
            SpoofPreset.TIVIMATE -> "TiviMate/5.3.3 (Android ${Build.VERSION.RELEASE})"
            SpoofPreset.OTT_NAVIGATOR -> "OTT Navigator/1.7.0.2 (Linux;Android ${Build.VERSION.RELEASE}; ${Build.MODEL}) ExoPlayerLib/2.19.1"
            SpoofPreset.VLC -> "VLC/3.5.4 (Linux; Android 12; LibVLC/3.5.4)"
            SpoofPreset.APPLE_TV -> "AppleCoreMedia/1.0.0.19E241 (Apple TV; U; CPU OS 15_4 like Mac OS X; en_us)"
            SpoofPreset.EXOPLAYER -> "ExoPlayerLib/2.19.1 (tvBuddy IPTV)"
            SpoofPreset.CUSTOM -> customUserAgent.ifBlank { "tvBuddy/1.0.0" }
        }
    }

    fun getEffectiveHeaders(): Map<String, String> {
        val headers = mutableMapOf<String, String>()
        headers["User-Agent"] = getUserAgent()
        // the real TiviMate sends no Accept / Accept-Language header
        if (preset != SpoofPreset.TIVIMATE) headers["Accept"] = "*/*"

        when (preset) {
            SpoofPreset.OTT_NAVIGATOR -> {
                headers["Accept-Language"] = "en-US,en;q=0.9"
            }
            SpoofPreset.APPLE_TV -> {
                headers["Connection"] = "keep-alive"
            }
            else -> {}
        }

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
