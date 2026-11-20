package com.example.data.backup

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.example.data.local.TvBuddyDatabase
import java.io.File
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** A backup file found in the Downloads folder. */
data class BackupFile(val name: String, val uri: Uri, val sizeBytes: Long, val modifiedMs: Long)

/**
 * Backup / restore of the whole app: the Room database (playlists, channels, groups, EPG, favourites, history),
 * every SharedPreferences file (all settings) and the tunnel configs (WireGuard / WARP).
 *
 * The backup is ONE file (a zip container with the extension .tvbackup) saved in the Downloads folder.
 * Layout inside the file:
 *   manifest.txt              app / database version + time
 *   db/tvbuddy_database.db    database snapshot (WAL checkpointed first)
 *   prefs/<name>.xml          SharedPreferences files
 *   files/<dir>/<file>        wireguard/ and warp/ folders from filesDir
 *
 * Note: the file contains the WireGuard / WARP keys too, so keep it private.
 */
object BackupManager {
    private const val DB_NAME = "tvbuddy_database.db"
    private const val PREFIX = "tvBuddy-backup-"
    const val EXTENSION = "tvbackup"
    private const val MANIFEST = "manifest.txt"
    private const val DB_ENTRY = "db/$DB_NAME"
    private const val PREFS_DIR = "prefs/"
    private const val FILES_DIR = "files/"
    private val FILE_FOLDERS = listOf("wireguard", "warp")
    // system / library prefs that do not belong to the app's own settings
    private val SKIPPED_PREFS_PREFIXES = listOf("WebView", "com.google.", "androidx.")

    // ------------------------------------------------------------------ backup

    /** Creates the backup file in Downloads. Returns a human readable location ("Downloads/<name>"). */
    fun backup(context: Context): Result<String> = runCatching {
        val app = context.applicationContext
        val db = TvBuddyDatabase.getDatabase(app)
        val sqlite = db.openHelper.writableDatabase
        // merge the write-ahead log into the main file so the copy below is complete
        sqlite.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }
        val dbVersion = sqlite.version

        val name = PREFIX + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".$EXTENSION"
        val temp = File(app.cacheDir, name)
        try {
            ZipOutputStream(temp.outputStream().buffered()).use { zip ->
                zip.putNextEntry(ZipEntry(MANIFEST))
                val versionName = runCatching {
                    app.packageManager.getPackageInfo(app.packageName, 0).versionName
                }.getOrNull() ?: "?"
                zip.write("app=$versionName\ndb=$dbVersion\ncreated=${System.currentTimeMillis()}\n".toByteArray())
                zip.closeEntry()

                val dbFile = app.getDatabasePath(DB_NAME)
                require(dbFile.exists()) { "Database file not found" }
                zip.putNextEntry(ZipEntry(DB_ENTRY))
                dbFile.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()

                prefsDir(app).listFiles { f -> f.isFile && f.extension == "xml" && !isSkippedPrefs(f.name) }
                    ?.forEach { f ->
                        zip.putNextEntry(ZipEntry(PREFS_DIR + f.name))
                        f.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                    }

                FILE_FOLDERS.forEach { folder ->
                    File(app.filesDir, folder).walkTopDown().filter { it.isFile }.forEach { f ->
                        val rel = f.relativeTo(app.filesDir).invariantSeparatorsPath
                        zip.putNextEntry(ZipEntry(FILES_DIR + rel))
                        f.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
                }
            }
            saveToDownloads(app, name, temp)
        } finally {
            temp.delete()
        }
    }

    private fun saveToDownloads(context: Context, name: String, source: File): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("Could not create the file in Downloads")
            context.contentResolver.openOutputStream(uri)?.use { out -> source.inputStream().use { it.copyTo(out) } }
                ?: throw IllegalStateException("Could not open the file for writing")
            return "Downloads/$name"
        }
        // Android 9 and older: public Downloads when it is writable, else the app's own external folder
        @Suppress("DEPRECATION")
        val publicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val target = runCatching {
            publicDir.mkdirs()
            File(publicDir, name).also { source.copyTo(it, overwrite = true) }
        }.getOrElse {
            val dir = context.getExternalFilesDir(null) ?: context.filesDir
            File(dir, name).also { source.copyTo(it, overwrite = true) }
        }
        return target.absolutePath
    }

    // ------------------------------------------------------------------ list

    /** Backup files that can be seen in Downloads, newest first. */
    fun listBackups(context: Context): List<BackupFile> {
        val app = context.applicationContext
        val found = ArrayList<BackupFile>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching {
                app.contentResolver.query(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    arrayOf(
                        MediaStore.Downloads._ID,
                        MediaStore.Downloads.DISPLAY_NAME,
                        MediaStore.Downloads.SIZE,
                        MediaStore.Downloads.DATE_MODIFIED
                    ),
                    "${MediaStore.Downloads.DISPLAY_NAME} LIKE ?",
                    arrayOf("$PREFIX%.$EXTENSION"),
                    "${MediaStore.Downloads.DATE_MODIFIED} DESC"
                )?.use { c ->
                    while (c.moveToNext()) {
                        val id = c.getLong(0)
                        found += BackupFile(
                            name = c.getString(1),
                            uri = android.content.ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id),
                            sizeBytes = c.getLong(2),
                            modifiedMs = c.getLong(3) * 1000L
                        )
                    }
                }
            }
        } else {
            @Suppress("DEPRECATION")
            val dirs = listOfNotNull(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                app.getExternalFilesDir(null)
            )
            dirs.forEach { dir ->
                runCatching {
                    dir.listFiles { f -> f.isFile && f.name.startsWith(PREFIX) && f.extension == EXTENSION }
                        ?.forEach { found += BackupFile(it.name, Uri.fromFile(it), it.length(), it.lastModified()) }
                }
            }
        }
        return found.sortedByDescending { it.modifiedMs }
    }

    // ------------------------------------------------------------------ restore

    /**
     * Replaces the database, all settings and the tunnel configs with the content of the backup at [uri].
     * The caller must restart the app afterwards (see [restartApp]); until then the app must not be used.
     * Nothing is touched when the file is not a valid backup.
     */
    fun restore(context: Context, uri: Uri): Result<Unit> = runCatching {
        val app = context.applicationContext
        val staging = File(app.cacheDir, "restore_staging").apply { deleteRecursively(); mkdirs() }
        try {
            // 1) unpack into the staging folder and validate (the live data is still untouched)
            val input = if (uri.scheme == "file") File(uri.path!!).inputStream()
            else app.contentResolver.openInputStream(uri) ?: throw IllegalStateException("Could not open the file")
            input.use { unpack(it, staging) }

            val stagedDb = File(staging, DB_ENTRY)
            val manifest = File(staging, MANIFEST)
            if (!manifest.exists() || !stagedDb.exists()) throw IllegalArgumentException("This is not a tvBuddy backup file")
            val backupDbVersion = manifest.readLines().firstOrNull { it.startsWith("db=") }?.substringAfter("=")?.toIntOrNull()
            val currentDbVersion = TvBuddyDatabase.getDatabase(app).openHelper.readableDatabase.version
            if (backupDbVersion != null && backupDbVersion > currentDbVersion) {
                throw IllegalArgumentException("This backup was made by a newer version of the app. Update the app first")
            }

            // 2) database: close it, drop the old files, put the snapshot in place
            TvBuddyDatabase.getDatabase(app).close()
            val dbFile = app.getDatabasePath(DB_NAME)
            listOf(dbFile, File(dbFile.path + "-wal"), File(dbFile.path + "-shm"), File(dbFile.path + "-journal"))
                .forEach { it.delete() }
            dbFile.parentFile?.mkdirs()
            stagedDb.copyTo(dbFile, overwrite = true)

            // 3) tunnel configs
            FILE_FOLDERS.forEach { folder ->
                val staged = File(staging, FILES_DIR + folder)
                if (staged.isDirectory) {
                    val live = File(app.filesDir, folder)
                    live.deleteRecursively()
                    staged.copyRecursively(live, overwrite = true)
                }
            }

            // 4) settings last, right before the restart, so a late write of the running app cannot undo them
            val stagedPrefs = File(staging, PREFS_DIR).listFiles { f -> f.isFile && f.extension == "xml" }.orEmpty()
            if (stagedPrefs.isNotEmpty()) {
                val live = prefsDir(app).apply { mkdirs() }
                live.listFiles { f -> f.isFile && f.extension == "xml" && !isSkippedPrefs(f.name) }?.forEach { it.delete() }
                stagedPrefs.forEach { it.copyTo(File(live, it.name), overwrite = true) }
            }
        } finally {
            staging.deleteRecursively()
        }
    }

    /** Unpacks only the entries this manager writes; names are checked so nothing can escape [target]. */
    private fun unpack(input: InputStream, target: File) {
        val root = target.canonicalFile
        ZipInputStream(input.buffered()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val n = entry.name
                val known = !entry.isDirectory &&
                    (n == MANIFEST || n == DB_ENTRY ||
                        (n.startsWith(PREFS_DIR) && n.endsWith(".xml") && n.count { it == '/' } == 1) ||
                        (FILE_FOLDERS.any { n.startsWith("$FILES_DIR$it/") }))
                if (known) {
                    val out = File(root, n).canonicalFile
                    if (!out.path.startsWith(root.path + File.separator)) throw SecurityException("Invalid backup file")
                    out.parentFile?.mkdirs()
                    out.outputStream().use { zip.copyTo(it) }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
    }

    private fun prefsDir(context: Context) = File(context.applicationInfo.dataDir, "shared_prefs")

    private fun isSkippedPrefs(fileName: String) = SKIPPED_PREFS_PREFIXES.any { fileName.startsWith(it) }

    /** Starts the app again from scratch (new process) so it opens the restored data. */
    fun restartApp(context: Context) {
        val app = context.applicationContext
        val launch = app.packageManager.getLaunchIntentForPackage(app.packageName) ?: return
        launch.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)
        val restart = android.content.Intent(app, RestartActivity::class.java).apply {
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(RestartActivity.EXTRA_NEXT, launch)
            putExtra(RestartActivity.EXTRA_PID, android.os.Process.myPid())
        }
        app.startActivity(restart)
    }
}
