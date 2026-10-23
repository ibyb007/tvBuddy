package com.example.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.data.epg.EpgMatcher
import com.example.data.local.entity.ChannelEntity
import com.example.data.local.entity.ProgramEntity
import com.example.ui.TvGroupSection
import com.example.ui.theme.AccentCyan

private val PanelBg = Color(0xF2101418)
private val RowSelected = Color(0xFFF1F3F4)

enum class MobilePanelPage { CHANNELS, GROUPS }

/** What the phone currently lists: a playlist (null = all) and a category ("All", "★ Favorites" or a group title). */
data class MobileGroupSelection(val playlistId: Long?, val category: String)

private enum class GKind { HEADER, FAVORITES, ALL, GROUP }
private data class GRow(val kind: GKind, val playlistId: Long?, val title: String)

private fun buildGroupRows(sections: List<TvGroupSection>): List<GRow> {
    val out = ArrayList<GRow>()
    if (sections.size > 1) {
        out += GRow(GKind.HEADER, null, "All playlists")
        out += GRow(GKind.FAVORITES, null, "Favorites")
        out += GRow(GKind.ALL, null, "All channels")
    }
    for (s in sections) {
        if (sections.size > 1) out += GRow(GKind.HEADER, s.playlistId, s.name)
        out += GRow(GKind.FAVORITES, s.playlistId, "Favorites")
        out += GRow(GKind.ALL, s.playlistId, "All channels")
        s.groups.forEach { out += GRow(GKind.GROUP, s.playlistId, it) }
    }
    return out
}

/**
 * Phone version of the TV channel list (LEFT) and playlist-groups page (LEFT twice), shown over the fullscreen player.
 *
 *  - CHANNELS: channels of the current group, numbered by position in it, with what is on now. "Groups" opens GROUPS.
 *  - GROUPS:   playlists with Favorites / All channels / every group; picking one returns to CHANNELS for that group.
 *  - BACK: CHANNELS -> GROUPS -> closed. Tapping anywhere else on the player closes the panel (handled by the caller).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MobileChannelPanel(
    page: MobilePanelPage,
    onPageChange: (MobilePanelPage) -> Unit,
    onClose: () -> Unit,
    channels: List<ChannelEntity>,
    numbering: Map<Long, Int>,
    playingId: Long?,
    programs: List<ProgramEntity>,
    sections: List<TvGroupSection>,
    selection: MobileGroupSelection,
    onSelectChannel: (ChannelEntity) -> Unit,
    onSelectGroup: (playlistId: Long?, category: String) -> Unit,
    modifier: Modifier = Modifier
) {
    // BACK: channel list -> groups -> closed
    BackHandler {
        if (page == MobilePanelPage.CHANNELS) onPageChange(MobilePanelPage.GROUPS) else onClose()
    }

    val screenW = LocalConfiguration.current.screenWidthDp
    val panelW = minOf(330f, screenW * 0.82f).dp
    val multi = sections.size > 1
    val effectivePid = selection.playlistId ?: sections.singleOrNull()?.playlistId

    val title = when {
        selection.category == "All" -> sections.firstOrNull { it.playlistId == selection.playlistId }?.name
            ?.takeIf { multi } ?: "All channels"
        selection.category == "★ Favorites" -> "Favorites"
        else -> selection.category
    }

    Box(
        modifier = modifier
            .width(panelW)
            .fillMaxHeight()
            .background(PanelBg)
            // swallow taps so they don't reach the player behind (which would close the panel)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
    ) {
        Column(Modifier.fillMaxSize()) {
            // ---- header ----
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (page == MobilePanelPage.CHANNELS) title else "Playlists & groups",
                    color = Color.White,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Clip,
                    modifier = Modifier.weight(1f).basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 1000)
                )
                if (page == MobilePanelPage.CHANNELS) {
                    IconButton(onClick = { onPageChange(MobilePanelPage.GROUPS) }, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Default.FolderOpen, contentDescription = "Groups", tint = Color.White, modifier = Modifier.size(22.dp))
                    }
                }
                IconButton(onClick = onClose, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White, modifier = Modifier.size(22.dp))
                }
            }
            if (page == MobilePanelPage.CHANNELS) {
                Text(
                    "${channels.size} channels",
                    color = Color.White.copy(alpha = 0.55f),
                    fontSize = 11.sp,
                    modifier = Modifier.padding(start = 16.dp, bottom = 4.dp)
                )
            } else Spacer(Modifier.height(6.dp))

            if (page == MobilePanelPage.CHANNELS) {
                ChannelPage(channels, numbering, playingId, programs, onSelectChannel, selKey = title)
            } else {
                GroupsPage(sections, selection, effectivePid, multi, onSelectGroup) { onPageChange(MobilePanelPage.CHANNELS) }
            }
        }
    }
}

@Composable
private fun ChannelPage(
    channels: List<ChannelEntity>,
    numbering: Map<Long, Int>,
    playingId: Long?,
    programs: List<ProgramEntity>,
    onSelectChannel: (ChannelEntity) -> Unit,
    selKey: String
) {
    val playingIdx = channels.indexOfFirst { it.id == playingId }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (playingIdx - 2).coerceAtLeast(0))
    // jump to the playing channel when another group is shown
    LaunchedEffect(selKey) { listState.scrollToItem((channels.indexOfFirst { it.id == playingId } - 2).coerceAtLeast(0)) }

    if (channels.isEmpty()) {
        Text("No channels in this group", color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp, modifier = Modifier.padding(16.dp))
        return
    }
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
        itemsIndexed(channels, key = { _, c -> c.id }) { i, ch ->
            val sel = ch.id == playingId
            val fg = if (sel) Color.Black else Color.White
            val prog = remember(ch.id, programs) { EpgMatcher.currentProgramOf(ch, programs) }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 2.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (sel) RowSelected else Color.Transparent)
                    .clickable { onSelectChannel(ch) }
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(width = 44.dp, height = 32.dp), contentAlignment = Alignment.Center) {
                    if (!ch.logoUrl.isNullOrBlank()) {
                        AsyncImage(
                            model = ch.logoUrl,
                            contentDescription = ch.name,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Text(ch.name.take(2).uppercase(), color = fg, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "${numbering[ch.id] ?: (i + 1)}  ${ch.name}",
                        color = fg,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        prog?.title ?: ch.groupTitle,
                        color = fg.copy(alpha = 0.65f),
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (prog != null && prog.isLive) {
                        Spacer(Modifier.height(4.dp))
                        Box(Modifier.fillMaxWidth().height(2.dp).background(fg.copy(alpha = 0.25f))) {
                            Box(Modifier.fillMaxWidth(prog.progress).height(2.dp).background(fg))
                        }
                    }
                }
                if (ch.isFavorite) {
                    Spacer(Modifier.width(6.dp))
                    Icon(Icons.Default.Star, contentDescription = "Favorite", tint = if (sel) Color.Black else Color(0xFFFBBF24), modifier = Modifier.size(14.dp))
                }
                if (sel) {
                    Spacer(Modifier.width(4.dp))
                    Icon(Icons.Default.PlayArrow, contentDescription = "Playing", tint = fg, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

@Composable
private fun GroupsPage(
    sections: List<TvGroupSection>,
    selection: MobileGroupSelection,
    effectivePid: Long?,
    multi: Boolean,
    onSelectGroup: (Long?, String) -> Unit,
    onPicked: () -> Unit
) {
    val rows = remember(sections) { buildGroupRows(sections) }
    fun categoryOf(r: GRow) = when (r.kind) {
        GKind.FAVORITES -> "★ Favorites"
        GKind.GROUP -> r.title
        else -> "All"
    }
    fun isSelected(r: GRow): Boolean {
        if (r.kind == GKind.HEADER) return false
        val rowPid = if (multi) r.playlistId else sections.singleOrNull()?.playlistId
        return rowPid == effectivePid && categoryOf(r).equals(selection.category, ignoreCase = true)
    }
    val selIdx = rows.indexOfFirst(::isSelected)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (selIdx - 2).coerceAtLeast(0))

    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
        itemsIndexed(rows) { _, r ->
            if (r.kind == GKind.HEADER) {
                Text(
                    r.title.uppercase(),
                    color = AccentCyan,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().padding(start = 18.dp, end = 12.dp, top = 12.dp, bottom = 4.dp)
                )
            } else {
                val sel = isSelected(r)
                val fg = if (sel) Color.Black else Color.White
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 1.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (sel) RowSelected else Color.Transparent)
                        .clickable {
                            // a single playlist needs no playlist filter
                            onSelectGroup(if (multi) r.playlistId else null, categoryOf(r))
                            onPicked()
                        }
                        .padding(horizontal = 10.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        if (sel) Icons.Default.RadioButtonChecked else Icons.Default.RadioButtonUnchecked,
                        contentDescription = null,
                        tint = fg.copy(alpha = if (sel) 1f else 0.7f),
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        r.title,
                        color = fg,
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
