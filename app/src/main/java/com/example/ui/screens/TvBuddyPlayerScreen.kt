package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.FormatListBulleted
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.BrightnessMedium
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.example.data.local.entity.ChannelEntity
import com.example.data.local.entity.ProgramEntity
import com.example.data.model.SpoofProfile
import com.example.player.AspectMode
import com.example.player.AudioBoostProfile
import com.example.player.StreamInfo
import com.example.player.TrackInfo
import com.example.player.VideoQuality
import com.example.ui.TvGroupSection
import com.example.ui.components.Badge
import com.example.ui.components.MobileChannelPanel
import com.example.ui.components.MobileGroupSelection
import com.example.ui.components.MobilePanelPage
import com.example.ui.components.VideoPlayerSurface
import com.example.ui.components.fmtClock
import com.example.ui.components.fmtFps
import com.example.ui.theme.AccentCyan
import com.example.ui.theme.DarkSurfaceElevated
import com.example.ui.theme.StatusDrm
import com.example.ui.theme.StatusLive
import com.example.ui.theme.StatusWarning
import kotlinx.coroutines.delay

/**
 * Custom Composable player screen integrating Media3 PlayerView to handle
 * adaptive HLS (.m3u8), DASH (.mpd), RTSP, and progressive video streams.
 * Includes Picture-in-Picture (PiP), Quality selector, and EPG integration.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@kotlin.OptIn(ExperimentalFoundationApi::class)
@Composable
fun TvBuddyPlayerScreen(
    player: ExoPlayer,
    channel: ChannelEntity?,
    isPlaying: Boolean,
    isBuffering: Boolean,
    errorMessage: String?,
    aspectMode: AspectMode,
    streamInfo: StreamInfo,
    spoofProfile: SpoofProfile,
    videoQuality: VideoQuality,
    currentProgram: ProgramEntity?,
    isInPipMode: Boolean = false,
    isFullscreen: Boolean = false,
    onEnterPip: () -> Unit = {},
    onToggleFullscreen: () -> Unit = {},
    onTogglePlayPause: () -> Unit,
    onNextChannel: () -> Unit,
    onPreviousChannel: () -> Unit,
    onToggleFavorite: (ChannelEntity) -> Unit,
    onCycleAspect: () -> Unit,
    onOpenQualityPicker: () -> Unit,
    onOpenAudioTracks: () -> Unit,
    onOpenSubtitles: () -> Unit,
    onRetry: () -> Unit,
    onClosePlayer: () -> Unit,
    onOpenSpoofSettings: () -> Unit = {},
    audioBoostProfile: AudioBoostProfile = AudioBoostProfile.OFF,
    onCycleAudioBoost: () -> Unit = {},
    audioTracks: List<TrackInfo> = emptyList(),
    onOpenSettings: () -> Unit = {},
    // channel list / groups panel (fullscreen only)
    channelsList: List<ChannelEntity> = emptyList(),
    channelNumbering: Map<Long, Int> = emptyMap(),
    allPrograms: List<ProgramEntity> = emptyList(),
    groupSections: List<TvGroupSection> = emptyList(),
    groupSelection: MobileGroupSelection = MobileGroupSelection(null, "All"),
    onSelectChannel: (ChannelEntity) -> Unit = {},
    onSelectGroup: (Long?, String) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier
) {
    // null = closed; CHANNELS -> (back) GROUPS -> (back) closed
    var panel by remember { mutableStateOf<MobilePanelPage?>(null) }
    LaunchedEffect(isFullscreen, isInPipMode) { if (!isFullscreen || isInPipMode) panel = null }
    val isLive = channel == null || channel.contentType == com.example.data.model.ContentType.TV
    var showControls by remember { mutableStateOf(true) }

    // Intercept hardware/gesture Back to close player cleanly
    BackHandler(enabled = !isInPipMode) {
        if (isFullscreen) {
            onToggleFullscreen()
        } else {
            onClosePlayer()
        }
    }

    // Auto-hide controls after 4 seconds of inactivity
    LaunchedEffect(showControls) {
        if (showControls && !isInPipMode) {
            delay(4000)
            showControls = false
        }
    }

    // ---- Swipe gestures: right half = volume, left half = brightness ----
    val context = LocalContext.current
    val activity = remember(context) { context.findHostActivity() }
    val audioManager = remember(context) { context.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager }
    var gestureKind by remember { mutableStateOf<SwipeKind?>(null) }
    var gestureLevel by remember { mutableFloatStateOf(0f) }
    var gestureTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(gestureTick) {
        if (gestureKind != null) {
            delay(900)
            gestureKind = null
        }
    }
    // hand brightness control back to the system when the player goes away
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
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                if (panel != null) {
                    panel = null
                } else if (!isInPipMode) {
                    showControls = !showControls
                }
            }
            .pointerInput(isInPipMode, panel != null) {
                if (isInPipMode || panel != null) return@pointerInput
                var kind = SwipeKind.VOLUME
                var level = 0f
                detectVerticalDragGestures(
                    onDragStart = { offset ->
                        kind = if (offset.x > size.width / 2f) SwipeKind.VOLUME else SwipeKind.BRIGHTNESS
                        level = when (kind) {
                            SwipeKind.VOLUME -> {
                                val max = audioManager.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                                audioManager.getStreamVolume(android.media.AudioManager.STREAM_MUSIC).toFloat() / max
                            }
                            SwipeKind.BRIGHTNESS -> currentBrightness(activity, context)
                        }
                        gestureKind = kind
                        gestureLevel = level
                    },
                    onDragEnd = { gestureTick++ },
                    onDragCancel = { gestureTick++ },
                    onVerticalDrag = { change, dragAmount ->
                        change.consume()
                        // a full-height swipe moves the level ~100%
                        level = (level - dragAmount / size.height * 1.2f).coerceIn(0f, 1f)
                        when (kind) {
                            SwipeKind.VOLUME -> {
                                val max = audioManager.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                                audioManager.setStreamVolume(
                                    android.media.AudioManager.STREAM_MUSIC,
                                    Math.round(level * max),
                                    0
                                )
                            }
                            SwipeKind.BRIGHTNESS -> activity?.window?.let { w ->
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
        // Core Media3 PlayerView Surface
        VideoPlayerSurface(
            player = player,
            aspectMode = aspectMode,
            modifier = Modifier.fillMaxSize()
        )

        // If in Picture-in-Picture mode, hide all overlay controls
        if (isInPipMode) {
            return@Box
        }

        // Buffering Indicator
        if (isBuffering) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color.Black.copy(alpha = 0.75f))
                    .padding(20.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(
                        color = AccentCyan,
                        strokeWidth = 3.dp,
                        modifier = Modifier.size(40.dp)
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "Buffering Stream...",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        // Error Banner
        if (errorMessage != null && !isBuffering) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.88f))
                    .padding(20.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth(0.85f)
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        tint = StatusLive,
                        modifier = Modifier.size(40.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Playback Failed",
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = errorMessage,
                        color = Color(0xFFFCA5A5),
                        fontSize = 12.sp,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = AccentCyan,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable(onClick = onRetry)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = null, tint = Color.Black, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Retry", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                        }

                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = Color(0xFF334155),
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable(onClick = onClosePlayer)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Close", color = Color.White, fontWeight = FontWeight.Medium, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }

        // Channel list / groups panel (left side, fullscreen only)
        if (panel != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxHeight()
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(androidx.compose.foundation.layout.WindowInsetsSides.Start))
            ) {
                MobileChannelPanel(
                    page = panel ?: MobilePanelPage.CHANNELS,
                    onPageChange = { panel = it },
                    onClose = { panel = null },
                    channels = channelsList,
                    numbering = channelNumbering,
                    playingId = channel?.id,
                    programs = allPrograms,
                    sections = groupSections,
                    selection = groupSelection,
                    onSelectChannel = {
                        onSelectChannel(it)
                        panel = null
                    },
                    onSelectGroup = onSelectGroup
                )
            }
        }

        // Volume / brightness indicator
        AnimatedVisibility(
            visible = gestureKind != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(if (gestureKind == SwipeKind.VOLUME) Alignment.CenterEnd else Alignment.CenterStart)
        ) {
            SwipeLevelIndicator(kind = gestureKind ?: SwipeKind.VOLUME, level = gestureLevel)
        }

        // Overlay Controls
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
                // Top bar: back + channel / programme (long names scroll)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        .background(
                            Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.85f), Color.Transparent))
                        )
                        .padding(start = 8.dp, end = 12.dp, top = 6.dp, bottom = 10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = onClosePlayer,
                            modifier = Modifier
                                .size(34.dp)
                                .testTag("player_back_btn")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                tint = Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(6.dp))

                        if (channel != null) {
                            Column(modifier = Modifier.weight(1f)) {
                                // shows at most 15 letters; a longer name scrolls inside that width
                                val titleStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                val measurer = androidx.compose.ui.text.rememberTextMeasurer()
                                val density = androidx.compose.ui.platform.LocalDensity.current
                                val titleWidth = remember(channel.name) {
                                    if (channel.name.length > 15) {
                                        with(density) { measurer.measure(channel.name.take(15), titleStyle).size.width.toDp() }
                                    } else null
                                }
                                Text(
                                    text = channel.name,
                                    color = Color.White,
                                    style = titleStyle,
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Clip,
                                    modifier = (if (titleWidth != null) Modifier.width(titleWidth) else Modifier)
                                        .basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 800)
                                )
                                val sub = if (currentProgram != null)
                                    "▶ ${fmtClock(currentProgram.startTimeMs)} - ${fmtClock(currentProgram.endTimeMs)}  ${currentProgram.title}"
                                else channel.groupTitle
                                if (sub.isNotBlank()) {
                                    Text(
                                        text = sub,
                                        color = if (currentProgram != null) AccentCyan else Color(0xFF94A3B8),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        softWrap = false,
                                        overflow = TextOverflow.Clip,
                                        modifier = Modifier.fillMaxWidth().basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 1200)
                                    )
                                }
                            }
                        }
                    }
                }

                // Centre: nothing for live TV; movies / shows keep a play-pause button
                if (!isLive) {
                    IconButton(
                        onClick = onTogglePlayPause,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(52.dp)
                            .clip(CircleShape)
                            .background(AccentCyan)
                            .testTag("player_play_pause_btn")
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (isPlaying) "Pause" else "Play",
                            tint = Color.Black,
                            modifier = Modifier.size(30.dp)
                        )
                    }
                }

                // Bottom bar: the same action buttons as the TV long-OK bar, fullscreen pinned right
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .background(
                            Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.9f)))
                        )
                        .padding(start = 8.dp, end = 8.dp, top = 18.dp, bottom = 6.dp)
                ) {
                    // quality / fps / audio layout / DRM, right side just above the progress bar
                    run {
                        val res = when {
                            streamInfo.height >= 2160 -> "4K"
                            streamInfo.height >= 1080 -> "FHD"
                            streamInfo.height >= 720 -> "HD"
                            streamInfo.height > 0 -> "SD"
                            else -> null
                        }
                        val layout = when (audioTracks.firstOrNull { it.isSelected }?.channelCount ?: 0) {
                            0 -> null
                            1 -> "Mono"
                            2 -> "Stereo"
                            6 -> "5.1"
                            8 -> "7.1"
                            else -> "${audioTracks.first { it.isSelected }.channelCount} ch"
                        }
                        val tags = listOfNotNull(
                            res,
                            streamInfo.frameRate.takeIf { it > 0f }?.let { fmtFps(it) },
                            layout,
                            if (streamInfo.isDrmProtected) "DRM" else null
                        )
                        if (tags.isNotEmpty()) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
                                horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                tags.forEach { Badge(it) }
                            }
                        }
                    }

                    if (currentProgram != null && currentProgram.isLive) {
                        LinearProgressIndicator(
                            progress = { currentProgram.progress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 4.dp, vertical = 4.dp)
                                .height(3.dp)
                                .clip(RoundedCornerShape(1.5.dp)),
                            color = AccentCyan,
                            trackColor = Color(0xFF1E293B)
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Top
                    ) {
                        val audioSelected = audioTracks.firstOrNull { it.isSelected }
                        val audioLabel = when {
                            audioTracks.isEmpty() -> "Audio"
                            audioSelected == null -> "Audio off"
                            else -> audioSelected.language.takeIf { it.isNotBlank() && it != "und" }
                                ?.let { code ->
                                    java.util.Locale(code).getDisplayLanguage(java.util.Locale.ENGLISH)
                                        .takeIf { it.isNotBlank() && it != code } ?: code
                                } ?: "Audio"
                        }
                        val qualityLabel = if (streamInfo.height > 0) "${streamInfo.height}p"
                        else videoQuality.title.substringBefore(" ")
                        val fav = channel?.isFavorite == true

                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(0.dp)
                        ) {
                            if (isFullscreen && isLive) {
                                MobileActionButton("Channels", Icons.Default.FormatListBulleted, tag = "channels_btn") {
                                    showControls = false
                                    panel = MobilePanelPage.CHANNELS
                                }
                            }
                            MobileActionButton("Boost: ${audioBoostProfile.shortTitle}", Icons.Default.GraphicEq, onClick = onCycleAudioBoost)
                            MobileActionButton(qualityLabel, Icons.Default.HighQuality, tag = "quality_btn", onClick = onOpenQualityPicker)
                            MobileActionButton(audioLabel, Icons.AutoMirrored.Filled.VolumeUp, onClick = onOpenAudioTracks)
                            MobileActionButton(
                                label = if (fav) "Favorited" else "Favorite",
                                icon = if (fav) Icons.Filled.Star else Icons.Outlined.StarOutline,
                                highlight = fav,
                                onClick = { channel?.let(onToggleFavorite) }
                            )
                            MobileActionButton(aspectMode.title, Icons.Default.AspectRatio, onClick = onCycleAspect)
                            MobileActionButton("PiP", Icons.Default.PictureInPictureAlt, tag = "pip_btn", onClick = onEnterPip)
                        }

                        // Fullscreen toggle: bottom-right corner (auto-rotates with the phone)
                        IconButton(
                            onClick = onToggleFullscreen,
                            modifier = Modifier.size(40.dp).testTag("fullscreen_toggle_btn")
                        ) {
                            Icon(
                                imageVector = if (isFullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
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

private enum class SwipeKind { VOLUME, BRIGHTNESS }

/** One round action button with its label underneath (same look as the TV long-OK action bar, phone sized). */
@kotlin.OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MobileActionButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier,
    highlight: Boolean = false,
    tag: String? = null,
    onClick: () -> Unit
) {
    Column(
        modifier = modifier
            .width(50.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .then(if (tag != null) Modifier.testTag(tag) else Modifier)
            .padding(vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                icon,
                contentDescription = label,
                tint = if (highlight) StatusWarning else Color.White,
                modifier = Modifier.size(19.dp)
            )
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
private fun SwipeLevelIndicator(kind: SwipeKind, level: Float) {
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
                kind == SwipeKind.BRIGHTNESS -> Icons.Default.BrightnessMedium
                level <= 0f -> Icons.Default.VolumeOff
                else -> Icons.AutoMirrored.Filled.VolumeUp
            },
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.height(6.dp))
        Box(
            modifier = Modifier
                .width(4.dp)
                .height(70.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Color.White.copy(alpha = 0.25f)),
            contentAlignment = Alignment.BottomCenter
        ) {
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .fillMaxHeight(level.coerceIn(0f, 1f))
                    .background(AccentCyan)
            )
        }
        Spacer(Modifier.height(6.dp))
        Text("${(level * 100).toInt()}%", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

private fun currentBrightness(activity: android.app.Activity?, context: android.content.Context): Float {
    val w = activity?.window?.attributes?.screenBrightness ?: -1f
    if (w >= 0f) return w
    return try {
        android.provider.Settings.System.getInt(context.contentResolver, android.provider.Settings.System.SCREEN_BRIGHTNESS) / 255f
    } catch (_: Exception) {
        0.5f
    }
}

private fun android.content.Context.findHostActivity(): android.app.Activity? {
    var ctx: android.content.Context? = this
    while (ctx is android.content.ContextWrapper) {
        if (ctx is android.app.Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}
