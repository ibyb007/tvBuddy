package com.example.ui.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.player.TrackInfo
import com.example.ui.components.audioTrackText

// Same look as the TV "Video tracks" / "Audio tracks" panels, sized for a phone.
private val PanelBg = Color(0xF0263238)

/**
 * Audio / subtitle track picker: a compact TV-style panel with radio rows and an "Off" row on top.
 * Audio: pass [isAudio] + [onDisableAudio]. Subtitles: pass [isSubtitles] + [onDisableSubtitles].
 */
@Composable
fun TrackSelectionDialog(
    title: String,
    tracks: List<TrackInfo>,
    isSubtitles: Boolean = false,
    isAudio: Boolean = false,
    onSelectTrack: (TrackInfo) -> Unit,
    onDisableSubtitles: () -> Unit = {},
    onDisableAudio: (() -> Unit)? = null,
    onDismiss: () -> Unit
) {
    val maxH = (LocalConfiguration.current.screenHeightDp * 0.75f).dp
    val anySelected = tracks.any { it.isSelected }
    val showOff = isSubtitles || (isAudio && onDisableAudio != null)
    // null = Off
    val rows: List<TrackInfo?> = if (showOff) listOf<TrackInfo?>(null) + tracks else tracks

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .width(280.dp)
                .heightIn(max = maxH)
                .clip(RoundedCornerShape(14.dp))
                .background(PanelBg)
                .padding(horizontal = 8.dp, vertical = 10.dp)
        ) {
            Text(
                title,
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(start = 10.dp, top = 2.dp, bottom = 6.dp)
            )
            if (rows.isEmpty()) {
                Text(
                    "No additional tracks found in this stream.",
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 12.dp)
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    itemsIndexed(rows) { _, t ->
                        val checked = if (t == null) !anySelected else t.isSelected
                        val label = when {
                            t == null -> "Off"
                            isAudio -> audioTrackText(t)
                            else -> t.label
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp)
                                .heightIn(min = 42.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .clickable {
                                    when {
                                        t != null -> onSelectTrack(t)
                                        isSubtitles -> onDisableSubtitles()
                                        else -> onDisableAudio?.invoke()
                                    }
                                    onDismiss()
                                }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                if (checked) Icons.Filled.RadioButtonChecked else Icons.Filled.RadioButtonUnchecked,
                                contentDescription = if (checked) "Selected" else null,
                                tint = Color.White.copy(alpha = if (checked) 1f else 0.7f),
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(
                                label,
                                color = Color.White,
                                fontSize = 14.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}
