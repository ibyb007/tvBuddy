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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.local.entity.ChannelEntity
import com.example.player.AudioBoostProfile
import com.example.player.StreamInfo
import com.example.player.TrackInfo
import com.example.player.VideoQuality
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class VodPanel { NONE, INFO, MENU, AUDIO }

private val PanelBg = Color(0xE6101418)
private val Selected = Color(0xFFF1F3F4)
private val ProgressBlue = Color(0xFF42A5F5)

private const val SEEK_STEP_MS = 10_000L

/** "1:49:04" for long movies, "00:14" for short positions. */
private fun clock(ms: Long): String {
    val total = (ms.coerceAtLeast(0) / 1000)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(Locale.US, h, m, s) else "%02d:%02d".format(Locale.US, m, s)
}

/** Seek step for the n-th repeat of a held LEFT / RIGHT key: slow at first, then faster and faster. */
private fun heldSeekStep(repeat: Int): Long = when {
    repeat < 15 -> 10_000L
    repeat < 35 -> 30_000L
    else -> 60_000L
}

private class VodMenuItem(val label: String, val icon: ImageVector, val action: () -> Unit)

/**
 * Player UI for movies (VOD), TiviMate style.
 *
 *  - OK            : info panel with seek bar (rewind / pause / forward / watch from start)
 *  - OK on panel   : press the focused button;  DOWN on the info panel opens the action bar
 *  - long OK       : action bar (TV, Audio boost, Video, Audio, Subtitles, Settings)
 *  - LEFT / RIGHT  : jump 10 s back / forward, hold the key to keep seeking faster and faster
 *  - BACK          : closes a panel; with no panel HomeScreen leaves the movie
 */
@Composable
fun TvVodPlayerOverlay(
    channel: ChannelEntity?,
    isPlaying: Boolean,
    isBuffering: Boolean,
    errorMessage: String?,
    streamInfo: StreamInfo,
    videoQuality: VideoQuality,
    audioBoostProfile: AudioBoostProfile,
    audioTracks: List<TrackInfo>,
    subtitleTracks: List<TrackInfo>,
    positionMs: () -> Long,
    durationMs: () -> Long,
    onSeekTo: (Long) -> Unit,
    onTogglePlayPause: () -> Unit,
    onRetry: () -> Unit,
    onWatchTv: () -> Unit,
    onCycleAudioBoost: () -> Unit,
    onOpenQualityPicker: () -> Unit,
    onSelectAudioTrack: (TrackInfo) -> Unit,
    onDisableAudio: () -> Unit,
    onOpenSubtitles: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val k = rememberUiScale()
    var panel by remember { mutableStateOf(VodPanel.NONE) }
    var tick by remember { mutableLongStateOf(0L) }
    var infoFocus by remember { mutableIntStateOf(1) }      // -1 seek bar, 0 rewind, 1 play / pause, 2 forward, 3 watch from start
    var menuIndex by remember { mutableIntStateOf(0) }
    var audioIndex by remember { mutableIntStateOf(0) }
    var okLongHandled by remember { mutableStateOf(false) }

    // ---- position / duration, refreshed twice a second ----
    var pos by remember { mutableLongStateOf(0L) }
    var dur by remember { mutableLongStateOf(0L) }
    LaunchedEffect(channel?.id) {
        while (true) {
            pos = positionMs()
            dur = durationMs()
            delay(500)
        }
    }

    // ---- seeking: presses add up to a pending jump that is applied once the key rests ----
    var pending by remember { mutableLongStateOf(0L) }
    LaunchedEffect(pending) {
        if (pending != 0L) {
            delay(650)
            val limit = if (dur > 0) (dur - 1000).coerceAtLeast(0) else Long.MAX_VALUE
            val target = (pos + pending).coerceIn(0L, limit)
            onSeekTo(target)
            pos = target
            pending = 0L
        }
    }
    fun addSeek(delta: Long) {
        pending += delta
        if (panel == VodPanel.NONE) panel = VodPanel.INFO
    }

    // ---- focus ----
    val focusRequester = remember { FocusRequester() }
    var hasFocus by remember { mutableStateOf(false) }
    LaunchedEffect(panel, channel?.id) { try { focusRequester.requestFocus() } catch (_: Exception) {} }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            if (!hasFocus) try { focusRequester.requestFocus() } catch (_: Exception) {}
        }
    }

    // ---- auto hide ----
    LaunchedEffect(panel, tick) {
        when (panel) {
            VodPanel.INFO -> { delay(6000); panel = VodPanel.NONE }
            VodPanel.MENU -> { delay(10000); panel = VodPanel.NONE }
            else -> {}
        }
    }
    BackHandler(enabled = panel != VodPanel.NONE) {
        panel = if (panel == VodPanel.AUDIO) VodPanel.MENU else VodPanel.NONE
    }

    // ---- action bar: TV, Audio boost, Video, Audio, Subtitles, Settings ----
    fun menuItems(): List<VodMenuItem> {
        val audioSel = audioTracks.firstOrNull { it.isSelected }
        val audioLabel = when {
            audioTracks.isEmpty() -> "Audio"
            audioSel == null -> "Audio off"
            else -> audioTrackText(audioSel)
        }
        val subSel = subtitleTracks.firstOrNull { it.isSelected }
        val subLabel = subSel?.let { audioTrackText(it) } ?: "Off"
        val videoLabel = if (streamInfo.width > 0 && streamInfo.height > 0) "${streamInfo.width} × ${streamInfo.height}"
        else videoQuality.title.substringBefore(" ")
        return listOf(
            VodMenuItem("TV", Icons.Default.Tv) { panel = VodPanel.NONE; onWatchTv() },
            VodMenuItem("Audio boost: ${audioBoostProfile.shortTitle}", Icons.Default.GraphicEq) { onCycleAudioBoost() },
            VodMenuItem(videoLabel, Icons.Default.HighQuality) { panel = VodPanel.NONE; onOpenQualityPicker() },
            VodMenuItem(audioLabel, Icons.AutoMirrored.Filled.VolumeUp) {
                val sel = audioTracks.indexOfFirst { it.isSelected }
                audioIndex = if (sel >= 0) sel + 1 else 0
                panel = VodPanel.AUDIO
            },
            VodMenuItem(subLabel, Icons.Default.ClosedCaption) { panel = VodPanel.NONE; onOpenSubtitles() },
            VodMenuItem("Settings", Icons.Default.Settings) { panel = VodPanel.NONE; onOpenSettings() }
        )
    }

    fun onShortOk() {
        when (panel) {
            VodPanel.NONE -> if (errorMessage != null) onRetry() else { infoFocus = 1; panel = VodPanel.INFO }
            VodPanel.INFO -> when (infoFocus) {
                -1 -> onTogglePlayPause()                                  // on the seek bar OK pauses / resumes
                0 -> addSeek(-SEEK_STEP_MS)
                1 -> onTogglePlayPause()
                2 -> addSeek(SEEK_STEP_MS)
                else -> { pending = 0L; onSeekTo(0L); pos = 0L }          // watch from start
            }
            VodPanel.MENU -> menuItems().getOrNull(menuIndex)?.action?.invoke()
            VodPanel.AUDIO -> {
                if (audioIndex == 0) onDisableAudio() else audioTracks.getOrNull(audioIndex - 1)?.let(onSelectAudioTrack)
                panel = VodPanel.NONE
            }
        }
    }

    fun onLongOk() {
        if (panel == VodPanel.NONE || panel == VodPanel.INFO) { menuIndex = 0; panel = VodPanel.MENU }
    }

    fun onDirection(key: Key, repeat: Int): Boolean {
        val sign = if (key == Key.DirectionLeft) -1L else 1L
        when (panel) {
            VodPanel.NONE -> when (key) {
                Key.DirectionLeft, Key.DirectionRight -> {
                    if (repeat == 0) addSeek(sign * SEEK_STEP_MS)
                    else if (repeat % 5 == 0) addSeek(sign * heldSeekStep(repeat))
                }
                Key.DirectionDown -> if (repeat == 0) { infoFocus = 1; panel = VodPanel.INFO }
                Key.DirectionUp -> if (repeat == 0) { infoFocus = -1; panel = VodPanel.INFO }
                else -> return false
            }
            VodPanel.INFO -> when (key) {
                Key.DirectionLeft, Key.DirectionRight -> {
                    if (infoFocus == -1) {
                        // seek bar focused: LEFT / RIGHT scrub, a held key keeps seeking faster and faster
                        if (repeat == 0) addSeek(sign * SEEK_STEP_MS)
                        else if (repeat % 5 == 0) addSeek(sign * heldSeekStep(repeat))
                    } else if (repeat == 0) infoFocus = (infoFocus + sign.toInt()).coerceIn(0, 3)
                    else if (repeat % 5 == 0) addSeek(sign * heldSeekStep(repeat))     // held key = keep seeking
                }
                // UP focuses the seek bar, UP again hides the panel
                Key.DirectionUp -> if (repeat == 0) { if (infoFocus == -1) panel = VodPanel.NONE else infoFocus = -1 }
                Key.DirectionDown -> if (repeat == 0) {
                    if (infoFocus == -1) infoFocus = 1 else { menuIndex = 0; panel = VodPanel.MENU }
                }
                else -> return false
            }
            VodPanel.MENU -> {
                val n = menuItems().size
                when (key) {
                    Key.DirectionLeft -> if (n > 0) menuIndex = (menuIndex - 1 + n) % n
                    Key.DirectionRight -> if (n > 0) menuIndex = (menuIndex + 1) % n
                    Key.DirectionUp -> if (repeat == 0) panel = VodPanel.INFO
                    Key.DirectionDown -> {}
                    else -> return false
                }
            }
            VodPanel.AUDIO -> when (key) {
                Key.DirectionUp -> audioIndex = (audioIndex - 1).coerceAtLeast(0)
                Key.DirectionDown -> audioIndex = (audioIndex + 1).coerceAtMost(audioTracks.size)
                Key.DirectionLeft -> if (repeat == 0) panel = VodPanel.MENU
                Key.DirectionRight -> {}
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
                val key = e.key
                val isOk = key == Key.DirectionCenter || key == Key.Enter || key == Key.NumPadEnter
                when {
                    isOk -> when (e.type) {
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
                    e.type != KeyEventType.KeyDown -> false
                    key == Key.MediaPlayPause || key == Key.MediaPlay || key == Key.MediaPause -> { onTogglePlayPause(); true }
                    key == Key.MediaFastForward -> { addSeek(30_000L); true }
                    key == Key.MediaRewind -> { addSeek(-30_000L); true }
                    key == Key.DirectionUp || key == Key.DirectionDown || key == Key.DirectionLeft || key == Key.DirectionRight ->
                        onDirection(key, e.nativeKeyEvent.repeatCount)
                    else -> false
                }
            }
    ) {
        if (isBuffering) {
            CircularProgressIndicator(
                color = ProgressBlue,
                strokeWidth = 4.dp,
                modifier = Modifier.align(Alignment.Center).size((64 * k).dp)
            )
        }

        if (errorMessage != null && !isBuffering && panel == VodPanel.NONE) {
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .clip(RoundedCornerShape((16 * k).dp))
                    .background(Color(0xE61A1215))
                    .padding((36 * k).dp)
                    .fillMaxWidth(0.5f),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(Icons.Default.Warning, contentDescription = null, tint = Color(0xFFFCA5A5), modifier = Modifier.size((48 * k).dp))
                Spacer(Modifier.height((10 * k).dp))
                Text("Playback failed", color = Color.White, fontSize = (30 * k).sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height((8 * k).dp))
                Text(errorMessage, color = Color(0xFFFCA5A5), fontSize = (22 * k).sp, maxLines = 3, textAlign = TextAlign.Center)
                Spacer(Modifier.height((12 * k).dp))
                Text("Press OK to retry", color = Color.White.copy(alpha = 0.7f), fontSize = (22 * k).sp)
            }
        }

        // clock, top right (info panel and action bar)
        AnimatedVisibility(
            visible = panel == VodPanel.INFO || panel == VodPanel.MENU,
            enter = fadeIn(), exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopEnd)
        ) {
            Text(
                SimpleDateFormat("EEE, d MMM, h:mm a", Locale.getDefault()).format(Date()),
                color = Color.White,
                fontSize = (34 * k).sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(horizontal = (36 * k).dp, vertical = (14 * k).dp)
            )
        }

        // ---------- info panel with seek bar ----------
        AnimatedVisibility(
            visible = panel == VodPanel.INFO,
            enter = fadeIn(), exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f), Color.Black.copy(alpha = 0.92f))))
                    .padding(start = (80 * k).dp, end = (80 * k).dp, top = (120 * k).dp, bottom = (10 * k).dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        channel?.name.orEmpty(),
                        color = Color.White,
                        fontSize = (44 * k).sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy((10 * k).dp)) {
                        if (streamInfo.width > 0) Badge("${streamInfo.width}x${streamInfo.height}")
                        if (streamInfo.frameRate > 0f) Badge(fmtFps(streamInfo.frameRate))
                        audioTracks.firstOrNull { it.isSelected }?.channelCount?.let { ch ->
                            when (ch) { 1 -> "1.0"; 2 -> "2.0"; 6 -> "5.1"; 8 -> "7.1"; else -> null }
                        }?.let { Badge(it) }
                    }
                }
                Spacer(Modifier.height((18 * k).dp))

                // seek bar
                val shown = (pos + pending).coerceIn(0L, if (dur > 0) dur else Long.MAX_VALUE)
                val fraction = if (dur > 0) (shown.toFloat() / dur).coerceIn(0f, 1f) else 0f
                val barFocused = infoFocus == -1
                val barH = if (barFocused) 10f else 5f
                val thumb = if (barFocused) 26f else 14f
                Box(modifier = Modifier.fillMaxWidth().height((28 * k).dp), contentAlignment = Alignment.CenterStart) {
                    Box(Modifier.fillMaxWidth().height((barH * k).dp).clip(CircleShape).background(Color.White.copy(alpha = if (barFocused) 0.4f else 0.3f)))
                    Box(Modifier.fillMaxWidth(fraction).height((barH * k).dp).clip(CircleShape).background(ProgressBlue))
                    Box(Modifier.fillMaxWidth(fraction), contentAlignment = Alignment.CenterEnd) {
                        Box(Modifier.size((thumb * k).dp).clip(CircleShape).background(if (barFocused) Color.White else ProgressBlue))
                    }
                }
                Spacer(Modifier.height((8 * k).dp))

                // times (left), transport buttons (centre), watch from start (right)
                Box(modifier = Modifier.fillMaxWidth().height((84 * k).dp)) {
                    Row(modifier = Modifier.align(Alignment.CenterStart), verticalAlignment = Alignment.CenterVertically) {
                        Text(clock(shown), color = Color.White, fontSize = (30 * k).sp)
                        Text("  /  ${if (dur > 0) clock(dur) else "--:--"}", color = Color.White.copy(alpha = 0.6f), fontSize = (30 * k).sp)
                        if (pending != 0L) {
                            Spacer(Modifier.width((12 * k).dp))
                            Text(
                                "[${if (pending > 0) "+" else "-"}${clock(kotlin.math.abs(pending))}]",
                                color = Color.White.copy(alpha = 0.85f),
                                fontSize = (26 * k).sp
                            )
                        }
                    }
                    Row(modifier = Modifier.align(Alignment.Center), horizontalArrangement = Arrangement.spacedBy((24 * k).dp)) {
                        TransportButton(k, Icons.Default.FastRewind, infoFocus == 0)
                        TransportButton(k, if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, infoFocus == 1)
                        TransportButton(k, Icons.Default.FastForward, infoFocus == 2)
                    }
                    Box(modifier = Modifier.align(Alignment.CenterEnd)) {
                        TransportButton(k, Icons.Default.Replay, infoFocus == 3)
                    }
                }
                Icon(
                    Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.7f),
                    modifier = Modifier.align(Alignment.CenterHorizontally).size((40 * k).dp)
                )
            }
        }

        // ---------- action bar (long OK, or DOWN on the info panel) ----------
        AnimatedVisibility(
            visible = panel == VodPanel.MENU,
            enter = fadeIn(), exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            val items = menuItems()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.65f), Color.Black.copy(alpha = 0.9f))))
                    .padding(start = (24 * k).dp, end = (24 * k).dp, top = (90 * k).dp, bottom = (24 * k).dp),
                verticalAlignment = Alignment.Top
            ) {
                items.forEachIndexed { i, item ->
                    val selected = i == menuIndex
                    Column(
                        modifier = Modifier.weight(1f).padding(horizontal = (4 * k).dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            modifier = Modifier
                                .size((80 * k).dp)
                                .clip(CircleShape)
                                .background(if (selected) Selected else Color.White.copy(alpha = 0.14f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                item.icon,
                                contentDescription = item.label,
                                tint = if (selected) Color.Black else Color.White,
                                modifier = Modifier.size((42 * k).dp)
                            )
                        }
                        Spacer(Modifier.height((8 * k).dp))
                        Text(
                            item.label,
                            color = Color.White,
                            fontSize = (24 * k).sp,
                            lineHeight = (28 * k).sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                            textAlign = TextAlign.Center,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }

        // ---------- audio tracks ----------
        AnimatedVisibility(
            visible = panel == VodPanel.AUDIO,
            enter = fadeIn(), exit = fadeOut(),
            modifier = Modifier.align(Alignment.CenterEnd)
        ) {
            AudioTracksPanel(tracks = audioTracks, focusIndex = audioIndex)
        }
    }
}

@Composable
private fun TransportButton(k: Float, icon: ImageVector, focused: Boolean) {
    Box(
        modifier = Modifier
            .size((68 * k).dp)
            .clip(CircleShape)
            .background(if (focused) Selected else Color.Transparent),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (focused) Color.Black else Color.White.copy(alpha = 0.85f),
            modifier = Modifier.size((38 * k).dp)
        )
    }
}
