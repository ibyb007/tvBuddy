package com.example.updater

data class GitHubAsset(val name: String, val downloadUrl: String, val size: Long?)

data class GitHubRelease(
    val tagName: String?,
    val name: String?,
    val body: String?,
    val draft: Boolean,
    val prerelease: Boolean,
    val htmlUrl: String?,
    val assets: List<GitHubAsset>
)

data class AppUpdate(
    val tag: String,
    val title: String,
    val notes: String,
    val releaseUrl: String?,
    val assetName: String,
    val assetUrl: String,
    val assetSizeBytes: Long?
)

internal object ReleaseSelector {

    /** Releases the [channel] may offer, newest version first. Stable never sees pre-releases or canary builds. */
    fun eligibleReleases(releases: List<GitHubRelease>, channel: UpdateChannel): List<GitHubRelease> = releases
        .asSequence()
        .filterNot { it.draft }
        .mapNotNull { release ->
            val version = VersionUtils.parse(release.tagName) ?: return@mapNotNull null
            release to version
        }
        .filter { (release, version) ->
            channel == UpdateChannel.CANARY || (!release.prerelease && !version.isCanary)
        }
        .sortedByDescending { it.second }
        .map { it.first }
        .toList()

    /** The APK of a release; with several, the one for the device ABI, else a universal one. */
    fun chooseApk(assets: List<GitHubAsset>, supportedAbis: List<String>): GitHubAsset? {
        val apks = assets.filter { it.name.endsWith(".apk", ignoreCase = true) }
        if (apks.size <= 1) return apks.firstOrNull()
        for (abi in supportedAbis) {
            apks.firstOrNull { it.name.contains(abi, ignoreCase = true) }?.let { return it }
        }
        apks.firstOrNull { it.name.contains("universal", ignoreCase = true) }?.let { return it }
        return apks.first()
    }
}
