package com.example.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.BookmarkRemove
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Web
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.data.local.entity.ChannelEntity
import com.example.data.local.entity.ChannelGroupEntity
import com.example.data.local.entity.PlaylistEntity
import com.example.data.model.ContentType
import com.example.data.model.VodInfo
import com.example.ui.theme.AccentCyan
import com.example.ui.theme.DarkBackground
import com.example.ui.theme.DarkSurface
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

private val MvPosterBg = Color(0xFF1A233A)
private val MvMuted = Color(0xFF94A3B8)

private enum class MvRowKind { MY_LIST, HISTORY, ALL, GROUP }

/** One entry of the groups strip: the same list as the TV page (My list, History, All movies / shows, then every visible group). */
private data class MvRow(val kind: MvRowKind, val title: String, val playlistId: Long = -1L)

/**
 * Movies / Shows page of the phone UI.
 *
 *  - top: header with the SELECTED poster's title, meta line and synopsis (this is the slot the live player uses),
 *    hidden while a movie plays (the player takes that slot)
 *  - below it: the groups of the TV page as chips, then the posters of the selected group in a scrolling grid
 *  - tap a poster: select it (header shows its info); tap the selected poster again: opens its details page
 *    (Play / Resume / Play from beginning / external player / Trailer / My list / Remove from History)
 *  - long-press a poster: add to / remove from My list
 *
 * [detailId] is owned by the caller (BACK handling); while [playing] is non-null the details page stays hidden and comes
 * back when the movie is closed.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MobileVodSection(
    contentType: String,
    itemsFlow: Flow<List<ChannelEntity>>,
    historyFlow: Flow<List<ChannelEntity>>,
    groups: List<ChannelGroupEntity>,
    playlists: List<PlaylistEntity>,
    /** Playlist picked in the navbar's Playlists panel (null = all playlists). */
    playlistFilterId: Long?,
    searchQuery: String,
    detailId: Long?,
    onDetailChange: (Long?) -> Unit,
    /** The movie that is playing right now (null when nothing / live TV plays). */
    playing: ChannelEntity?,
    onPlay: (ChannelEntity, Long) -> Unit,
    onToggleMyList: (ChannelEntity) -> Unit,
    onOpenTrailer: (String) -> Unit,
    onOpenExternal: (ChannelEntity) -> Unit,
    onRemoveHistory: (ChannelEntity) -> Unit,
    resumeOf: (ChannelEntity) -> Long,
    loadInfo: suspend (ChannelEntity) -> VodInfo?,
    onOpenGroup: (playlistId: Long, groupTitle: String) -> Unit,
    onNeedMoreAll: () -> Unit,
    loading: Boolean,
    modifier: Modifier = Modifier,
    /** Space kept free at the bottom for the floating navbar. */
    bottomPadding: androidx.compose.ui.unit.Dp = 0.dp
) {
    val isMovies = contentType == ContentType.MOVIE
    val items by itemsFlow.collectAsState(initial = emptyList())
    val historyItems by historyFlow.collectAsState(initial = emptyList())

    // ---- what is shown: active playlists (optionally one), visible groups only ----
    val activeIds = remember(playlists, playlistFilterId) {
        playlists.filter { it.isActive && (playlistFilterId == null || it.id == playlistFilterId) }.map { it.id }.toSet()
    }
    val typeGroups = remember(groups, contentType, activeIds) {
        groups.filter { it.contentType == contentType && it.playlistId in activeIds }
    }
    val hiddenKeys = remember(typeGroups) {
        typeGroups.filter { !it.isVisible }.map { it.playlistId to it.groupTitle }.toSet()
    }
    val visibleItems = remember(items, activeIds, hiddenKeys) {
        items.filter { it.playlistId in activeIds && (it.playlistId to it.groupTitle) !in hiddenKeys }
    }
    val byGroup = remember(visibleItems) { visibleItems.groupBy { it.playlistId to it.groupTitle } }

    val rows = remember(typeGroups, playlists, activeIds, isMovies) {
        buildList {
            add(MvRow(MvRowKind.MY_LIST, "My list"))
            add(MvRow(MvRowKind.HISTORY, "History"))
            add(MvRow(MvRowKind.ALL, if (isMovies) "All movies" else "All shows"))
            for (pl in playlists.filter { it.id in activeIds }) {
                val own = typeGroups.filter { it.playlistId == pl.id && it.isVisible }
                val sorted = when (pl.groupsSorting) {
                    "By name" -> own.sortedBy { it.groupTitle.lowercase() }
                    "Manual" -> own.sortedBy { it.sortOrder }
                    else -> own.sortedBy { it.originalOrder }
                }
                sorted.forEach { add(MvRow(MvRowKind.GROUP, it.groupTitle, pl.id)) }
            }
        }
    }

    var groupIndex by remember(contentType) { mutableIntStateOf(2) }   // starts on "All movies" / "All shows"
    val safeGroup = groupIndex.coerceIn(0, (rows.size - 1).coerceAtLeast(0))
    val selectedRow = rows.getOrNull(safeGroup)

    val searching = searchQuery.isNotBlank()
    val gridItems = remember(selectedRow, visibleItems, byGroup, historyItems, activeIds, searching, searchQuery) {
        val sr = selectedRow
        val base = when {
            searching -> visibleItems                                    // a search looks through everything loaded
            sr == null -> visibleItems
            sr.kind == MvRowKind.MY_LIST -> visibleItems.filter { it.isFavorite }
            sr.kind == MvRowKind.HISTORY -> historyItems.filter { it.playlistId in activeIds }
            sr.kind == MvRowKind.GROUP -> byGroup[sr.playlistId to sr.title].orEmpty()
            else -> visibleItems
        }
        if (searching) base.filter { it.name.contains(searchQuery.trim(), ignoreCase = true) } else base
    }

    var selectedId by remember(contentType) { mutableStateOf<Long?>(null) }
    val selectedItem = remember(selectedId, items, historyItems) {
        selectedId?.let { id -> items.firstOrNull { it.id == id } ?: historyItems.firstOrNull { it.id == id } }
    }
    val detailItem = remember(detailId, items, historyItems) {
        detailId?.let { id -> items.firstOrNull { it.id == id } ?: historyItems.firstOrNull { it.id == id } }
    }
    val showDetails = detailItem != null && playing == null

    // movies / shows of a group come from the portal a moment after the group is picked
    val openGroup by rememberUpdatedState(onOpenGroup)
    val needMoreAll by rememberUpdatedState(onNeedMoreAll)
    val gridState = rememberLazyGridState()
    LaunchedEffect(selectedRow) {
        gridState.scrollToItem(0)
        val row = selectedRow ?: return@LaunchedEffect
        delay(250)
        when (row.kind) {
            MvRowKind.GROUP -> openGroup(row.playlistId, row.title)
            MvRowKind.ALL -> needMoreAll()
            MvRowKind.MY_LIST, MvRowKind.HISTORY -> {}
        }
    }
    LaunchedEffect(searchQuery) { gridState.scrollToItem(0) }
    // "All movies" scrolled towards its end asks for more
    LaunchedEffect(selectedRow, gridItems.size) {
        if (selectedRow?.kind != MvRowKind.ALL) return@LaunchedEffect
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .collect { last -> if (last >= gridItems.size - 24) needMoreAll() }
    }

    // plot / cast / backdrop of the selected poster (or of the opened details), asked from the server after the selection settles
    val infoItem = detailItem ?: selectedItem
    var info by remember { mutableStateOf<VodInfo?>(null) }
    LaunchedEffect(infoItem?.id) {
        info = null
        val item = infoItem ?: return@LaunchedEffect
        delay(300)
        info = loadInfo(item)
    }

    // Play was tapped: keep the details page responsive until the player really starts
    var playPending by remember { mutableStateOf(false) }
    LaunchedEffect(playPending) { if (playPending) { delay(15_000); playPending = false } }
    LaunchedEffect(playing?.id) { if (playing != null) playPending = false }

    Box(modifier = modifier.fillMaxSize().background(DarkBackground)) {
        Column(modifier = Modifier.fillMaxSize()) {
            // header = selected poster's info, in the place of the live player (hidden while a movie plays)
            if (playing == null) {
                MvHeader(
                    item = selectedItem,
                    info = info?.takeIf { selectedItem != null && infoItem?.id == selectedItem.id },
                    hint = (selectedRow?.title.orEmpty()) + if (gridItems.isNotEmpty()) "  •  ${gridItems.size}" else "",
                    modifier = Modifier.fillMaxWidth().height(240.dp)
                )
            }

            // groups: same list as the TV page
            LazyRow(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(horizontal = 12.dp)
            ) {
                items(rows.size, key = { i -> "${rows[i].kind}_${rows[i].playlistId}_${rows[i].title}" }) { i ->
                    val row = rows[i]
                    val isSelected = i == safeGroup && !searching
                    FilterChip(
                        selected = isSelected,
                        onClick = { groupIndex = i },
                        label = {
                            Text(
                                text = row.title,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                fontSize = 12.sp,
                                maxLines = 1
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = AccentCyan,
                            selectedLabelColor = Color.Black,
                            containerColor = DarkSurface,
                            labelColor = Color.White
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            enabled = true,
                            selected = isSelected,
                            borderColor = if (isSelected) AccentCyan else Color(0xFF334155)
                        )
                    )
                }
            }

            if (loading) {
                LinearProgressIndicator(
                    color = AccentCyan,
                    trackColor = Color.Transparent,
                    modifier = Modifier.fillMaxWidth().height(2.dp)
                )
            }

            if (gridItems.isEmpty()) {
                Box(modifier = Modifier.fillMaxWidth().weight(1f).padding(24.dp), contentAlignment = Alignment.Center) {
                    Text(
                        text = when {
                            searching -> if (loading) "Searching..." else "No matching titles"
                            selectedRow?.kind == MvRowKind.MY_LIST -> "Nothing in My list yet.\nLong-press a poster to add it."
                            selectedRow?.kind == MvRowKind.HISTORY -> "Nothing watched yet"
                            loading || items.isEmpty() -> "Loading..."
                            else -> "Nothing here"
                        },
                        color = MvMuted,
                        fontSize = 14.sp
                    )
                }
            } else {
                // every poster is the same size (3 columns on a phone); the whole grid scrolls
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 104.dp),
                    state = gridState,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = bottomPadding + 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(gridItems, key = { it.id }) { item ->
                        MvPoster(
                            item = item,
                            selected = item.id == selectedId,
                            onClick = {
                                when {
                                    // a movie is playing: a tap goes straight to the details of that poster
                                    playing != null -> { selectedId = item.id; onDetailChange(item.id) }
                                    item.id == selectedId -> onDetailChange(item.id)   // second tap: details page
                                    else -> selectedId = item.id                          // first tap: select
                                }
                            },
                            onLongClick = { onToggleMyList(item) }
                        )
                    }
                }
            }
        }

        // ---- details page (drawn over the grid, which keeps its scroll position underneath) ----
        if (showDetails && detailItem != null) {
            val resumeMs = resumeOf(detailItem)
            MvDetails(
                item = detailItem,
                info = info,
                isMovies = isMovies,
                resumeMs = resumeMs,
                inHistory = historyItems.any { it.id == detailItem.id },
                playPending = playPending,
                bottomPadding = bottomPadding,
                onBack = { onDetailChange(null) },
                onPlay = { start -> playPending = true; onPlay(detailItem, start) },
                onExternal = { onOpenExternal(detailItem) },
                onTrailer = { info?.trailer?.takeIf { it.isNotBlank() }?.let(onOpenTrailer) },
                onToggleMyList = { onToggleMyList(detailItem) },
                onRemoveHistory = { onRemoveHistory(detailItem) }
            )
        }
    }
}

/** Header in the player's place: backdrop, title, rating / year / duration / genre and the synopsis of the selected poster. */
@Composable
private fun MvHeader(item: ChannelEntity?, info: VodInfo?, hint: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier.background(Color(0xFF0B1220))) {
        if (item == null) {
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(Icons.Default.Movie, contentDescription = null, tint = Color(0xFF475569), modifier = Modifier.size(44.dp))
                Spacer(Modifier.height(10.dp))
                Text("Select a title", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                if (hint.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(hint, color = MvMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            return@Box
        }
        val backdrop = info?.backdropUrl.orEmpty().ifBlank { item.logoUrl.orEmpty() }
        if (backdrop.isNotBlank()) {
            AsyncImage(
                model = backdrop,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alignment = Alignment.TopCenter,
                modifier = Modifier.fillMaxSize()
            )
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.25f), 0.4f to Color.Black.copy(alpha = 0.7f), 1f to Color(0xFF0A0E17))
            )
        )
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.Bottom
        ) {
            Text(
                item.name,
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(4.dp))
            MvMetaRow(item, info)
            val plot = info?.plot.orEmpty()
            if (plot.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(plot, color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp, lineHeight = 16.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
            } else if (info == null) {
                Spacer(Modifier.height(6.dp))
                Text("Loading details...", color = MvMuted, fontSize = 12.sp)
            }
            Spacer(Modifier.height(4.dp))
            Text("Tap the poster again to open", color = AccentCyan.copy(alpha = 0.85f), fontSize = 10.sp)
        }
    }
}

/** Rating badge + "2019 • 1h 55m • Action". */
@Composable
private fun MvMetaRow(item: ChannelEntity, info: VodInfo?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        val rating = info?.rating?.takeIf { it.isNotBlank() } ?: item.rating
        if (!rating.isNullOrBlank()) {
            Text(
                rating,
                color = Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(Color(0xFF1E88E5)).padding(horizontal = 6.dp, vertical = 1.dp)
            )
            Spacer(Modifier.width(8.dp))
        }
        Text(
            info?.metaLine.orEmpty(),
            color = Color.White.copy(alpha = 0.8f),
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MvPoster(item: ChannelEntity, selected: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(8.dp))
                .background(MvPosterBg)
                .border(
                    width = if (selected) 2.5.dp else 0.dp,
                    color = if (selected) AccentCyan else Color.Transparent,
                    shape = RoundedCornerShape(8.dp)
                )
        ) {
            if (!item.logoUrl.isNullOrBlank()) {
                val ctx = LocalContext.current
                val request = remember(item.logoUrl) { ImageRequest.Builder(ctx).data(item.logoUrl).crossfade(150).build() }
                AsyncImage(
                    model = request,
                    contentDescription = item.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(
                    Icons.Default.Movie,
                    contentDescription = null,
                    tint = Color(0xFF475569),
                    modifier = Modifier.align(Alignment.Center).size(28.dp)
                )
            }
            if (!item.rating.isNullOrBlank()) {
                Text(
                    item.rating,
                    color = Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(5.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(0xFF1E88E5))
                        .padding(horizontal = 5.dp, vertical = 1.dp)
                )
            }
            if (item.isFavorite) {
                Text(
                    "★",
                    color = Color(0xFFFFD54F),
                    fontSize = 16.sp,
                    modifier = Modifier.align(Alignment.TopEnd).padding(5.dp)
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            item.name,
            color = if (selected) AccentCyan else Color.White.copy(alpha = 0.85f),
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** Details page: the TV page's backdrop, title, meta, synopsis and action buttons, laid out for a phone (scrolls). */
@Composable
private fun MvDetails(
    item: ChannelEntity,
    info: VodInfo?,
    isMovies: Boolean,
    resumeMs: Long,
    inHistory: Boolean,
    playPending: Boolean,
    bottomPadding: androidx.compose.ui.unit.Dp,
    onBack: () -> Unit,
    onPlay: (Long) -> Unit,
    onExternal: () -> Unit,
    onTrailer: () -> Unit,
    onToggleMyList: () -> Unit,
    onRemoveHistory: () -> Unit
) {
    val trailerId = info?.trailer.orEmpty()
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
            // swallow touches so nothing reaches the grid underneath
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {})
    ) {
        Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Box(modifier = Modifier.fillMaxWidth().height(230.dp)) {
                val backdrop = info?.backdropUrl.orEmpty().ifBlank { item.logoUrl.orEmpty() }
                if (backdrop.isNotBlank()) {
                    AsyncImage(
                        model = backdrop,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        alignment = Alignment.TopCenter,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.35f), 0.6f to Color.Black.copy(alpha = 0.55f), 1f to DarkBackground)
                    )
                )
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.padding(6.dp).size(38.dp).clip(RoundedCornerShape(19.dp)).background(Color.Black.copy(alpha = 0.45f))
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White, modifier = Modifier.size(20.dp))
                }
                Row(
                    modifier = Modifier.align(Alignment.BottomStart).padding(start = 14.dp, end = 14.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.Bottom
                ) {
                    Box(
                        modifier = Modifier
                            .width(84.dp)
                            .aspectRatio(2f / 3f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MvPosterBg)
                    ) {
                        if (!item.logoUrl.isNullOrBlank()) {
                            AsyncImage(
                                model = item.logoUrl,
                                contentDescription = item.name,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            item.name,
                            color = Color.White,
                            fontSize = 19.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(4.dp))
                        MvMetaRow(item, info)
                    }
                }
            }

            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                // primary: Play / Resume
                if (isMovies) {
                    MvButton(
                        label = if (resumeMs > 0) "Resume  ${mvResumeLabel(resumeMs)}" else "Play",
                        icon = Icons.Default.PlayArrow,
                        primary = true,
                        busy = playPending,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { if (!playPending) onPlay(resumeMs) }
                    )
                    Spacer(Modifier.height(8.dp))
                } else {
                    Text("Episodes of shows can't be played yet.", color = MvMuted, fontSize = 12.sp)
                    Spacer(Modifier.height(8.dp))
                }
                // secondary actions scroll sideways
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (isMovies && resumeMs > 0) MvButton("Play from beginning", Icons.Default.Replay) { if (!playPending) onPlay(0L) }
                    if (isMovies) MvButton("External player", Icons.Default.Web, onClick = onExternal)
                    if (trailerId.isNotBlank()) MvButton("Trailer", Icons.Default.Movie, onClick = onTrailer)
                    MvButton(
                        if (item.isFavorite) "Remove from My list" else "Add to My list",
                        if (item.isFavorite) Icons.Default.BookmarkRemove else Icons.Default.BookmarkAdd,
                        onClick = onToggleMyList
                    )
                    if (inHistory) MvButton("Remove from History", Icons.Default.Delete, onClick = onRemoveHistory)
                }

                // synopsis, cast, director
                if (info == null) {
                    Spacer(Modifier.height(16.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(color = AccentCyan, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Loading details...", color = MvMuted, fontSize = 12.sp)
                    }
                } else {
                    if (info.plot.isNotBlank()) {
                        Spacer(Modifier.height(16.dp))
                        Text("Synopsis", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(4.dp))
                        Text(info.plot, color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp, lineHeight = 18.sp)
                    }
                    if (info.cast.isNotBlank()) MvLabelled("Cast", info.cast)
                    if (info.director.isNotBlank()) MvLabelled("Director", info.director)
                }
                Spacer(Modifier.height(bottomPadding + 12.dp))
            }
        }
    }
}

@Composable
private fun MvLabelled(label: String, value: String) {
    Spacer(Modifier.height(12.dp))
    Text(label, color = MvMuted, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    Spacer(Modifier.height(2.dp))
    Text(value, color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp, lineHeight = 18.sp)
}

@Composable
private fun MvButton(
    label: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    busy: Boolean = false,
    onClick: () -> Unit
) {
    Row(
        modifier = modifier
            .height(if (primary) 46.dp else 38.dp)
            .clip(RoundedCornerShape(if (primary) 12.dp else 19.dp))
            .background(if (primary) AccentCyan else Color(0xFF1E293B))
            .clickable(onClick = onClick)
            .padding(horizontal = if (primary) 16.dp else 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        if (busy) {
            CircularProgressIndicator(color = Color.Black, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
        } else {
            Icon(icon, contentDescription = null, tint = if (primary) Color.Black else Color.White, modifier = Modifier.size(if (primary) 22.dp else 18.dp))
        }
        Spacer(Modifier.width(8.dp))
        Text(
            label,
            color = if (primary) Color.Black else Color.White,
            fontSize = if (primary) 15.sp else 12.sp,
            fontWeight = if (primary) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1,
            softWrap = false
        )
    }
}

private fun mvResumeLabel(ms: Long): String {
    val total = ms.coerceAtLeast(0L) / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) String.format(java.util.Locale.US, "%d:%02d:%02d", h, m, s) else String.format(java.util.Locale.US, "%02d:%02d", m, s)
}
