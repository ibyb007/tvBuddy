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
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.ui.draw.clipToBounds
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
import androidx.compose.runtime.rememberUpdatedState
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
import com.example.data.model.VodInfo
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.focus.onFocusChanged
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow

private val VodBg = Color(0xFF111418)
private val VodPanelBg = Color(0xFF181B1F)
private val VodPillFocused = Color(0xFFE8EAED)
private val VodPillApplied = Color(0xFF30343A)
private val VodPosterBg = Color(0xFF23272D)
/** Poster columns while the rail + groups are showing, and while they are slid out (more room, same poster size). */
private const val VOD_COLUMNS_NARROW = 6
private const val VOD_COLUMNS_WIDE = 8
private const val VOD_PANEL_PX = 500f

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
    /** Plot, cast, backdrop ... of a movie / show (null when the provider has none). */
    loadInfo: suspend (ChannelEntity) -> VodInfo? = { null },
    /** A group got the focus: movies / shows are fetched from the portal per group, when it is opened. */
    onOpenGroup: (playlistId: Long, groupTitle: String) -> Unit = { _, _ -> },
    /** "All movies" / "All shows" was opened or scrolled near its end: fetch the next part. */
    onNeedMoreAll: () -> Unit = {},
    /** True while the app is downloading a group / the "All" list. */
    loading: Boolean = false,
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

    // every visible group is listed, also while its items are still to be downloaded
    val rows = remember(typeGroups, playlists, isMovies) {
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
                sorted.forEach { add(VodRow(VodRowKind.GROUP, it.groupTitle, pl.id)) }
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

    // TiviMate behaviour: moving into the posters slides the nav rail + groups out to the left, moving back slides them in
    val panelsOut = level == VodLevel.GRID
    val columns = if (panelsOut) VOD_COLUMNS_WIDE else VOD_COLUMNS_NARROW
    val railWidth by animateDpAsState(if (panelsOut) 0.dp else (RAIL_COLLAPSED_PX * k).dp, tween(260), label = "vodRail")
    val groupsWidth by animateDpAsState(if (panelsOut) 0.dp else (VOD_PANEL_PX * k).dp, tween(260), label = "vodGroups")
    val railEntries = remember(playlists, isMovies) {
        buildRailEntries(
            hasMovies = isMovies || playlists.any { it.isActive && it.movieCount > 0 },
            hasShows = !isMovies || playlists.any { it.isActive && it.showCount > 0 }
        )
    }
    val detailItem = remember(detailId, items) { detailId?.let { id -> items.firstOrNull { it.id == id } } }

    // the grid restarts at its first poster whenever another group is focused
    val openGroup by rememberUpdatedState(onOpenGroup)
    val needMoreAll by rememberUpdatedState(onNeedMoreAll)
    LaunchedEffect(selectedRow) {
        gridIndex = 0
        // ... and the group's items come from the portal a moment after the focus settles (skipped while scrolling the list)
        val row = selectedRow ?: return@LaunchedEffect
        delay(250)
        when (row.kind) {
            VodRowKind.GROUP -> openGroup(row.playlistId, row.title)
            VodRowKind.ALL -> needMoreAll()
            VodRowKind.MY_LIST -> {}
        }
    }
    // scrolling "All movies" towards its end asks for more
    LaunchedEffect(selectedRow, level, safeGrid, gridItems.size) {
        if (selectedRow?.kind == VodRowKind.ALL && level == VodLevel.GRID && safeGrid >= gridItems.size - 36) needMoreAll()
    }

    BackHandler(enabled = true) {
        when {
            hideRow != null -> hideRow = null
            detailId != null -> detailId = null
            level == VodLevel.GRID -> { gridIndex = 0; level = VodLevel.GROUPS }
            else -> onBack()
        }
    }

    val focusRequester = remember { FocusRequester() }
    var hasFocus by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        // grab the key focus and keep it (another screen underneath must not take it back)
        while (true) {
            if (!hasFocus) try { focusRequester.requestFocus() } catch (_: Exception) {}
            delay(500)
        }
    }

    // details of the focused poster, asked from the server a moment after the focus settles
    val infoItem = gridItems.getOrNull(safeGrid)
    var info by remember { mutableStateOf<VodInfo?>(null) }
    LaunchedEffect(infoItem?.id) {
        info = null
        val item = infoItem ?: return@LaunchedEffect
        delay(300)
        info = loadInfo(item)
    }

    val listState = rememberLazyListState()
    val gridState = rememberLazyGridState()
    LaunchedEffect(safeGroup) {
        val first = listState.firstVisibleItemIndex
        val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: first
        if (safeGroup < first || safeGroup > last - 1) listState.scrollToItem((safeGroup - 3).coerceAtLeast(0))
    }
    LaunchedEffect(safeGrid, level) {
        if (level != VodLevel.GRID) {
            // back at the groups: the posters start from their first one again
            if (gridState.firstVisibleItemIndex != 0) gridState.scrollToItem(0)
            return@LaunchedEffect
        }
        val visible = gridState.layoutInfo.visibleItemsInfo
        val first = visible.firstOrNull()?.index ?: 0
        val last = visible.lastOrNull()?.index ?: 0
        if (safeGrid < first || safeGrid > last - columns) {
            gridState.scrollToItem((safeGrid - safeGrid % columns - columns).coerceAtLeast(0))
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
                Key.DirectionLeft -> if (safeGrid % columns == 0) { gridIndex = 0; level = VodLevel.GROUPS } else gridIndex = safeGrid - 1
                Key.DirectionRight -> gridIndex = (safeGrid + 1).coerceAtMost(gridItems.lastIndex)
                Key.DirectionUp -> if (safeGrid - columns >= 0) gridIndex = safeGrid - columns
                Key.DirectionDown -> gridIndex = (safeGrid + columns).coerceAtMost(gridItems.lastIndex)
                else -> {}
            }
        }
        return true
    }

    Box(
        modifier = modifier
            .background(VodBg)
            .focusRequester(focusRequester)
            .onFocusChanged { hasFocus = it.isFocused }
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
            // ---- nav rail (slides out with the groups) ----
            RailPanel(
                width = railWidth,
                k = k,
                expanded = false,
                focused = false,
                selectedIndex = 0,
                entries = railEntries,
                currentId = if (isMovies) RailId.MOVIES else RailId.SHOWS
            )

            // ---- groups list (slides out to the left while the posters are browsed) ----
            Box(
                modifier = Modifier
                    .width(groupsWidth)
                    .fillMaxHeight()
                    .clipToBounds()
                    .background(VodPanelBg)
            ) {
            Column(
                modifier = Modifier
                    .wrapContentWidth(Alignment.Start, unbounded = true)
                    .width((VOD_PANEL_PX * k).dp)
                    .fillMaxHeight()
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
            }

            // ---- info + poster grid ----
            Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                val backdrop = info?.backdropUrl.orEmpty().ifBlank { infoItem?.logoUrl.orEmpty() }
                if (backdrop.isNotBlank()) {
                    AsyncImage(
                        model = backdrop,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        alignment = Alignment.TopEnd,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                // dark fades (left and bottom) keep the text readable over the picture
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.horizontalGradient(0f to VodBg.copy(alpha = 0.96f), 0.6f to VodBg.copy(alpha = 0.78f), 1f to VodBg.copy(alpha = 0.35f))
                    )
                )
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.verticalGradient(0.35f to Color.Transparent, 0.62f to VodBg.copy(alpha = 0.92f), 1f to VodBg)
                    )
                )
                Column(modifier = Modifier.fillMaxSize()) {
                    InfoBlock(k, infoItem, info, modifier = Modifier.fillMaxWidth().height((430 * k).dp))
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = (40 * k).dp, end = (40 * k).dp, bottom = (10 * k).dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = selectedRow?.title.orEmpty(),
                            color = Color.White,
                            fontSize = (30 * k).sp,
                            fontWeight = FontWeight.Medium,
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
                                else if (loading || items.isEmpty()) "Loading..." else "Nothing here",
                                color = Color.White.copy(alpha = 0.6f),
                                fontSize = (28 * k).sp
                            )
                        }
                    } else {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(columns),
                            state = gridState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = (40 * k).dp, vertical = (6 * k).dp),
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

/** Title, rating, year / duration / genre, cast, director and plot of the focused poster. */
@Composable
private fun InfoBlock(k: Float, item: ChannelEntity?, info: VodInfo?, modifier: Modifier) {
    Column(modifier = modifier.padding(start = (40 * k).dp, end = (40 * k).dp, top = (36 * k).dp)) {
        if (item == null) return@Column
        Text(item.name, color = Color.White, fontSize = (46 * k).sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height((12 * k).dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            val rating = info?.rating?.takeIf { it.isNotBlank() } ?: item.rating
            if (!rating.isNullOrBlank()) {
                Text(
                    rating, color = Color.White, fontSize = (26 * k).sp,
                    modifier = Modifier.clip(RoundedCornerShape((4 * k).dp)).background(Color(0xFF5F6368)).padding(horizontal = (10 * k).dp, vertical = (2 * k).dp)
                )
                Spacer(Modifier.width((20 * k).dp))
            }
            Text(info?.metaLine.orEmpty(), color = Color.White.copy(alpha = 0.8f), fontSize = (28 * k).sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (info != null) {
            Spacer(Modifier.height((16 * k).dp))
            if (info.cast.isNotBlank()) LabelledLine(k, "Cast:", info.cast)
            if (info.director.isNotBlank()) LabelledLine(k, "Director:", info.director)
            if (info.plot.isNotBlank()) {
                Spacer(Modifier.height((12 * k).dp))
                Text(
                    info.plot, color = Color.White.copy(alpha = 0.85f), fontSize = (27 * k).sp,
                    maxLines = 5, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(0.62f)
                )
            }
        }
    }
}

@Composable
private fun LabelledLine(k: Float, label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(0.62f)) {
        Text(label, color = Color.White.copy(alpha = 0.55f), fontSize = (27 * k).sp, modifier = Modifier.width((150 * k).dp))
        Text(value, color = Color.White.copy(alpha = 0.85f), fontSize = (27 * k).sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
            if (!item.rating.isNullOrBlank()) {
                Text(
                    item.rating,
                    color = Color.White,
                    fontSize = (20 * k).sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding((8 * k).dp)
                        .clip(RoundedCornerShape((4 * k).dp))
                        .background(Color(0xFF1E88E5))
                        .padding(horizontal = (8 * k).dp, vertical = (2 * k).dp)
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
