package com.example

import android.app.PictureInPictureParams
import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.example.data.network.TunnelManager
import com.example.data.network.VpnManager
import com.example.ui.LogoLoader
import java.net.CookieHandler
import java.net.CookieManager
import java.net.CookiePolicy
import com.example.ui.MainViewModel
import com.example.ui.screens.HomeScreen
import com.example.ui.theme.DarkBackground
import com.example.ui.theme.TvBuddyTheme
import com.example.updater.UpdateViewModel
import com.example.updater.ui.UpdateBannerHost
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()
    private val updateViewModel: UpdateViewModel by viewModels()
    private val _isInPipMode = MutableStateFlow(false)

    // VPN mode: the system "Connection request" dialog
    private val vpnPermissionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        VpnManager.onPermissionResult(result.resultCode == RESULT_OK)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        VpnManager.permissionRequester = { intent ->
            try { vpnPermissionLauncher.launch(intent); true } catch (_: Exception) { false }
        }
        installNetworkDefaults()

        // Detect if running on an Android TV device
        val isTv = isRunningOnTv()
        if (isTv) {
            viewModel.setTvMode(true)
        }

        setContent {
            val inPip by _isInPipMode.collectAsState()
            val updateState by updateViewModel.uiState.collectAsStateWithLifecycle()

            TvBuddyTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = DarkBackground
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        HomeScreen(
                            viewModel = viewModel,
                            updateViewModel = updateViewModel,
                            isInPipMode = inPip,
                            onEnterPip = { enterPipMode() }
                        )
                        // Update banner over the top of the app (hidden in picture-in-picture)
                        if (!inPip) {
                            UpdateBannerHost(
                                modifier = Modifier.align(Alignment.TopCenter),
                                state = updateState,
                                onDismissBanner = { updateViewModel.dismissBanner() },
                                onDownload = { updateViewModel.downloadUpdate() },
                                onInstall = { updateViewModel.installUpdateOrRequestPermission() },
                                onDismissUnknownSources = { updateViewModel.dismissUnknownSourcesDialog() },
                                onOpenUnknownSources = { updateViewModel.openUnknownSourcesSettings() },
                                onFeedbackShown = { updateViewModel.consumeFeedbackMessage() }
                            )
                        }
                    }
                }
            }
        }
    }

    /**
     * - Cookie handler: HLS providers (Akamai/Hotstar style) hand out auth cookies on the master playlist
     *   that the variant playlists / segments need; HttpURLConnection (used by ExoPlayer) only keeps
     *   them when a default CookieHandler exists.
     * - WARP / VPN mode: installs the proxy selector (must happen before any HTTP client is built) and
     *   restarts the tunnel if it was left enabled.
     * - Logo loader: see [LogoLoader].
     */
    private fun installNetworkDefaults() {
        TunnelManager.init(applicationContext)
        if (CookieHandler.getDefault() == null) {
            CookieHandler.setDefault(CookieManager(null, CookiePolicy.ACCEPT_ALL))
        }
        LogoLoader.install(applicationContext)
    }

    fun enterPipMode() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            toastPip("Picture-in-picture needs Android 8 or newer")
            return
        }
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
            toastPip("This device does not support picture-in-picture")
            return
        }
        // post: the remote key that opened it is still being handled, and the call needs a settled, resumed activity
        window.decorView.post {
            try {
                if (isFinishing || isDestroyed) return@post
                val info = viewModel.playerManager.streamInfo.value
                // use the video's own shape when known (the system only accepts ratios between 1:2.39 and 2.39:1)
                val ratio = if (info.width > 0 && info.height > 0) {
                    val r = info.width.toFloat() / info.height
                    if (r in 0.45f..2.35f) Rational(info.width, info.height) else Rational(16, 9)
                } else Rational(16, 9)
                val params = PictureInPictureParams.Builder().setAspectRatio(ratio).build()
                if (!enterPictureInPictureMode(params)) {
                    toastPip("Picture-in-picture is turned off for this app. Enable it in the system settings")
                }
            } catch (e: Exception) {
                com.example.player.PlaybackLog.error("PIP", "Could not enter picture-in-picture: ${e.message}")
                toastPip("Picture-in-picture failed: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    private fun toastPip(text: String) {
        android.widget.Toast.makeText(this, text, android.widget.Toast.LENGTH_LONG).show()
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        _isInPipMode.value = isInPictureInPictureMode
    }

    private fun isPip() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInPictureInPictureMode

    override fun onStart() {
        super.onStart()
        // coming back from the background: re-open the channel fresh (no stale paused stream)
        viewModel.onAppStarted()
    }

    override fun onResume() {
        super.onResume()
        viewModel.onAppResumed()
    }

    override fun onPause() {
        super.onPause()
        // In PiP mode keep the video playing, otherwise pause so audio/video does not continue in background.
        // On a TV onPause also fires when a system overlay (Quick settings, volume / input panels) takes the focus
        // while our window is still visible: playback must keep running then, like TiviMate. When the user really
        // leaves the app (Home, another app) onStop follows and stops the stream.
        if (!isPip() && !isRunningOnTv()) viewModel.onAppPaused()
    }

    override fun onStop() {
        super.onStop()
        // Tear the live stream down (it is re-opened in onStart) instead of keeping a stale paused one
        if (!isPip()) viewModel.onAppStopped()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (!isChangingConfigurations) viewModel.closePlayer()
        VpnManager.permissionRequester = null
        if (isFinishing) TunnelManager.shutdown()
    }

    private fun isRunningOnTv(): Boolean {
        val uiModeManager = getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        val isTvUi = uiModeManager?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
        val hasLeanback = packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
        return isTvUi || hasLeanback
    }
}
