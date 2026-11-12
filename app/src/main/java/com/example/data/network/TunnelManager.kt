package com.example.data.network

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

enum class TunnelPhase { OFF, STARTING, RUNNING, ERROR }

/**
 * One on/off switch with two modes:
 *  - WARP: Cloudflare WARP through the bundled usque proxy ([WarpManager])
 *  - VPN:  the imported WireGuard config as a per-app VPN ([VpnManager])
 * Only the selected mode's engine ever runs.
 */
object TunnelManager {

    enum class Mode(val title: String) { WARP("WARP"), VPN("VPN") }

    data class State(
        val mode: Mode = Mode.WARP,
        val enabled: Boolean = false,
        val phase: TunnelPhase = TunnelPhase.OFF,
        val message: String? = null,
        val vpnConfigName: String? = null,
        val vpnEndpoint: String? = null
    ) {
        val modeTitle: String get() = mode.title
        val statusText: String
            get() = when (phase) {
                TunnelPhase.STARTING -> "Connecting"
                TunnelPhase.RUNNING -> "On"
                TunnelPhase.ERROR -> "Failed"
                TunnelPhase.OFF -> if (enabled) "Enabled" else "Off"
            }
    }

    private const val PREFS = "tvbuddy_prefs"
    private const val KEY_MODE = "tunnel_mode"

    private lateinit var appContext: Context
    private var initialised = false
    private val mode = MutableStateFlow(Mode.WARP)
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private fun prefs() = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Call once before any HTTP client is built (installs the proxy selector, restores the last choice). */
    fun init(context: Context) {
        val first = synchronized(this) {
            val f = !initialised
            if (f) {
                initialised = true
                appContext = context.applicationContext
            }
            f
        }
        mode.value = if (prefs().getString(KEY_MODE, null) == "vpn") Mode.VPN else Mode.WARP
        WarpManager.init(appContext)
        VpnManager.init(appContext)
        // never leave the other engine running
        if (mode.value == Mode.VPN && WarpManager.state.value.enabled) WarpManager.setEnabled(false)
        if (mode.value == Mode.WARP && VpnManager.state.value.enabled) VpnManager.setEnabled(false)
        recompute()
        if (first) {
            CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
                // only a trigger: recompute() always reads the latest values, so it can never publish stale ones
                combine(mode, WarpManager.state, VpnManager.state) { _, _, _ -> }.collect { recompute() }
            }
            // log switched on while the tunnel is already up: record the exit then
            CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
                com.example.player.PlaybackLog.enabled.collect { on ->
                    if (on && _state.value.phase == TunnelPhase.RUNNING) logExit(_state.value.modeTitle)
                }
            }
        }
    }

    @Volatile private var lastPhase = TunnelPhase.OFF
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Playback-log diagnostic: which address / data centre the tunnel exits from (sites block by exit address). */
    private fun logExit(modeTitle: String) {
        if (!com.example.player.PlaybackLog.enabled.value) return
        ioScope.launch {
            try {
                val client = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).build()
                val body = client.newCall(Request.Builder().url("https://www.cloudflare.com/cdn-cgi/trace").build())
                    .execute().use { it.body?.string().orEmpty() }
                val keep = setOf("ip", "colo", "loc", "warp", "http", "tls")
                val info = body.lines().map { it.split("=", limit = 2) }
                    .filter { it.size == 2 && it[0] in keep }.joinToString(" ") { "${it[0]}=${it[1]}" }
                com.example.player.PlaybackLog.info("EXIT", "$modeTitle exit: $info")
            } catch (e: Exception) {
                com.example.player.PlaybackLog.warn("EXIT", "$modeTitle exit check failed: ${e.message}")
            }
        }
    }

    private fun recompute() {
        val m = mode.value
        val v = VpnManager.state.value
        _state.value = if (m == Mode.VPN) {
            State(m, v.enabled, v.phase, v.message, v.configName, v.endpoint)
        } else {
            val w = WarpManager.state.value
            val wp = when (w.phase) {
                WarpManager.Phase.OFF -> TunnelPhase.OFF
                WarpManager.Phase.STARTING -> TunnelPhase.STARTING
                WarpManager.Phase.RUNNING -> TunnelPhase.RUNNING
                WarpManager.Phase.ERROR -> TunnelPhase.ERROR
            }
            State(m, w.enabled, wp, w.message, v.configName, v.endpoint)
        }
        val cur = _state.value
        if (cur.phase == TunnelPhase.RUNNING && lastPhase != TunnelPhase.RUNNING) logExit(cur.modeTitle)
        lastPhase = cur.phase
    }

    fun setEnabled(enabled: Boolean) {
        if (!initialised) return
        if (mode.value == Mode.VPN) {
            if (enabled) WarpManager.setEnabled(false)
            VpnManager.setEnabled(enabled)
        } else {
            if (enabled) VpnManager.setEnabled(false)
            WarpManager.setEnabled(enabled)
        }
        recompute()
    }

    /** Switches mode; if the tunnel was on, it comes back up in the new mode. */
    fun setMode(newMode: Mode) {
        if (!initialised || mode.value == newMode) return
        val wasOn = _state.value.enabled
        prefs().edit().putString(KEY_MODE, if (newMode == Mode.VPN) "vpn" else "warp").apply()
        if (mode.value == Mode.WARP) WarpManager.setEnabled(false) else VpnManager.setEnabled(false)
        mode.value = newMode
        if (wasOn) {
            if (newMode == Mode.VPN) VpnManager.setEnabled(true) else WarpManager.setEnabled(true)
        }
        recompute()
    }

    /** Stops whichever tunnel runs, keeping the "enabled" choice for the next launch. */
    fun shutdown() {
        WarpManager.shutdown()
        VpnManager.shutdown()
    }

    fun describe(): String =
        "mode=${mode.value},${if (mode.value == Mode.VPN) VpnManager.describe() else WarpManager.describe()}"

    // ---- WireGuard config import (returns an error message, or null on success) ----
    suspend fun importFromUri(context: Context, uri: Uri): String? = VpnManager.importFromUri(context, uri)
    suspend fun importFromUrl(url: String): String? = VpnManager.importFromUrl(url)
    fun removeConfig() = VpnManager.removeConfig()
}
