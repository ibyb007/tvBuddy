package com.example.updater

import android.content.Context
import com.example.BuildConfig
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Keeps the downloaded update APK in app storage and remembers which release it belongs to, so a download made in the
 * background can be installed later, from the banner or by the idle install job.
 */
class UpdateDownloadStore(
    context: Context,
    private val preferences: UpdatePreferences,
    private val client: OkHttpClient = UpdateRepository.httpClient
) {
    // Files storage instead of cache, which the system may clear before the update is installed.
    private val updatesDir = File(context.applicationContext.filesDir, "updates")

    // The banner and the background job can ask for the same download; the second one waits and reuses the file.
    private val mutex = Mutex()

    fun destinationFor(update: AppUpdate): File =
        File(updatesDir, update.assetName.replace(Regex("[^a-zA-Z0-9._-]"), "_"))

    /** The already downloaded APK of this release, or null when it is missing or incomplete. */
    fun downloadedFileFor(update: AppUpdate): File? {
        val (tag, path) = preferences.downloadedUpdate ?: return null
        if (tag != update.tag) return null
        val file = File(path)
        val expected = update.assetSizeBytes
        return file.takeIf { it.exists() && (expected == null || it.length() == expected) }
    }

    /** The downloaded APK when it is for a version newer than the installed one. */
    fun pendingInstall(): Pair<String, File>? {
        val (tag, path) = preferences.downloadedUpdate ?: return null
        val file = File(path)
        if (!file.exists() || !VersionUtils.isRemoteNewer(tag, BuildConfig.VERSION_NAME)) return null
        return tag to file
    }

    suspend fun download(
        update: AppUpdate,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit = { _, _ -> }
    ): Result<File> = mutex.withLock {
        downloadedFileFor(update)?.let { return@withLock Result.success(it) }
        runCatching {
            val destination = destinationFor(update)
            updatesDir.mkdirs()
            val partial = File(updatesDir, destination.name + ".part")
            partial.delete()
            client.newCall(Request.Builder().url(update.assetUrl).build()).execute().use { response ->
                if (!response.isSuccessful) error("Download failed: HTTP ${response.code}")
                val body = response.body ?: error("Empty download")
                val total = body.contentLength().takeIf { it > 0 }
                body.byteStream().use { input ->
                    FileOutputStream(partial).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var done = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            done += read
                            onProgress(done, total)
                        }
                        output.flush()
                    }
                }
            }
            destination.delete()
            if (!partial.renameTo(destination)) error("Could not save the downloaded update")
            updatesDir.listFiles()?.filter { it != destination }?.forEach { it.delete() }
            preferences.setDownloadedUpdate(update.tag, destination.absolutePath)
            destination
        }
    }

    /** Drops the downloaded APK once it is installed, outdated, or no longer wanted. */
    fun clear() {
        updatesDir.deleteRecursively()
        preferences.setDownloadedUpdate(null, null)
    }

    fun clearIfInstalled() {
        val (tag, _) = preferences.downloadedUpdate ?: return
        if (!VersionUtils.isRemoteNewer(tag, BuildConfig.VERSION_NAME)) clear()
    }
}
