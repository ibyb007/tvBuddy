package com.example.ui.components

import android.content.Context
import android.content.ContextWrapper
import android.media.AudioManager
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.BookmarkRemove
import androidx.compose.material.icons.filled.BrightnessMedium
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.exoplayer.ExoPlayer
import com.example.data.local.entity.ChannelEntity
import com.example.player.AspectMode
import com.example.player.AudioBoostProfile
import com.example.player.StreamInfo
import com.example.player.TrackInfo
import com.example.player.VideoQuality
import com.example.ui.theme.AccentCyan
import com.example.ui.theme.StatusLive
import com.example.ui.theme.StatusWarning
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

private enum class MvSwipeKind { VOLUME, BRIGHTNESS }

/** Taps closer together than this belong to the same multi-tap sequence. */
private const val MV_TAP_WINDOW_MS = 320L

/** Every tap after the first one adds this much (2 taps = 10 s, 3 taps = 20 s, 4 taps = 30 s ...). */
private const val MV_SEEK_STEP_MS = 10_000L

/** "1:49:04" for long movies, "14:05" for short positions. */
private fun mvClock(ms: Long): String {
    val total = ms.coerceAtLeast(0L) / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%02d:%02d", m, s)
}

/**
 * Phone player for movies (VOD). Gestures on the picture:
 *  - swipe up / down on the RIGHT half  : volume
 *  - swipe up / down on the LEFT half   : brightness
 *  - tap twice on the right / left half : +10 s / -10 s, three taps +20 s / -20 s, four taps +30 s ... (the sum of the sequence)
 *  - one tap                            : show / hide the controls
 * Controls: back, title, -10 s / play-pause / +10 s, a touch seek bar (drag or tap), and the same action buttons
 * as the live-TV phone player (audio boost, quality, audio, subtitles, My list, aspect, PiP, fullscreen).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MobileVodPlayer(
    player: ExoPlayer,
    channel: ChannelEntity,
    isPlaying: Boolean,
    isBuffering: Boolean,
    errorMessage: String?,
    aspectMode: AspectMode,
    streamInfo: StreamInfo,
    videoQuality: VideoQuality,
    audioBoostProfile: AudioBoostProfile,
    audioTracks: List<TrackInfo>,
    positionMs: () -> Long,
    durationMs: () -> Long,
    onSeekTo: (Long) -> Unit,
    onTogglePlayPause: () -> Unit,
    onToggleMyList: () -> Unit,
    onCycleAspect: () -> Unit,
    onCycleAudioBoost: () -> Unit,
    onOpenQualityPicker: () -> Unit,
    onOpenAudioTracks: () -> Unit,
    onOpenSubtitles: () -> Unit,
    onRetry: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    isInPipMode: Boolean = false,
    isFullscreen: Boolean = false,
    onEnterPip: () -> Unit = {},
    onToggleFullscreen: () -> Unit = {}
) {
    val context = LocalContext.current
    val activity = remember(context) { context.mvFindActivity() }
    val audioManager = remember(context) { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val scope = rememberCoroutineScope()

    val latestPosition by rememberUpdatedState(positionMs)
    val latestDuration by rememberUpdatedState(durationMs)
    val latestSeek by rememberUpdatedState(onSeekTo)

    var showControls by remember { mutableStateOf(true) }
    var controlsTick by remember { mutableIntStateOf(0) }   // any touch on the controls restarts the auto-hide timer

    // ---- position / duration ----
    var pos by remember { mutableLongStateOf(0L) }
    var dur by remember { mutableLongStateOf(0L) }
    var scrubFraction by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(channel.id) {
        while (true) {
            if (scrubFraction == null) pos = latestPosition()
            dur = latestDuration()
            delay(300)
        }
    }

    // auto-hide: only while playing and not touching the seek bar
    LaunchedEffect(showControls, controlsTick, isPlaying, scrubFraction == null, isInPipMode) {
        if (showControls && isPlaying && scrubFraction == null && !isInPipMode) {
            delay(4000)
            showControls = false
        }
    }

    BackHandler(enabled = !isInPipMode) {
        if (isFullscreen) onToggleFullscreen() else onClose()
    }

    // ---- multi-tap seeking ----
    var tapCount by remember { mutableIntStateOf(0) }
    var tapSide by remember { mutableIntStateOf(0) }            // -1 left half, +1 right half
    var lastTapAt by remember { mutableLongStateOf(0L) }
    var tapJob by remember { mutableStateOf<Job?>(null) }
    var seekFeedbackMs by remember { mutableLongStateOf(0L) }   // signed, 0 = hidden
    var lastFeedbackMs by remember { mutableLongStateOf(MV_SEEK_STEP_MS) }   // keeps the pill's text while it fades out

    fun seekBy(deltaMs: Long) {
        val d = latestDuration()
        val limit = if (d > 0) (d - 1000).coerceAtLeast(0) else Long.MAX_VALUE
        val target = (latestPosition() + deltaMs).coerceIn(0L, limit)
        latestSeek(target)
        pos = target
    }

    fun handleTap(x: Float, width: Int) {
        val side = if (x < width / 2f) -1 else 1
        val now = SystemClock.uptimeMillis()
        tapCount = if (tapCount > 0 && side == tapSide && now - lastTapAt <= MV_TAP_WINDOW_MS) tapCount + 1 else 1
        tapSide = side
        lastTapAt = now
        tapJob?.cancel()
        // 2 taps = 10 s, 3 taps = 20 s ...: shown straight away, applied once the tapping stops
        seekFeedbackMs = if (tapCount >= 2) side * (tapCount - 1) * MV_SEEK_STEP_MS else 0L
        if (seekFeedbackMs != 0L) lastFeedbackMs = seekFeedbackMs
        tapJob = scope.launch {
            delay(MV_TAP_WINDOW_MS + 40)
            val count = tapCount
            tapCount = 0
            if (count <= 1) {
                showControls = !showControls
                controlsTick++
            } else {
                seekBy(side * (count - 1) * MV_SEEK_STEP_MS)
                delay(650)
                seekFeedbackMs = 0L
            }
        }
    }

    // ---- swipe: volume (right half) / brightness (left half) ----
    var gestureKind by remember { mutableStateOf<MvSwipeKind?>(null) }
    var gestureLevel by remember { mutableFloatStateOf(0f) }
    var gestureTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(gestureTick) {
        if (gestureKind != null) {
            delay(900)
            gestureKind = null
        }
    }
    DisposableEffect(activity) {
        onDispose {
            activity?.window?.let { w ->
                val lp = w.attributes
                lp.screenBrightness = android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                w.attributes = lp
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(isInPipMode) {
                if (isInPipMode) return@pointerInput
                detectTapGestures(onTap = { offset -> handleTap(offset.x, size.width) })
            }
            .pointerInput(isInPipMode) {
                if (isInPipMode) return@pointerInput
                var kind = MvSwipeKind.VOLUME
                var level = 0f
                detectVerticalDragGestures(
                    onDragStart = { offset ->
                        kind = if (offset.x > size.width / 2f) MvSwipeKind.VOLUME else MvSwipeKind.BRIGHTNESS
                        level = when (kind) {
                            MvSwipeKind.VOLUME -> {
                                val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                                audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max
                            }
                            MvSwipeKind.BRIGHTNESS -> mvCurrentBrightness(activity, context)
                        }
                        gestureKind = kind
                        gestureLevel = level
                    },
                    onDragEnd = { gestureTick++ },
                    onDragCancel = { gestureTick++ },
                    onVerticalDrag = { change, dragAmount ->
                        change.consume()
                        // a swipe over the full height moves the level by ~100 %
                        level = (level - dragAmount / size.height * 1.2f).coerceIn(0f, 1f)
                        when (kind) {
                            MvSwipeKind.VOLUME -> {
                                val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, (level * max).roundToInt(), 0)
                            }
                            MvSwipeKind.BRIGHTNESS -> activity?.window?.let { w ->
                                val lp = w.attributes
                                lp.screenBrightness = level.coerceAtLeast(0.02f)
                                w.attributes = lp
                            }
                        }
                        gestureKind = kind
                        gestureLevel = level
                    }
                )
            }
    ) {
        VideoPlayerSurface(
            player = player,
            aspectMode = aspectMode,
            modifier = Modifier.fillMaxSize()
        )

        if (isInPipMode) return@Box

        // ---- error ----
        if (errorMessage != null && !isBuffering) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.88f))
                    .padding(20.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth(0.85f)) {
                    Icon(Icons.Default.Warning, contentDescription = null, tint = StatusLive, modifier = Modifier.size(36.dp))
                    Spacer(Modifier.height(6.dp))
                    Text("Playback failed", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    Text(errorMessage, color = Color(0xFFFCA5A5), fontSize = 12.sp, maxLines = 3, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(AccentCyan)
                                .clickable(onClick = onRetry)
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, tint = Color.Black, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Retry", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFF334155))
                                .clickable(onClick = onClose)
                                .padding(horizontal = 14.dp, vertical = 8.dp)
                        ) {
                            Text("Close", color = Color.White, fontWeight = FontWeight.Medium, fontSize = 12.sp)
                        }
                    }
                }
            }
            return@Box
        }

        // ---- double / triple tap feedback ----
        AnimatedVisibility(
            visible = seekFeedbackMs != 0L,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(if (lastFeedbackMs < 0) Alignment.CenterStart else Alignment.CenterEnd)
        ) {
            Column(
                modifier = Modifier
                    .padding(horizontal = 36.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(horizontal = 18.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    if (lastFeedbackMs < 0) Icons.Default.FastRewind else Icons.Default.FastForward,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(26.dp)
                )
                Text(
                    "${if (lastFeedbackMs < 0) "-" else "+"}${abs(lastFeedbackMs) / 1000} s",
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // ---- volume / brightness indicator ----
        AnimatedVisibility(
            visible = gestureKind != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(if (gestureKind == MvSwipeKind.VOLUME) Alignment.CenterEnd else Alignment.CenterStart)
        ) {
            MvLevelIndicator(kind = gestureKind ?: MvSwipeKind.VOLUME, level = gestureLevel)
        }

        // ---- scrub preview (time under the finger while dragging the seek bar) ----
        scrubFraction?.let { f ->
            Text(
                text = "${mvClock((f * dur).toLong())} / ${mvClock(dur)}",
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .align(Alignment.Center)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }

        // ---- controls ----
        AnimatedVisibility(
            visible = showControls,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (isFullscreen) Modifier.windowInsetsPadding(WindowInsets.safeDrawing) else Modifier)
            ) {
                // top bar: back + title
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.85f), Color.Transparent)))
                        .padding(start = 8.dp, end = 12.dp, top = 6.dp, bottom = 10.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        IconButton(
                            onClick = onClose,
                            modifier = Modifier.size(34.dp).testTag("player_back_btn")
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White, modifier = Modifier.size(18.dp))
                        }
                        Spacer(Modifier.width(6.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = channel.name,
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Clip,
                                modifier = Modifier.fillMaxWidth().basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 1200)
                            )
                            if (channel.groupTitle.isNotBlank()) {
                                Text(
                                    text = channel.groupTitle,
                                    color = Color(0xFF94A3B8),
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }

                // centre: -10 s, play / pause (spinner while buffering), +10 s
                Row(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalArrangement = Arrangement.spacedBy(26.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    MvRoundButton(Icons.Default.Replay10, 40.dp, 26.dp, "Back 10 seconds") {
                        seekBy(-MV_SEEK_STEP_MS); controlsTick++
                    }
                    if (isBuffering) {
                        Box(Modifier.size(52.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = AccentCyan, strokeWidth = 3.dp, modifier = Modifier.size(40.dp))
                        }
                    } else {
                        IconButton(
                            onClick = { onTogglePlayPause(); controlsTick++ },
                            modifier = Modifier
                                .size(52.dp)
                                .clip(CircleShape)
                                .background(AccentCyan)
                                .testTag("player_play_pause_btn")
                        ) {
                            Icon(
                                if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = if (isPlaying) "Pause" else "Play",
                                tint = Color.Black,
                                modifier = Modifier.size(30.dp)
                            )
                        }
                    }
                    MvRoundButton(Icons.Default.Forward10, 40.dp, 26.dp, "Forward 10 seconds") {
                        seekBy(MV_SEEK_STEP_MS); controlsTick++
                    }
                }

                // bottom: seek bar + action buttons
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.9f))))
                        .padding(start = 8.dp, end = 8.dp, top = 14.dp, bottom = 4.dp)
                ) {
                    val shownPos = scrubFraction?.let { (it * dur).toLong() } ?: pos
                    val fraction = scrubFraction ?: if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(mvClock(shownPos), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                        MvSeekBar(
                            fraction = fraction,
                            enabled = dur > 0,
                            onScrub = { scrubFraction = it; controlsTick++ },
                            onScrubEnd = { f ->
                                val target = (f * dur).toLong().coerceIn(0L, (dur - 1000).coerceAtLeast(0L))
                                latestSeek(target)
                                pos = target
                                scrubFraction = null
                                controlsTick++
                            },
                            modifier = Modifier.weight(1f).padding(horizontal = 10.dp)
                        )
                        Text(if (dur > 0) mvClock(dur) else "--:--", color = Color.White.copy(alpha = 0.75f), fontSize = 11.sp)
                    }

                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                        val audioSelected = audioTracks.firstOrNull { it.isSelected }
                        val audioLabel = when {
                            audioTracks.isEmpty() -> "Audio"
                            audioSelected == null -> "Audio off"
                            else -> audioSelected.language.takeIf { it.isNotBlank() && it != "und" }
                                ?.let { code ->
                                    Locale(code).getDisplayLanguage(Locale.ENGLISH).takeIf { it.isNotBlank() && it != code } ?: code
                                } ?: "Audio"
                        }
                        val qualityLabel = if (streamInfo.height > 0) "${streamInfo.height}p" else videoQuality.title.substringBefore(" ")
                        Row(
                            modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(0.dp)
                        ) {
                            MvActionButton("Boost: ${audioBoostProfile.shortTitle}", Icons.Default.GraphicEq) { onCycleAudioBoost(); controlsTick++ }
                            MvActionButton(qualityLabel, Icons.Default.HighQuality, tag = "quality_btn") { onOpenQualityPicker() }
                            MvActionButton(audioLabel, Icons.AutoMirrored.Filled.VolumeUp) { onOpenAudioTracks() }
                            MvActionButton("Subtitles", Icons.Default.ClosedCaption) { onOpenSubtitles() }
                            MvActionButton(
                                label = if (channel.isFavorite) "In My list" else "My list",
                                icon = if (channel.isFavorite) Icons.Default.BookmarkRemove else Icons.Default.BookmarkAdd,
                                highlight = channel.isFavorite
                            ) { onToggleMyList(); controlsTick++ }
                            MvActionButton(aspectMode.title, Icons.Default.AspectRatio) { onCycleAspect(); controlsTick++ }
                            MvActionButton("PiP", Icons.Default.PictureInPictureAlt, tag = "pip_btn") { onEnterPip() }
                        }
                        IconButton(
                            onClick = onToggleFullscreen,
                            modifier = Modifier.size(40.dp).testTag("fullscreen_toggle_btn")
                        ) {
                            Icon(
                                if (isFullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                                contentDescription = if (isFullscreen) "Exit fullscreen" else "Fullscreen",
                                tint = Color.White,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Touch seek bar: a thin track that grows while it is dragged, with a round thumb. Dragging anywhere on the bar
 * scrubs, a tap jumps there. The bar itself is 36 dp tall so it is easy to hit with a thumb.
 * [onScrub] reports the fraction while the finger is down, [onScrubEnd] the final fraction when it is lifted.
 */
@Composable
private fun MvSeekBar(
    fraction: Float,
    enabled: Boolean,
    onScrub: (Float) -> Unit,
    onScrubEnd: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    var widthPx by remember { mutableIntStateOf(1) }
    var dragging by remember { mutableStateOf(false) }
    val scrub by rememberUpdatedState(onScrub)
    val scrubEnd by rememberUpdatedState(onScrubEnd)
    val barHeight by animateDpAsState(if (dragging) 6.dp else 3.dp, label = "mvBarH")
    val thumbSize by animateDpAsState(if (dragging) 18.dp else 12.dp, label = "mvThumb")
    val f = fraction.coerceIn(0f, 1f)

    Box(
        modifier = modifier
            .height(36.dp)
            .onSizeChanged { widthPx = it.width.coerceAtLeast(1) }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures { offset ->
                    val x = (offset.x / size.width).coerceIn(0f, 1f)
                    scrub(x)
                    scrubEnd(x)
                }
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                var current = 0f
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        dragging = true
                        current = (offset.x / size.width).coerceIn(0f, 1f)
                        scrub(current)
                    },
                    onDragEnd = { dragging = false; scrubEnd(current) },
                    onDragCancel = { dragging = false; scrubEnd(current) },
                    onHorizontalDrag = { change, dx ->
                        change.consume()
                        current = (current + dx / size.width).coerceIn(0f, 1f)
                        scrub(current)
                    }
                )
            },
        contentAlignment = Alignment.CenterStart
    ) {
        Box(Modifier.fillMaxWidth().height(barHeight).clip(CircleShape).background(Color.White.copy(alpha = 0.28f)))
        Box(Modifier.fillMaxWidth(f).height(barHeight).clip(CircleShape).background(AccentCyan))
        Box(
            Modifier
                .offset { IntOffset((f * widthPx - thumbSize.toPx() / 2f).roundToInt(), 0) }
                .size(thumbSize)
                .clip(CircleShape)
                .background(Color.White)
        )
    }
}

@Composable
private fun MvRoundButton(icon: ImageVector, size: androidx.compose.ui.unit.Dp, iconSize: androidx.compose.ui.unit.Dp, description: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(size + 8.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.35f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = description, tint = Color.White, modifier = Modifier.size(iconSize + 4.dp))
    }
}

/** Round action button with its label underneath (same look as the live-TV phone player). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MvActionButton(
    label: String,
    icon: ImageVector,
    highlight: Boolean = false,
    tag: String? = null,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .width(50.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .then(if (tag != null) Modifier.testTag(tag) else Modifier)
            .padding(vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier.size(34.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = label, tint = if (highlight) StatusWarning else Color.White, modifier = Modifier.size(19.dp))
        }
        Spacer(Modifier.height(3.dp))
        Text(
            text = label,
            color = Color.White,
            fontSize = 9.5.sp,
            lineHeight = 11.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Clip,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp).basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 1000)
        )
    }
}

/** Small side pill shown while swiping: icon, vertical level bar and percentage. */
@Composable
private fun MvLevelIndicator(kind: MvSwipeKind, level: Float) {
    Column(
        modifier = Modifier
            .padding(horizontal = 18.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color.Black.copy(alpha = 0.6f))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = when {
                kind == MvSwipeKind.BRIGHTNESS -> Icons.Default.BrightnessMedium
                level <= 0f -> Icons.Default.VolumeOff
                else -> Icons.AutoMirrored.Filled.VolumeUp
            },
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.height(6.dp))
        Box(
            modifier = Modifier.width(4.dp).height(70.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.25f)),
            contentAlignment = Alignment.BottomCenter
        ) {
            Box(Modifier.width(4.dp).fillMaxHeight(level.coerceIn(0f, 1f)).background(AccentCyan))
        }
        Spacer(Modifier.height(6.dp))
        Text("${(level * 100).toInt()}%", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

private fun mvCurrentBrightness(activity: android.app.Activity?, context: Context): Float {
    val w = activity?.window?.attributes?.screenBrightness ?: -1f
    if (w >= 0f) return w
    return try {
        android.provider.Settings.System.getInt(context.contentResolver, android.provider.Settings.System.SCREEN_BRIGHTNESS) / 255f
    } catch (_: Exception) {
        0.5f
    }
}

private fun Context.mvFindActivity(): android.app.Activity? {
    var ctx: Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is android.app.Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}
