package com.example.ui.components

import android.os.SystemClock
import androidx.compose.foundation.basicMarquee
import androidx.compose.ui.platform.LocalDensity
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import coil.request.ImageRequest
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.BookmarkRemove
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Web
import androidx.compose.material3.Icon
import androidx.compose.ui.graphics.vector.ImageVector
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
/**
 * The poster grid has ONE fixed layout (full screen width, always this many columns). The rail + groups
 * panels never resize it: they slide in over its left part and push it to the right (the part that
 * leaves the screen is simply cut off), exactly like TiviMate. So posters keep their size and the
 * images are never re-laid-out / re-requested while the panels move.
 */
private const val VOD_COLUMNS = 8
private const val VOD_PANEL_PX = 500f
private const val RAIL_EXPANDED_VOD_PX = 440f

/** RAIL = expanded nav rail (Movies / Shows highlighted), GROUPS = groups list, GRID = posters. */
private enum class VodLevel { RAIL, GROUPS, GRID }

private enum class VodRowKind { MY_LIST, HISTORY, ALL, GROUP }

private enum class DetailAction { PLAY, RESUME, FROM_START, EXTERNAL, TRAILER, MY_LIST, REMOVE_HISTORY }

private data class VodRow(val kind: VodRowKind, val title: String, val playlistId: Long = -1L)

/**
 * The Movies / Shows page (opened from the nav rail when a playlist has VOD): a groups list on the left
 * (My list, All movies, then the groups) and a poster grid on the right that follows the focused group.
 *
 *  - LEFT / RIGHT hand focus between the expanded rail, the list and the grid, OK on a poster opens its details
 *  - long-press OK on a group asks to hide it (it can be shown again in the playlist settings)
 *  - BACK steps grid -> list -> expanded rail (still on this Movies / Shows page) -> "press back again to exit"
 */
@Composable
internal fun TvVodScreen(
    contentType: String,
    itemsFlow: Flow<List<ChannelEntity>>,
    groups: List<ChannelGroupEntity>,
    playlists: List<PlaylistEntity>,
    /** Plays the movie, starting at the given position (0 = from the beginning). */
    onPlay: (ChannelEntity, Long) -> Unit,
    onToggleMyList: (ChannelEntity) -> Unit,
    onHideGroup: (playlistId: Long, groupTitle: String) -> Unit,
    /** Movies / shows that were watched (newest first): the "History" group. */
    historyFlow: Flow<List<ChannelEntity>> = kotlinx.coroutines.flow.flowOf(emptyList()),
    /** Where a movie was left (0 = not started / finished). */
    resumeOf: (ChannelEntity) -> Long = { 0L },
    onRemoveHistory: (ChannelEntity) -> Unit = {},
    onOpenTrailer: (String) -> Unit = {},
    onOpenExternal: (ChannelEntity) -> Unit = {},
    /** Opens straight on this item's details (coming back from its playback). */
    initialDetailId: Long? = null,
    onBack: () -> Unit,
    /** Plot, cast, backdrop ... of a movie / show (null when the provider has none). */
    loadInfo: suspend (ChannelEntity) -> VodInfo? = { null },
    /** A group got the focus: movies / shows are fetched from the portal per group, when it is opened. */
    onOpenGroup: (playlistId: Long, groupTitle: String) -> Unit = { _, _ -> },
    /** "All movies" / "All shows" was opened or scrolled near its end: fetch the next part. */
    onNeedMoreAll: () -> Unit = {},
    /** True while the app is downloading a group / the "All" list. */
    loading: Boolean = false,
    /** A rail entry other than the current page was chosen (TV, Search, Favorites, Settings or the other VOD page). */
    onRailSelect: (RailId) -> Unit = {},
    /** Leave the app (second BACK on the expanded rail). */
    onExit: () -> Unit = {},
    /** True while a dialog (search / settings) is open above: this page must not grab the key focus back. */
    focusSuspended: Boolean = false,
    modifier: Modifier = Modifier
) {
    val k = rememberUiScale()
    val context = LocalContext.current
    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    val isMovies = contentType == ContentType.MOVIE
    val items by itemsFlow.collectAsState(initial = emptyList())
    val historyItems by historyFlow.collectAsState(initial = emptyList())

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
            add(VodRow(VodRowKind.HISTORY, "History"))
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

    val currentRailId = if (isMovies) RailId.MOVIES else RailId.SHOWS
    val railEntries = remember(playlists, isMovies) {
        buildRailEntries(
            hasMovies = isMovies || playlists.any { it.isActive && it.movieCount > 0 },
            hasShows = !isMovies || playlists.any { it.isActive && it.showCount > 0 }
        )
    }
    var level by remember { mutableStateOf(VodLevel.GROUPS) }
    var railIndex by remember { mutableIntStateOf(railEntries.indexOfFirst { it.id == currentRailId }.coerceAtLeast(0)) }
    var lastBackAt by remember { mutableLongStateOf(0L) }
    var groupFocus by remember { mutableIntStateOf(2) }       // start on "All movies" / "All shows"
    var gridIndex by remember { mutableIntStateOf(0) }
    var hideRow by remember { mutableStateOf<VodRow?>(null) }
    var hideChoice by remember { mutableIntStateOf(1) }       // 0 Cancel, 1 Hide group
    var detailId by remember { mutableStateOf(initialDetailId) }
    var detailChoice by remember { mutableIntStateOf(0) }
    var okLongHandled by remember { mutableStateOf(false) }

    val safeGroup = groupFocus.coerceIn(0, (rows.size - 1).coerceAtLeast(0))
    val selectedRow = rows.getOrNull(safeGroup)
    val gridItems = remember(selectedRow, visibleItems, byGroup, historyItems) {
        val sr = selectedRow
        when {
            sr == null -> visibleItems
            sr.kind == VodRowKind.MY_LIST -> visibleItems.filter { it.isFavorite }
            sr.kind == VodRowKind.HISTORY -> historyItems.filter { it.playlistId in activeIds }
            sr.kind == VodRowKind.GROUP -> byGroup[sr.playlistId to sr.title].orEmpty()
            else -> visibleItems
        }
    }
    val safeGrid = gridIndex.coerceIn(0, (gridItems.size - 1).coerceAtLeast(0))

    // TiviMate behaviour: moving into the posters slides the nav rail + groups out to the left, moving back slides them in.
    // Only offsets are animated (read in the layout phase), the poster grid itself never changes size.
    val slide = animateFloatAsState(if (level == VodLevel.GRID) 1f else 0f, tween(300, easing = FastOutSlowInEasing), label = "vodSlide")
    val railWidth = animateDpAsState(
        if (level == VodLevel.RAIL) (RAIL_EXPANDED_VOD_PX * k).dp else (RAIL_COLLAPSED_PX * k).dp,
        tween(220), label = "vodRail"
    )
    val detailItem = remember(detailId, items, historyItems) {
        detailId?.let { id -> items.firstOrNull { it.id == id } ?: historyItems.firstOrNull { it.id == id } }
    }

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
            VodRowKind.MY_LIST, VodRowKind.HISTORY -> {}
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
            // groups -> the expanded rail, still on this Movies / Shows page (not the live TV page)
            level == VodLevel.GROUPS -> level = VodLevel.RAIL
            else -> {
                val t = SystemClock.elapsedRealtime()
                if (t - lastBackAt < 2000L) onExit()
                else {
                    lastBackAt = t
                    Toast.makeText(context, "Press Back again to exit", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    val focusRequester = remember { FocusRequester() }
    var hasFocus by remember { mutableStateOf(false) }
    val suspendedNow by rememberUpdatedState(focusSuspended)
    LaunchedEffect(Unit) {
        // grab the key focus and keep it (another screen underneath must not take it back)
        while (true) {
            if (!hasFocus && !suspendedNow) try { focusRequester.requestFocus() } catch (_: Exception) {}
            delay(500)
        }
    }

    // details of the focused poster, asked from the server a moment after the focus settles
    val infoItem = detailItem ?: gridItems.getOrNull(safeGrid)
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
    LaunchedEffect(safeGrid, level, gridItems.size) {
        if (level != VodLevel.GRID) {
            // back at the groups: the posters start from their first one again
            if (gridState.firstVisibleItemIndex != 0 || gridState.firstVisibleItemScrollOffset != 0) gridState.scrollToItem(0)
            return@LaunchedEffect
        }
        val info = gridState.layoutInfo
        val cell = info.visibleItemsInfo.firstOrNull { it.index == safeGrid }
        if (cell == null) {
            // far jump: place the row directly instead of flying over hundreds of posters
            gridState.scrollToItem(safeGrid - safeGrid % VOD_COLUMNS)
            return@LaunchedEffect
        }
        val top = cell.offset.y
        val viewStart = info.viewportStartOffset + info.beforeContentPadding
        val viewEnd = info.viewportEndOffset - info.afterContentPadding
        // keep the focused row fully in view; a row step is one short, eased scroll (a new key press restarts it)
        if (top < viewStart || top + cell.size.height > viewEnd) gridState.animateScrollBy((top - viewStart).toFloat(), tween(220, easing = FastOutSlowInEasing))
    }

    // buttons of the details page (TiviMate): Play / Resume + Play from beginning, external player, trailer, My list, Remove from History
    val resumeMs = detailItem?.let { resumeOf(it) } ?: 0L
    val inHistory = detailItem?.let { d -> historyItems.any { it.id == d.id } } ?: false
    val trailerId = info?.trailer.orEmpty()
    val detailActions = remember(detailItem?.id, isMovies, resumeMs > 0, inHistory, trailerId.isNotBlank()) {
        buildList {
            if (isMovies) {
                if (resumeMs > 0) { add(DetailAction.RESUME); add(DetailAction.FROM_START) } else add(DetailAction.PLAY)
                add(DetailAction.EXTERNAL)
            }
            if (trailerId.isNotBlank()) add(DetailAction.TRAILER)
            add(DetailAction.MY_LIST)
            if (inHistory) add(DetailAction.REMOVE_HISTORY)
        }
    }
    val safeChoice = detailChoice.coerceIn(0, detailActions.lastIndex)

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
            when (detailActions.getOrNull(safeChoice)) {
                DetailAction.PLAY, DetailAction.FROM_START -> onPlay(detail, 0L)
                DetailAction.RESUME -> onPlay(detail, resumeMs)
                DetailAction.EXTERNAL -> onOpenExternal(detail)
                DetailAction.TRAILER -> onOpenTrailer(trailerId)
                DetailAction.MY_LIST -> onToggleMyList(detail)
                DetailAction.REMOVE_HISTORY -> onRemoveHistory(detail)
                null -> {}
            }
            return
        }
        when (level) {
            VodLevel.RAIL -> {
                val id = railEntries.getOrNull(railIndex)?.id
                if (id == null || id == currentRailId) level = VodLevel.GROUPS else onRailSelect(id)
            }
            VodLevel.GROUPS -> if (gridItems.isNotEmpty()) { level = VodLevel.GRID }
            VodLevel.GRID -> gridItems.getOrNull(safeGrid)?.let { openDetails(it) }
        }
    }

    fun onLongOk() {
        if (hideRow != null || detailItem != null) return
        when (level) {
            VodLevel.RAIL -> {}
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
            when (key) {
                Key.DirectionLeft -> detailChoice = (safeChoice - 1).coerceAtLeast(0)
                Key.DirectionRight -> detailChoice = (safeChoice + 1).coerceAtMost(detailActions.lastIndex)
                else -> {}
            }
            return true
        }
        when (level) {
            VodLevel.RAIL -> when (key) {
                Key.DirectionUp -> railIndex = (railIndex - 1).coerceAtLeast(0)
                Key.DirectionDown -> railIndex = (railIndex + 1).coerceAtMost(railEntries.lastIndex)
                Key.DirectionRight -> level = VodLevel.GROUPS
                else -> {}
            }
            VodLevel.GROUPS -> when (key) {
                Key.DirectionUp -> groupFocus = (safeGroup - 1).coerceAtLeast(0)
                Key.DirectionDown -> groupFocus = (safeGroup + 1).coerceAtMost(rows.lastIndex)
                Key.DirectionRight -> if (gridItems.isNotEmpty()) level = VodLevel.GRID
                // LEFT from the groups: the nav rail expands with this page (Movies / Shows) selected
                Key.DirectionLeft -> {
                    railIndex = railEntries.indexOfFirst { it.id == currentRailId }.coerceAtLeast(0)
                    level = VodLevel.RAIL
                }
                else -> {}
            }
            VodLevel.GRID -> when (key) {
                Key.DirectionLeft -> if (safeGrid % VOD_COLUMNS == 0) { gridIndex = 0; level = VodLevel.GROUPS } else gridIndex = safeGrid - 1
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
        // rail + groups + posters sit in one row that is only translated: panels in = 0, panels out = -(their width)
        Row(
            modifier = Modifier
                .fillMaxSize()
                .offset { IntOffset(-((railWidth.value + (VOD_PANEL_PX * k).dp) * slide.value).roundToPx(), 0) }
        ) {
            // ---- nav rail (expands with LEFT from the groups) ----
            VodRail(railWidth, k, level, railIndex, railEntries, currentRailId)

            // ---- groups list ----
            Box(
                modifier = Modifier
                    .width((VOD_PANEL_PX * k).dp)
                    .fillMaxHeight()
                    .background(VodPanelBg)
            ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
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
            Box(modifier = Modifier.requiredWidth(screenWidthDp.dp).fillMaxHeight()) {
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
                                else if (selectedRow?.kind == VodRowKind.HISTORY) "Nothing watched yet"
                                else if (loading || items.isEmpty()) "Loading..." else "Nothing here",
                                color = Color.White.copy(alpha = 0.6f),
                                fontSize = (28 * k).sp
                            )
                        }
                    } else {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(VOD_COLUMNS),
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
            VodDetails(k, item, info, detailActions, safeChoice, Modifier.fillMaxSize())
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

/** The nav rail of the Movies / Shows page; its width animation is read here so only the rail recomposes per frame. */
@Composable
private fun VodRail(
    width: State<androidx.compose.ui.unit.Dp>,
    k: Float,
    level: VodLevel,
    railIndex: Int,
    entries: List<RailEntry>,
    currentId: RailId
) {
    RailPanel(
        width = width.value,
        k = k,
        expanded = level == VodLevel.RAIL,
        focused = level == VodLevel.RAIL,
        selectedIndex = railIndex,
        entries = entries,
        currentId = currentId
    )
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

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
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
                val ctx = LocalContext.current
                val request = remember(item.logoUrl) {
                    ImageRequest.Builder(ctx).data(item.logoUrl).crossfade(150).build()
                }
                AsyncImage(
                    model = request,
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
        // one line like TiviMate: cut with "..." normally, scrolls sideways while the poster has the focus
        Text(
            item.name,
            color = if (focused) Color.White else Color.White.copy(alpha = 0.75f),
            fontSize = (22 * k).sp,
            maxLines = 1,
            softWrap = false,
            overflow = if (focused) TextOverflow.Clip else TextOverflow.Ellipsis,
            modifier = if (focused) Modifier.basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 600, velocity = (60 * k).dp)
            else Modifier
        )
    }
}

/** Details page of a poster: the same backdrop, title and synopsis as the browse page, and the action buttons below. */
@Composable
private fun VodDetails(k: Float, item: ChannelEntity, info: VodInfo?, actions: List<DetailAction>, choice: Int, modifier: Modifier) {
    Box(modifier = modifier.background(VodBg)) {
        val backdrop = info?.backdropUrl.orEmpty().ifBlank { item.logoUrl.orEmpty() }
        if (backdrop.isNotBlank()) {
            AsyncImage(
                model = backdrop,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alignment = Alignment.TopEnd,
                modifier = Modifier.fillMaxSize()
            )
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(0f to VodBg.copy(alpha = 0.96f), 0.6f to VodBg.copy(alpha = 0.78f), 1f to VodBg.copy(alpha = 0.35f))
            )
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(0.45f to Color.Transparent, 0.75f to VodBg.copy(alpha = 0.92f), 1f to VodBg)
            )
        )
        InfoBlock(k, item, info, modifier = Modifier.fillMaxWidth().height((620 * k).dp).padding(start = (16 * k).dp))
        Row(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = (48 * k).dp, end = (24 * k).dp, bottom = (192 * k).dp)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy((24 * k).dp)
        ) {
            actions.forEachIndexed { i, action ->
                val (label, icon) = when (action) {
                    DetailAction.PLAY -> "Play" to Icons.Default.PlayArrow
                    DetailAction.RESUME -> "Resume" to Icons.Default.PlayArrow
                    DetailAction.FROM_START -> "Play from beginning" to Icons.Default.Replay
                    DetailAction.EXTERNAL -> "Open in external player" to Icons.Default.Web
                    DetailAction.TRAILER -> "Trailer" to Icons.Default.Movie
                    DetailAction.MY_LIST ->
                        (if (item.isFavorite) "Remove from My list" else "Add to My list") to
                            (if (item.isFavorite) Icons.Default.BookmarkRemove else Icons.Default.BookmarkAdd)
                    DetailAction.REMOVE_HISTORY -> "Remove from History" to Icons.Default.Delete
                }
                ActionButton(k, label, icon, i == choice)
            }
        }
        if (actions.none { it == DetailAction.PLAY || it == DetailAction.RESUME }) {
            Text(
                "Episodes of shows can't be played yet.",
                color = Color.White.copy(alpha = 0.6f),
                fontSize = (24 * k).sp,
                modifier = Modifier.align(Alignment.BottomStart).padding(start = (56 * k).dp, bottom = (130 * k).dp)
            )
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ActionButton(k: Float, text: String, icon: ImageVector, focused: Boolean) {
    val bring = remember { androidx.compose.foundation.relocation.BringIntoViewRequester() }
    LaunchedEffect(focused) { if (focused) bring.bringIntoView() }   // keeps the focused button on screen in the scrolling row
    Row(
        modifier = Modifier
            .bringIntoViewRequester(bring)
            .height((72 * k).dp)
            .clip(RoundedCornerShape((10 * k).dp))
            .background(if (focused) VodPillFocused else Color(0xFF2B2F36))
            .padding(horizontal = (28 * k).dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = if (focused) Color.Black else Color.White, modifier = Modifier.size((36 * k).dp))
        Spacer(Modifier.width((16 * k).dp))
        Text(
            text,
            color = if (focused) Color.Black else Color.White,
            fontSize = (30 * k).sp,
            maxLines = 1,
            softWrap = false
        )
    }
}
