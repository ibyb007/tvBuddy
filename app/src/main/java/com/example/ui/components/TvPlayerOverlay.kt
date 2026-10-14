package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.FormatListBulleted
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.local.entity.ChannelEntity
import com.example.data.model.SpoofProfile
import com.example.player.AspectMode
import com.example.player.StreamInfo
import com.example.ui.theme.AccentCyan
import com.example.ui.theme.DarkBackground
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.DarkSurfaceElevated
import com.example.ui.theme.StatusDrm
import com.example.ui.theme.StatusLive
import com.example.ui.theme.StatusWarning
import kotlinx.coroutines.delay

@Composable
fun TvPlayerOverlay(
    channel: ChannelEntity?,
    isPlaying: Boolean,
    isBuffering: Boolean,
    errorMessage: String?,
    aspectMode: AspectMode,
    streamInfo: StreamInfo,
    spoofProfile: SpoofProfile,
    videoQuality: com.example.player.VideoQuality = com.example.player.VideoQuality.AUTO,
    currentProgram: com.example.data.local.entity.ProgramEntity? = null,
    channelsList: List<ChannelEntity>,
    onTogglePlayPause: () -> Unit,
    onNextChannel: () -> Unit,
    onPreviousChannel: () -> Unit,
    onSelectChannel: (ChannelEntity) -> Unit,
    onToggleFavorite: (ChannelEntity) -> Unit,
    onCycleAspect: () -> Unit,
    onOpenQualityPicker: () -> Unit = {},
    onOpenEpgGuide: () -> Unit = {},
    onOpenAudioTracks: () -> Unit,
    onOpenSubtitles: () -> Unit,
    onClosePlayer: () -> Unit,
    onOpenSpoofSettings: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var showControls by remember { mutableStateOf(true) }
    var showQuickChannelList by remember { mutableStateOf(false) }

    // Auto-hide OSD controls after 5 seconds of inactivity
    LaunchedEffect(showControls) {
        if (showControls && !showQuickChannelList) {
            delay(5000)
            showControls = false
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onKeyEvent { keyEvent ->
                when (keyEvent.key) {
                    Key.DirectionUp -> {
                        onNextChannel()
                        showControls = true
                        true
                    }
                    Key.DirectionDown -> {
                        onPreviousChannel()
                        showControls = true
                        true
                    }
                    Key.DirectionLeft -> {
                        showQuickChannelList = !showQuickChannelList
                        showControls = true
                        true
                    }
                    Key.DirectionCenter, Key.Enter -> {
                        showControls = !showControls
                        true
                    }
                    Key.Back -> {
                        if (showQuickChannelList) {
                            showQuickChannelList = false
                            true
                        } else {
                            onClosePlayer()
                            true
                        }
                    }
                    else -> false
                }
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                showControls = !showControls
            }
    ) {
        // Buffering Indicator
        if (isBuffering) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.Black.copy(alpha = 0.75f))
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(
                        color = AccentCyan,
                        strokeWidth = 3.dp,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Buffering Stream...",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        // Error Banner
        if (errorMessage != null && !isBuffering) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFF1E1014).copy(alpha = 0.95f))
                    .border(1.dp, StatusLive, RoundedCornerShape(16.dp))
                    .padding(24.dp)
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth(0.7f)
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        tint = StatusLive,
                        modifier = Modifier.size(44.dp)
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "Playback Failed",
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = errorMessage,
                        color = Color(0xFFFCA5A5),
                        fontSize = 13.sp,
                        maxLines = 3
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = AccentCyan,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    if (channel != null) onSelectChannel(channel)
                                }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = null, tint = Color.Black, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Retry", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 13.sp)
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
                                Text("Close", color = Color.White, fontWeight = FontWeight.Medium, fontSize = 13.sp)
                            }
                        }
                    }
                }
            }
        }

        // OSD Controls
        AnimatedVisibility(
            visible = showControls,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                // Top Gradient Bar
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(Color.Black.copy(alpha = 0.9f), Color.Transparent)
                            )
                        )
                        .padding(horizontal = 24.dp, vertical = 18.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(
                                onClick = onClosePlayer,
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(Color.White.copy(alpha = 0.15f))
                            ) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                            }

                            Spacer(modifier = Modifier.width(14.dp))

                            if (channel != null) {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(AccentCyan)
                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Text(
                                        text = "${channel.channelNumber}",
                                        color = Color.Black,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp
                                    )
                                }

                                Spacer(modifier = Modifier.width(10.dp))

                                Column {
                                    Text(
                                        text = channel.name,
                                        color = Color.White,
                                        fontSize = 18.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    if (currentProgram != null) {
                                        Text(
                                            text = "▶ ${currentProgram.title}",
                                            color = AccentCyan,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Medium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    } else {
                                        Text(
                                            text = channel.groupTitle,
                                            color = Color(0xFF94A3B8),
                                            fontSize = 12.sp
                                        )
                                    }
                                }
                            }
                        }

                        // Badges: Spoof, DRM, Aspect
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // Aspect Ratio Badge
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(Color.White.copy(alpha = 0.15f))
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = aspectMode.title,
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
                }

                // Bottom Gradient Control Bar
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.95f))
                            )
                        )
                        .padding(horizontal = 28.dp, vertical = 20.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        // Quick channels drawer button
                        TvActionButton(
                            icon = Icons.Default.FormatListBulleted,
                            label = "Channels",
                            isSelected = showQuickChannelList,
                            onClick = { showQuickChannelList = !showQuickChannelList }
                        )

                        // Center Playback Buttons
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            TvIconButton(
                                icon = Icons.Default.SkipPrevious,
                                contentDescription = "Previous Channel",
                                onClick = onPreviousChannel
                            )

                            TvIconButton(
                                icon = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = if (isPlaying) "Pause" else "Play",
                                isPrimary = true,
                                onClick = onTogglePlayPause
                            )

                            TvIconButton(
                                icon = Icons.Default.SkipNext,
                                contentDescription = "Next Channel",
                                onClick = onNextChannel
                            )
                        }

                        // Right Options: Favorite, Audio, Subtitles, Aspect
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (channel != null) {
                                TvActionButton(
                                    icon = if (channel.isFavorite) Icons.Filled.Star else Icons.Outlined.StarOutline,
                                    label = if (channel.isFavorite) "Favorite" else "Fav",
                                    tint = if (channel.isFavorite) StatusWarning else Color.White,
                                    onClick = { onToggleFavorite(channel) }
                                )
                            }

                            TvActionButton(
                                icon = Icons.AutoMirrored.Filled.VolumeUp,
                                label = "Audio",
                                onClick = onOpenAudioTracks
                            )

                            TvActionButton(
                                icon = Icons.Default.ClosedCaption,
                                label = "Subtitles",
                                onClick = onOpenSubtitles
                            )

                            TvActionButton(
                                icon = Icons.Default.HighQuality,
                                label = videoQuality.title.substringBefore(" "),
                                onClick = onOpenQualityPicker
                            )

                            TvActionButton(
                                icon = Icons.Default.Schedule,
                                label = "EPG",
                                onClick = onOpenEpgGuide
                            )

                            TvActionButton(
                                icon = Icons.Default.AspectRatio,
                                label = aspectMode.title,
                                onClick = onCycleAspect
                            )
                        }
                    }
                }
            }
        }

        // TiviMate-style Quick Channel List Side Drawer
        AnimatedVisibility(
            visible = showQuickChannelList,
            enter = slideInHorizontally(initialOffsetX = { -it }),
            exit = slideOutHorizontally(targetOffsetX = { -it }),
            modifier = Modifier.align(Alignment.CenterStart)
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(360.dp),
                color = DarkBackground.copy(alpha = 0.94f)
            ) {
                Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Channel Guide",
                            style = MaterialTheme.typography.titleMedium,
                            color = AccentCyan,
                            fontWeight = FontWeight.Bold
                        )

                        Text(
                            text = "${channelsList.size} channels",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF94A3B8)
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(channelsList, key = { it.id }) { item ->
                            ChannelListItem(
                                channel = item,
                                isSelected = item.id == channel?.id,
                                onSelect = {
                                    onSelectChannel(item)
                                },
                                onToggleFavorite = {
                                    onToggleFavorite(item)
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun TvIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    isPrimary: Boolean = false,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }

    Surface(
        shape = CircleShape,
        color = when {
            isPrimary -> AccentCyan
            isFocused -> DarkSurfaceElevated
            else -> Color.White.copy(alpha = 0.15f)
        },
        modifier = Modifier
            .size(if (isPrimary) 52.dp else 44.dp)
            .clip(CircleShape)
            .border(
                width = if (isFocused) 2.dp else 0.dp,
                color = if (isFocused) AccentCyan else Color.Transparent,
                shape = CircleShape
            )
            .onFocusChanged { isFocused = it.isFocused }
            .focusable()
            .clickable(onClick = onClick)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = if (isPrimary) Color.Black else Color.White,
                modifier = Modifier.size(if (isPrimary) 28.dp else 22.dp)
            )
        }
    }
}

@Composable
fun TvActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    isSelected: Boolean = false,
    tint: Color = Color.White,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = when {
            isSelected -> AccentCyan.copy(alpha = 0.2f)
            isFocused -> DarkSurfaceElevated
            else -> Color.White.copy(alpha = 0.1f)
        },
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .border(
                width = if (isFocused) 2.dp else 1.dp,
                color = when {
                    isFocused -> AccentCyan
                    isSelected -> AccentCyan
                    else -> Color.Transparent
                },
                shape = RoundedCornerShape(8.dp)
            )
            .onFocusChanged { isFocused = it.isFocused }
            .focusable()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (isSelected) AccentCyan else tint,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = label,
                color = if (isSelected) AccentCyan else tint,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}
