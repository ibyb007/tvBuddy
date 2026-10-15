package com.example.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.CheckCircle
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
import androidx.compose.runtime.rememberUpdatedState
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
import com.example.data.local.entity.PlaylistEntity
import com.example.data.local.entity.ProgramEntity
import com.example.data.model.SpoofProfile
import com.example.player.AspectMode
import com.example.player.StreamInfo
import com.example.ui.theme.StatusLive
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class TvPanel { NONE, INFO, MENU, CHANNELS, SETTINGS, PLAYLISTS }

private class TvMenuItem(
    val label: String,
    val icon: ImageVector,
    val action: () -> Unit
)

private val SETTINGS_ITEMS = listOf(
    "Playlists" to "Add, remove and manage playlists",
    "Player spoofing engine" to "Choose the player signature sent to providers",
    "EPG" to "TV guide and XMLTV import"
)

private val TvPanelBg = Color(0xE6101418)
private val TvSelected = Color(0xFFF1F3F4)

/**
 * TiviMate-style Android TV player UI.
 *
 * Remote control while watching (no panel open):
 *   OK (click)      -> info banner + channel strip (TV guide tile first); DOWN there opens the action bar
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
    recentChannels: List<ChannelEntity> = emptyList(),
    playlists: List<PlaylistEntity> = emptyList(),
    onAddPlaylist: () -> Unit = {},
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
    var settingsIndex by remember { mutableIntStateOf(0) }
    var playlistIndex by remember { mutableIntStateOf(0) }
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

    // Recently played channels, most recent first (falls back to the full list)
    val strip = recentChannels.ifEmpty { channelsList }

    fun currentIndex(): Int = channelsList.indexOfFirst { it.id == channel?.id }.coerceAtLeast(0)

    val focusRequester = remember { FocusRequester() }
    var hasFocus by remember { mutableStateOf(false) }
    LaunchedEffect(panel, channel?.id) {
        try { focusRequester.requestFocus() } catch (_: Exception) {}
    }
    // Nothing is playing (the last launch had no playback): show the channel list right away
    val latestChannel by rememberUpdatedState(channel)
    LaunchedEffect(channel == null, channelsList.isNotEmpty()) {
        if (channel == null && channelsList.isNotEmpty()) {
            delay(400)   // give a pending "resume last channel" a moment to start first
            if (latestChannel == null && panel == TvPanel.NONE) {
                listIndex = 0
                panel = TvPanel.CHANNELS
            }
        }
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

    // With no channel playing, the channel list is the home screen: BACK leaves the app
    BackHandler(enabled = panel != TvPanel.NONE && !(channel == null && panel == TvPanel.CHANNELS)) {
        panel = if (panel == TvPanel.PLAYLISTS) TvPanel.SETTINGS else TvPanel.NONE
    }

    fun openInfo() {
        stripIndex = 0
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
            TvMenuItem("Settings", Icons.Default.Settings) { settingsIndex = 0; panel = TvPanel.SETTINGS }
        )
    }

    fun onShortOk() {
        when (panel) {
            TvPanel.NONE -> {
                if (errorMessage != null && channel != null) onSelectChannel(channel) else openInfo()
            }
            TvPanel.INFO -> {
                if (stripIndex == 0) {
                    panel = TvPanel.NONE
                    onOpenEpgGuide()
                } else {
                    strip.getOrNull(stripIndex - 1)?.let { onSelectChannel(it); panel = TvPanel.NONE }
                }
            }
            TvPanel.SETTINGS -> {
                when (settingsIndex) {
                    0 -> { playlistIndex = 0; panel = TvPanel.PLAYLISTS }
                    1 -> { panel = TvPanel.NONE; onOpenSpoofSettings() }
                    else -> { panel = TvPanel.NONE; onOpenEpgGuide() }
                }
            }
            TvPanel.PLAYLISTS -> {
                when {
                    playlistIndex < playlists.size -> { panel = TvPanel.NONE; onOpenPlaylists() }
                    playlistIndex == playlists.size -> { panel = TvPanel.NONE; onAddPlaylist() }
                    else -> { panel = TvPanel.NONE; onOpenPlaylists() }
                }
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
        stripIndex = 0
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
                Key.DirectionRight -> stripIndex = (stripIndex + 1).coerceAtMost(strip.size)   // 0 = TV guide tile
                Key.DirectionUp -> if (!isRepeat) zap(-1)
                Key.DirectionDown -> if (!isRepeat) { menuIndex = 0; panel = TvPanel.MENU }
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
            TvPanel.SETTINGS -> when (key) {
                Key.DirectionUp -> settingsIndex = (settingsIndex - 1).coerceAtLeast(0)
                Key.DirectionDown -> settingsIndex = (settingsIndex + 1).coerceAtMost(SETTINGS_ITEMS.size - 1)
                Key.DirectionLeft -> if (!isRepeat) panel = TvPanel.MENU
                Key.DirectionRight -> {}
                else -> return false
            }
            TvPanel.PLAYLISTS -> when (key) {
                Key.DirectionUp -> playlistIndex = (playlistIndex - 1).coerceAtLeast(0)
                Key.DirectionDown -> playlistIndex = (playlistIndex + 1).coerceAtMost(playlists.size + 1)
                Key.DirectionLeft -> if (!isRepeat) panel = TvPanel.SETTINGS
                Key.DirectionRight -> {}
                else -> return false
            }
            TvPanel.CHANNELS -> when (key) {
                Key.DirectionUp -> listIndex = (listIndex - 1).coerceAtLeast(0)
                Key.DirectionDown -> listIndex = (listIndex + 1).coerceAtMost((channelsList.size - 1).coerceAtLeast(0))
                Key.DirectionLeft, Key.DirectionRight -> if (!isRepeat && channel != null) panel = TvPanel.NONE
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
                strip = strip,
                channelsList = channelsList,
                stripIndex = stripIndex,
                programFor = ::programFor
            )
        }

        // ---------- Action bar (long press OK) - compact, pinned to the bottom ----------
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
                    .background(Color.Black.copy(alpha = 0.94f))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.Top
            ) {
                items.forEachIndexed { i, item ->
                    val selected = i == menuIndex
                    Column(
                        modifier = Modifier.width(100.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(RoundedCornerShape(19.dp))
                                .background(if (selected) TvSelected else Color.Transparent),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                item.icon,
                                contentDescription = item.label,
                                tint = if (selected) Color.Black else Color.White.copy(alpha = 0.6f),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(Modifier.height(2.dp))
                        Text(
                            item.label,
                            color = if (selected) Color.White else Color.White.copy(alpha = 0.6f),
                            fontSize = 11.sp,
                            lineHeight = 13.sp,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }

        // ---------- Settings panel (right side, TiviMate style) ----------
        AnimatedVisibility(
            visible = panel == TvPanel.SETTINGS,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.CenterEnd)
        ) {
            SettingsPanel(settingsIndex)
        }

        // ---------- Playlists sub-menu ----------
        AnimatedVisibility(
            visible = panel == TvPanel.PLAYLISTS,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.CenterEnd)
        ) {
            PlaylistsPanel(playlists = playlists, selectedIndex = playlistIndex)
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

private fun fmtFps(fps: Float): String {
    val rounded = Math.round(fps)
    return if (kotlin.math.abs(fps - rounded) < 0.05f) "$rounded FPS"
    else "${"%.2f".format(Locale.US, fps)} FPS"
}

private fun fmtTime(ms: Long): String =
    SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date(ms)).lowercase(Locale.getDefault())

@Composable
private fun InfoBanner(
    channel: ChannelEntity?,
    program: ProgramEntity?,
    streamInfo: StreamInfo,
    strip: List<ChannelEntity>,
    channelsList: List<ChannelEntity>,
    stripIndex: Int,
    programFor: (ChannelEntity) -> ProgramEntity?
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.92f))))
            .padding(horizontal = 32.dp)
            .padding(top = 40.dp, bottom = 14.dp)
    ) {
        if (channel != null) {
            // Compact now-playing header
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(width = 104.dp, height = 58.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(0x33FFFFFF)),
                    contentAlignment = Alignment.Center
                ) {
                    if (!channel.logoUrl.isNullOrBlank()) {
                        AsyncImage(
                            model = channel.logoUrl,
                            contentDescription = channel.name,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize().padding(6.dp)
                        )
                    } else {
                        Text(channel.name.take(2).uppercase(), color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Spacer(Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        program?.title ?: channel.name,
                        color = Color.White,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (program != null) {
                            val left = ((program.endTimeMs - System.currentTimeMillis()) / 60_000L).coerceAtLeast(0)
                            Text(
                                "${fmtTime(program.startTimeMs)} — ${fmtTime(program.endTimeMs)}   $left min",
                                color = Color.White.copy(alpha = 0.7f),
                                fontSize = 14.sp
                            )
                            Spacer(Modifier.width(16.dp))
                        }
                        Text(
                            "${channel.channelNumber.takeIf { it > 0 } ?: (channelsList.indexOfFirst { it.id == channel.id } + 1)}  ${channel.name}",
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        Spacer(Modifier.width(10.dp))
                        val resBadge = when {
                            streamInfo.height >= 2160 -> "4K"
                            streamInfo.height >= 1080 -> "FHD"
                            streamInfo.height >= 720 -> "HD"
                            streamInfo.height > 0 -> "SD"
                            else -> null
                        }
                        resBadge?.let { Badge(it) }
                        if (streamInfo.frameRate > 0f) {
                            Spacer(Modifier.width(6.dp))
                            Badge(fmtFps(streamInfo.frameRate))
                        }
                        if (streamInfo.isDrmProtected) {
                            Spacer(Modifier.width(6.dp))
                            Badge("DRM")
                        }
                    }
                    if (program?.description?.isNotBlank() == true) {
                        Text(
                            program.description,
                            color = Color.White.copy(alpha = 0.55f),
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
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

        // Recently played channels - most recent on the left
        val listState = rememberLazyListState()
        LaunchedEffect(stripIndex) {
            listState.animateScrollToItem(stripIndex.coerceAtLeast(0))
        }
        LazyRow(
            state = listState,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            // Leftmost tile: TV guide
            item {
                val selected = stripIndex == 0
                Column(
                    modifier = Modifier
                        .width(132.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (selected) Color(0xFF3A3F47) else Color(0xFF22262C))
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(Modifier.height(48.dp).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Default.Schedule,
                            contentDescription = "TV guide",
                            tint = Color.White.copy(alpha = if (selected) 1f else 0.8f),
                            modifier = Modifier.size(32.dp)
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "TV guide",
                        color = Color.White.copy(alpha = if (selected) 1f else 0.8f),
                        fontSize = 12.sp,
                        lineHeight = 14.sp,
                        maxLines = 1
                    )
                }
            }
            itemsIndexed(strip) { index, ch ->
                val selected = stripIndex == index + 1
                val prog = programFor(ch)
                Column(
                    modifier = Modifier
                        .width(132.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (selected) Color(0xFF3A3F47) else Color(0xFF22262C))
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(Modifier.height(48.dp).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        if (!ch.logoUrl.isNullOrBlank()) {
                            AsyncImage(
                                model = ch.logoUrl,
                                contentDescription = ch.name,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Text(ch.name.take(3).uppercase(), color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        prog?.title ?: ch.name,
                        color = Color.White.copy(alpha = if (selected) 1f else 0.8f),
                        fontSize = 12.sp,
                        lineHeight = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
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


@Composable
private fun SettingsPanel(settingsIndex: Int) {
    Column(
        modifier = Modifier
            .fillMaxHeight()
            .width(520.dp)
            .background(TvPanelBg)
            .padding(horizontal = 16.dp, vertical = 24.dp)
    ) {
        Text(
            "Settings",
            color = Color.White,
            fontSize = 32.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(start = 16.dp, bottom = 24.dp)
        )
        SETTINGS_ITEMS.forEachIndexed { i, (title, subtitle) ->
            val sel = i == settingsIndex
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 3.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (sel) TvSelected else Color.Transparent)
                    .padding(horizontal = 16.dp, vertical = 18.dp)
            ) {
                Text(title, color = if (sel) Color.Black else Color.White, fontSize = 22.sp)
                Text(
                    subtitle,
                    color = (if (sel) Color.Black else Color.White).copy(alpha = 0.6f),
                    fontSize = 14.sp
                )
            }
        }
    }
}

/** TiviMate-style playlist list: every added playlist, then "Add playlist" / "Manage playlists". */
@Composable
private fun PlaylistsPanel(playlists: List<PlaylistEntity>, selectedIndex: Int) {
    Column(
        modifier = Modifier
            .fillMaxHeight()
            .width(640.dp)
            .background(TvPanelBg)
            .padding(horizontal = 16.dp, vertical = 24.dp)
    ) {
        Text(
            "Playlists",
            color = Color.White,
            fontSize = 32.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(start = 16.dp, bottom = 20.dp)
        )
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            itemsIndexed(playlists) { i, pl ->
                val sel = i == selectedIndex
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (sel) TvSelected else Color.Transparent)
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = if (sel) Color.Black else Color(0xFF2196F3),
                        modifier = Modifier.size(34.dp)
                    )
                    Spacer(Modifier.width(18.dp))
                    Column {
                        Text(
                            pl.name,
                            color = if (sel) Color.Black else Color.White,
                            fontSize = 22.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            "Channels: ${pl.channelCount}",
                            color = (if (sel) Color.Black else Color.White).copy(alpha = 0.65f),
                            fontSize = 16.sp
                        )
                    }
                }
            }
            item {
                val sel = selectedIndex == playlists.size
                Text(
                    "Add playlist",
                    color = if (sel) Color.Black else Color.White,
                    fontSize = 22.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (sel) TvSelected else Color.Transparent)
                        .padding(horizontal = 16.dp, vertical = 18.dp)
                )
            }
            item {
                val sel = selectedIndex == playlists.size + 1
                Text(
                    "Manage playlists",
                    color = if (sel) Color.Black else Color.White,
                    fontSize = 22.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (sel) TvSelected else Color.Transparent)
                        .padding(horizontal = 16.dp, vertical = 18.dp)
                )
            }
        }
    }
}

/**
 * First-launch / no-content screen (TiviMate style). LEFT/RIGHT picks a button, OK activates.
 * "Settings" opens the same settings list (Playlists, spoofing engine, EPG).
 */
@Composable
fun TvEmptyState(
    onAddPlaylist: () -> Unit,
    onOpenPlaylists: () -> Unit,
    onOpenSpoofSettings: () -> Unit,
    onOpenEpgGuide: () -> Unit,
    modifier: Modifier = Modifier
) {
    var selected by remember { mutableIntStateOf(0) }       // 0 = Add playlist, 1 = Settings
    var showSettings by remember { mutableStateOf(false) }
    var settingsIndex by remember { mutableIntStateOf(0) }
    val focusRequester = remember { FocusRequester() }
    var hasFocus by remember { mutableStateOf(false) }

    LaunchedEffect(showSettings) {
        try { focusRequester.requestFocus() } catch (_: Exception) {}
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            if (!hasFocus) {
                try { focusRequester.requestFocus() } catch (_: Exception) {}
            }
        }
    }
    BackHandler(enabled = showSettings) { showSettings = false }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF14181B))
            .focusRequester(focusRequester)
            .onFocusChanged { hasFocus = it.isFocused }
            .focusable()
            .onPreviewKeyEvent { e ->
                val k = e.key
                val isOk = k == Key.DirectionCenter || k == Key.Enter || k == Key.NumPadEnter
                when {
                    isOk -> {
                        if (e.type == KeyEventType.KeyUp) {
                            if (showSettings) {
                                when (settingsIndex) {
                                    0 -> onOpenPlaylists()
                                    1 -> onOpenSpoofSettings()
                                    else -> onOpenEpgGuide()
                                }
                            } else if (selected == 0) {
                                onAddPlaylist()
                            } else {
                                showSettings = true
                                settingsIndex = 0
                            }
                        }
                        true
                    }
                    e.type != KeyEventType.KeyDown -> false
                    showSettings -> when (k) {
                        Key.DirectionUp -> { settingsIndex = (settingsIndex - 1).coerceAtLeast(0); true }
                        Key.DirectionDown -> { settingsIndex = (settingsIndex + 1).coerceAtMost(SETTINGS_ITEMS.size - 1); true }
                        Key.DirectionLeft -> { showSettings = false; true }
                        else -> false
                    }
                    else -> when (k) {
                        Key.DirectionLeft -> { selected = 0; true }
                        Key.DirectionRight -> { selected = 1; true }
                        else -> false
                    }
                }
            }
    ) {
        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("tvBuddy does not provide any content", color = Color.White, fontSize = 32.sp)
            Spacer(Modifier.height(16.dp))
            Text(
                "Add your own playlist to start watching",
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 26.sp
            )
            Spacer(Modifier.height(48.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                listOf("Add playlist", "Settings").forEachIndexed { i, label ->
                    val sel = selected == i && !showSettings
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { if (i == 0) onAddPlaylist() else { showSettings = true; settingsIndex = 0 } }
                            .background(if (sel) TvSelected else Color(0xFF25292D))
                            .padding(horizontal = 32.dp, vertical = 20.dp)
                    ) {
                        Text(label, color = if (sel) Color.Black else Color.White, fontSize = 24.sp)
                    }
                }
            }
        }
        if (showSettings) {
            Box(Modifier.align(Alignment.CenterEnd)) { SettingsPanel(settingsIndex) }
        }
    }
}
