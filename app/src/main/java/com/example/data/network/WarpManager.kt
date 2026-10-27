package com.example.data.network

import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketAddress
import java.net.URI
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Cloudflare WARP mode (ported from the Vega app).
 *
 * Runs the bundled `usque` MASQUE client (libusque.so) as a local HTTP proxy and routes the app's
 * HTTP(S) traffic through it by installing a process-wide [ProxySelector]. That covers ExoPlayer's
 * HttpURLConnection data source, OkHttp (playlists, EPG, logos) and anything else that honours
 * the default selector. Loopback and LAN addresses are never proxied.
 */
object WarpManager {

    enum class Phase { OFF, STARTING, RUNNING, ERROR }

    data class State(
        val enabled: Boolean = false,
        val phase: Phase = Phase.OFF,
        val message: String? = null
    )

    private const val TAG = "WarpManager"
    private const val PREFS = "tvbuddy_prefs"
    private const val KEY_ENABLED = "warp_enabled"

    // usque is a static GOOS=linux Go binary, so Go only looks for CA roots in Linux paths that
    // don't exist on Android. Point it at Android's real trust store (Android 14 moved it into the
    // conscrypt APEX and kept /system/etc/security/cacerts as a compatibility path).
    private const val ANDROID_CA_DIRS = "/apex/com.android.conscrypt/cacerts:/system/etc/security/cacerts"

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private lateinit var appContext: Context
    private var initialised = false

    @Volatile private var proxyPort: Int? = null
    @Volatile private var process: Process? = null
    private val lock = Any()
    @Volatile private var generation = 0

    /** Call once, before any HTTP client is built. Restores the last on/off choice. */
    fun init(context: Context) {
        synchronized(lock) {
            if (initialised) {
                // The process outlived the previous Activity (exit -> relaunch): shutdown() stopped the tunnel
                // but kept "enabled", so bring it back up instead of showing "Enabled, not running".
                val st = _state.value
                if (st.enabled && (st.phase == Phase.OFF || st.phase == Phase.ERROR)) {
                    _state.value = State(enabled = true, phase = Phase.STARTING)
                    startAsync()
                }
                return
            }
            initialised = true
            appContext = context.applicationContext
        }
        installProxySelector()
        val enabled = prefs().getBoolean(KEY_ENABLED, false)
        _state.value = State(enabled = enabled, phase = if (enabled) Phase.STARTING else Phase.OFF)
        if (enabled) startAsync()
    }

    /** One-line status for the playback log: tells whether a stream request really went through the tunnel. */
    fun describe(): String {
        val st = _state.value
        return "enabled=${st.enabled},phase=${st.phase},routing=${proxyPort != null}"
    }

    fun isSupported(): Boolean =
        initialised && File(appContext.applicationInfo.nativeLibraryDir, "libusque.so").exists()

    fun setEnabled(enabled: Boolean) {
        if (!initialised) return
        prefs().edit().putBoolean(KEY_ENABLED, enabled).apply()
        if (enabled) {
            _state.value = State(enabled = true, phase = Phase.STARTING)
            startAsync()
        } else {
            generation++
            stopInternal()
            _state.value = State(enabled = false, phase = Phase.OFF)
        }
    }

    /** Stops the tunnel process without forgetting that WARP is enabled (it restarts next launch). */
    fun shutdown() {
        generation++
        stopInternal()
        if (_state.value.enabled) _state.value = _state.value.copy(phase = Phase.OFF)
    }

    private fun prefs() = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ------------------------------------------------------------------ routing

    private fun installProxySelector() {
        val previous = ProxySelector.getDefault()
        ProxySelector.setDefault(object : ProxySelector() {
            override fun select(uri: URI?): List<Proxy> {
                val port = proxyPort
                val host = uri?.host
                if (port != null && port > 0 && host != null && !isLocalHost(host)) {
                    return listOf(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", port)))
                }
                return previous?.select(uri) ?: listOf(Proxy.NO_PROXY)
            }

            override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) {
                Log.w(TAG, "Proxy connection failed for $uri: ${ioe?.message}")
                previous?.connectFailed(uri, sa, ioe)
            }
        })
    }

    private fun isLocalHost(host: String): Boolean {
        val h = host.lowercase()
        if (h == "localhost" || h.endsWith(".local")) return true
        // Only literal IPs are checked, so no DNS lookup happens here.
        val m = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$""").matchEntire(h)
        if (m != null) {
            val a = m.groupValues[1].toInt()
            val b = m.groupValues[2].toInt()
            return a == 127 || a == 10 || (a == 192 && b == 168) ||
                (a == 172 && b in 16..31) || (a == 169 && b == 254)
        }
        return h == "::1" || h == "[::1]"
    }

    // ------------------------------------------------------------------ process control

    private fun startAsync() {
        val gen = ++generation
        Thread {
            try {
                val port = startProcess(gen)
                if (gen != generation) {
                    stopInternal()
                    return@Thread
                }
                proxyPort = port
                _state.value = State(enabled = true, phase = Phase.RUNNING, message = null)
            } catch (e: Exception) {
                if (gen != generation) return@Thread
                Log.w(TAG, "WARP start failed: ${e.message}")
                stopInternal()
                _state.value = State(enabled = true, phase = Phase.ERROR, message = e.message ?: "WARP failed to start")
            }
        }.apply { isDaemon = true; name = "WarpStart" }.start()
    }

    private fun binary(): File {
        val lib = File(appContext.applicationInfo.nativeLibraryDir, "libusque.so")
        if (!lib.exists()) throw IOException("WARP is not bundled in this build (libusque.so missing)")
        if (!lib.canExecute()) lib.setExecutable(true, false)
        return lib
    }

    private fun warpDir(): File = File(appContext.filesDir, "warp").also { if (!it.exists()) it.mkdirs() }

    // Android apps can't read /etc/resolv.conf, so the Go binary would fall back to loopback DNS
    // and fail. Pass the device's real DNS servers (plus public fallbacks) via USQUE_DNS_SERVERS.
    private fun dnsServersEnv(): String {
        val servers = LinkedHashSet<String>()
        try {
            val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val lp = cm?.activeNetwork?.let { cm.getLinkProperties(it) }
            lp?.dnsServers?.forEach { addr ->
                if (!addr.isLoopbackAddress) {
                    val host = addr.hostAddress
                    if (!host.isNullOrBlank()) servers.add(if (host.contains(':')) "[$host]:53" else "$host:53")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read device DNS servers: ${e.message}")
        }
        servers.add("1.1.1.1:53")
        servers.add("8.8.8.8:53")
        return servers.joinToString(",")
    }

    private fun startProcess(gen: Int): Int {
        synchronized(lock) {
            val running = process
            val port = proxyPort
            if (running != null && running.isAlive && port != null) return port
        }
        val bin = binary()
        val config = File(warpDir(), "config.json")
        val dns = dnsServersEnv()

        if (!config.exists() || config.length() == 0L) register(bin, config, dns)
        if (gen != generation) throw IOException("cancelled")

        val port = try { ServerSocket(0).use { it.localPort } } catch (_: Exception) { 8086 }
        val pb = ProcessBuilder(bin.absolutePath, "-c", config.absolutePath, "http-proxy", "-b", "127.0.0.1", "-p", port.toString())
        pb.directory(warpDir())
        pb.environment()["USQUE_DNS_SERVERS"] = dns
        pb.environment()["SSL_CERT_DIR"] = ANDROID_CA_DIRS
        pb.redirectErrorStream(true)
        val proc = pb.start()
        synchronized(lock) { process = proc }

        val listening = AtomicBoolean(false)
        val log = StringBuffer()
        Thread {
            try {
                var lines = 0
                proc.inputStream.bufferedReader().forEachLine { line ->
                    // The tunnel keeps writing to stdout for as long as it runs (one line per connection / event).
                    // Keeping every line in memory and in logcat made the app slower and slower over a session
                    // (growing heap -> more GC pauses). Only the start-up output is logged, and only a short tail is kept.
                    if (lines++ < 60) Log.d(TAG, line)
                    log.append(line).append('\n')
                    if (log.length > 4000) log.delete(0, log.length - 2000)
                    if (!listening.get() && (line.contains("listening on", true) || line.contains("HTTP proxy", true))) listening.set(true)
                }
            } catch (_: Exception) {}
        }.apply { isDaemon = true }.start()

        var waited = 0
        while (waited < 2500 && proc.isAlive && !listening.get()) {
            Thread.sleep(100)
            waited += 100
        }
        if (!proc.isAlive) throw IOException("WARP proxy exited immediately: ${log.toString().trim().takeLast(300)}")
        return port
    }

    /** One-time, non-interactive account registration with Cloudflare. */
    private fun register(bin: File, config: File, dns: String) {
        // usque has no resolver on Android, so registration goes through a tiny in-app CONNECT
        // proxy: Android's own resolver does the lookup and the binary never needs DNS here.
        val regProxy = LocalConnectProxy()
        regProxy.start()
        try {
            val pb = ProcessBuilder(bin.absolutePath, "-c", config.absolutePath, "register", "-a")
            pb.directory(warpDir())
            pb.environment()["USQUE_DNS_SERVERS"] = dns
            pb.environment()["SSL_CERT_DIR"] = ANDROID_CA_DIRS
            val url = "http://127.0.0.1:${regProxy.port}"
            for (k in listOf("HTTPS_PROXY", "https_proxy", "HTTP_PROXY", "http_proxy")) pb.environment()[k] = url
            pb.redirectErrorStream(true)
            val proc = pb.start()
            val out = StringBuffer()
            val reader = Thread {
                try { proc.inputStream.bufferedReader().forEachLine { out.append(it).append('\n') } } catch (_: Exception) {}
            }.apply { isDaemon = true }
            reader.start()
            if (!proc.waitFor(20, TimeUnit.SECONDS)) {
                proc.destroyForcibly()
                throw IOException("WARP registration timed out")
            }
            reader.join(1000)
            if (proc.exitValue() != 0 || !config.exists() || config.length() == 0L) {
                config.delete()
                throw IOException("WARP registration failed: ${out.toString().trim().takeLast(300)}")
            }
        } finally {
            regProxy.shutdown()
        }
    }

    private fun stopInternal() {
        proxyPort = null
        val proc = synchronized(lock) { process.also { process = null } }
        try {
            if (proc != null && proc.isAlive) {
                proc.destroy()
                if (!proc.waitFor(2, TimeUnit.SECONDS)) proc.destroyForcibly()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping WARP: ${e.message}")
        }
    }
}

/** Minimal HTTP CONNECT proxy used only while registering the WARP account. */
private class LocalConnectProxy : Thread("WarpRegisterProxy") {
    private val server = ServerSocket(0, 20, InetAddress.getByName("127.0.0.1"))
    val port: Int get() = server.localPort

    @Volatile private var running = true

    init { isDaemon = true }

    override fun run() {
        while (running) {
            val client = try { server.accept() } catch (_: Exception) { break }
            Thread { handle(client) }.apply { isDaemon = true }.start()
        }
    }

    fun shutdown() {
        running = false
        try { server.close() } catch (_: Exception) {}
    }

    private fun readHead(input: InputStream): String? {
        val sb = StringBuilder()
        while (sb.length < 8192) {
            val b = input.read()
            if (b < 0) return null
            sb.append(b.toChar())
            if (sb.endsWith("\r\n\r\n")) return sb.toString()
        }
        return null
    }

    private fun handle(client: Socket) {
        var upstream: Socket? = null
        try {
            client.soTimeout = 20000
            val head = readHead(client.getInputStream()) ?: return
            val parts = head.lineSequence().first().split(" ")
            if (parts.size < 2 || !parts[0].equals("CONNECT", ignoreCase = true)) {
                client.getOutputStream().write("HTTP/1.1 405 Method Not Allowed\r\n\r\n".toByteArray())
                return
            }
            val hostPort = parts[1]
            val idx = hostPort.lastIndexOf(':')
            val host = if (idx > 0) hostPort.substring(0, idx).trim('[', ']') else hostPort
            val port = if (idx > 0) hostPort.substring(idx + 1).toIntOrNull() ?: 443 else 443

            for (addr in InetAddress.getAllByName(host)) {
                try {
                    val s = Socket()
                    s.connect(InetSocketAddress(addr, port), 10000)
                    upstream = s
                    break
                } catch (_: Exception) {}
            }
            val up = upstream
            if (up == null) {
                client.getOutputStream().write("HTTP/1.1 502 Bad Gateway\r\n\r\n".toByteArray())
                return
            }
            client.getOutputStream().write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
            client.getOutputStream().flush()
            client.soTimeout = 0

            Thread {
                try { up.getInputStream().copyTo(client.getOutputStream()) } catch (_: Exception) {}
                finally {
                    try { client.close() } catch (_: Exception) {}
                    try { up.close() } catch (_: Exception) {}
                }
            }.apply { isDaemon = true }.start()
            try { client.getInputStream().copyTo(up.getOutputStream()) } catch (_: Exception) {}
        } catch (_: Exception) {
        } finally {
            try { client.close() } catch (_: Exception) {}
            try { upstream?.close() } catch (_: Exception) {}
        }
    }
}
