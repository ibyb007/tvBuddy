package com.example.data.model

import androidx.media3.common.C
import java.util.UUID

enum class DrmType(val title: String) {
    NONE("None / Clear"),
    WIDEVINE("Widevine Modular"),
    CLEARKEY("ClearKey"),
    PLAYREADY("PlayReady");

    val uuid: UUID?
        get() = when (this) {
            NONE -> null
            WIDEVINE -> C.WIDEVINE_UUID
            CLEARKEY -> C.CLEARKEY_UUID
            PLAYREADY -> C.PLAYREADY_UUID
        }

    companion object {
        fun fromString(value: String?): DrmType {
            if (value.isNullOrBlank()) return NONE
            return when (value.uppercase().trim()) {
                "WIDEVINE", "COM.WIDEVINE.ALPHA" -> WIDEVINE
                "CLEARKEY", "ORG.W3.CLEARKEY" -> CLEARKEY
                "PLAYREADY", "COM.MICROSOFT.PLAYREADY" -> PLAYREADY
                else -> NONE
            }
        }
    }
}

data class DrmConfig(
    val type: DrmType = DrmType.NONE,
    val licenseUrl: String = "",
    val headers: Map<String, String> = emptyMap(),
    val keyIdKey: String = "" // format: "keyId:key" (hex) for ClearKey
) {
    val isEnabled: Boolean
        get() = type != DrmType.NONE && (licenseUrl.isNotBlank() || keyIdKey.isNotBlank())
}
