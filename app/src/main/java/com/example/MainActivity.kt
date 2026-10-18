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
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import coil.Coil
import coil.ImageLoader
import okhttp3.OkHttpClient
import java.net.CookieHandler
import java.net.CookieManager
import java.net.CookiePolicy
import java.util.concurrent.TimeUnit
import com.example.ui.MainViewModel
import com.example.ui.screens.HomeScreen
import com.example.ui.theme.DarkBackground
import com.example.ui.theme.TvBuddyTheme
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()
    private val _isInPipMode = MutableStateFlow(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        installNetworkDefaults()

        // Detect if running on an Android TV device
        val isTv = isRunningOnTv()
        if (isTv) {
            viewModel.setTvMode(true)
        }

        setContent {
            val inPip by _isInPipMode.collectAsState()

            TvBuddyTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = DarkBackground
                ) {
                    HomeScreen(
                        viewModel = viewModel,
                        isInPipMode = inPip,
                        onEnterPip = { enterPipMode() }
                    )
                }
            }
        }
    }

    /**
     * - Cookie handler: HLS providers (Akamai/Hotstar style) hand out auth cookies on the master playlist
     *   that the variant playlists / segments need; HttpURLConnection (used by ExoPlayer) only keeps
     *   them when a default CookieHandler exists.
     * - Logo loader: many hosts (Wikimedia, Amazon CDN, ...) reject OkHttp's default "okhttp/x.y"
     *   User-Agent, so channel logos are fetched with a browser-style one. AVIF is not advertised
     *   because Coil cannot decode it on older Android versions.
     */
    private fun installNetworkDefaults() {
        if (CookieHandler.getDefault() == null) {
            CookieHandler.setDefault(CookieManager(null, CookiePolicy.ACCEPT_ALL))
        }
        val client = OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                chain.proceed(
                    chain.request().newBuilder()
                        .header("User-Agent", LOGO_USER_AGENT)
                        .header("Accept", "image/webp,image/png,image/jpeg,image/gif,image/*;q=0.8,*/*;q=0.5")
                        .build()
                )
            }
            .build()
        Coil.setImageLoader(
            ImageLoader.Builder(applicationContext)
                .okHttpClient(client)
                .respectCacheHeaders(false) // logo hosts often send no-store; keep them cached on the TV
                .build()
        )
    }

    private companion object {
        const val LOGO_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 12; TV) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/124.0.0.0 Safari/537.36 tvBuddy/1.0"
    }

    fun enterPipMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val params = PictureInPictureParams.Builder()
                    .setAspectRatio(Rational(16, 9))
                    .build()
                enterPictureInPictureMode(params)
            } catch (_: Exception) {
                try {
                    @Suppress("DEPRECATION")
                    enterPictureInPictureMode()
                } catch (_: Exception) {}
            }
        }
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
        // In PiP mode keep the video playing, otherwise pause so audio/video does not continue in background
        if (!isPip()) viewModel.onAppPaused()
    }

    override fun onStop() {
        super.onStop()
        // Tear the live stream down (it is re-opened in onStart) instead of keeping a stale paused one
        if (!isPip()) viewModel.onAppStopped()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (!isChangingConfigurations) viewModel.closePlayer()
    }

    private fun isRunningOnTv(): Boolean {
        val uiModeManager = getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        val isTvUi = uiModeManager?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
        val hasLeanback = packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
        return isTvUi || hasLeanback
    }
}
