package com.example.updater

import android.os.Build
import com.example.BuildConfig
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray

class NoEligibleUpdateException(channel: UpdateChannel) :
    IllegalStateException("No APK release found for the ${channel.storedValue} channel")

/** Reads the GitHub releases of the app and picks the newest one the update channel may offer. */
class UpdateRepository(
    private val client: OkHttpClient = httpClient
) {
    suspend fun getLatestUpdate(channel: UpdateChannel): Result<AppUpdate> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url("https://api.github.com/repos/${BuildConfig.GITHUB_OWNER}/${BuildConfig.GITHUB_REPO}/releases?per_page=30")
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .build()
            val releases = client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("GitHub API error: ${response.code}")
                parseReleases(response.body?.string() ?: error("Empty GitHub response"))
            }
            val abis = Build.SUPPORTED_ABIS?.toList().orEmpty()
            val (release, asset) = ReleaseSelector.eligibleReleases(releases, channel)
                .firstNotNullOfOrNull { r -> ReleaseSelector.chooseApk(r.assets, abis)?.let { r to it } }
                ?: throw NoEligibleUpdateException(channel)
            val tag = release.tagName?.takeIf { it.isNotBlank() } ?: error("Release has no tag")
            AppUpdate(
                tag = tag,
                title = release.name?.takeIf { it.isNotBlank() } ?: tag,
                notes = release.body.orEmpty(),
                releaseUrl = release.htmlUrl,
                assetName = asset.name,
                assetUrl = asset.downloadUrl,
                assetSizeBytes = asset.size
            )
        }
    }

    private fun parseReleases(json: String): List<GitHubRelease> {
        val array = JSONArray(json)
        return (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            val assets = o.optJSONArray("assets") ?: JSONArray()
            GitHubRelease(
                tagName = o.optString("tag_name").takeIf { it.isNotEmpty() },
                name = o.optString("name").takeIf { it.isNotEmpty() },
                body = o.optString("body").takeIf { it.isNotEmpty() },
                draft = o.optBoolean("draft"),
                prerelease = o.optBoolean("prerelease"),
                htmlUrl = o.optString("html_url").takeIf { it.isNotEmpty() },
                assets = (0 until assets.length()).map { j ->
                    val a = assets.getJSONObject(j)
                    GitHubAsset(
                        name = a.optString("name"),
                        downloadUrl = a.optString("browser_download_url"),
                        size = a.optLong("size", -1L).takeIf { it > 0 }
                    )
                }
            )
        }
    }

    companion object {
        val httpClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build()
        }
    }
}
