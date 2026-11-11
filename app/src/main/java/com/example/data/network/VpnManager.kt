package com.example.data.network

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.util.Log
import com.wireguard.android.backend.BackendException
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.BadConfigException
import com.wireguard.config.Config
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * VPN mode: runs the imported WireGuard config through wireguard-android's GoBackend as a per-app VpnService.
 * Only tvBuddy's own traffic enters the tunnel (IncludedApplications = this package), the rest of the device is untouched.
 */
object VpnManager {

    data class State(
        val enabled: Boolean = false,
        val phase: TunnelPhase = TunnelPhase.OFF,
        val message: String? = null,
        val configName: String? = null,
        val endpoint: String? = null
    )

    private class VpnStartException(message: String, val disable: Boolean) : IOException(message)

    private const val TAG = "VpnManager"
    private const val PREFS = "tvbuddy_prefs"
    private const val KEY_ENABLED = "vpn_enabled"
    private const val KEY_NAME = "wg_config_name"
    private const val PROBE_URL = "https://cp.cloudflare.com/generate_204"

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private lateinit var appContext: Context
    private var initialised = false
    private val lock = Any()
    @Volatile private var generation = 0
    @Volatile private var selfStop = false
    @Volatile private var backendInstance: GoBackend? = null
    @Volatile private var pendingPermission: CompletableFuture<Boolean>? = null

    // all backend calls run one after another on this thread (GoBackend.setState blocks)
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "WgTunnel").apply { isDaemon = true } }

    /** Set by MainActivity: shows the system "Connection request" dialog. Returns false if it could not be opened. */
    @Volatile var permissionRequester: ((Intent) -> Boolean)? = null

    fun onPermissionResult(granted: Boolean) {
        pendingPermission?.complete(granted)
    }

    private object WgTunnel : Tunnel {
        override fun getName(): String = "tvbuddy"

        override fun onStateChange(newState: Tunnel.State) {
            // the system (or another VPN app) took the tunnel down without us asking
            if (newState == Tunnel.State.DOWN && !selfStop && _state.value.phase == TunnelPhase.RUNNING) {
                generation++
                prefs().edit().putBoolean(KEY_ENABLED, false).apply()
                _state.value = _state.value.copy(
                    enabled = false, phase = TunnelPhase.ERROR, message = "VPN was stopped by the system or another VPN app"
                )
            }
        }
    }

    private fun prefs() = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun configFile() = File(File(appContext.filesDir, "wireguard").also { it.mkdirs() }, "tvbuddy.conf")

    fun init(context: Context) {
        synchronized(lock) {
            if (initialised) {
                val st = _state.value
                if (st.enabled && (st.phase == TunnelPhase.OFF || st.phase == TunnelPhase.ERROR)) {
                    _state.value = st.copy(phase = TunnelPhase.STARTING, message = null)
                    startAsync(interactive = false)
                }
                return
            }
            initialised = true
            appContext = context.applicationContext
        }
        val enabled = prefs().getBoolean(KEY_ENABLED, false) && hasConfig()
        _state.value = State(enabled = enabled, phase = if (enabled) TunnelPhase.STARTING else TunnelPhase.OFF).withConfigInfo()
        if (enabled) startAsync(interactive = false)
    }

    fun describe(): String {
        val st = _state.value
        return "vpn enabled=${st.enabled},phase=${st.phase},config=${st.configName != null}"
    }

    fun hasConfig(): Boolean = initialised && configFile().let { it.exists() && it.length() > 0 }

    private fun State.withConfigInfo(): State {
        val text = readConfigText()
        return copy(
            configName = if (text != null) prefs().getString(KEY_NAME, null) ?: "WireGuard" else null,
            endpoint = text?.let { WireGuardConfig.endpoint(it) }
        )
    }

    private fun readConfigText(): String? =
        try { configFile().takeIf { it.exists() && it.length() > 0 }?.readText() } catch (_: Exception) { null }

    // ------------------------------------------------------------------ on / off

    fun setEnabled(enabled: Boolean) {
        if (!initialised) return
        if (enabled) {
            if (!hasConfig()) {
                prefs().edit().putBoolean(KEY_ENABLED, false).apply()
                _state.value = _state.value.copy(
                    enabled = false, phase = TunnelPhase.ERROR, message = "Import a WireGuard config in Settings first"
                )
                return
            }
            prefs().edit().putBoolean(KEY_ENABLED, true).apply()
            _state.value = _state.value.copy(enabled = true, phase = TunnelPhase.STARTING, message = null)
            startAsync(interactive = true)
        } else {
            prefs().edit().putBoolean(KEY_ENABLED, false).apply()
            stopAsync()
            _state.value = _state.value.copy(enabled = false, phase = TunnelPhase.OFF, message = null)
        }
    }

    /** Stops the tunnel without forgetting that VPN mode is enabled (it comes back on the next launch). */
    fun shutdown() {
        if (!initialised) return
        stopAsync()
        if (_state.value.enabled) _state.value = _state.value.copy(phase = TunnelPhase.OFF, message = null)
    }

    fun removeConfig() {
        if (!initialised) return
        prefs().edit().putBoolean(KEY_ENABLED, false).remove(KEY_NAME).apply()
        stopAsync()
        try { configFile().delete() } catch (_: Exception) {}
        _state.value = State(configName = null, endpoint = null)
    }

    private fun startAsync(interactive: Boolean) {
        val gen = ++generation
        executor.execute { runStart(gen, interactive) }
    }

    private fun stopAsync() {
        generation++
        pendingPermission?.complete(false)
        executor.execute { teardown() }
    }

    private fun backend(): GoBackend {
        backendInstance?.let { return it }
        synchronized(lock) {
            backendInstance?.let { return it }
            try {
                return GoBackend(appContext).also { backendInstance = it }
            } catch (t: Throwable) {
                throw IOException("WireGuard is not available on this device (${t.javaClass.simpleName})")
            }
        }
    }

    private fun teardown() {
        val b = backendInstance ?: return
        selfStop = true
        try {
            b.setState(WgTunnel, Tunnel.State.DOWN, null)
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping WireGuard: ${e.message}")
        } finally {
            selfStop = false
        }
    }

    private fun parse(text: String): Config = try {
        Config.parse(ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)))
    } catch (e: BadConfigException) {
        throw IOException("Invalid WireGuard config: ${e.reason} in ${e.section} (${e.location})")
    }

    private fun runStart(gen: Int, interactive: Boolean) {
        try {
            if (gen != generation) return
            val text = readConfigText() ?: throw VpnStartException("No WireGuard config imported", true)
            val cfg = parse(WireGuardConfig.forApp(text, appContext.packageName))
            ensurePermission(interactive)
            if (gen != generation) return
            try {
                backend().setState(WgTunnel, Tunnel.State.UP, cfg)
            } catch (e: BackendException) {
                throw IOException("WireGuard could not start: ${e.reason}")
            }
            if (gen != generation) { teardown(); return }
            if (!probe(gen)) {
                teardown()
                if (gen != generation) return
                throw IOException("Tunnel is up but no traffic passes. Check the keys, endpoint and AllowedIPs of the config")
            }
            if (gen != generation) { teardown(); return }
            _state.value = _state.value.copy(enabled = true, phase = TunnelPhase.RUNNING, message = null)
        } catch (e: Exception) {
            if (gen != generation) return
            Log.w(TAG, "VPN start failed: ${e.message}")
            try { teardown() } catch (_: Exception) {}
            val disable = (e as? VpnStartException)?.disable == true
            if (disable) prefs().edit().putBoolean(KEY_ENABLED, false).apply()
            _state.value = _state.value.copy(
                enabled = !disable, phase = TunnelPhase.ERROR, message = e.message ?: "VPN failed to start"
            )
        }
    }

    private fun ensurePermission(interactive: Boolean) {
        val intent = VpnService.prepare(appContext) ?: return
        if (!interactive) throw VpnStartException("VPN permission is needed. Turn VPN off and on again to grant it", false)
        val requester = permissionRequester ?: throw VpnStartException("Cannot ask for the VPN permission right now", true)
        val future = CompletableFuture<Boolean>()
        val launchFailed = AtomicBoolean(false)
        pendingPermission = future
        Handler(Looper.getMainLooper()).post {
            val ok = try { requester(intent) } catch (_: Exception) { false }
            if (!ok) { launchFailed.set(true); future.complete(false) }
        }
        val granted = try { future.get(120, TimeUnit.SECONDS) } catch (_: Exception) { false } finally { pendingPermission = null }
        if (launchFailed.get()) throw VpnStartException("This device has no VPN permission dialog, so VPN mode is not supported", true)
        if (!granted) throw VpnStartException("VPN permission was not granted", true)
    }

    /** WireGuard is silent when keys/endpoint are wrong, so check that a request really gets through the tunnel. */
    private fun probe(gen: Int): Boolean {
        val client = OkHttpClient.Builder()
            .connectTimeout(7, TimeUnit.SECONDS).readTimeout(7, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.SECONDS).retryOnConnectionFailure(false).build()
        repeat(2) { attempt ->
            if (gen != generation) return false
            try {
                client.newCall(Request.Builder().url(PROBE_URL).head().build()).execute().use { return true }
            } catch (_: Exception) {
                if (attempt == 0) Thread.sleep(500)
            }
        }
        return false
    }

    // ------------------------------------------------------------------ config import

    /** Returns an error message, or null when the config was saved. */
    suspend fun importFromUri(context: Context, uri: Uri): String? = withContext(Dispatchers.IO) {
        try {
            val text = context.contentResolver.openInputStream(uri)?.use { readLimited(it) }
                ?: return@withContext "Could not open the file"
            var name: String? = null
            try {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) name = c.getString(0)
                }
            } catch (_: Exception) {}
            saveConfig(text, name?.removeSuffix(".conf"))
        } catch (e: Exception) {
            e.message ?: "Could not read the file"
        }
    }

    suspend fun importFromUrl(url: String): String? = withContext(Dispatchers.IO) {
        val u = url.trim()
        if (!u.startsWith("http://", true) && !u.startsWith("https://", true)) return@withContext "Enter a link starting with http:// or https://"
        try {
            val client = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()
            val req = Request.Builder().url(u).header("User-Agent", "tvBuddy").build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext "The server answered ${resp.code}"
                val body = resp.body ?: return@withContext "The server sent no data"
                val text = body.byteStream().use { readLimited(it) }
                val last = req.url.pathSegments.lastOrNull().orEmpty()
                val name = if (last.endsWith(".conf", true)) last.dropLast(5) else req.url.host
                saveConfig(text, name)
            }
        } catch (e: Exception) {
            e.message ?: "Download failed"
        }
    }

    private fun readLimited(input: InputStream): String {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        var total = 0
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > WireGuardConfig.MAX_BYTES) throw IOException("That file is too large for a WireGuard config")
            out.write(buf, 0, n)
        }
        return out.toString("UTF-8").removePrefix("﻿")
    }

    private fun saveConfig(text: String, suggestedName: String?): String? {
        WireGuardConfig.precheck(text)?.let { return it }
        try {
            parse(WireGuardConfig.forApp(text, appContext.packageName))
        } catch (e: IOException) {
            return e.message
        }
        val name = suggestedName?.trim()?.takeIf { it.isNotEmpty() }
            ?: WireGuardConfig.endpointHost(WireGuardConfig.endpoint(text)) ?: "WireGuard"
        val file = configFile()
        val tmp = File(file.parentFile, "tvbuddy.conf.tmp")
        try {
            tmp.writeText(text)
            if (!tmp.renameTo(file)) { file.delete(); if (!tmp.renameTo(file)) return "Could not save the config" }
        } catch (e: Exception) {
            return "Could not save the config: ${e.message}"
        }
        prefs().edit().putString(KEY_NAME, name.take(40)).apply()
        val wasOn = _state.value.enabled
        _state.value = _state.value.withConfigInfo()
        if (wasOn) {
            // running with the old config: bring it back up with the new one
            stopAsync()
            _state.value = _state.value.copy(phase = TunnelPhase.STARTING, message = null)
            startAsync(interactive = true)
        }
        return null
    }
}
