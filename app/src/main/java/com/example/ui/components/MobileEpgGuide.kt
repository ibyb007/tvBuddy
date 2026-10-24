package com.example.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.data.epg.EpgMatcher
import com.example.data.local.entity.ChannelEntity
import com.example.data.local.entity.ProgramEntity
import com.example.ui.dialogs.ProgramDetailsDialog
import com.example.ui.theme.AccentCyan
import com.example.ui.theme.DarkBackground
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

private val CHANNEL_COL = 104.dp
private val HALF_HOUR_W = 110.dp
private val ROW_H = 64.dp
private val HEADER_H = 36.dp

/**
 * Phone version of the TV guide grid (the panel a single OK press opens in the TV player): time axis on top,
 * one row per channel, programme cells that scroll sideways together. Tap a channel to watch it, tap a
 * programme for its details.
 */
@Composable
fun MobileEpgGuide(
    channels: List<ChannelEntity>,
    programs: List<ProgramEntity>,
    playingId: Long?,
    onPlayChannel: (ChannelEntity) -> Unit,
    onDismiss: () -> Unit
) {
    BackHandler(onBack = onDismiss)
    val now by produceState(System.currentTimeMillis()) {
        while (true) { value = System.currentTimeMillis(); delay(15_000) }
    }
    val from = remember { floorToHour(System.currentTimeMillis()) - 3 * HOUR_MS }
    val to = remember { floorToHour(System.currentTimeMillis()) + 24 * HOUR_MS }
    val dpPerMs = HALF_HOUR_W.value / HALF_HOUR_MS
    val totalWidth = ((to - from) * dpPerMs).dp

    val byEpgId = remember(programs) {
        programs.groupBy { it.epgChannelId.lowercase() }.mapValues { (_, v) -> v.sortedBy { it.startTimeMs } }
    }
    val hScroll = rememberScrollState()
    val density = LocalDensity.current
    // open with "now" (minus half an hour) at the left edge of the grid
    LaunchedEffect(Unit) {
        val start = floorToHalfHour(System.currentTimeMillis()) - HALF_HOUR_MS
        hScroll.scrollTo(with(density) { (((start - from) * dpPerMs).dp).roundToPx() })
    }
    val listState = rememberLazyListState()
    LaunchedEffect(Unit) {
        val i = channels.indexOfFirst { it.id == playingId }
        if (i > 0) listState.scrollToItem(i)
    }
    var details by remember { mutableStateOf<Pair<ProgramEntity, String>?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
            .statusBarsPadding()
    ) {
        // ----- top bar -----
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onDismiss) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
            }
            Column(Modifier.weight(1f)) {
                Text("TV guide", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(
                    SimpleDateFormat("EEE, d MMM", Locale.getDefault()).format(Date(now)) + ", " + fmtClock(now),
                    color = Color(0xFFCFE0FF), fontSize = 12.sp
                )
            }
        }

        // ----- time axis -----
        Row(Modifier.fillMaxWidth().height(HEADER_H), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(CHANNEL_COL))
            Box(Modifier.weight(1f).fillMaxHeight().clipToBounds().horizontalScroll(hScroll)) {
                Box(Modifier.width(totalWidth).fillMaxHeight()) {
                    var t = from
                    while (t < to) {
                        Text(
                            fmtClock(t),
                            color = Color.White.copy(alpha = 0.8f),
                            fontSize = 11.sp,
                            maxLines = 1,
                            softWrap = false,
                            textAlign = TextAlign.Start,
                            modifier = Modifier
                                .offset(x = (((t - from) * dpPerMs) + 6).dp)
                                .align(Alignment.CenterStart)
                        )
                        t += HALF_HOUR_MS
                    }
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.2f)))

        // ----- channel rows -----
        Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                itemsIndexed(channels, key = { _, c -> c.id }) { i, ch ->
                    val slots = remember(ch.id, byEpgId, from, to) {
                        buildSlots(EpgMatcher.programsOf(ch, byEpgId), from, to)
                    }
                    Row(Modifier.fillMaxWidth().height(ROW_H)) {
                        // channel column
                        Row(
                            modifier = Modifier
                                .width(CHANNEL_COL)
                                .fillMaxHeight()
                                .clickable { onPlayChannel(ch) }
                                .padding(start = 6.dp, end = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                (i + 1).toString(),
                                color = if (ch.id == playingId) AccentCyan else Color.White.copy(alpha = 0.7f),
                                fontSize = 11.sp,
                                modifier = Modifier.width(24.dp)
                            )
                            if (!ch.logoUrl.isNullOrBlank()) {
                                AsyncImage(
                                    model = ch.logoUrl,
                                    contentDescription = ch.name,
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier.size(width = 44.dp, height = 36.dp)
                                )
                            } else {
                                Text(
                                    ch.name.take(3).uppercase(),
                                    color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                                    modifier = Modifier.width(44.dp), maxLines = 1
                                )
                            }
                            if (ch.id == playingId) {
                                Icon(Icons.Default.PlayArrow, contentDescription = "Playing", tint = AccentCyan, modifier = Modifier.size(16.dp))
                            }
                        }
                        // programme cells (scroll together with the time axis)
                        Box(Modifier.weight(1f).fillMaxHeight().clipToBounds().horizontalScroll(hScroll)) {
                            Box(Modifier.width(totalWidth).fillMaxHeight()) {
                                slots.forEach { s ->
                                    val isNow = s.contains(now)
                                    Box(
                                        modifier = Modifier
                                            .offset(x = ((s.startMs - from) * dpPerMs).dp)
                                            .width(((s.endMs - s.startMs) * dpPerMs - 3).coerceAtLeast(4f).dp)
                                            .fillMaxHeight()
                                            .padding(vertical = 3.dp)
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(if (isNow) AccentCyan.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.08f))
                                            .clickable {
                                                val p = s.program
                                                if (p != null) details = p to ch.name else onPlayChannel(ch)
                                            }
                                            .padding(horizontal = 8.dp),
                                        contentAlignment = Alignment.CenterStart
                                    ) {
                                        Column {
                                            Text(
                                                s.title,
                                                color = if (s.program == null) Color.White.copy(alpha = 0.45f) else Color.White,
                                                fontSize = 12.sp,
                                                maxLines = 1,
                                                softWrap = false,
                                                overflow = TextOverflow.Clip
                                            )
                                            if (s.program != null) {
                                                Text(
                                                    "${fmtClock(s.startMs)} — ${fmtClock(s.endMs)}",
                                                    color = Color.White.copy(alpha = 0.55f),
                                                    fontSize = 10.sp, maxLines = 1, softWrap = false,
                                                    overflow = TextOverflow.Clip
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                item { Spacer(Modifier.navigationBarsPadding().height(12.dp)) }
            }

            // "now" marker, aligned with the scrolling grid
            val nowX = with(density) { (((now - from) * dpPerMs).dp).roundToPx() - hScroll.value }
            if (nowX >= 0) {
                Box(
                    Modifier
                        .padding(start = CHANNEL_COL)
                        .fillMaxHeight()
                        .fillMaxWidth()
                        .clipToBounds()
                ) {
                    Box(
                        Modifier
                            .offset(x = with(density) { nowX.toDp() })
                            .fillMaxHeight()
                            .width(2.dp)
                            .background(Color(0xFF3B9BFF).copy(alpha = 0.8f))
                    )
                }
            }
        }
    }

    details?.let { (p, name) ->
        ProgramDetailsDialog(program = p, channelName = name, onDismiss = { details = null })
    }
}
