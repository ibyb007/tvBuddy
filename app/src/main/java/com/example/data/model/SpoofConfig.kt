package com.example.data.model

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
            SpoofPreset.TIVIMATE -> "TiviMate/5.3.3 (Android 12)"
            SpoofPreset.OTT_NAVIGATOR -> "OTT Navigator/1.6.8.5 (Android TV; Android 11; OTT-Nav)"
            SpoofPreset.VLC -> "VLC/3.5.4 (Linux; Android 12; LibVLC/3.5.4)"
            SpoofPreset.APPLE_TV -> "AppleCoreMedia/1.0.0.19E241 (Apple TV; U; CPU OS 15_4 like Mac OS X; en_us)"
            SpoofPreset.EXOPLAYER -> "ExoPlayerLib/2.19.1 (tvBuddy IPTV)"
            SpoofPreset.CUSTOM -> customUserAgent.ifBlank { "tvBuddy/1.0.0" }
        }
    }

    fun getEffectiveHeaders(): Map<String, String> {
        val headers = mutableMapOf<String, String>()
        headers["User-Agent"] = getUserAgent()
        headers["Accept"] = "*/*"

        when (preset) {
            SpoofPreset.TIVIMATE -> {
                headers["Accept-Language"] = "en-US,en;q=0.9"
            }
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
