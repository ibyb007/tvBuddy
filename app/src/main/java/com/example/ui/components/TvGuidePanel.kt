package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.data.local.entity.ChannelEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Guide geometry on the 1080p design grid (scaled by rememberUiScale()).
private const val CHANNEL_COL_PX = 543f      // channel number + logo + name column
private const val HALF_HOUR_PX = 320f        // width of 30 minutes on the time axis
private const val ROW_PX = 78f
private const val HEADER_PX = 90f

/** Minutes of schedule that fit to the right of the channel column on a 16:9 screen. */
const val GUIDE_VISIBLE_MS = 129 * 60_000L

private val GuideBlue = Color(0xFF3B9BFF)
private val GuideSelected = Color(0xFFF1F3F4)

/**
 * TiviMate-style TV guide: time axis on top, one row per channel, the focused row expands into a
 * detail block. Channels without EPG data show hour-long "No information" cells.
 */
@Composable
fun TvGuidePanel(
    channels: List<ChannelEntity>,
    selectedRow: Int,
    cursorMs: Long,
    originMs: Long,
    now: Long,
    playingId: Long?,
    slotsFor: (ChannelEntity) -> List<EpgSlot>,
    modifier: Modifier = Modifier
) {
    val k = rememberUiScale()
    val dpPerMs = (HALF_HOUR_PX * k) / HALF_HOUR_MS
    val listState = rememberLazyListState()
    LaunchedEffect(selectedRow) {
        // keep the focused channel as the 5th row, like TiviMate
        listState.animateScrollToItem((selectedRow - 4).coerceAtLeast(0))
    }

    Box(modifier = modifier.fillMaxSize().background(Color(0xFF101418).copy(alpha = 0.5f))) {
        Column(Modifier.fillMaxSize()) {
            // ----- header: date/time + 30 min ticks -----
            Box(Modifier.fillMaxWidth().height((HEADER_PX * k).dp)) {
                Text(
                    SimpleDateFormat("EEE, d MMM, ", Locale.getDefault()).format(Date(now)) +
                        SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(now)).lowercase(Locale.getDefault()),
                    color = Color(0xFFCFE0FF),
                    fontSize = (30 * k).sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    modifier = Modifier.align(Alignment.CenterStart).padding(start = (32 * k).dp)
                )
                for (i in 0..4) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .offset(x = ((CHANNEL_COL_PX + i * HALF_HOUR_PX - 70f) * k).dp)
                            .width((140 * k).dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            fmtClock(originMs + i * HALF_HOUR_MS),
                            color = Color.White.copy(alpha = 0.85f),
                            fontSize = (28 * k).sp,
                            maxLines = 1,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.25f)))

            // ----- channel rows -----
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                itemsIndexed(channels, key = { _, c -> c.id }) { i, ch ->
                    GuideRow(
                        channel = ch,
                        number = ch.channelNumber.takeIf { it > 0 } ?: (i + 1),
                        selected = i == selectedRow,
                        playing = ch.id == playingId,
                        slots = slotsFor(ch),
                        cursorMs = cursorMs,
                        originMs = originMs,
                        now = now,
                        k = k,
                        dpPerMs = dpPerMs
                    )
                }
            }
        }

        // ----- "now" marker -----
        if (now in originMs..(originMs + GUIDE_VISIBLE_MS)) {
            val x = (CHANNEL_COL_PX * k).dp + ((now - originMs) * dpPerMs).dp
            Box(
                Modifier
                    .offset(x = x - (1 * k).dp, y = ((HEADER_PX - 8) * k).dp)
                    .fillMaxHeight()
                    .width((2 * k).dp)
                    .background(GuideBlue.copy(alpha = 0.8f))
            )
            Box(
                Modifier
                    .offset(x = x - (6 * k).dp, y = ((HEADER_PX - 14) * k).dp)
                    .size((12 * k).dp)
                    .clip(CircleShape)
                    .background(GuideBlue)
            )
        }
    }
}

@Composable
private fun GuideRow(
    channel: ChannelEntity,
    number: Int,
    selected: Boolean,
    playing: Boolean,
    slots: List<EpgSlot>,
    cursorMs: Long,
    originMs: Long,
    now: Long,
    k: Float,
    dpPerMs: Float
) {
    val selIdx = if (selected) slots.indexAt(cursorMs) else -1
    val nameColor = if (selected) GuideBlue else Color.White

    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().height((ROW_PX * k).dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // channel column
            Row(
                modifier = Modifier.width((CHANNEL_COL_PX * k).dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.width((100 * k).dp), contentAlignment = Alignment.Center) {
                    Text(number.toString(), color = nameColor, fontSize = (30 * k).sp, maxLines = 1)
                }
                Spacer(Modifier.width((4 * k).dp))
                Box(Modifier.size(width = (76 * k).dp, height = (58 * k).dp), contentAlignment = Alignment.Center) {
                    if (!channel.logoUrl.isNullOrBlank()) {
                        AsyncImage(
                            model = channel.logoUrl,
                            contentDescription = channel.name,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
                Spacer(Modifier.width((34 * k).dp))
                Text(
                    channel.name,
                    color = nameColor,
                    fontSize = (30 * k).sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (playing) {
                    Icon(
                        Icons.Default.PlayArrow,
                        contentDescription = "Playing",
                        tint = GuideBlue,
                        modifier = Modifier.padding(horizontal = (12 * k).dp).size((40 * k).dp)
                    )
                }
            }

            // program cells
            Box(Modifier.weight(1f).fillMaxHeight().clipToBounds()) {
                val visibleEnd = originMs + GUIDE_VISIBLE_MS + HALF_HOUR_MS
                slots.forEachIndexed { idx, s ->
                    if (s.endMs <= originMs || s.startMs >= visibleEnd) return@forEachIndexed
                    val left = maxOf(s.startMs, originMs)
                    val isSel = idx == selIdx
                    val widthDp = ((s.endMs - left) * dpPerMs - 6 * k).coerceAtLeast(4f)
                    Box(
                        modifier = Modifier
                            .offset(x = ((left - originMs) * dpPerMs).dp)
                            .fillMaxHeight()
                            .width(widthDp.dp)
                            .padding(vertical = (3 * k).dp)
                            .clip(RoundedCornerShape((6 * k).dp))
                            .background(if (isSel) GuideSelected else Color.White.copy(alpha = 0.10f))
                            .padding(horizontal = (20 * k).dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        Text(
                            s.title,
                            color = when {
                                isSel -> Color.Black
                                s.program == null -> Color.White.copy(alpha = 0.5f)
                                else -> Color.White
                            },
                            fontSize = (30 * k).sp,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Clip
                        )
                    }
                }
            }
        }

        if (selected) {
            val slot = slots.getOrNull(selIdx)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height((210 * k).dp)
            ) {
                Box(Modifier.width((CHANNEL_COL_PX * k).dp).fillMaxHeight()) {
                    Box(
                        modifier = Modifier
                            .padding(start = (40 * k).dp, top = (12 * k).dp)
                            .size(width = (216 * k).dp, height = (122 * k).dp),
                        contentAlignment = Alignment.Center
                    ) {
                        if (!channel.logoUrl.isNullOrBlank()) {
                            AsyncImage(
                                model = channel.logoUrl,
                                contentDescription = channel.name,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Text(channel.name.take(2).uppercase(), color = Color.White, fontSize = (44 * k).sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                if (slot != null) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .background(Color.White.copy(alpha = 0.06f))
                            .padding(start = (21 * k).dp, end = (40 * k).dp, top = (8 * k).dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                slot.title,
                                color = Color.White,
                                fontSize = (34 * k).sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Icon(
                                if (channel.isFavorite) Icons.Default.Star else Icons.Outlined.StarOutline,
                                contentDescription = "Favorite",
                                tint = Color.White.copy(alpha = 0.85f),
                                modifier = Modifier.size((36 * k).dp)
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "${fmtClock(slot.startMs)} — ${fmtClock(slot.endMs)}",
                                    color = Color.White.copy(alpha = 0.7f),
                                    fontSize = (28 * k).sp,
                                    maxLines = 1
                                )
                                if (slot.contains(now)) {
                                    Spacer(Modifier.width((20 * k).dp))
                                    MiniProgress(slot.progress(now), k)
                                    Spacer(Modifier.width((20 * k).dp))
                                    Text(
                                        "${slot.minutesLeft(now)} min",
                                        color = Color.White.copy(alpha = 0.7f),
                                        fontSize = (28 * k).sp,
                                        maxLines = 1
                                    )
                                }
                            }
                            Text(
                                channel.groupTitle,
                                color = Color.White.copy(alpha = 0.85f),
                                fontSize = (28 * k).sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        slot.description?.let {
                            Spacer(Modifier.height((10 * k).dp))
                            Text(
                                it,
                                color = Color.White.copy(alpha = 0.75f),
                                fontSize = (28 * k).sp,
                                lineHeight = (32 * k).sp,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Thin progress pill used next to "10:00 — 11:00 am". */
@Composable
internal fun MiniProgress(progress: Float, k: Float, width: Float = 80f, color: Color = Color.White) {
    Box(
        Modifier
            .width((width * k).dp)
            .height((5 * k).dp)
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.3f))
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .background(color)
        )
    }
}
