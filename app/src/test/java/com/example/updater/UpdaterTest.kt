package com.example.updater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdaterTest {

    private fun release(tag: String, prerelease: Boolean = false, draft: Boolean = false, apk: Boolean = true) = GitHubRelease(
        tagName = tag,
        name = tag,
        body = null,
        draft = draft,
        prerelease = prerelease,
        htmlUrl = null,
        assets = if (apk) listOf(GitHubAsset("tvBuddy-$tag.apk", "https://example.com/$tag.apk", 10L)) else emptyList()
    )

    @Test
    fun parsesStableAndCanaryVersions() {
        assertEquals(AppVersion(1, 2, 0, 0), VersionUtils.parse("v1.2.0"))
        assertEquals(AppVersion(1, 2, 0, 3), VersionUtils.parse("1.2.0-canary.3"))
        assertEquals(AppVersion(1, 2, 0, 3), VersionUtils.parse("v1.2.0-canary.3+abc1234"))
        assertEquals(AppVersion(1, 0, 0, 0), VersionUtils.parse("1.0"))
        assertNull(VersionUtils.parse("canary-12"))
        assertNull(VersionUtils.parse(null))
    }

    @Test
    fun displaysCanaryAsVersionWithNumber() {
        assertEquals("1.2.0", VersionUtils.display("v1.2.0"))
        assertEquals("1.2.0 (1)", VersionUtils.display("v1.2.0-canary.1"))
        assertEquals("canary-12", VersionUtils.display("canary-12"))
    }

    @Test
    fun canaryAfterStableIsNewerButNextStableWins() {
        assertTrue(VersionUtils.isRemoteNewer("1.2.0-canary.1", "1.2.0"))
        assertTrue(VersionUtils.isRemoteNewer("1.2.0-canary.2", "1.2.0-canary.1"))
        assertFalse(VersionUtils.isRemoteNewer("1.2.0", "1.2.0-canary.2"))
        assertTrue(VersionUtils.isRemoteNewer("1.2.1", "1.2.0-canary.9"))
        assertTrue(VersionUtils.isRemoteNewer("1.3.0-canary.1", "1.2.9"))
        assertFalse(VersionUtils.isRemoteNewer("1.2.0", "1.2.0"))
        assertFalse(VersionUtils.isRemoteNewer("garbage", "1.2.0"))
    }

    @Test
    fun defaultChannelFollowsInstalledBuild() {
        assertEquals(UpdateChannel.STABLE, UpdateChannel.defaultForVersion("1.2.0"))
        assertEquals(UpdateChannel.CANARY, UpdateChannel.defaultForVersion("1.2.0-canary.4"))
    }

    @Test
    fun stableChannelIgnoresCanaryAndPrereleases() {
        val releases = listOf(
            release("v1.2.0-canary.2", prerelease = true),
            release("v1.2.0"),
            release("v1.1.0"),
            release("v1.3.0", draft = true),
            release("canary-7", prerelease = true)
        )
        val tags = ReleaseSelector.eligibleReleases(releases, UpdateChannel.STABLE).map { it.tagName }
        assertEquals(listOf("v1.2.0", "v1.1.0"), tags)
    }

    @Test
    fun canaryChannelOffersNewestOfBoth() {
        val releases = listOf(
            release("v1.1.0"),
            release("v1.2.0"),
            release("v1.2.0-canary.1", prerelease = true),
            release("v1.2.0-canary.2", prerelease = true)
        )
        val tags = ReleaseSelector.eligibleReleases(releases, UpdateChannel.CANARY).map { it.tagName }
        assertEquals("v1.2.0-canary.2", tags.first())
        assertEquals(4, tags.size)
    }

    @Test
    fun choosesApkAssetForAbi() {
        val assets = listOf(
            GitHubAsset("tvBuddy.apk.sha256", "u0", 1),
            GitHubAsset("tvBuddy-armeabi-v7a.apk", "u1", 1),
            GitHubAsset("tvBuddy-arm64-v8a.apk", "u2", 1)
        )
        assertEquals("u2", ReleaseSelector.chooseApk(assets, listOf("arm64-v8a", "armeabi-v7a"))?.downloadUrl)
        assertNull(ReleaseSelector.chooseApk(listOf(GitHubAsset("notes.txt", "u", 1)), emptyList()))
    }

    @Test
    fun releaseNotesShowAtMostFourBullets() {
        val body = """
            > [!IMPORTANT]
            > Canary builds may be unstable

            ## What's changed
            - Add **About** page
            - Fix [crash](https://example.com) on start
            * Faster `EPG` update
            - Four
            - Five
        """.trimIndent()
        assertEquals(
            listOf("Add About page", "Fix crash on start", "Faster EPG update", "Four"),
            ReleaseNotes.summarize(body)
        )
        assertTrue(ReleaseNotes.summarize(null).isEmpty())
    }
}
