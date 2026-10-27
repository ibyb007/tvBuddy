package com.example.ui.components

import com.example.data.epg.EpgMatcher
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.gestures.animateScrollBy
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.FormatListBulleted
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.RadioButtonUnchecked
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.gestures.scrollBy
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
import androidx.compose.ui.platform.LocalConfiguration
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
import com.example.player.TrackInfo
import com.example.ui.theme.StatusLive
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class TvPanel { NONE, INFO, MENU, CHANNELS, GUIDE, SETTINGS, PLAYLISTS, AUDIO }

private class TvMenuItem(
    val label: String,
    val icon: ImageVector,
    val action: () -> Unit
)

private val SETTINGS_ITEMS = listOf(
    "Playlists" to "Add, remove and manage playlists",
    "Player spoofing engine" to "Choose the player signature sent to providers",
    "EPG" to "EPG sources and update options"
)

private val TvPanelBg = Color(0xE6101418)
private val TvSelected = Color(0xFFF1F3F4)
// Recent-channel cards: 50% opaque so the video shows through
private val StripCard = Color(0xFF22262C).copy(alpha = 0.5f)
private val StripCardSelected = Color(0xFF3A3F47).copy(alpha = 0.5f)

/**
 * TiviMate-style Android TV player UI.
 *
 * Remote control while watching (no panel open):
 *   OK (click)      -> info banner + channel strip (TV guide tile first); DOWN there opens the action bar
 *   OK (long press) -> action bar (channels, audio boost, quality, audio, subtitles, favourite ...)
 *   LEFT            -> channel list
 *   UP / DOWN       -> previous / next channel
 *   RIGHT           -> last played channel
 * In the channel list: UP/DOWN navigate, OK plays, LEFT opens the playlist groups page, RIGHT/BACK close.
 * BACK with no panel open is handled by HomeScreen (it reveals the TV page: preview + EPG).
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
    /** programmes per lower-case EPG channel id, grouped + sorted off the main thread by the view model */
    epgByChannel: Map<String, List<ProgramEntity>> = emptyMap(),
    recentChannels: List<ChannelEntity> = emptyList(),
    playlists: List<PlaylistEntity> = emptyList(),
    onAddPlaylist: () -> Unit = {},
    onTogglePlayPause: () -> Unit,
    onNextChannel: () -> Unit,
    onPreviousChannel: () -> Unit,
    onSelectChannel: (ChannelEntity) -> Unit,
    onRecallPrevious: () -> Unit = {},
    onRemoveRecent: (ChannelEntity) -> Unit = {},
    onClearRecent: () -> Unit = {},
    onToggleFavorite: (ChannelEntity) -> Unit,
    onCycleAspect: () -> Unit,
    onOpenQualityPicker: () -> Unit = {},
    onOpenEpgGuide: () -> Unit = {},
    audioBoostProfile: com.example.player.AudioBoostProfile = com.example.player.AudioBoostProfile.OFF,
    onCycleAudioBoost: () -> Unit = {},
    onOpenAudioTracks: () -> Unit,
    audioTracks: List<TrackInfo> = emptyList(),
    onSelectAudioTrack: (TrackInfo) -> Unit = {},
    onDisableAudio: () -> Unit = {},
    onOpenSubtitles: () -> Unit,
    onClosePlayer: () -> Unit,
    onOpenSpoofSettings: () -> Unit = {},
    onOpenPlaylists: () -> Unit = {},
    onOpenPlaylistSettings: (Long) -> Unit = {},
    onOpenEpgSettings: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onOpenGroups: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var panel by remember { mutableStateOf(TvPanel.NONE) }
    var tick by remember { mutableLongStateOf(0L) }          // bumps on every key press (auto-hide timer)
    var stripIndex by remember { mutableIntStateOf(0) }       // 0 = TV guide tile, 1.. = channels
    var menuIndex by remember { mutableIntStateOf(0) }
    var listIndex by remember { mutableIntStateOf(0) }
    var settingsIndex by remember { mutableIntStateOf(0) }
    var playlistIndex by remember { mutableIntStateOf(0) }
    var audioIndex by remember { mutableIntStateOf(0) }          // 0 = Off, 1.. = audio tracks
    var okLongHandled by remember { mutableStateOf(false) }

    // Recent strip: "Remove" popup on a card (long press OK) and ids already removed/cleared (hidden at once,
    // before the database update arrives)
    var removeOpen by remember { mutableStateOf(false) }
    var hiddenIds by remember { mutableStateOf(setOf<Long>()) }
    LaunchedEffect(recentChannels) { hiddenIds = emptySet() }
    LaunchedEffect(panel) { if (panel != TvPanel.INFO) removeOpen = false }

    // Channel-change banner: shown on app launch and whenever the channel changes (until a panel is opened)
    var quickInfo by remember { mutableStateOf(false) }
    LaunchedEffect(channel?.id) {
        if (channel != null) {
            quickInfo = true
            delay(5000)
            quickInfo = false
        }
    }


    var guideRow by remember { mutableIntStateOf(0) }
    var guideCursorMs by remember { mutableLongStateOf(0L) }   // a point in time inside the focused cell
    var guideOriginMs by remember { mutableLongStateOf(0L) }   // time at the left edge of the grid
    val now by produceNow()

    // Schedule per channel (window query when available, otherwise just the "now" programs)
    // (the grouping itself used to run here on the main thread every time the overlay was composed)
    val byEpgId = epgByChannel
    fun programsOf(ch: ChannelEntity): List<ProgramEntity> =
        EpgMatcher.programsOf(ch, byEpgId)

    /** Slots for a channel; gaps without EPG data become hour-long "No information" slots. */
    fun slotsOf(ch: ChannelEntity, from: Long, to: Long): List<EpgSlot> = buildSlots(programsOf(ch), from, to)

    // 3 h window per channel, built once per EPG snapshot and hour (every row of the channel list asks for it
    // on every key press; before, each ask rebuilt the slots from scratch)
    val hourNow = floorToHour(now)
    val nearCache = remember(byEpgId, hourNow) { HashMap<Long, List<EpgSlot>>() }
    fun nearSlots(ch: ChannelEntity): List<EpgSlot> =
        nearCache.getOrPut(ch.id) { slotsOf(ch, hourNow, hourNow + 3 * HOUR_MS) }

    fun slotNow(ch: ChannelEntity): EpgSlot {
        val slots = nearSlots(ch)
        return slots.firstOrNull { it.contains(now) } ?: slots.first()
    }

    fun slotNext(ch: ChannelEntity): EpgSlot? {
        val slots = nearSlots(ch)
        val i = slots.indexOfFirst { it.contains(now) }
        return slots.getOrNull(i + 1)
    }

    val guideFrom = floorToHour(now) - 3 * HOUR_MS
    val guideTo = floorToHour(now) + 24 * HOUR_MS
    val guideCache = remember(byEpgId, guideFrom) { HashMap<Long, List<EpgSlot>>() }
    fun guideSlots(ch: ChannelEntity): List<EpgSlot> = guideCache.getOrPut(ch.id) { slotsOf(ch, guideFrom, guideTo) }

    // Recently played channels, most recent first (falls back to the full list)
    val strip = recentChannels.filter { it.id !in hiddenIds }.take(10)

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

    // With no channel playing, the channel list is the home screen: BACK leaves the app
    BackHandler(enabled = panel != TvPanel.NONE && !(channel == null && panel == TvPanel.CHANNELS)) {
        if (removeOpen) removeOpen = false
        else panel = if (panel == TvPanel.PLAYLISTS) TvPanel.SETTINGS else TvPanel.NONE
    }

    fun openInfo() {
        stripIndex = 0
        panel = TvPanel.INFO
    }

    fun openChannelList() {
        listIndex = currentIndex()
        panel = TvPanel.CHANNELS
    }

    fun openGuide() {
        guideRow = currentIndex()
        guideCursorMs = System.currentTimeMillis()
        guideOriginMs = floorToHalfHour(guideCursorMs)
        panel = TvPanel.GUIDE
    }

    fun menuItems(): List<TvMenuItem> {
        // the audio button shows what is playing now, e.g. "English, Stereo, 0.20 Mbps"
        val audioSelected = audioTracks.firstOrNull { it.isSelected }
        val audioLabel = when {
            audioTracks.isEmpty() -> "Audio"
            audioSelected == null -> "Audio off"
            else -> audioTrackText(audioSelected)
        }
        val qualityLabel = if (streamInfo.width > 0 && streamInfo.height > 0) {
            val mbps = streamInfo.bitrate / 1_000_000f
            if (mbps > 0f) "${streamInfo.width} × ${streamInfo.height}, ${"%.2f".format(mbps)} Mbps"
            else "${streamInfo.width} × ${streamInfo.height}"
        } else videoQuality.title.substringBefore(" ")
        return listOf(
            TvMenuItem("Channels list", Icons.Default.FormatListBulleted) { openChannelList() },
            TvMenuItem("Audio boost: ${audioBoostProfile.shortTitle}", Icons.Default.GraphicEq) { onCycleAudioBoost() },
            TvMenuItem(qualityLabel, Icons.Default.HighQuality) { panel = TvPanel.NONE; onOpenQualityPicker() },
            TvMenuItem(audioLabel, Icons.AutoMirrored.Filled.VolumeUp) {
                val sel = audioTracks.indexOfFirst { it.isSelected }
                audioIndex = if (sel >= 0) sel + 1 else 0
                panel = TvPanel.AUDIO
            },
            TvMenuItem("Subtitles", Icons.Default.ClosedCaption) { panel = TvPanel.NONE; onOpenSubtitles() },
            TvMenuItem(
                if (channel?.isFavorite == true) "Remove from Favorites" else "Add to Favorites",
                if (channel?.isFavorite == true) Icons.Default.Star else Icons.Outlined.StarOutline
            ) { channel?.let(onToggleFavorite) },
            TvMenuItem(aspectMode.title, Icons.Default.AspectRatio) { onCycleAspect() },
            TvMenuItem("Settings", Icons.Default.Settings) { panel = TvPanel.NONE; onOpenSettings() }
        )
    }

    fun onShortOk() {
        when (panel) {
            TvPanel.NONE -> {
                if (errorMessage != null && channel != null) onSelectChannel(channel) else openInfo()
            }
            TvPanel.INFO -> {
                if (removeOpen) {
                    // remove the focused card; focus goes to the card on its left, else the one on its right
                    removeOpen = false
                    val ch = strip.getOrNull(stripIndex - 1)
                    if (ch != null) {
                        hiddenIds = hiddenIds + ch.id
                        onRemoveRecent(ch)
                        val remaining = strip.size - 1
                        stripIndex = when {
                            stripIndex > 1 -> stripIndex - 1
                            remaining > 0 -> 1
                            else -> 0                       // nothing left: back on the TV guide tile
                        }
                    }
                } else if (stripIndex == 0) {
                    openGuide()
                } else if (stripIndex == strip.size + 1) {
                    // Clear tile: wipe the history, focus lands on the TV guide tile
                    hiddenIds = hiddenIds + recentChannels.map { it.id }
                    onClearRecent()
                    stripIndex = 0
                } else {
                    strip.getOrNull(stripIndex - 1)?.let { onSelectChannel(it); panel = TvPanel.NONE }
                }
            }
            TvPanel.SETTINGS -> {
                when (settingsIndex) {
                    0 -> { playlistIndex = 0; panel = TvPanel.PLAYLISTS }
                    1 -> { panel = TvPanel.NONE; onOpenSpoofSettings() }
                    else -> { panel = TvPanel.NONE; onOpenEpgSettings() }
                }
            }
            TvPanel.PLAYLISTS -> {
                when {
                    playlistIndex < playlists.size -> {
                        val pl = playlists[playlistIndex]
                        panel = TvPanel.NONE
                        onOpenPlaylistSettings(pl.id)
                    }
                    playlistIndex == playlists.size -> { panel = TvPanel.NONE; onAddPlaylist() }
                    else -> { panel = TvPanel.NONE; onOpenPlaylists() }
                }
            }
            TvPanel.MENU -> menuItems().getOrNull(menuIndex)?.action?.invoke()
            TvPanel.AUDIO -> {
                if (audioIndex == 0) onDisableAudio()
                else audioTracks.getOrNull(audioIndex - 1)?.let(onSelectAudioTrack)
                panel = TvPanel.NONE
            }
            TvPanel.CHANNELS -> {
                channelsList.getOrNull(listIndex)?.let { onSelectChannel(it) }
                panel = TvPanel.NONE
            }
            TvPanel.GUIDE -> {
                channelsList.getOrNull(guideRow)?.let { onSelectChannel(it) }
                panel = TvPanel.NONE
            }
        }
    }

    fun onLongOk() {
        if (panel == TvPanel.INFO && stripIndex in 1..strip.size) {
            removeOpen = true
            return
        }
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
        removeOpen = false
    }

    fun onDirection(key: Key, isRepeat: Boolean): Boolean {
        when (panel) {
            TvPanel.NONE -> {
                if (isRepeat) return true
                when (key) {
                    Key.DirectionUp -> zap(-1)
                    Key.DirectionDown -> zap(+1)
                    Key.DirectionLeft -> openChannelList()
                    Key.DirectionRight -> onRecallPrevious()
                    else -> return false
                }
            }
            TvPanel.INFO -> if (removeOpen) {
                removeOpen = false                      // any direction closes the Remove popup
            } else when (key) {
                Key.DirectionLeft -> stripIndex = (stripIndex - 1).coerceAtLeast(0)
                // 0 = TV guide tile, 1..n = cards, n+1 = Clear tile
                Key.DirectionRight -> stripIndex = (stripIndex + 1).coerceAtMost(if (strip.isEmpty()) 0 else strip.size + 1)
                Key.DirectionUp -> if (!isRepeat) zap(-1)
                Key.DirectionDown -> if (!isRepeat) { menuIndex = 0; panel = TvPanel.MENU }
                else -> return false
            }
            TvPanel.MENU -> {
                val n = menuItems().size
                when (key) {
                    // wraps around: LEFT on the first button -> last, RIGHT on the last button -> first
                    Key.DirectionLeft -> if (n > 0) menuIndex = (menuIndex - 1 + n) % n
                    Key.DirectionRight -> if (n > 0) menuIndex = (menuIndex + 1) % n
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
            TvPanel.AUDIO -> when (key) {
                Key.DirectionUp -> audioIndex = (audioIndex - 1).coerceAtLeast(0)
                Key.DirectionDown -> audioIndex = (audioIndex + 1).coerceAtMost(audioTracks.size)
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
            TvPanel.GUIDE -> {
                val lastRow = (channelsList.size - 1).coerceAtLeast(0)
                fun moveRow(to: Int) {
                    guideRow = to.coerceIn(0, lastRow)
                    guideCursorMs = maxOf(guideCursorMs, guideOriginMs)
                }
                fun moveCell(delta: Int) {
                    val ch = channelsList.getOrNull(guideRow) ?: return
                    val slots = guideSlots(ch)
                    val target = slots.getOrNull(slots.indexAt(guideCursorMs) + delta) ?: return
                    guideCursorMs = target.startMs
                    if (target.startMs < guideOriginMs) {
                        guideOriginMs = floorToHalfHour(target.startMs)
                    } else if (target.endMs > guideOriginMs + GUIDE_VISIBLE_MS) {
                        guideOriginMs = floorToHalfHour(target.startMs)
                    }
                }
                when (key) {
                    Key.DirectionUp -> moveRow(guideRow - 1)
                    Key.DirectionDown -> moveRow(guideRow + 1)
                    Key.DirectionLeft -> moveCell(-1)
                    Key.DirectionRight -> moveCell(+1)
                    else -> return false
                }
            }
            TvPanel.CHANNELS -> when (key) {
                Key.DirectionUp -> listIndex = (listIndex - 1).coerceAtLeast(0)
                Key.DirectionDown -> listIndex = (listIndex + 1).coerceAtMost((channelsList.size - 1).coerceAtLeast(0))
                Key.DirectionLeft -> if (!isRepeat) onOpenGroups()      // reveal the playlist groups page
                Key.DirectionRight -> if (!isRepeat && channel != null) panel = TvPanel.NONE
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
            visible = panel == TvPanel.INFO || panel == TvPanel.MENU || (quickInfo && panel == TvPanel.NONE),
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 20.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    listOfNotNull(
                        playlists.firstOrNull { it.id == channel?.playlistId }?.name,
                        channel?.groupTitle
                    ).filter { it.isNotBlank() }.joinToString("  •  "),
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
                slot = channel?.let(::slotNow),
                nextSlot = channel?.let(::slotNext),
                now = now,
                streamInfo = streamInfo,
                audioTracks = audioTracks,
                strip = strip,
                channelsList = channelsList,
                stripIndex = stripIndex,
                slotFor = ::slotNow,
                removeOpen = removeOpen
            )
        }

        // ---------- Channel-change banner (info header only: no progress bar / strip) ----------
        AnimatedVisibility(
            visible = quickInfo && panel == TvPanel.NONE && errorMessage == null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            InfoBanner(
                channel = channel,
                slot = channel?.let(::slotNow),
                nextSlot = channel?.let(::slotNext),
                now = now,
                streamInfo = streamInfo,
                audioTracks = audioTracks,
                strip = emptyList(),
                channelsList = channelsList,
                stripIndex = 0,
                slotFor = ::slotNow,
                compact = true
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
            // dark fade behind the bar so icons/labels stay readable over bright video
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, Color.Black.copy(alpha = 0.65f), Color.Black.copy(alpha = 0.9f))
                        )
                    )
                    .padding(start = 8.dp, end = 8.dp, top = 56.dp, bottom = 14.dp),
                verticalAlignment = Alignment.Top
            ) {
                items.forEachIndexed { i, item ->
                    val selected = i == menuIndex
                    Column(
                        modifier = Modifier.weight(1f).padding(horizontal = 2.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .clip(CircleShape)
                                .background(if (selected) TvSelected else Color.White.copy(alpha = 0.14f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                item.icon,
                                contentDescription = item.label,
                                tint = if (selected) Color.Black else Color.White,
                                modifier = Modifier.size(30.dp)
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            item.label,
                            color = Color.White,
                            fontSize = 15.sp,
                            lineHeight = 18.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                            style = androidx.compose.ui.text.TextStyle(
                                shadow = androidx.compose.ui.graphics.Shadow(
                                    color = Color.Black,
                                    offset = androidx.compose.ui.geometry.Offset(0f, 1f),
                                    blurRadius = 8f
                                )
                            ),
                            textAlign = TextAlign.Center,
                            maxLines = 3,                       // long labels wrap (e.g. "Remove from Favorites")
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

        // ---------- Audio tracks panel (right side, TiviMate style) ----------
        AnimatedVisibility(
            visible = panel == TvPanel.AUDIO,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.CenterEnd)
        ) {
            AudioTracksPanel(tracks = audioTracks, focusIndex = audioIndex)
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
                now = now,
                slotNowFor = ::slotNow,
                scheduleFor = ::guideSlots
            )
        }

        // ---------- TV guide (grid) ----------
        AnimatedVisibility(
            visible = panel == TvPanel.GUIDE,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            TvGuidePanel(
                channels = channelsList,
                selectedRow = guideRow,
                cursorMs = guideCursorMs,
                originMs = guideOriginMs,
                now = now,
                playingId = channel?.id,
                slotsFor = ::guideSlots
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

internal fun fmtFps(fps: Float): String {
    val rounded = Math.round(fps)
    return if (kotlin.math.abs(fps - rounded) < 0.05f) "$rounded FPS"
    else "${"%.2f".format(Locale.US, fps)} FPS"
}

private fun fmtTime(ms: Long): String =
    SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date(ms)).lowercase(Locale.getDefault())

@Composable
private fun InfoBanner(
    channel: ChannelEntity?,
    slot: EpgSlot?,
    nextSlot: EpgSlot?,
    now: Long,
    streamInfo: StreamInfo,
    audioTracks: List<TrackInfo>,
    strip: List<ChannelEntity>,
    channelsList: List<ChannelEntity>,
    stripIndex: Int,
    slotFor: (ChannelEntity) -> EpgSlot,
    /** channel-change banner: header only (no progress bar, no recent-channel strip) */
    compact: Boolean = false,
    removeOpen: Boolean = false
) {
    val k = rememberUiScale()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.92f))))
            .padding(horizontal = 32.dp)
            .padding(top = 40.dp, bottom = 14.dp)
    ) {
        if (channel != null && slot != null) {
            // Now-playing header: logo | title / time + progress / next slot
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(width = 104.dp, height = 58.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(0x33FFFFFF)),
                    contentAlignment = Alignment.Center
                ) {
                    if (!channel.logoUrl.isNullOrBlank()) {
                        ChannelLogo(
                            url = channel.logoUrl,
                            contentDescription = channel.name,
                            modifier = Modifier.fillMaxSize().padding(6.dp)
                        )
                    } else {
                        Text(channel.name.take(2).uppercase(), color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Spacer(Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        slot.title,
                        color = Color.White,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${fmtClock(slot.startMs)} — ${fmtClock(slot.endMs)}",
                            color = Color.White.copy(alpha = 0.7f),
                            fontSize = 14.sp
                        )
                        Spacer(Modifier.width(12.dp))
                        MiniProgress(slot.progress(now), k = 1f, width = 60f)
                        Spacer(Modifier.width(12.dp))
                        Text(
                            "${slot.minutesLeft(now)} min",
                            color = Color.White.copy(alpha = 0.7f),
                            fontSize = 14.sp
                        )
                        Spacer(Modifier.width(24.dp))
                        Text(
                            "${channelsList.indexOfFirst { it.id == channel.id } + 1}  ${channel.name}",
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
                        when (audioTracks.firstOrNull { it.isSelected }?.channelCount ?: 0) {
                            0 -> null
                            1 -> "Mono"
                            2 -> "Stereo"
                            6 -> "5.1"
                            8 -> "7.1"
                            else -> "${audioTracks.first { it.isSelected }.channelCount} ch"
                        }?.let {
                            Spacer(Modifier.width(6.dp))
                            Badge(it)
                        }
                        if (streamInfo.isDrmProtected) {
                            Spacer(Modifier.width(6.dp))
                            Badge("DRM")
                        }
                    }
                    if (nextSlot != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "${fmtClock(nextSlot.startMs)} — ${fmtClock(nextSlot.endMs)}",
                                color = Color.White.copy(alpha = 0.7f),
                                fontSize = 14.sp
                            )
                            Spacer(Modifier.width(14.dp))
                            Text(
                                nextSlot.title,
                                color = Color.White.copy(alpha = if (nextSlot.program == null) 0.55f else 0.85f),
                                fontSize = 15.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
            if (compact) return@Column
            Spacer(Modifier.height(8.dp))
            // Full-width programme progress with a knob (hour-long slot when there is no EPG)
            val p = slot.progress(now).coerceIn(0f, 1f)
            BoxWithConstraints(Modifier.fillMaxWidth().height(12.dp)) {
                Box(
                    Modifier.align(Alignment.CenterStart).fillMaxWidth().height(4.dp)
                        .background(Color.White.copy(alpha = 0.35f))
                )
                Box(
                    Modifier.align(Alignment.CenterStart).fillMaxWidth(p).height(4.dp)
                        .background(Color(0xFF3B82F6))
                )
                Box(
                    Modifier.align(Alignment.CenterStart)
                        .offset(x = (maxWidth * p) - 6.dp)
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFE8F0FF))
                )
            }
            Spacer(Modifier.height(10.dp))
        }

        // Recently played channels - most recent on the left. Cards are 50% transparent.
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
                        .background(if (selected) StripCardSelected else StripCard)
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
                    Spacer(Modifier.height(4.dp))
                    Box(Modifier.fillMaxWidth().height(2.dp))
                }
            }
            itemsIndexed(strip) { index, ch ->
                val selected = stripIndex == index + 1
                val chSlot = slotFor(ch)
                Box {
                Column(
                    modifier = Modifier
                        .width(132.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (selected) StripCardSelected else StripCard)
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(Modifier.height(48.dp).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        if (!ch.logoUrl.isNullOrBlank()) {
                            ChannelLogo(
                                url = ch.logoUrl,
                                contentDescription = ch.name,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Text(ch.name.take(3).uppercase(), color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        chSlot.title,
                        color = Color.White.copy(alpha = if (selected) 1f else 0.8f),
                        fontSize = 12.sp,
                        lineHeight = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(4.dp))
                    // progress of the current slot (hour-long when there is no EPG)
                    MiniProgress(chSlot.progress(now), k = 1f, width = 116f)
                }
                if (selected && removeOpen) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(6.dp)
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(TvSelected)
                            .padding(horizontal = 12.dp, vertical = 12.dp)
                    ) {
                        Text("Remove", color = Color.Black, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    }
                }
                }
            }
            // Last tile: clear the whole recent list
            if (strip.isNotEmpty()) item {
                val selected = stripIndex == strip.size + 1
                Column(
                    modifier = Modifier
                        .width(132.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (selected) StripCardSelected else StripCard)
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(Modifier.height(48.dp).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "Clear",
                            tint = Color.White.copy(alpha = if (selected) 1f else 0.8f),
                            modifier = Modifier.size(32.dp)
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Clear",
                        color = Color.White.copy(alpha = if (selected) 1f else 0.8f),
                        fontSize = 12.sp,
                        lineHeight = 14.sp,
                        maxLines = 1
                    )
                    Spacer(Modifier.height(4.dp))
                    Box(Modifier.fillMaxWidth().height(2.dp))
                }
            }
        }
    }
}

@Composable
internal fun Badge(text: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(3.dp))
            .background(Color(0x66FFFFFF))
            .padding(horizontal = 5.dp, vertical = 1.dp)
    ) {
        Text(text, color = Color.White, fontSize = 10.sp, lineHeight = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** Scale so panel sizes match TiviMate's proportions on any TV density (design grid: 1080 px tall). */
/**
 * Keeps a lazy list's top item in step with [target]. Single steps animate (smooth key repeat);
 * anything bigger (list replaced, group switched, page just opened) jumps instantly so the list never
 * visibly "flies" to its position. Does nothing when the list is already there.
 */
private val lastFollowMs = java.util.WeakHashMap<androidx.compose.foundation.lazy.LazyListState, Long>()

internal suspend fun androidx.compose.foundation.lazy.LazyListState.followTarget(target: Int, jump: Boolean) {
    if (firstVisibleItemIndex == target && firstVisibleItemScrollOffset == 0) return
    val t = android.os.SystemClock.uptimeMillis()
    val rapid = t - (lastFollowMs[this] ?: 0L) < 160L
    lastFollowMs[this] = t
    val itemPx = layoutInfo.visibleItemsInfo.firstOrNull()?.size ?: 0
    val far = kotlin.math.abs(target - firstVisibleItemIndex) > 6
    if (jump || far || itemPx <= 0) {
        scrollToItem(target)
        return
    }
    // Scroll by an exact pixel distance with a short animation that is restarted from the CURRENT position on each
    // step. While a key is held the list therefore glides at a steady speed (TiviMate-like) instead of jumping a
    // whole row per key repeat; a single press eases in and out.
    val distance = ((target - firstVisibleItemIndex) * itemPx - firstVisibleItemScrollOffset).toFloat()
    animateScrollBy(
        distance,
        androidx.compose.animation.core.tween(
            durationMillis = if (rapid) 100 else 170,
            easing = if (rapid) androidx.compose.animation.core.LinearEasing
            else androidx.compose.animation.core.FastOutSlowInEasing
        )
    )
}

/** Rows kept above the focused one (focused row sits on the 4th line, like TiviMate). */
internal const val LIST_PIN_ROWS = 3

/** Time constant of the list follow (ms): smaller = snappier. ~45 ms settles a single step in about 150 ms. */
private const val FOLLOW_TAU_MS = 45f

/**
 * Pinned-focus scrolling without blank filler rows: the list tries to keep [selected] on line [pinRows] + 1.
 * Near the start / end the list simply cannot scroll any further, so the focus moves to the real first / last row
 * instead of leaving an empty gap.
 *
 * The list is moved every frame by a fraction of the remaining distance (exponential follow). The speed therefore
 * adapts by itself: a single press eases in quickly, and a held key (any repeat rate) keeps the list only about
 * one row behind the focus instead of queueing up fixed-length animations. Restarting for a new target continues
 * from the current position, so there are no speed discontinuities.
 */
internal suspend fun androidx.compose.foundation.lazy.LazyListState.followPinned(
    selected: Int,
    jump: Boolean,
    pinRows: Int = LIST_PIN_ROWS
) {
    val target = (selected - pinRows).coerceAtLeast(0)
    val itemPx = layoutInfo.visibleItemsInfo.firstOrNull()?.size ?: 0
    if (jump || itemPx <= 0 || kotlin.math.abs(target - firstVisibleItemIndex) > 8) {
        scrollToItem(target)
        return
    }
    val minStep = itemPx * 0.05f
    var last = 0L
    while (true) {
        val remaining = ((target - firstVisibleItemIndex) * itemPx - firstVisibleItemScrollOffset).toFloat()
        if (kotlin.math.abs(remaining) < 1f) return
        if (remaining > 0f && !canScrollForward) return      // already at the end
        if (remaining < 0f && !canScrollBackward) return     // already at the start
        val now = androidx.compose.runtime.withFrameNanos { it }
        val dt = if (last == 0L) 16f else ((now - last) / 1_000_000f).coerceIn(1f, 50f)
        last = now
        var step = remaining * (1f - kotlin.math.exp(-dt / FOLLOW_TAU_MS))
        if (kotlin.math.abs(step) < minStep) step = kotlin.math.sign(remaining) * minOf(kotlin.math.abs(remaining), minStep)
        scrollBy(step)
    }
}

/**
 * Focus bar drawn as its own layer behind a pinned-focus list (see [followPinned]). Rows slide under it, so while
 * the list scrolls the bar stays put; it only travels where the list cannot scroll any further (first / last rows).
 * Place it before the LazyColumn inside the same Box.
 */
@Composable
internal fun PinnedFocusBar(
    selectedIndex: Int,
    itemCount: Int,
    rowPx: Float,
    viewportPx: Int,
    contentTopPx: Float,
    contentBottomPx: Float,
    insetPx: Float,
    barHeight: androidx.compose.ui.unit.Dp,
    startPad: androidx.compose.ui.unit.Dp,
    endPad: androidx.compose.ui.unit.Dp,
    corner: androidx.compose.ui.unit.Dp,
    color: Color
) {
    if (viewportPx <= 0 || itemCount <= 0) return
    val maxScroll = (contentTopPx + itemCount * rowPx + contentBottomPx - viewportPx).coerceAtLeast(0f)
    val restScroll = ((selectedIndex - LIST_PIN_ROWS) * rowPx).coerceIn(0f, maxScroll)
    val restTop = contentTopPx + selectedIndex * rowPx - restScroll
    // big moves (wrap-around, group switch) snap instead of sliding across the whole list
    val prevTop = remember { floatArrayOf(restTop) }
    val snap = kotlin.math.abs(restTop - prevTop[0]) > rowPx * 2.5f
    androidx.compose.runtime.SideEffect { prevTop[0] = restTop }
    val barTop = androidx.compose.animation.core.animateFloatAsState(
        targetValue = restTop,
        animationSpec = if (snap) androidx.compose.animation.core.snap()
        else androidx.compose.animation.core.tween(
            durationMillis = 90,
            easing = androidx.compose.animation.core.LinearOutSlowInEasing
        ),
        label = "focusBar"
    )
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = startPad, end = endPad)
            .height(barHeight)
            .offset { androidx.compose.ui.unit.IntOffset(0, (barTop.value + insetPx).toInt()) }
            .clip(RoundedCornerShape(corner))
            .background(color)
    )
}

@Composable
internal fun rememberUiScale(): Float = LocalConfiguration.current.screenHeightDp / 1080f

@Composable
internal fun ChannelListPanel(
    channelsList: List<ChannelEntity>,
    selectedIndex: Int,
    playingId: Long?,
    now: Long,
    slotNowFor: (ChannelEntity) -> EpgSlot,
    scheduleFor: (ChannelEntity) -> List<EpgSlot>,
    showSchedule: Boolean = true,
    highlightSelected: Boolean = true,
    /** channel id -> number inside its group; rows fall back to their 1-based position */
    numbering: Map<Long, Int> = emptyMap()
) {
    val k = rememberUiScale()
    // opens already positioned on the selected row (no scroll animation when the panel appears)
    // 3 spacer rows before / 6 after the channels keep the selected row pinned on the 4th line (list slides under it)
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = (selectedIndex - LIST_PIN_ROWS).coerceAtLeast(0)
    )
    val prevIndex = remember { intArrayOf(selectedIndex, channelsList.size) }
    LaunchedEffect(selectedIndex, channelsList.size) {
        val jump = kotlin.math.abs(selectedIndex - prevIndex[0]) > 2 || prevIndex[1] != channelsList.size
        prevIndex[0] = selectedIndex
        prevIndex[1] = channelsList.size
        listState.followPinned(selectedIndex.coerceAtLeast(0), jump)
    }
    val density = androidx.compose.ui.platform.LocalDensity.current.density
    var listHeightPx by remember { mutableIntStateOf(0) }
    // The schedule + detail columns follow the selection ~100 ms after the last key press: while a key is held they
    // would otherwise rebuild on every step and steal frames from the list scroll.
    var settledIndex by remember { mutableIntStateOf(selectedIndex) }
    LaunchedEffect(selectedIndex, channelsList.size) {
        kotlinx.coroutines.delay(100)
        settledIndex = selectedIndex
    }
    val selected = channelsList.getOrNull(settledIndex)
    val schedule = remember(selected?.id, now / 60_000L) { selected?.let(scheduleFor) ?: emptyList() }
    val currentIdx = schedule.indexOfFirst { it.contains(now) }.coerceAtLeast(0)
    val currentSlot = schedule.getOrNull(currentIdx)

    Row(modifier = Modifier.fillMaxSize()) {
        // Column 1: channels (8 rows fit on screen, like TiviMate)
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .width((600 * k).dp)
                .background(TvPanelBg)
        ) {
            Text(
                selected?.groupTitle ?: "All channels",
                color = Color.White,
                fontSize = (30 * k).sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().padding(horizontal = (32 * k).dp, vertical = (20 * k).dp)
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .onSizeChanged { listHeightPx = it.height }
            ) {
                // Focus bar: a separate layer, not part of any row (rows slide under it, see PinnedFocusBar)
                if (highlightSelected) {
                    PinnedFocusBar(
                        selectedIndex = selectedIndex,
                        itemCount = channelsList.size,
                        rowPx = 125f * k * density,
                        viewportPx = listHeightPx,
                        contentTopPx = 0f,
                        contentBottomPx = 0f,
                        insetPx = 4f * k * density,
                        barHeight = (117 * k).dp,
                        startPad = (16 * k).dp,
                        endPad = (16 * k).dp,
                        corner = (14 * k).dp,
                        color = TvSelected
                    )
                }
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    itemsIndexed(channelsList, key = { _, c -> c.id }) { i, ch ->
                        // own composable with plain value parameters: a key press changes only the old and the new
                        // selected row, the other visible rows are skipped instead of being recomposed
                        ChannelListRow(
                            k = k,
                            channel = ch,
                            number = numbering[ch.id] ?: (i + 1),
                            isSel = highlightSelected && i == selectedIndex,
                            playing = ch.id == playingId,
                            slot = slotNowFor(ch),
                            now = now
                        )
                    }
                }
            }
        }

        if (showSchedule) {
        // Column 2: schedule (previous + upcoming) of the highlighted channel
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .width((550 * k).dp)
                .background(Color(0xFF101418).copy(alpha = 0.55f))
        ) {
            if (selected != null) {
                val scheduleState = rememberLazyListState()
                LaunchedEffect(selected.id, currentIdx) {
                    scheduleState.scrollToItem((currentIdx - 5).coerceAtLeast(0))
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = (24 * k).dp, end = (16 * k).dp, top = (24 * k).dp, bottom = (16 * k).dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.size(width = (96 * k).dp, height = (72 * k).dp), contentAlignment = Alignment.Center) {
                        if (!selected.logoUrl.isNullOrBlank()) {
                            ChannelLogo(
                                url = selected.logoUrl,
                                contentDescription = selected.name,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Text(selected.name.take(2).uppercase(), color = Color.White, fontSize = (28 * k).sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    Spacer(Modifier.width((16 * k).dp))
                    Column {
                        Text(
                            "${numbering[selected.id] ?: (settledIndex + 1)}  ${selected.name}",
                            color = Color.White,
                            fontSize = (32 * k).sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(selected.groupTitle, color = Color.White.copy(alpha = 0.7f), fontSize = (28 * k).sp, maxLines = 1)
                    }
                }
                LazyColumn(state = scheduleState, modifier = Modifier.fillMaxSize()) {
                    itemsIndexed(schedule) { idx, slot ->
                        val cur = idx == currentIdx
                        val c = if (cur) Color(0xFF5BA8FF) else Color.White
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height((75 * k).dp)
                                .padding(horizontal = (24 * k).dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                fmtClock(slot.startMs),
                                color = c,
                                fontSize = (30 * k).sp,
                                maxLines = 1,
                                modifier = Modifier.width((150 * k).dp)
                            )
                            Text(
                                slot.title,
                                color = c.copy(alpha = if (slot.program == null && !cur) 0.6f else 1f),
                                fontSize = (30 * k).sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        }

        // Column 3: details of the programme airing now on the highlighted channel
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .weight(1f)
                .background(Color(0xFF000000).copy(alpha = 0.25f))
                .padding(top = (48 * k).dp, end = (48 * k).dp, start = (32 * k).dp)
        ) {
            if (selected != null && currentSlot != null) {
                Column(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape((20 * k).dp))
                        .background(Color(0xFF3A3F47).copy(alpha = 0.75f))
                        .padding((32 * k).dp)
                ) {
                    Text(
                        currentSlot.title,
                        color = Color.White,
                        fontSize = (40 * k).sp,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(Modifier.height((8 * k).dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${fmtClock(currentSlot.startMs)} — ${fmtClock(currentSlot.endMs)}",
                            color = Color.White.copy(alpha = 0.7f),
                            fontSize = (28 * k).sp
                        )
                        Spacer(Modifier.width((20 * k).dp))
                        MiniProgress(currentSlot.progress(now), k, width = 80f)
                        Spacer(Modifier.width((20 * k).dp))
                        Text(
                            "${currentSlot.minutesLeft(now)} min",
                            color = Color.White.copy(alpha = 0.7f),
                            fontSize = (28 * k).sp
                        )
                    }
                    currentSlot.description?.let {
                        Spacer(Modifier.height((12 * k).dp))
                        Text(it, color = Color.White.copy(alpha = 0.75f), fontSize = (28 * k).sp, maxLines = 5, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        }   // showSchedule
    }
}


@Composable
private fun ChannelListRow(
    k: Float,
    channel: ChannelEntity,
    number: Int,
    isSel: Boolean,
    playing: Boolean,
    slot: EpgSlot,
    now: Long
) {
    // the focus bar is drawn by the list (see ChannelListPanel); the row only fades its text colour as the bar passes
    val fg by androidx.compose.animation.animateColorAsState(
        targetValue = if (isSel) Color.Black else Color.White,
        animationSpec = androidx.compose.animation.core.tween(90),
        label = "rowFg"
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = (16 * k).dp, vertical = (4 * k).dp)
            .height((117 * k).dp)
            .padding(horizontal = (24 * k).dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(width = (96 * k).dp, height = (72 * k).dp), contentAlignment = Alignment.Center) {
            if (!channel.logoUrl.isNullOrBlank()) {
                ChannelLogo(
                    url = channel.logoUrl,
                    contentDescription = channel.name,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Text(
                    channel.name.take(2).uppercase(),
                    color = fg,
                    fontSize = (28 * k).sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
        Spacer(Modifier.width((24 * k).dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "$number  ${channel.name}",
                color = fg,
                fontSize = (28 * k).sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                slot.title,
                color = fg.copy(alpha = 0.65f),
                fontSize = (26 * k).sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height((8 * k).dp))
            Box(
                Modifier.fillMaxWidth().height((4 * k).dp)
                    .background(fg.copy(alpha = 0.25f))
            ) {
                Box(
                    Modifier.fillMaxWidth(slot.progress(now)).height((4 * k).dp)
                        .background(fg)
                )
            }
        }
        if (playing) {
            Spacer(Modifier.width((8 * k).dp))
            Icon(
                Icons.Default.PlayArrow,
                contentDescription = "Playing",
                tint = fg,
                modifier = Modifier.size((40 * k).dp)
            )
        }
    }
}

/** "English, Stereo, 0.20 Mbps" - language, channel layout and bitrate read from the stream itself. */
internal fun audioTrackText(t: TrackInfo): String {
    val lang = t.language.takeIf { it.isNotBlank() && it != "und" }
        ?.let { code -> Locale(code).getDisplayLanguage(Locale.ENGLISH).takeIf { it.isNotBlank() && it != code } ?: code }
    val layout = when (t.channelCount) {
        0 -> null
        1 -> "Mono"
        2 -> "Stereo"
        6 -> "5.1"
        8 -> "7.1"
        else -> "${t.channelCount} ch"
    }
    val rate = if (t.bitrate > 0) "%.2f Mbps".format(Locale.US, t.bitrate / 1_000_000f) else null
    val parts = listOfNotNull(lang, layout, rate)
    return if (parts.isEmpty()) t.label else parts.joinToString(", ")
}

/** TiviMate-style "Audio tracks" card: Off + every embedded audio track, radio = what is playing. */
@Composable
internal fun AudioTracksPanel(tracks: List<TrackInfo>, focusIndex: Int) {
    val k = rememberUiScale()
    val listState = rememberLazyListState()
    LaunchedEffect(focusIndex) { listState.followTarget((focusIndex - 4).coerceAtLeast(0), jump = false) }
    val anySelected = tracks.any { it.isSelected }
    val rows = listOf<TrackInfo?>(null) + tracks          // null = Off
    Column(
        modifier = Modifier
            .padding(top = (48 * k).dp, bottom = (48 * k).dp, end = (48 * k).dp)
            .fillMaxHeight()
            .width((640 * k).dp)
            .clip(RoundedCornerShape((24 * k).dp))
            .background(TvPanelBg)
            .padding(horizontal = (16 * k).dp, vertical = (24 * k).dp)
    ) {
        Text(
            "Audio tracks",
            color = Color.White,
            fontSize = (40 * k).sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(start = (32 * k).dp, top = (8 * k).dp, bottom = (28 * k).dp)
        )
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            itemsIndexed(rows) { i, t ->
                val focused = i == focusIndex
                val checked = if (t == null) !anySelected else t.isSelected
                val fg = if (focused) Color.Black else Color.White
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = (3 * k).dp)
                        .clip(RoundedCornerShape((10 * k).dp))
                        .background(if (focused) TvSelected else Color.Transparent)
                        .padding(horizontal = (32 * k).dp, vertical = (22 * k).dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        if (checked) Icons.Default.RadioButtonChecked else Icons.Default.RadioButtonUnchecked,
                        contentDescription = null,
                        tint = fg.copy(alpha = if (checked) 1f else 0.7f),
                        modifier = Modifier.size((40 * k).dp)
                    )
                    Spacer(Modifier.width((28 * k).dp))
                    Text(
                        if (t == null) "Off" else audioTrackText(t),
                        color = fg,
                        fontSize = (28 * k).sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsPanel(settingsIndex: Int) {
    val k = rememberUiScale()
    Column(
        modifier = Modifier
            .fillMaxHeight()
            .width((720 * k).dp)
            .background(TvPanelBg)
            .padding(horizontal = (16 * k).dp, vertical = (24 * k).dp)
    ) {
        Text(
            "Settings",
            color = Color.White,
            fontSize = (40 * k).sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(start = (32 * k).dp, top = (8 * k).dp, bottom = (28 * k).dp)
        )
        SETTINGS_ITEMS.forEachIndexed { i, (title, subtitle) ->
            val sel = i == settingsIndex
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = (3 * k).dp)
                    .clip(RoundedCornerShape((10 * k).dp))
                    .background(if (sel) TvSelected else Color.Transparent)
                    .padding(horizontal = (32 * k).dp, vertical = (16 * k).dp)
            ) {
                Text(title, color = if (sel) Color.Black else Color.White, fontSize = (28 * k).sp)
                Text(
                    subtitle,
                    color = (if (sel) Color.Black else Color.White).copy(alpha = 0.6f),
                    fontSize = (20 * k).sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/** TiviMate-style playlist list: every added playlist, then "Add playlist" / "Manage playlists". */
@Composable
private fun PlaylistsPanel(playlists: List<PlaylistEntity>, selectedIndex: Int) {
    val k = rememberUiScale()
    Column(
        modifier = Modifier
            .fillMaxHeight()
            .width((720 * k).dp)
            .background(TvPanelBg)
            .padding(horizontal = (16 * k).dp, vertical = (24 * k).dp)
    ) {
        Text(
            "Playlists",
            color = Color.White,
            fontSize = (40 * k).sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(start = (32 * k).dp, top = (8 * k).dp, bottom = (24 * k).dp)
        )
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            itemsIndexed(playlists) { i, pl ->
                val sel = i == selectedIndex
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = (3 * k).dp)
                        .clip(RoundedCornerShape((10 * k).dp))
                        .background(if (sel) TvSelected else Color.Transparent)
                        .padding(horizontal = (32 * k).dp, vertical = (16 * k).dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = if (sel) Color.Black else Color(0xFF2196F3),
                        modifier = Modifier.size((44 * k).dp)
                    )
                    Spacer(Modifier.width((24 * k).dp))
                    Column {
                        Text(
                            pl.name,
                            color = if (sel) Color.Black else Color.White,
                            fontSize = (28 * k).sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            "Channels: ${pl.channelCount}",
                            color = (if (sel) Color.Black else Color.White).copy(alpha = 0.65f),
                            fontSize = (22 * k).sp
                        )
                    }
                }
            }
            item {
                val sel = selectedIndex == playlists.size
                Text(
                    "Add playlist",
                    color = if (sel) Color.Black else Color.White,
                    fontSize = (28 * k).sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = (3 * k).dp)
                        .clip(RoundedCornerShape((10 * k).dp))
                        .background(if (sel) TvSelected else Color.Transparent)
                        .padding(horizontal = (32 * k).dp, vertical = (24 * k).dp)
                )
            }
            item {
                val sel = selectedIndex == playlists.size + 1
                Text(
                    "Manage playlists",
                    color = if (sel) Color.Black else Color.White,
                    fontSize = (28 * k).sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = (3 * k).dp)
                        .clip(RoundedCornerShape((10 * k).dp))
                        .background(if (sel) TvSelected else Color.Transparent)
                        .padding(horizontal = (32 * k).dp, vertical = (24 * k).dp)
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
    onOpenEpgSettings: () -> Unit,
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
                                    else -> onOpenEpgSettings()
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
        StartupEmptyState(
            onAddPlaylist = onAddPlaylist,
            onOpenSettings = { showSettings = true; settingsIndex = 0 },
            selected = if (showSettings) -1 else selected,
            compact = false
        )
        if (showSettings) {
            Box(Modifier.align(Alignment.CenterEnd)) { SettingsPanel(settingsIndex) }
        }
    }
}
