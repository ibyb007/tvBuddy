package com.example.updater

/**
 * A tvBuddy version: `major.minor.patch` plus, for canary builds, the number of the canary since the last stable
 * release. Stable 1.2.0 is (1, 2, 0, canary 0); the canaries published after it are `1.2.0-canary.1`, shown as
 * "1.2.0 (1)", "1.2.0 (2)" ... and rank above 1.2.0 but below the next stable (1.2.1 / 1.3.0 / 2.0.0).
 */
data class AppVersion(val major: Int, val minor: Int, val patch: Int, val canary: Int) : Comparable<AppVersion> {
    val isCanary: Boolean get() = canary > 0

    override fun compareTo(other: AppVersion): Int = compareValuesBy(
        this, other,
        { it.major }, { it.minor }, { it.patch }, { it.canary }
    )

    /** "1.2.0" for a stable release, "1.2.0 (3)" for canary number 3 after it. */
    fun display(): String = if (isCanary) "$major.$minor.$patch ($canary)" else "$major.$minor.$patch"
}

object VersionUtils {
    // 1.2.0 | v1.2.0 | 1.2 | 1.2.0-canary.3 | 1.2.0-canary.3+abc1234
    private val pattern = Regex(
        "^(\\d+)\\.(\\d+)(?:\\.(\\d+))?(?:-canary\\.(\\d+))?(?:\\+[0-9A-Za-z.-]+)?$"
    )

    fun normalize(raw: String?): String = raw?.trim()?.removePrefix("v")?.removePrefix("V").orEmpty()

    fun parse(raw: String?): AppVersion? {
        val m = pattern.matchEntire(normalize(raw)) ?: return null
        return AppVersion(
            major = m.groupValues[1].toIntOrNull() ?: return null,
            minor = m.groupValues[2].toIntOrNull() ?: return null,
            patch = m.groupValues[3].ifEmpty { "0" }.toIntOrNull() ?: return null,
            canary = m.groupValues[4].ifEmpty { "0" }.toIntOrNull() ?: return null
        )
    }

    fun isCanary(raw: String?): Boolean = parse(raw)?.isCanary == true

    fun isRemoteNewer(remote: String?, local: String?): Boolean {
        val r = parse(remote) ?: return false
        val l = parse(local) ?: return false
        return r > l
    }

    /** Human readable form of a tag or version name; unknown formats are returned as they are. */
    fun display(raw: String?): String = parse(raw)?.display() ?: raw.orEmpty()
}
