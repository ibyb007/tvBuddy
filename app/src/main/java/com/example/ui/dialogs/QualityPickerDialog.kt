package com.example.ui.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.Icon
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.player.VideoTrackOption
import kotlinx.coroutines.delay
import java.util.Locale

private val PanelBg = Color(0xF0263238)
private val RowFocus = Color(0xFFE6E6E8)

/**
 * TiviMate-style "Video tracks" panel: every rendition of the stream ("1920 × 1080, 4.57 Mbps"),
 * lowest first, plus Auto. OK selects, BACK closes.
 */
@Composable
fun QualityPickerDialog(
    videoTracks: List<VideoTrackOption>,
    onSelectTrack: (VideoTrackOption?) -> Unit,
    onDismiss: () -> Unit,
    isTvMode: Boolean = false
) {
    val k = LocalConfiguration.current.screenHeightDp / 1080f
    val tv = isTvMode

    // null = Auto (adaptive)
    val entries: List<VideoTrackOption?> = listOf(null) + videoTracks
    val autoSelected = videoTracks.none { it.isSelected }
    val selectedIndex = entries.indexOfFirst { if (it == null) autoSelected else it.isSelected }.coerceAtLeast(0)

    val listState = rememberLazyListState()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        listState.scrollToItem((selectedIndex - 2).coerceAtLeast(0))
        delay(100)
        try { focus.requestFocus() } catch (_: Exception) {}
    }

    val shape = RoundedCornerShape(if (tv) (24 * k).dp else 16.dp)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(if (tv) (48 * k).dp else 12.dp),
            contentAlignment = if (tv) Alignment.TopEnd else Alignment.Center
        ) {
            Column(
                modifier = (if (tv) Modifier.width((640 * k).dp).fillMaxHeight()
                else Modifier.fillMaxWidth().heightIn(max = 520.dp))
                    .clip(shape)
                    .background(PanelBg)
                    .padding(horizontal = if (tv) (16 * k).dp else 12.dp, vertical = if (tv) (24 * k).dp else 12.dp)
            ) {
                Text(
                    "Video tracks",
                    color = Color.White,
                    fontSize = if (tv) (40 * k).sp else 20.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(
                        start = if (tv) (32 * k).dp else 12.dp,
                        top = if (tv) (8 * k).dp else 4.dp,
                        bottom = if (tv) (24 * k).dp else 8.dp
                    )
                )
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    itemsIndexed(entries) { index, option ->
                        val chosen = index == selectedIndex
                        TrackRow(
                            label = option?.let { trackLabel(it) } ?: "Auto",
                            chosen = chosen,
                            k = k,
                            tv = tv,
                            modifier = if (index == selectedIndex) Modifier.focusRequester(focus) else Modifier
                        ) {
                            onSelectTrack(option)
                            onDismiss()
                        }
                    }
                }
            }
        }
    }
}

private fun trackLabel(t: VideoTrackOption): String =
    if (t.bitrate > 0) "${t.width} × ${t.height}, ${"%.2f".format(Locale.US, t.bitrate / 1_000_000f)} Mbps"
    else "${t.width} × ${t.height}"

@Composable
private fun TrackRow(
    label: String,
    chosen: Boolean,
    k: Float,
    tv: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val fg = if (focused) Color.Black else Color.White
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = if (tv) (3 * k).dp else 2.dp)
            .height(if (tv) (84 * k).dp else 48.dp)
            .clip(RoundedCornerShape(if (tv) (10 * k).dp else 10.dp))
            .background(if (focused) RowFocus else Color.Transparent)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(horizontal = if (tv) (32 * k).dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            if (chosen) Icons.Filled.RadioButtonChecked else Icons.Filled.RadioButtonUnchecked,
            contentDescription = if (chosen) "Selected" else null,
            tint = fg.copy(alpha = if (chosen) 1f else 0.7f),
            modifier = Modifier.size(if (tv) (44 * k).dp else 22.dp)
        )
        Spacer(Modifier.width(if (tv) (32 * k).dp else 12.dp))
        Text(
            label,
            color = fg,
            fontSize = if (tv) (28 * k).sp else 15.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
