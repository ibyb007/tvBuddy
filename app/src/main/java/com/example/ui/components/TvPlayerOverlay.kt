package com.example.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.FormatListBulleted
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.data.local.entity.ChannelEntity
import com.example.data.local.entity.ProgramEntity
import com.example.data.model.SpoofProfile
import com.example.player.AspectMode
import com.example.player.StreamInfo
import com.example.ui.theme.StatusLive
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class TvPanel { NONE, INFO, MENU, CHANNELS }

private class TvMenuItem(
    val label: String,
    val icon: ImageVector,
    val action: () -> Unit
)

private val TvPanelBg = Color(0xE6101418)
private val TvSelected = Color(0xFFF1F3F4)

/**
 * TiviMate-style Android TV player UI.
 *
 * Remote control while watching (no panel open):
 *   OK (click)      -> info banner + channel strip
 *   OK (long press) -> action bar (channels, guide, quality, audio, subtitles, favourite ...)
 *   LEFT            -> channel list
 *   UP / DOWN       -> previous / next channel
 *   RIGHT           -> last played channel
 * In the channel list: UP/DOWN navigate, OK plays, LEFT/RIGHT/BACK close.
 */
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
    currentProgram: ProgramEntity? = null,
    channelsList: List<ChannelEntity>,
    allPrograms: List<ProgramEntity> = emptyList(),
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
    onOpenPlaylists: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var panel by remember { mutableStateOf(TvPanel.NONE) }
    var tick by remember { mutableLongStateOf(0L) }          // bumps on every key press (auto-hide timer)
    var stripIndex by remember { mutableIntStateOf(0) }       // 0 = TV guide tile, 1.. = channels
    var menuIndex by remember { mutableIntStateOf(0) }
    var listIndex by remember { mutableIntStateOf(0) }
    var okLongHandled by remember { mutableStateOf(false) }

    // Remember the previously played channel for the RIGHT key
    var trackedChannel by remember { mutableStateOf<ChannelEntity?>(null) }
    var previousChannel by remember { mutableStateOf<ChannelEntity?>(null) }
    LaunchedEffect(channel?.id) {
        if (channel != null && trackedChannel?.id != channel.id) {
            previousChannel = trackedChannel
            trackedChannel = channel
        }
    }

    fun programFor(ch: ChannelEntity): ProgramEntity? =
        allPrograms.firstOrNull {
            it.epgChannelId.equals(ch.streamId, ignoreCase = true) ||
                it.epgChannelId.equals(ch.name, ignoreCase = true)
        }

    fun currentIndex(): Int = channelsList.indexOfFirst { it.id == channel?.id }.coerceAtLeast(0)

    val focusRequester = remember { FocusRequester() }
    var hasFocus by remember { mutableStateOf(false) }
    LaunchedEffect(panel, channel?.id) {
        try { focusRequester.requestFocus() } catch (_: Exception) {}
    }
    // Take focus back after dialogs (audio, quality, ...) close
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            if (!hasFocus) {
                try { focusRequester.requestFocus() } catch (_: Exception) {}
            }
        }
    }

    // Auto-hide banner / action bar
    LaunchedEffect(panel, tick) {
        when (panel) {
            TvPanel.INFO -> { delay(6000); panel = TvPanel.NONE }
            TvPanel.MENU -> { delay(10000); panel = TvPanel.NONE }
            else -> {}
        }
    }

    BackHandler(enabled = panel != TvPanel.NONE) { panel = TvPanel.NONE }

    fun openInfo() {
        stripIndex = currentIndex() + 1
        panel = TvPanel.INFO
    }

    fun openChannelList() {
        listIndex = currentIndex()
        panel = TvPanel.CHANNELS
    }

    fun menuItems(): List<TvMenuItem> {
        val qualityLabel = if (streamInfo.width > 0 && streamInfo.height > 0) {
            val mbps = streamInfo.bitrate / 1_000_000f
            if (mbps > 0f) "${streamInfo.width} × ${streamInfo.height}, ${"%.2f".format(mbps)} Mbps"
            else "${streamInfo.width} × ${streamInfo.height}"
        } else videoQuality.title.substringBefore(" ")
        return listOf(
            TvMenuItem("Channels list", Icons.Default.FormatListBulleted) { openChannelList() },
            TvMenuItem("TV guide", Icons.Default.Schedule) { panel = TvPanel.NONE; onOpenEpgGuide() },
            TvMenuItem(qualityLabel, Icons.Default.HighQuality) { panel = TvPanel.NONE; onOpenQualityPicker() },
            TvMenuItem("Audio", Icons.AutoMirrored.Filled.VolumeUp) { panel = TvPanel.NONE; onOpenAudioTracks() },
            TvMenuItem("Subtitles", Icons.Default.ClosedCaption) { panel = TvPanel.NONE; onOpenSubtitles() },
            TvMenuItem(
                if (channel?.isFavorite == true) "Remove favorite" else "Add to Favorites",
                if (channel?.isFavorite == true) Icons.Default.Star else Icons.Outlined.StarOutline
            ) { channel?.let(onToggleFavorite) },
            TvMenuItem(aspectMode.title, Icons.Default.AspectRatio) { onCycleAspect() },
            TvMenuItem("Spoofing", Icons.Default.Settings) { panel = TvPanel.NONE; onOpenSpoofSettings() },
            TvMenuItem("Playlists", Icons.Default.FormatListBulleted) { panel = TvPanel.NONE; onOpenPlaylists() }
        )
    }

    fun onShortOk() {
        when (panel) {
            TvPanel.NONE -> {
                if (errorMessage != null && channel != null) onSelectChannel(channel) else openInfo()
            }
            TvPanel.INFO -> {
                if (stripIndex == 0) { panel = TvPanel.NONE; onOpenEpgGuide() }
                else channelsList.getOrNull(stripIndex - 1)?.let { onSelectChannel(it); panel = TvPanel.NONE }
            }
            TvPanel.MENU -> menuItems().getOrNull(menuIndex)?.action?.invoke()
            TvPanel.CHANNELS -> {
                channelsList.getOrNull(listIndex)?.let { onSelectChannel(it) }
                panel = TvPanel.NONE
            }
        }
    }

    fun onLongOk() {
        if (panel == TvPanel.NONE || panel == TvPanel.INFO) {
            menuIndex = 0
            panel = TvPanel.MENU
        }
    }

    fun zap(delta: Int) {
        if (channelsList.isEmpty()) return
        val next = (currentIndex() + delta + channelsList.size) % channelsList.size
        onSelectChannel(channelsList[next])
        stripIndex = next + 1
    }

    fun onDirection(key: Key, isRepeat: Boolean): Boolean {
        when (panel) {
            TvPanel.NONE -> {
                if (isRepeat) return true
                when (key) {
                    Key.DirectionUp -> zap(-1)
                    Key.DirectionDown -> zap(+1)
                    Key.DirectionLeft -> openChannelList()
                    Key.DirectionRight -> previousChannel?.let(onSelectChannel)
                    else -> return false
                }
            }
            TvPanel.INFO -> when (key) {
                Key.DirectionLeft -> stripIndex = (stripIndex - 1).coerceAtLeast(0)
                Key.DirectionRight -> stripIndex = (stripIndex + 1).coerceAtMost(channelsList.size)
                Key.DirectionUp -> if (!isRepeat) zap(-1)
                Key.DirectionDown -> if (!isRepeat) zap(+1)
                else -> return false
            }
            TvPanel.MENU -> {
                val n = menuItems().size
                when (key) {
                    Key.DirectionLeft -> menuIndex = (menuIndex - 1).coerceAtLeast(0)
                    Key.DirectionRight -> menuIndex = (menuIndex + 1).coerceAtMost(n - 1)
                    Key.DirectionUp, Key.DirectionDown -> {}
                    else -> return false
                }
            }
            TvPanel.CHANNELS -> when (key) {
                Key.DirectionUp -> listIndex = (listIndex - 1).coerceAtLeast(0)
                Key.DirectionDown -> listIndex = (listIndex + 1).coerceAtMost((channelsList.size - 1).coerceAtLeast(0))
                Key.DirectionLeft, Key.DirectionRight -> if (!isRepeat) panel = TvPanel.NONE
                else -> return false
            }
        }
        return true
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .focusRequester(focusRequester)
            .onFocusChanged { hasFocus = it.isFocused }
            .focusable()
            .onPreviewKeyEvent { e ->
                tick++
                val k = e.key
                val isOk = k == Key.DirectionCenter || k == Key.Enter || k == Key.NumPadEnter
                when {
                    isOk -> {
                        when (e.type) {
                            KeyEventType.KeyDown -> {
                                if (e.nativeKeyEvent.repeatCount == 0) okLongHandled = false
                                else if (!okLongHandled) { okLongHandled = true; onLongOk() }
                                true
                            }
                            KeyEventType.KeyUp -> {
                                if (!okLongHandled) onShortOk()
                                okLongHandled = false
                                true
                            }
                            else -> true
                        }
                    }
                    e.type != KeyEventType.KeyDown -> false
                    k == Key.MediaPlayPause || k == Key.MediaPlay || k == Key.MediaPause -> {
                        onTogglePlayPause(); true
                    }
                    k == Key.ChannelUp -> { zap(-1); true }
                    k == Key.ChannelDown -> { zap(+1); true }
                    k == Key.DirectionUp || k == Key.DirectionDown ||
                        k == Key.DirectionLeft || k == Key.DirectionRight ->
                        onDirection(k, e.nativeKeyEvent.repeatCount > 0)
                    else -> false
                }
            }
    ) {
        // Buffering
        if (isBuffering) {
            CircularProgressIndicator(
                color = Color.White,
                strokeWidth = 3.dp,
                modifier = Modifier.align(Alignment.Center).size(48.dp)
            )
        }

        // Error
        if (errorMessage != null && !isBuffering && panel == TvPanel.NONE) {
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xE61A1215))
                    .padding(28.dp)
                    .fillMaxWidth(0.55f),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(Icons.Default.Warning, contentDescription = null, tint = StatusLive, modifier = Modifier.size(40.dp))
                Spacer(Modifier.height(8.dp))
                Text("Playback failed", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Text(errorMessage, color = Color(0xFFFCA5A5), fontSize = 14.sp, maxLines = 3)
                Spacer(Modifier.height(10.dp))
                Text("Press OK to retry", color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp)
            }
        }

        // Clock (top-right) + playlist label (top-left) while a panel other than the list is open
        AnimatedVisibility(
            visible = panel == TvPanel.INFO || panel == TvPanel.MENU,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            val now by produceNow()
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 20.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    channel?.groupTitle ?: "",
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    SimpleDateFormat("EEE, d MMM, h:mm a", Locale.getDefault()).format(Date(now)),
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        // ---------- INFO banner + channel strip ----------
        AnimatedVisibility(
            visible = panel == TvPanel.INFO,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            InfoBanner(
                channel = channel,
                program = currentProgram,
                streamInfo = streamInfo,
                channelsList = channelsList,
                stripIndex = stripIndex,
                programFor = ::programFor
            )
        }

        // ---------- Action bar (long press OK) ----------
        AnimatedVisibility(
            visible = panel == TvPanel.MENU,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            val items = menuItems()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.9f))))
                    .padding(horizontal = 32.dp)
                    .padding(top = 48.dp, bottom = 24.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.Top
            ) {
                items.forEachIndexed { i, item ->
                    val selected = i == menuIndex
                    Column(
                        modifier = Modifier.width(120.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .clip(RoundedCornerShape(28.dp))
                                .background(if (selected) TvSelected else Color.Transparent),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                item.icon,
                                contentDescription = item.label,
                                tint = if (selected) Color.Black else Color.White.copy(alpha = 0.7f),
                                modifier = Modifier.size(30.dp)
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            item.label,
                            color = if (selected) Color.White else Color.White.copy(alpha = 0.65f),
                            fontSize = 15.sp,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }

        // ---------- Channel list (LEFT) ----------
        AnimatedVisibility(
            visible = panel == TvPanel.CHANNELS,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            ChannelListPanel(
                channelsList = channelsList,
                selectedIndex = listIndex,
                playingId = channel?.id,
                programFor = ::programFor
            )
        }
    }
}

@Composable
private fun produceNow() = androidx.compose.runtime.produceState(System.currentTimeMillis()) {
    while (true) {
        value = System.currentTimeMillis()
        delay(15_000)
    }
}

private fun fmtTime(ms: Long): String =
    SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date(ms)).lowercase(Locale.getDefault())

@Composable
private fun InfoBanner(
    channel: ChannelEntity?,
    program: ProgramEntity?,
    streamInfo: StreamInfo,
    channelsList: List<ChannelEntity>,
    stripIndex: Int,
    programFor: (ChannelEntity) -> ProgramEntity?
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.92f))))
            .padding(horizontal = 40.dp)
            .padding(top = 60.dp, bottom = 16.dp)
    ) {
        if (channel != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(width = 150.dp, height = 84.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(0x33FFFFFF)),
                    contentAlignment = Alignment.Center
                ) {
                    if (!channel.logoUrl.isNullOrBlank()) {
                        AsyncImage(
                            model = channel.logoUrl,
                            contentDescription = channel.name,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize().padding(8.dp)
                        )
                    } else {
                        Text(channel.name.take(2).uppercase(), color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Spacer(Modifier.width(24.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        program?.title ?: channel.name,
                        color = Color.White,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (program != null) {
                            Text(
                                "${fmtTime(program.startTimeMs)} — ${fmtTime(program.endTimeMs)}",
                                color = Color.White.copy(alpha = 0.7f),
                                fontSize = 18.sp
                            )
                            Spacer(Modifier.width(16.dp))
                            val left = ((program.endTimeMs - System.currentTimeMillis()) / 60_000L).coerceAtLeast(0)
                            Text("$left min", color = Color.White.copy(alpha = 0.7f), fontSize = 18.sp)
                            Spacer(Modifier.width(24.dp))
                        }
                        Text(
                            "${channel.channelNumber.takeIf { it > 0 } ?: (channelsList.indexOfFirst { it.id == channel.id } + 1)}  ${channel.name}",
                            color = Color.White,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.width(16.dp))
                        val resBadge = when {
                            streamInfo.height >= 2160 -> "4K"
                            streamInfo.height >= 1080 -> "FHD"
                            streamInfo.height >= 720 -> "HD"
                            streamInfo.height > 0 -> "SD"
                            else -> null
                        }
                        resBadge?.let { Badge(it) }
                        if (streamInfo.isDrmProtected) {
                            Spacer(Modifier.width(8.dp))
                            Badge("DRM")
                        }
                    }
                    if (program?.description?.isNotBlank() == true) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            program.description,
                            color = Color.White.copy(alpha = 0.6f),
                            fontSize = 16.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            if (program != null && program.isLive) {
                LinearProgressIndicator(
                    progress = { program.progress },
                    modifier = Modifier.fillMaxWidth().height(3.dp),
                    color = Color(0xFF3B82F6),
                    trackColor = Color.White.copy(alpha = 0.3f)
                )
            } else {
                Box(Modifier.fillMaxWidth().height(3.dp).background(Color.White.copy(alpha = 0.3f)))
            }
            Spacer(Modifier.height(10.dp))
        }

        // Strip: TV guide tile + channels
        val listState = rememberLazyListState()
        LaunchedEffect(stripIndex) {
            listState.animateScrollToItem((stripIndex - 1).coerceAtLeast(0))
        }
        LazyRow(
            state = listState,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            item {
                StripTile(selected = stripIndex == 0, width = 150.dp) {
                    Icon(
                        Icons.Default.FormatListBulleted,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(44.dp)
                    )
                    Spacer(Modifier.height(6.dp))
                    Text("TV guide", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Medium)
                }
            }
            itemsIndexed(channelsList) { i, ch ->
                val selected = stripIndex == i + 1
                StripTile(selected = selected, width = 130.dp) {
                    Box(Modifier.height(56.dp).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        if (!ch.logoUrl.isNullOrBlank()) {
                            AsyncImage(
                                model = ch.logoUrl,
                                contentDescription = ch.name,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Text(ch.name.take(3).uppercase(), color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        programFor(ch)?.title ?: "No information",
                        color = Color.White.copy(alpha = if (programFor(ch) != null) 0.9f else 0.5f),
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun StripTile(selected: Boolean, width: androidx.compose.ui.unit.Dp, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .width(width)
            .height(if (selected) 100.dp else 92.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(if (selected) Color(0xFF3A3F47) else Color(0xFF22262C))
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) { content() }
}

@Composable
private fun Badge(text: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(Color(0x66FFFFFF))
            .padding(horizontal = 8.dp, vertical = 2.dp)
    ) {
        Text(text, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun ChannelListPanel(
    channelsList: List<ChannelEntity>,
    selectedIndex: Int,
    playingId: Long?,
    programFor: (ChannelEntity) -> ProgramEntity?
) {
    val listState = rememberLazyListState()
    LaunchedEffect(selectedIndex) {
        listState.animateScrollToItem((selectedIndex - 3).coerceAtLeast(0))
    }
    val selected = channelsList.getOrNull(selectedIndex)
    val selectedProgram = selected?.let(programFor)

    Row(modifier = Modifier.fillMaxSize()) {
        // Left: channels
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .width(420.dp)
                .background(TvPanelBg)
        ) {
            Text(
                selected?.groupTitle ?: "All channels",
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 18.dp)
            )
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                itemsIndexed(channelsList, key = { _, c -> c.id }) { i, ch ->
                    val isSel = i == selectedIndex
                    val prog = programFor(ch)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 2.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isSel) TvSelected else Color.Transparent)
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(Modifier.size(width = 72.dp, height = 48.dp), contentAlignment = Alignment.Center) {
                            if (!ch.logoUrl.isNullOrBlank()) {
                                AsyncImage(
                                    model = ch.logoUrl,
                                    contentDescription = ch.name,
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier.fillMaxSize()
                                )
                            } else {
                                Text(ch.name.take(2).uppercase(), color = if (isSel) Color.Black else Color.White, fontWeight = FontWeight.Bold)
                            }
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            val num = ch.channelNumber.takeIf { it > 0 } ?: (i + 1)
                            Text(
                                "$num  ${ch.name}",
                                color = if (isSel) Color.Black else Color.White,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                prog?.title ?: "No information",
                                color = (if (isSel) Color.Black else Color.White).copy(alpha = 0.65f),
                                fontSize = 15.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(Modifier.height(4.dp))
                            Box(
                                Modifier.fillMaxWidth().height(2.dp)
                                    .background((if (isSel) Color.Black else Color.White).copy(alpha = 0.25f))
                            ) {
                                if (prog != null) {
                                    Box(
                                        Modifier.fillMaxWidth(prog.progress.coerceIn(0f, 1f)).height(2.dp)
                                            .background(if (isSel) Color.Black else Color.White)
                                    )
                                }
                            }
                        }
                        if (ch.id == playingId) {
                            Spacer(Modifier.width(8.dp))
                            Icon(
                                Icons.Default.PlayArrow,
                                contentDescription = "Playing",
                                tint = if (isSel) Color.Black else Color.White,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }
            }
        }

        // Right: details of highlighted channel
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .weight(1f)
                .background(Brush.horizontalGradient(listOf(Color(0xCC000000), Color(0x55000000))))
                .padding(32.dp)
        ) {
            if (selected != null) {
                Column(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .width(560.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0x66FFFFFF).copy(alpha = 0.18f))
                        .padding(24.dp)
                ) {
                    Text(
                        selectedProgram?.title ?: selected.name,
                        color = Color.White,
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Medium
                    )
                    if (selectedProgram != null) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "${fmtTime(selectedProgram.startTimeMs)} — ${fmtTime(selectedProgram.endTimeMs)}",
                            color = Color.White.copy(alpha = 0.7f),
                            fontSize = 17.sp
                        )
                        selectedProgram.description?.takeIf { it.isNotBlank() }?.let {
                            Spacer(Modifier.height(8.dp))
                            Text(it, color = Color.White.copy(alpha = 0.75f), fontSize = 16.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
                        }
                    } else {
                        Spacer(Modifier.height(4.dp))
                        Text("No information", color = Color.White.copy(alpha = 0.6f), fontSize = 17.sp)
                    }
                }
            }
        }
    }
}
