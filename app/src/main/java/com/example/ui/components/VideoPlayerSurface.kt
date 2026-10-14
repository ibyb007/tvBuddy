package com.example.ui.components

import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.example.player.AspectMode

@OptIn(UnstableApi::class)
@Composable
fun VideoPlayerSurface(
    player: ExoPlayer,
    aspectMode: AspectMode,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val playerView = remember {
        PlayerView(context).apply {
            useController = false
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            keepScreenOn = true
            setBackgroundColor(android.graphics.Color.BLACK)
        }
    }

    DisposableEffect(player) {
        playerView.player = player
        onDispose {
            playerView.player = null
        }
    }

    // Update aspect ratio resize mode dynamically
    playerView.resizeMode = aspectMode.resizeMode

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        AndroidView(
            factory = { playerView },
            modifier = Modifier.fillMaxSize()
        )
    }
}
