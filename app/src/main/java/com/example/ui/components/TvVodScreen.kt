package com.example.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.data.local.entity.ChannelEntity
import com.example.data.local.entity.ChannelGroupEntity
import com.example.data.local.entity.PlaylistEntity
import com.example.data.model.ContentType
import kotlinx.coroutines.flow.Flow

private val VodBg = Color(0xFF111418)
private val VodPanelBg = Color(0xFF181B1F)
private val VodPillFocused = Color(0xFFE8EAED)
private val VodPillApplied = Color(0xFF30343A)
private val VodPosterBg = Color(0xFF23272D)
private const val VOD_COLUMNS = 6

private enum class VodLevel { GROUPS, GRID }

private enum class VodRowKind { MY_LIST, ALL, GROUP }

private data class VodRow(val kind: VodRowKind, val title: String, val playlistId: Long = -1L)

/**
 * The Movies / Shows page (opened from the nav rail when a playlist has VOD): a groups list on the left
 * (My list, All movies, then the groups) and a poster grid on the right that follows the focused group.
 *
 *  - LEFT / RIGHT hand focus between the list and the grid, OK on a poster opens its details
 *  - long-press OK on a group asks to hide it (it can be shown again in the playlist settings)
 *  - BACK steps grid -> list -> back to the rail
 */
@Composable
fun TvVodScreen(
    contentType: String,
    itemsFlow: Flow<List<ChannelEntity>>,
    groups: List<ChannelGroupEntity>,
    playlists: List<PlaylistEntity>,
    onPlay: (ChannelEntity) -> Unit,
    onToggleMyList: (ChannelEntity) -> Unit,
    onHideGroup: (playlistId: Long, groupTitle: String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val k = rememberUiScale()
    val isMovies = contentType == ContentType.MOVIE
    val items by itemsFlow.collectAsState(initial = emptyList())

    // ---- what is shown: active playlists, visible groups only ----
    val activeIds = remember(playlists) { playlists.filter { it.isActive }.map { it.id }.toSet() }
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

    val rows = remember(typeGroups, playlists, byGroup, isMovies) {
        buildList {
            add(VodRow(VodRowKind.MY_LIST, "My list"))
            add(VodRow(VodRowKind.ALL, if (isMovies) "All movies" else "All shows"))
            for (pl in playlists.filter { it.isActive }) {
                val own = typeGroups.filter { it.playlistId == pl.id && it.isVisible }
                val sorted = when (pl.groupsSorting) {
                    "By name" -> own.sortedBy { it.groupTitle.lowercase() }
                    "Manual" -> own.sortedBy { it.sortOrder }
                    else -> own.sortedBy { it.originalOrder }
                }
                sorted.filter { byGroup.containsKey(pl.id to it.groupTitle) }
                    .forEach { add(VodRow(VodRowKind.GROUP, it.groupTitle, pl.id)) }
            }
        }
    }

    var level by remember { mutableStateOf(VodLevel.GROUPS) }
    var groupFocus by remember { mutableIntStateOf(1) }       // start on "All movies" / "All shows"
    var gridIndex by remember { mutableIntStateOf(0) }
    var hideRow by remember { mutableStateOf<VodRow?>(null) }
    var hideChoice by remember { mutableIntStateOf(1) }       // 0 Cancel, 1 Hide group
    var detailId by remember { mutableStateOf<Long?>(null) }
    var detailChoice by remember { mutableIntStateOf(0) }
    var okLongHandled by remember { mutableStateOf(false) }

    val safeGroup = groupFocus.coerceIn(0, (rows.size - 1).coerceAtLeast(0))
    val selectedRow = rows.getOrNull(safeGroup)
    val gridItems = remember(selectedRow, visibleItems, byGroup) {
        val sr = selectedRow
        when {
            sr == null -> visibleItems
            sr.kind == VodRowKind.MY_LIST -> visibleItems.filter { it.isFavorite }
            sr.kind == VodRowKind.GROUP -> byGroup[sr.playlistId to sr.title].orEmpty()
            else -> visibleItems
        }
    }
    val safeGrid = gridIndex.coerceIn(0, (gridItems.size - 1).coerceAtLeast(0))
    val detailItem = remember(detailId, items) { detailId?.let { id -> items.firstOrNull { it.id == id } } }

    // the grid restarts at its first poster whenever another group is focused
    LaunchedEffect(selectedRow) { gridIndex = 0 }

    BackHandler(enabled = true) {
        when {
            hideRow != null -> hideRow = null
            detailId != null -> detailId = null
            level == VodLevel.GRID -> level = VodLevel.GROUPS
            else -> onBack()
        }
    }

    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { try { focusRequester.requestFocus() } catch (_: Exception) {} }

    val listState = rememberLazyListState()
    val gridState = rememberLazyGridState()
    LaunchedEffect(safeGroup) {
        val first = listState.firstVisibleItemIndex
        val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: first
        if (safeGroup < first || safeGroup > last - 1) listState.scrollToItem((safeGroup - 3).coerceAtLeast(0))
    }
    LaunchedEffect(safeGrid, level) {
        if (level != VodLevel.GRID) return@LaunchedEffect
        val visible = gridState.layoutInfo.visibleItemsInfo
        val first = visible.firstOrNull()?.index ?: 0
        val last = visible.lastOrNull()?.index ?: 0
        if (safeGrid < first || safeGrid > last - VOD_COLUMNS) {
            gridState.scrollToItem((safeGrid - safeGrid % VOD_COLUMNS - VOD_COLUMNS).coerceAtLeast(0))
        }
    }

    fun openDetails(item: ChannelEntity) {
        detailId = item.id
        detailChoice = 0
    }

    fun onShortOk() {
        val hide = hideRow
        if (hide != null) {
            if (hideChoice == 1 && hide.kind == VodRowKind.GROUP) onHideGroup(hide.playlistId, hide.title)
            hideRow = null
            return
        }
        val detail = detailItem
        if (detail != null) {
            val playable = isMovies
            val choice = if (playable) detailChoice else 1
            if (choice == 0) onPlay(detail) else onToggleMyList(detail)
            return
        }
        when (level) {
            VodLevel.GROUPS -> if (gridItems.isNotEmpty()) { level = VodLevel.GRID }
            VodLevel.GRID -> gridItems.getOrNull(safeGrid)?.let { openDetails(it) }
        }
    }

    fun onLongOk() {
        if (hideRow != null || detailItem != null) return
        when (level) {
            VodLevel.GROUPS -> {
                val row = rows.getOrNull(safeGroup) ?: return
                if (row.kind == VodRowKind.GROUP) { hideChoice = 1; hideRow = row }
            }
            VodLevel.GRID -> gridItems.getOrNull(safeGrid)?.let(onToggleMyList)   // long OK: add to / remove from My list
        }
    }

    fun onDirection(key: Key): Boolean {
        if (hideRow != null) {
            when (key) {
                Key.DirectionLeft -> hideChoice = 0
                Key.DirectionRight -> hideChoice = 1
                else -> {}
            }
            return true
        }
        if (detailItem != null) {
            val max = if (isMovies) 1 else 0
            when (key) {
                Key.DirectionLeft -> detailChoice = (detailChoice - 1).coerceAtLeast(0)
                Key.DirectionRight -> detailChoice = (detailChoice + 1).coerceAtMost(max)
                else -> {}
            }
            return true
        }
        when (level) {
            VodLevel.GROUPS -> when (key) {
                Key.DirectionUp -> groupFocus = (safeGroup - 1).coerceAtLeast(0)
                Key.DirectionDown -> groupFocus = (safeGroup + 1).coerceAtMost(rows.lastIndex)
                Key.DirectionRight -> if (gridItems.isNotEmpty()) level = VodLevel.GRID
                else -> {}
            }
            VodLevel.GRID -> when (key) {
                Key.DirectionLeft -> if (safeGrid % VOD_COLUMNS == 0) level = VodLevel.GROUPS else gridIndex = safeGrid - 1
                Key.DirectionRight -> gridIndex = (safeGrid + 1).coerceAtMost(gridItems.lastIndex)
                Key.DirectionUp -> if (safeGrid - VOD_COLUMNS >= 0) gridIndex = safeGrid - VOD_COLUMNS
                Key.DirectionDown -> gridIndex = (safeGrid + VOD_COLUMNS).coerceAtMost(gridItems.lastIndex)
                else -> {}
            }
        }
        return true
    }

    Box(
        modifier = modifier
            .background(VodBg)
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { e ->
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
                    key == Key.DirectionUp || key == Key.DirectionDown ||
                        key == Key.DirectionLeft || key == Key.DirectionRight -> onDirection(key)
                    else -> false
                }
            }
    ) {
        Row(modifier = Modifier.fillMaxSize()) {
            // ---- groups list ----
            Column(
                modifier = Modifier
                    .width((500 * k).dp)
                    .fillMaxHeight()
                    .background(VodPanelBg)
            ) {
                Text(
                    text = if (isMovies) "Movies" else "Shows",
                    color = Color.White,
                    fontSize = (40 * k).sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(start = (40 * k).dp, top = (36 * k).dp, bottom = (12 * k).dp)
                )
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = (24 * k).dp)
                ) {
                    itemsIndexed(rows, key = { _, r -> "${r.kind}_${r.playlistId}_${r.title}" }) { i, row ->
                        val isFocus = level == VodLevel.GROUPS && i == safeGroup
                        val isApplied = !isFocus && i == safeGroup
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = (32 * k).dp, end = (24 * k).dp, top = (2 * k).dp, bottom = (2 * k).dp)
                                .height((74 * k).dp)
                                .clip(RoundedCornerShape((14 * k).dp))
                                .background(
                                    when {
                                        isFocus -> VodPillFocused
                                        isApplied -> VodPillApplied
                                        else -> Color.Transparent
                                    }
                                )
                                .padding(horizontal = (24 * k).dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                row.title,
                                color = if (isFocus) Color.Black else Color.White,
                                fontSize = (30 * k).sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            // ---- poster grid ----
            Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = (40 * k).dp, end = (40 * k).dp, top = (40 * k).dp, bottom = (16 * k).dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = selectedRow?.title.orEmpty(),
                        color = Color.White,
                        fontSize = (30 * k).sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (gridItems.isNotEmpty()) {
                        Text(
                            text = "${if (level == VodLevel.GRID) safeGrid + 1 else 1} / ${gridItems.size}",
                            color = Color.White.copy(alpha = 0.7f),
                            fontSize = (26 * k).sp
                        )
                    }
                }
                if (gridItems.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = if (selectedRow?.kind == VodRowKind.MY_LIST) "Nothing in My list yet. Long-press OK on a poster to add it."
                            else if (items.isEmpty()) "Loading..." else "Nothing here",
                            color = Color.White.copy(alpha = 0.6f),
                            fontSize = (28 * k).sp
                        )
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(VOD_COLUMNS),
                        state = gridState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = (40 * k).dp, vertical = (12 * k).dp),
                        horizontalArrangement = Arrangement.spacedBy((18 * k).dp),
                        verticalArrangement = Arrangement.spacedBy((18 * k).dp)
                    ) {
                        itemsIndexed(gridItems, key = { _, poster -> poster.id }) { i, item ->
                            PosterCell(k, item, focused = level == VodLevel.GRID && i == safeGrid)
                        }
                    }
                }
            }
        }

        // ---- details ----
        detailItem?.let { item ->
            VodDetails(k, item, isMovies, detailChoice, Modifier.fillMaxSize())
        }

        // ---- hide group? ----
        hideRow?.let {
            Box(
                modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    modifier = Modifier
                        .width((900 * k).dp)
                        .clip(RoundedCornerShape((16 * k).dp))
                        .background(Color(0xFF2B2F36))
                        .padding((40 * k).dp)
                ) {
                    Text("Hide group?", color = Color.White, fontSize = (38 * k).sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height((14 * k).dp))
                    Text(
                        "You can show the group again in the playlist settings",
                        color = Color.White.copy(alpha = 0.75f),
                        fontSize = (28 * k).sp
                    )
                    Spacer(Modifier.height((32 * k).dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy((16 * k).dp, Alignment.End)
                    ) {
                        DialogButton(k, "Cancel", hideChoice == 0)
                        DialogButton(k, "Hide group", hideChoice == 1)
                    }
                }
            }
        }
    }
}

@Composable
private fun DialogButton(k: Float, text: String, focused: Boolean) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape((12 * k).dp))
            .background(if (focused) VodPillFocused else Color.Transparent)
            .padding(horizontal = (28 * k).dp, vertical = (14 * k).dp)
    ) {
        Text(
            text,
            color = if (focused) Color.Black else Color.White,
            fontSize = (28 * k).sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun PosterCell(k: Float, item: ChannelEntity, focused: Boolean) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape((8 * k).dp))
                .background(VodPosterBg)
                .border(
                    width = if (focused) (4 * k).dp else 0.dp,
                    color = if (focused) Color.White else Color.Transparent,
                    shape = RoundedCornerShape((8 * k).dp)
                )
        ) {
            if (!item.logoUrl.isNullOrBlank()) {
                AsyncImage(
                    model = item.logoUrl,
                    contentDescription = item.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
            if (item.isFavorite) {
                Text(
                    "★",
                    color = Color(0xFFFFD54F),
                    fontSize = (30 * k).sp,
                    modifier = Modifier.align(Alignment.TopEnd).padding((8 * k).dp)
                )
            }
        }
        Spacer(Modifier.height((8 * k).dp))
        Text(
            item.name,
            color = if (focused) Color.White else Color.White.copy(alpha = 0.75f),
            fontSize = (22 * k).sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun VodDetails(k: Float, item: ChannelEntity, isMovies: Boolean, choice: Int, modifier: Modifier) {
    Box(modifier = modifier.background(VodBg)) {
        Row(modifier = Modifier.fillMaxSize().padding((64 * k).dp), horizontalArrangement = Arrangement.spacedBy((48 * k).dp)) {
            Box(
                modifier = Modifier
                    .width((420 * k).dp)
                    .aspectRatio(2f / 3f)
                    .clip(RoundedCornerShape((12 * k).dp))
                    .background(VodPosterBg)
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
            Column(modifier = Modifier.weight(1f)) {
                Text(item.name, color = Color.White, fontSize = (48 * k).sp, fontWeight = FontWeight.Medium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height((12 * k).dp))
                Text(item.groupTitle, color = Color.White.copy(alpha = 0.7f), fontSize = (28 * k).sp)
                Spacer(Modifier.height((40 * k).dp))
                Row(horizontalArrangement = Arrangement.spacedBy((16 * k).dp)) {
                    if (isMovies) DialogButton(k, "Play", choice == 0)
                    DialogButton(k, if (item.isFavorite) "Remove from My list" else "Add to My list", choice == 1 || !isMovies)
                }
                if (!isMovies) {
                    Spacer(Modifier.height((24 * k).dp))
                    Text(
                        "Episodes of shows can't be played yet.",
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = (24 * k).sp
                    )
                }
            }
        }
    }
}
