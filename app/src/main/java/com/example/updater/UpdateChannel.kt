package com.example.updater

/** Which releases the in-app updater offers: only stable releases, or canary pre-releases as well. */
enum class UpdateChannel(val storedValue: String, val title: String) {
    STABLE("stable", "Stable"),
    CANARY("canary", "Canary");

    companion object {
        fun fromStoredValue(value: String?): UpdateChannel? =
            entries.firstOrNull { it.storedValue.equals(value, ignoreCase = true) }

        /** A canary build starts on the canary channel, anything else on stable. */
        fun defaultForVersion(versionName: String): UpdateChannel =
            if (VersionUtils.isCanary(versionName)) CANARY else STABLE
    }
}
