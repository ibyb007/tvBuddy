package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.filled.HighQuality
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
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
import com.example.player.StreamInfo
import com.example.player.VideoQuality
import com.example.ui.components.VideoPlayerSurface
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
@OptIn(UnstableApi::class)
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
    modifier: Modifier = Modifier
) {
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

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                if (!isInPipMode) {
                    showControls = !showControls
                }
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
                // Top Action Bar
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        .background(
                            Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.85f), Color.Transparent))
                        )
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        // Back Button & Channel Info
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f, fill = false)
                        ) {
                            IconButton(
                                onClick = onClosePlayer,
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(CircleShape)
                                    .background(Color.White.copy(alpha = 0.15f))
                                    .testTag("player_back_btn")
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "Back",
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                            }

                            Spacer(modifier = Modifier.width(10.dp))

                            if (channel != null) {
                                Column {
                                    Text(
                                        text = channel.name,
                                        color = Color.White,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    if (currentProgram != null) {
                                        Text(
                                            text = "▶ ${currentProgram.title}",
                                            color = AccentCyan,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Medium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    } else {
                                        Text(
                                            text = channel.groupTitle,
                                            color = Color(0xFF94A3B8),
                                            fontSize = 11.sp
                                        )
                                    }
                                }
                            }
                        }

                        // Right Badges & Controls
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            // Fullscreen toggle (auto-rotates with the phone while fullscreen)
                            IconButton(
                                onClick = onToggleFullscreen,
                                modifier = Modifier
                                    .size(36.dp)
                                    .testTag("fullscreen_toggle_btn")
                            ) {
                                Icon(
                                    imageVector = if (isFullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                                    contentDescription = if (isFullscreen) "Exit fullscreen" else "Fullscreen",
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }

                // Center Play / Zapping Controls
                Row(
                    modifier = Modifier.align(Alignment.Center),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    IconButton(
                        onClick = onPreviousChannel,
                        modifier = Modifier
                            .size(46.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.55f))
                            .border(1.dp, Color(0xFF334155), CircleShape)
                            .testTag("player_prev_btn")
                    ) {
                        Icon(Icons.Default.SkipPrevious, contentDescription = "Previous Channel", tint = Color.White, modifier = Modifier.size(24.dp))
                    }

                    IconButton(
                        onClick = onTogglePlayPause,
                        modifier = Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(AccentCyan)
                            .testTag("player_play_pause_btn")
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (isPlaying) "Pause" else "Play",
                            tint = Color.Black,
                            modifier = Modifier.size(32.dp)
                        )
                    }

                    IconButton(
                        onClick = onNextChannel,
                        modifier = Modifier
                            .size(46.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.55f))
                            .border(1.dp, Color(0xFF334155), CircleShape)
                            .testTag("player_next_btn")
                    ) {
                        Icon(Icons.Default.SkipNext, contentDescription = "Next Channel", tint = Color.White, modifier = Modifier.size(24.dp))
                    }
                }

                // Bottom Bar Controls
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .background(
                            Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.9f)))
                        )
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Column {
                        if (currentProgram != null && currentProgram.isLive) {
                            LinearProgressIndicator(
                                progress = { currentProgram.progress },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(3.dp)
                                    .clip(RoundedCornerShape(1.5.dp))
                                    .padding(bottom = 6.dp),
                                color = AccentCyan,
                                trackColor = Color(0xFF1E293B)
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            if (channel != null) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    IconButton(
                                        onClick = { onToggleFavorite(channel) },
                                        modifier = Modifier.size(36.dp)
                                    ) {
                                        Icon(
                                            imageVector = if (channel.isFavorite) Icons.Filled.Star else Icons.Outlined.StarOutline,
                                            contentDescription = "Favorite",
                                            tint = if (channel.isFavorite) StatusWarning else Color.White,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                    Text(
                                        text = channel.groupTitle,
                                        color = Color(0xFF94A3B8),
                                        fontSize = 11.sp
                                    )
                                }
                            }

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                IconButton(onClick = onOpenAudioTracks, modifier = Modifier.size(36.dp)) {
                                    Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = "Audio Tracks", tint = Color.White, modifier = Modifier.size(20.dp))
                                }
                                IconButton(onClick = onOpenSubtitles, modifier = Modifier.size(36.dp)) {
                                    Icon(Icons.Default.ClosedCaption, contentDescription = "Subtitles", tint = Color.White, modifier = Modifier.size(20.dp))
                                }

                                // Picture-in-Picture
                                IconButton(
                                    onClick = onEnterPip,
                                    modifier = Modifier.size(36.dp).testTag("pip_btn")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.PictureInPictureAlt,
                                        contentDescription = "Picture in Picture",
                                        tint = Color.White,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }

                                // Quality picker (no box / border)
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .clickable(onClick = onOpenQualityPicker)
                                        .padding(horizontal = 6.dp, vertical = 8.dp)
                                        .testTag("quality_btn")
                                ) {
                                    Icon(Icons.Default.HighQuality, contentDescription = "Quality", tint = Color.White, modifier = Modifier.size(20.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = videoQuality.title.substringBefore(" "),
                                        color = Color.White,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }

                                // Aspect ratio / resize
                                IconButton(onClick = onCycleAspect, modifier = Modifier.size(36.dp)) {
                                    Icon(Icons.Default.AspectRatio, contentDescription = "Aspect Ratio", tint = Color.White, modifier = Modifier.size(20.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
