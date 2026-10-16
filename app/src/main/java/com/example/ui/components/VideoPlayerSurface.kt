package com.example.ui.components

import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.util.lerp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.example.player.AspectMode
import kotlin.math.roundToInt

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

/**
 * ONE video surface for the whole Android TV mode.
 *
 * The TV page (small preview window) and full-screen playback used to each create their own
 * PlayerView/SurfaceView. Every switch therefore tore down one surface and built another, which
 * made the decoder wait for a new surface (and often the next keyframe) -> black flash, or a black
 * screen with audio still playing.
 *
 * Here the surface is composed once at a fixed spot in the tree and is only RESIZED: it morphs
 * between the full screen and [previewBounds] (the preview slot reported by the TV page). A resized
 * SurfaceView keeps its surface, so the picture never drops out.
 *
 * @param browsing true while the TV page is shown (video sits in the preview slot), false for full-screen.
 * @param previewBounds preview slot in root coordinates; null until the TV page has been laid out.
 */
@OptIn(UnstableApi::class)
@Composable
fun TvSharedVideoSurface(
    player: ExoPlayer,
    aspectMode: AspectMode,
    browsing: Boolean,
    previewBounds: State<Rect?>,
    modifier: Modifier = Modifier
) {
    val progress = animateFloatAsState(
        targetValue = if (browsing) 1f else 0f,
        animationSpec = tween(durationMillis = 220),
        label = "tvVideoMorph"
    )
    var origin by remember { mutableStateOf(Offset.Zero) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onGloballyPositioned { origin = it.positionInRoot() }
    ) {
        VideoPlayerSurface(
            player = player,
            aspectMode = if (browsing) AspectMode.FIT else aspectMode,
            modifier = Modifier.layout { measurable, constraints ->
                val fullW = constraints.maxWidth.toFloat()
                val fullH = constraints.maxHeight.toFloat()
                // state reads here only re-run layout (no recomposition) while the preview moves
                val target = previewBounds.value
                val p = progress.value

                var left = 0f
                var top = 0f
                var w = fullW
                var h = fullH
                if (target != null) {
                    left = lerp(0f, target.left - origin.x, p)
                    top = lerp(0f, target.top - origin.y, p)
                    w = lerp(fullW, target.width, p)
                    h = lerp(fullH, target.height, p)
                }
                val wi = w.roundToInt().coerceAtLeast(1)
                val hi = h.roundToInt().coerceAtLeast(1)
                val placeable = measurable.measure(Constraints.fixed(wi, hi))
                layout(constraints.maxWidth, constraints.maxHeight) {
                    placeable.place(left.roundToInt(), top.roundToInt())
                }
            }
        )
    }
}
