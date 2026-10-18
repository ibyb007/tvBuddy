package com.example.player

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.HttpDataSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * In-memory playback diagnostics, switched on/off from Settings ("Playback log").
 *
 *  - ON : every playback start, state change, retry / format fallback and error (with the full cause
 *         chain, HTTP status, failing URL and stack trace) is recorded.
 *  - OFF: everything recorded so far is discarded.
 *
 * [exportToFile] writes the log as a .txt file (Downloads folder on Android 10+).
 */
object PlaybackLog {
    private const val TAG = "TvBuddyPlayback"
    private const val MAX_LINES = 4000

    private val lock = Any()
    private val lines = ArrayDeque<String>()

    @Volatile
    private var on = false

    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _entryCount = MutableStateFlow(0)
    val entryCount: StateFlow<Int> = _entryCount.asStateFlow()

    private fun stamp(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())

    /** Turning ON starts a fresh log with a device header; turning OFF clears it. */
    fun setEnabled(enabled: Boolean) {
        synchronized(lock) {
            lines.clear()
            on = enabled
        }
        _enabled.value = enabled
        _entryCount.value = 0
        if (enabled) {
            add("I", "LOG", "Playback logging started")
            add("I", "DEVICE", "${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}), Android ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}")
            add("I", "DEVICE", "ABIs: ${Build.SUPPORTED_ABIS.joinToString()}")
        }
    }

    val isEnabled: Boolean get() = on

    fun info(tag: String, msg: String) = add("I", tag, msg)
    fun warn(tag: String, msg: String) = add("W", tag, msg)
    fun error(tag: String, msg: String, t: Throwable? = null) {
        if (!on) return // never do any work (or risk anything) while logging is off
        add("E", tag, if (t == null) msg else "$msg\n${describeThrowable(t)}")
    }

    /** Logging must never be able to crash playback, so everything here is guarded. */
    private fun add(level: String, tag: String, msg: String) {
        if (!on) return
        try {
            val entry = "${stamp()} $level/$tag: $msg"
            synchronized(lock) {
                if (!on) return
                lines.addLast(entry)
                while (lines.size > MAX_LINES) lines.removeFirst()
                _entryCount.value = lines.size
            }
            when (level) {
                "E" -> Log.e(TAG, "$tag: $msg")
                "W" -> Log.w(TAG, "$tag: $msg")
                else -> Log.i(TAG, "$tag: $msg")
            }
        } catch (_: Throwable) {
        }
    }

    fun render(): String = synchronized(lock) { lines.joinToString("\n") } + "\n"

    // ---- helpers used by the player ----

    /** Hides credentials: user:pass@host, and values of token / password / key style query parameters. */
    fun redactUrl(url: String?): String {
        if (url.isNullOrBlank()) return "(none)"
        var out = url.replace(Regex("(://)([^/@\\s]+)@"), "$1***@")
        out = out.replace(
            Regex("([?&](?:token|password|pass|pwd|key|auth|authorization|sig|signature|mac|username|user)=)[^&]*", RegexOption.IGNORE_CASE),
            "$1***"
        )
        return out
    }

    fun stateName(state: Int): String = when (state) {
        androidx.media3.common.Player.STATE_IDLE -> "IDLE"
        androidx.media3.common.Player.STATE_BUFFERING -> "BUFFERING"
        androidx.media3.common.Player.STATE_READY -> "READY"
        androidx.media3.common.Player.STATE_ENDED -> "ENDED"
        else -> "UNKNOWN($state)"
    }

    fun contentTypeName(type: Int): String = when (type) {
        androidx.media3.common.C.CONTENT_TYPE_HLS -> "HLS"
        androidx.media3.common.C.CONTENT_TYPE_DASH -> "DASH"
        androidx.media3.common.C.CONTENT_TYPE_RTSP -> "RTSP"
        androidx.media3.common.C.CONTENT_TYPE_SS -> "SmoothStreaming"
        else -> "Progressive/other"
    }

    /** Cause chain, HTTP details and the top of the innermost stack trace. */
    fun describeThrowable(t: Throwable): String = try {
        describeThrowableUnsafe(t)
    } catch (e: Throwable) {
        "  error: ${t.javaClass.name}: ${t.message} (details unavailable: ${e.javaClass.simpleName})"
    }

    private fun describeThrowableUnsafe(t: Throwable): String {
        val sb = StringBuilder()
        var cur: Throwable? = t
        var depth = 0
        var last: Throwable = t
        while (cur != null && depth < 8) {
            sb.append(if (depth == 0) "  error: " else "  caused by: ")
                .append(cur.javaClass.name).append(": ").append(cur.message ?: "(no message)").append('\n')
            when (cur) {
                is HttpDataSource.InvalidResponseCodeException -> {
                    sb.append("    http status: ${cur.responseCode} ${cur.responseMessage ?: ""}\n")
                    sb.append("    request url: ${redactUrl(cur.dataSpec.uri.toString())}\n")
                    val interesting = listOf("Server", "Location", "Retry-After", "Content-Type", "WWW-Authenticate", "X-Error", "Via")
                    for (entry in cur.headerFields.entries) {
                        val k: String? = entry.key
                        if (k != null && interesting.any { it.equals(k, true) }) {
                            sb.append("    response header $k: ${entry.value?.joinToString()}\n")
                        }
                    }
                    val body = try { String(cur.responseBody, Charsets.UTF_8).take(300) } catch (_: Throwable) { "" }
                    if (body.isNotBlank()) sb.append("    response body (first 300 chars): ${body.replace('\n', ' ')}\n")
                }
                is HttpDataSource.HttpDataSourceException -> {
                    sb.append("    request url: ${redactUrl(cur.dataSpec.uri.toString())}\n")
                    sb.append("    data source error type: ${cur.type}\n")
                }
            }
            last = cur
            cur = cur.cause?.takeIf { it !== cur }
            depth++
        }
        last.stackTrace.take(8).forEach { sb.append("    at ").append(it).append('\n') }
        return sb.toString().trimEnd()
    }

    fun describePlaybackException(e: PlaybackException): String = try {
        "${e.errorCodeName} (code ${e.errorCode})\n${describeThrowable(e)}"
    } catch (_: Throwable) {
        "error code ${e.errorCode}"
    }

    // ---- export ----

    /**
     * Writes the log to a text file. Android 10+: Downloads folder (no permission needed).
     * Older versions: the app's external files folder. Returns a human readable location.
     */
    fun exportToFile(context: Context): Result<String> = runCatching {
        val fileName = "tvbuddy-playback-log-" +
            SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".txt"
        val text = render()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("Could not create the file in Downloads")
            context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
                ?: throw IllegalStateException("Could not open the file for writing")
            "Downloads/$fileName"
        } else {
            val dir = context.getExternalFilesDir(null) ?: context.filesDir
            val file = File(dir, fileName)
            file.writeText(text)
            file.absolutePath
        }
    }
}
