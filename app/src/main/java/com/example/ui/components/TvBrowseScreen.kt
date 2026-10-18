package com.example.ui.components

import android.os.SystemClock
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.ui.draw.alpha
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
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
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.window.Dialog
import androidx.media3.exoplayer.ExoPlayer
import coil.compose.AsyncImage
import com.example.data.local.entity.ChannelEntity
import com.example.data.local.entity.ProgramEntity
import com.example.player.AspectMode
import com.example.ui.TvGroupKind
import com.example.ui.TvGroupSection
import com.example.ui.TvGroupSelection
import com.example.ui.dialogs.iptvTextFieldColors
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Which layer of the TiviMate-style "TV" page is in focus.
 *
 *  GUIDE  - preview + EPG grid only (what BACK from full-screen playback reveals)
 *  GROUPS - playlist / group panel slid in next to a slim icon rail
 *  RAIL   - the rail expanded to icons + labels (Search, TV, Favorites, Settings)
 */
enum class TvBrowseLevel { GUIDE, GROUPS, RAIL }

// ---- palette (taken from the TiviMate reference videos) ----
private val RailBg = Color(0xFF1C1F23)
private val GroupsBg = Color(0xFF171A1E)
private val ContentBg = Color(0xFF111418)
private val PillFocused = Color(0xFFE8EAED)
private val PillApplied = Color(0xFF30343A)
private val BrowseBlue = Color(0xFF3B9BFF)
private val DateBlue = Color(0xFF8DB9F5)
private val CellBg = Color(0xFF25282D)
private val CellFocused = Color(0xFF5B5F65)
private val OptionsBg = Color(0xFF1F2327)
private val OptionsHeaderBg = Color(0xFF2A2E33)

// ---- geometry on the 1080p design grid (scaled with rememberUiScale()) ----
private const val RAIL_COLLAPSED_PX = 112f
private const val RAIL_EXPANDED_PX = 440f
private const val GROUPS_PX = 500f
private const val CONTENT_PAD_PX = 32f
private const val CHANNEL_COL_END_PX = 543f      // x where the programme cells start (same as TvGuidePanel)
private const val BROWSE_HALF_HOUR_PX = 320f
private const val BROWSE_ROW_PX = 78f
private const val BROWSE_HEADER_PX = 78f
private const val PREVIEW_W_PX = 640f
private const val PREVIEW_H_PX = 360f
private const val PREVIEW_TOP_PX = 24f
private const val TOP_SECTION_PX = 393f           // preview block, up to the time axis

// ---------------------------------------------------------------------------------------------
// Group rows (flat model of the collapsible tree)
// ---------------------------------------------------------------------------------------------

private enum class RowKind { HEADER, FAVORITES, ALL, GROUP }

private data class GroupRow(
    val kind: RowKind,
    val playlistId: Long?,
    val playlistName: String,
    val title: String
) {
    fun toSelection(): TvGroupSelection? = when (kind) {
        RowKind.HEADER -> null
        RowKind.FAVORITES -> TvGroupSelection(playlistId, TvGroupKind.FAVORITES)
        RowKind.ALL -> TvGroupSelection(playlistId, TvGroupKind.ALL)
        RowKind.GROUP -> TvGroupSelection(playlistId, TvGroupKind.GROUP, title)
    }

    /**
     * What the channel list shows while this row has focus. A playlist name previews ALL of that
     * playlist's channels (hidden groups are already excluded by the view model's filter).
     */
    fun previewSelection(): TvGroupSelection? =
        if (kind == RowKind.HEADER) TvGroupSelection(playlistId, TvGroupKind.ALL) else toSelection()
}

private fun sectionKey(playlistId: Long?): Long = playlistId ?: -1L

private fun buildRows(sections: List<TvGroupSection>, collapsed: Set<Long>): List<GroupRow> {
    val out = ArrayList<GroupRow>()
    // "All playlists" only makes sense with more than one playlist
    if (sections.size > 1) {
        out += GroupRow(RowKind.HEADER, null, "All playlists", "All playlists")
        if (sectionKey(null) !in collapsed) {
            out += GroupRow(RowKind.FAVORITES, null, "All playlists", "Favorites")
            out += GroupRow(RowKind.ALL, null, "All playlists", "All channels")
        }
    }
    for (s in sections) {
        out += GroupRow(RowKind.HEADER, s.playlistId, s.name, s.name)
        if (sectionKey(s.playlistId) !in collapsed) {
            out += GroupRow(RowKind.FAVORITES, s.playlistId, s.name, "Favorites")
            out += GroupRow(RowKind.ALL, s.playlistId, s.name, "All channels")
            for (g in s.groups) out += GroupRow(RowKind.GROUP, s.playlistId, s.name, g)
        }
    }
    return out
}

/** Row that represents the applied selection (falls back sensibly when it is not listed). */
private fun indexOfSelection(rows: List<GroupRow>, sel: TvGroupSelection): Int {
    val exact = rows.indexOfFirst { r ->
        val rs = r.toSelection() ?: return@indexOfFirst false
        rs == sel
    }
    if (exact >= 0) return exact
    // "all playlists" with a single playlist -> that playlist's row of the same kind
    if (sel.playlistId == null && sel.kind != TvGroupKind.GROUP) {
        val byKind = rows.indexOfFirst { it.toSelection()?.kind == sel.kind }
        if (byKind >= 0) return byKind
    }
    return rows.indexOfFirst { it.kind != RowKind.HEADER }
}

private class OptionItem(val title: String, val subtitle: String?, val toggle: Boolean?)

@Composable
private fun produceBrowseNow() = produceState(System.currentTimeMillis()) {
    while (true) {
        value = System.currentTimeMillis()
        delay(15_000)
    }
}

// ---------------------------------------------------------------------------------------------
// Screen
// ---------------------------------------------------------------------------------------------

/**
 * TiviMate-style TV page for the Android TV mode:
 *
 *  - BACK from full-screen playback  -> preview window + EPG grid (GUIDE)
 *  - BACK again / LEFT from the grid -> playlist groups panel + slim rail (GROUPS)
 *  - BACK / LEFT again               -> rail expands with labels (RAIL)
 *  - BACK once more                  -> "press back again to exit"
 *
 * Moving over a group applies it straight away (the grid on the right updates live); OK / RIGHT
 * hands focus back to the grid, long-press OK on a group opens its options.
 */
@Composable
fun TvBrowseScreen(
    initialLevel: TvBrowseLevel,
    player: ExoPlayer,
    playingChannel: ChannelEntity?,
    channels: List<ChannelEntity>,
    sections: List<TvGroupSection>,
    selection: TvGroupSelection,
    allPrograms: List<ProgramEntity>,
    epgPrograms: List<ProgramEntity>,
    favoriteOnlyGroups: Set<String>,
    nameSortedGroups: Set<String>,
    searchQuery: String,
    onSelectGroup: (TvGroupSelection) -> Unit,
    onPlayChannel: (ChannelEntity) -> Unit,
    onToggleFavorite: (ChannelEntity) -> Unit,
    onToggleGroupFavoritesOnly: (Long, String) -> Unit,
    onToggleGroupNameSort: (Long, String) -> Unit,
    onHideGroup: (Long, String) -> Unit,
    onManageGroups: (Long) -> Unit,
    onOpenSearch: () -> Unit,
    onClearSearch: () -> Unit,
    onOpenSettings: () -> Unit,
    onExit: () -> Unit,
    /** Reports where the preview window sits (root coordinates) so the shared video surface can follow it. */
    onPreviewBounds: (Rect) -> Unit = {},
    /**
     * true when the page was opened from full-screen playback (LEFT, LEFT): the video keeps playing
     * full-screen behind a see-through page that shows the rail + groups + a plain channel list
     * (no preview window, no EPG grid) and BACK simply closes the page ([onDismiss]).
     */
    overlayMode: Boolean = false,
    onDismiss: () -> Unit = {},
    /** Lets the host raise this page above the video while the options panel is open. */
    onOptionsVisible: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val k = rememberUiScale()
    val context = LocalContext.current
    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    val now by produceBrowseNow()

    var level by remember { mutableStateOf(initialLevel) }
    // start on the applied group so the groups list opens already positioned (no scroll on open)
    var groupFocus by remember {
        mutableIntStateOf(indexOfSelection(buildRows(sections, emptySet()), selection).coerceAtLeast(0))
    }
    // true once the user has moved focus between groups; then the channel list shows channels from the
    // first one instead of jumping to the playing channel
    var browsedGroups by remember { mutableStateOf(false) }
    // starts on the playing channel so the list is already in place when the page opens
    var guideRow by remember {
        mutableIntStateOf(channels.indexOfFirst { it.id == playingChannel?.id }.coerceAtLeast(0))
    }
    var railIndex by remember { mutableIntStateOf(1) }                 // 0 Search, 1 TV, 2 Favorites, 3 Settings
    var optionsRow by remember { mutableStateOf<GroupRow?>(null) }
    var optionsIndex by remember { mutableIntStateOf(0) }
    var okLongHandled by remember { mutableStateOf(false) }
    var lastBackAt by remember { mutableLongStateOf(0L) }
    val collapsed = remember { mutableStateListOf<Long>() }

    // ---------------- group tree ----------------
    val rows = remember(sections, collapsed.toList()) { buildRows(sections, collapsed.toSet()) }
    val appliedIndex = remember(rows, selection) { indexOfSelection(rows, selection) }

    LaunchedEffect(level) {
        if (level == TvBrowseLevel.GROUPS) groupFocus = appliedIndex.coerceAtLeast(0)
        else browsedGroups = false
    }
    LaunchedEffect(optionsRow != null) { onOptionsVisible(optionsRow != null) }
    DisposableEffect(Unit) { onDispose { onOptionsVisible(false) } }
    // Live preview: the grid follows the focused group (debounced so held keys stay smooth)
    LaunchedEffect(groupFocus, level) {
        if (level != TvBrowseLevel.GROUPS) return@LaunchedEffect
        val sel = rows.getOrNull(groupFocus)?.previewSelection() ?: return@LaunchedEffect
        if (sel != selection) {
            delay(150)
            onSelectGroup(sel)
        } else if (browsedGroups) {
            // back on the group that is already applied: show it from its first channel
            guideRow = 0
        }
    }

    // ---------------- EPG ----------------
    val windowStart = remember { floorToHour(System.currentTimeMillis()) }
    val windowEnd = windowStart + 30 * HOUR_MS
    val byEpgId = remember(epgPrograms, allPrograms) {
        (if (epgPrograms.isNotEmpty()) epgPrograms else allPrograms)
            .groupBy { it.epgChannelId.lowercase() }
            .mapValues { (_, v) -> v.sortedBy { it.startTimeMs } }
    }
    fun programsOf(ch: ChannelEntity): List<ProgramEntity> =
        (if (ch.streamId.isNotBlank()) byEpgId[ch.streamId.lowercase()] else null)
            ?: byEpgId[ch.name.lowercase()]
            ?: emptyList()
    fun slotsFor(ch: ChannelEntity): List<EpgSlot> = buildSlots(programsOf(ch), windowStart, windowEnd)

    // ---------------- grid focus ----------------
    val channelIds = remember(channels) { channels.map { it.id } }
    var cursorMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var originMs by remember { mutableLongStateOf(windowStart) }
    LaunchedEffect(channelIds) {
        val playingId = playingChannel?.id
        val idx = if (playingId == null) -1 else channels.indexOfFirst { it.id == playingId }
        guideRow = when {
            browsedGroups && level == TvBrowseLevel.GROUPS -> 0     // moving over groups: start at the top
            idx >= 0 -> idx
            else -> 0
        }
        cursorMs = System.currentTimeMillis()
        originMs = windowStart
    }
    val safeRow = guideRow.coerceIn(0, (channels.size - 1).coerceAtLeast(0))
    val focusChannel = channels.getOrNull(safeRow)
    val focusSlots = focusChannel?.let { slotsFor(it) }.orEmpty()
    val focusSlot = focusSlots.getOrNull(focusSlots.indexAt(cursorMs))

    // ---------------- focus plumbing ----------------
    val focusRequester = remember { FocusRequester() }
    var hasFocus by remember { mutableStateOf(false) }
    LaunchedEffect(level, optionsRow) {
        try { focusRequester.requestFocus() } catch (_: Exception) {}
    }
    LaunchedEffect(Unit) {
        // take focus back after dialogs (search / settings) close
        while (true) {
            delay(1000)
            if (!hasFocus) {
                try { focusRequester.requestFocus() } catch (_: Exception) {}
            }
        }
    }

    // ---------------- back ----------------
    BackHandler(enabled = true) {
        when {
            optionsRow != null -> optionsRow = null
            searchQuery.isNotBlank() -> onClearSearch()
            overlayMode -> onDismiss()          // back to full-screen playback
            level == TvBrowseLevel.GUIDE -> level = TvBrowseLevel.GROUPS
            level == TvBrowseLevel.GROUPS -> level = TvBrowseLevel.RAIL
            else -> {
                val t = SystemClock.elapsedRealtime()
                if (t - lastBackAt < 2000L) {
                    onExit()
                } else {
                    lastBackAt = t
                    Toast.makeText(context, "Press Back again to exit", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // ---------------- actions ----------------
    fun optionItems(row: GroupRow): List<OptionItem> {
        val key = "${row.playlistId}_${row.title}"
        return listOf(
            OptionItem("Channels sorting", if (key in nameSortedGroups) "By name" else "By order in playlist", null),
            OptionItem("Show Favorites only", null, key in favoriteOnlyGroups),
            OptionItem("Hide group", null, null),
            OptionItem("Manage groups", null, null)
        )
    }

    fun moveCell(delta: Int): Boolean {
        val ch = channels.getOrNull(safeRow) ?: return false
        val slots = slotsFor(ch)
        val target = slots.getOrNull(slots.indexAt(cursorMs) + delta) ?: return false
        cursorMs = target.startMs
        if (target.startMs < originMs || target.endMs > originMs + GUIDE_VISIBLE_MS) {
            originMs = floorToHalfHour(target.startMs)
        }
        return true
    }

    fun moveRow(to: Int) {
        guideRow = to.coerceIn(0, (channels.size - 1).coerceAtLeast(0))
        cursorMs = maxOf(cursorMs, originMs)
    }

    fun onShortOk() {
        val opts = optionsRow
        if (opts != null) {
            val pid = opts.playlistId
            if (pid != null) {
                when (optionsIndex) {
                    0 -> onToggleGroupNameSort(pid, opts.title)
                    1 -> onToggleGroupFavoritesOnly(pid, opts.title)
                    2 -> { optionsRow = null; onHideGroup(pid, opts.title) }
                    else -> { optionsRow = null; onManageGroups(pid) }
                }
            }
            return
        }
        when (level) {
            TvBrowseLevel.GUIDE -> channels.getOrNull(safeRow)?.let(onPlayChannel)
            TvBrowseLevel.GROUPS -> {
                val row = rows.getOrNull(groupFocus) ?: return
                if (row.kind == RowKind.HEADER) {
                    val key = sectionKey(row.playlistId)
                    if (key in collapsed) collapsed.remove(key) else collapsed.add(key)
                } else {
                    row.toSelection()?.let(onSelectGroup)
                    level = TvBrowseLevel.GUIDE
                }
            }
            TvBrowseLevel.RAIL -> when (railIndex) {
                0 -> onOpenSearch()
                1 -> level = TvBrowseLevel.GUIDE
                2 -> { onSelectGroup(TvGroupSelection(null, TvGroupKind.FAVORITES)); level = TvBrowseLevel.GUIDE }
                else -> onOpenSettings()
            }
        }
    }

    fun onLongOk() {
        when (level) {
            TvBrowseLevel.GUIDE -> channels.getOrNull(safeRow)?.let(onToggleFavorite)
            TvBrowseLevel.GROUPS -> {
                if (overlayMode) return        // no group options menu on this page
                val row = rows.getOrNull(groupFocus) ?: return
                if (row.kind == RowKind.GROUP && optionsRow == null) {
                    optionsIndex = 0
                    optionsRow = row
                }
            }
            TvBrowseLevel.RAIL -> {}
        }
    }

    fun onDirection(key: Key, isRepeat: Boolean): Boolean {
        val opts = optionsRow
        if (opts != null) {
            when (key) {
                Key.DirectionUp -> optionsIndex = (optionsIndex - 1).coerceAtLeast(0)
                Key.DirectionDown -> optionsIndex = (optionsIndex + 1).coerceAtMost(3)
                Key.DirectionLeft -> if (!isRepeat) optionsRow = null
                else -> {}
            }
            return true
        }
        when (level) {
            TvBrowseLevel.GUIDE -> when (key) {
                Key.DirectionUp -> moveRow(safeRow - 1)
                Key.DirectionDown -> moveRow(safeRow + 1)
                Key.DirectionRight -> if (overlayMode) { if (!isRepeat) onDismiss() } else moveCell(+1)
                Key.DirectionLeft -> {
                    // nothing earlier on this channel -> reveal the playlist groups page
                    if (overlayMode) {
                        if (!isRepeat) level = TvBrowseLevel.GROUPS
                    } else if (!moveCell(-1) && !isRepeat) level = TvBrowseLevel.GROUPS
                }
                else -> return false
            }
            TvBrowseLevel.GROUPS -> when (key) {
                Key.DirectionUp -> { browsedGroups = true; groupFocus = (groupFocus - 1).coerceAtLeast(0) }
                Key.DirectionDown -> { browsedGroups = true; groupFocus = (groupFocus + 1).coerceAtMost((rows.size - 1).coerceAtLeast(0)) }
                Key.DirectionRight -> if (!isRepeat) {
                    if (!browsedGroups) {
                        // opened with BACK/LEFT twice and nothing browsed: RIGHT returns to the playing channel
                        val idx = channels.indexOfFirst { it.id == playingChannel?.id }
                        if (idx >= 0) guideRow = idx
                    }
                    level = TvBrowseLevel.GUIDE
                }
                Key.DirectionLeft -> if (!isRepeat) {
                    if (overlayMode) {
                        // climb the tree: group -> its playlist -> playlist above; nothing above the top
                        var i = groupFocus - 1
                        while (i >= 0 && rows[i].kind != RowKind.HEADER) i--
                        if (i >= 0) groupFocus = i
                    } else level = TvBrowseLevel.RAIL
                }
                else -> return false
            }
            TvBrowseLevel.RAIL -> when (key) {
                Key.DirectionUp -> railIndex = (railIndex - 1).coerceAtLeast(0)
                Key.DirectionDown -> railIndex = (railIndex + 1).coerceAtMost(3)
                Key.DirectionRight -> if (!isRepeat) level = TvBrowseLevel.GROUPS
                Key.DirectionLeft -> {}
                else -> return false
            }
        }
        return true
    }

    // ---------------- layout ----------------
    val railWidth by animateDpAsState(
        targetValue = when (level) {
            TvBrowseLevel.GUIDE -> 0.dp
            TvBrowseLevel.GROUPS -> if (overlayMode) 0.dp else (RAIL_COLLAPSED_PX * k).dp   // no nav rail on the overlay page
            TvBrowseLevel.RAIL -> (RAIL_EXPANDED_PX * k).dp
        },
        animationSpec = tween(220),
        label = "railWidth"
    )
    val groupsWidth by animateDpAsState(
        targetValue = if (level == TvBrowseLevel.GUIDE) 0.dp else (GROUPS_PX * k).dp,
        animationSpec = tween(220),
        label = "groupsWidth"
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(if (overlayMode) Color.Transparent else ContentBg)
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
                        key == Key.DirectionLeft || key == Key.DirectionRight ->
                        onDirection(key, e.nativeKeyEvent.repeatCount > 0)
                    else -> false
                }
            }
    ) {
        // ---- content: preview + EPG; gets pushed to the right (and clipped) when the panels slide in ----
        Box(
            modifier = Modifier
                .offset(x = railWidth + groupsWidth)
                .requiredWidth(screenWidthDp.dp)
                .fillMaxHeight()
        ) {
            if (overlayMode) {
                ChannelListPanel(
                    channelsList = channels,
                    selectedIndex = safeRow,
                    playingId = playingChannel?.id,
                    now = now,
                    showSchedule = false,                       // plain channel list, video stays visible on the right
                    highlightSelected = level == TvBrowseLevel.GUIDE,
                    slotNowFor = { ch ->
                        val h = floorToHour(now)
                        val slots = buildSlots(programsOf(ch), h, h + 3 * HOUR_MS)
                        slots.firstOrNull { it.contains(now) } ?: slots.first()
                    },
                    scheduleFor = { ch -> slotsFor(ch) }
                )
            } else BrowseContent(
                k = k,
                player = player,
                playingChannel = playingChannel,
                channels = channels,
                selectedRow = safeRow,
                focusChannel = focusChannel,
                focusSlot = focusSlot,
                cursorMs = cursorMs,
                originMs = originMs,
                windowStart = windowStart,
                windowEnd = windowEnd,
                now = now,
                searchQuery = searchQuery,
                programsOf = ::programsOf,
                onPreviewBounds = onPreviewBounds
            )
        }

        // ---- rail + groups panel ----
        Row(modifier = Modifier.fillMaxHeight()) {
            RailPanel(
                width = railWidth,
                k = k,
                expanded = level == TvBrowseLevel.RAIL,
                focused = level == TvBrowseLevel.RAIL,
                selectedIndex = railIndex,
                bgAlpha = if (overlayMode) 0.92f else 1f
            )
            GroupsPanel(
                width = groupsWidth,
                k = k,
                rows = rows,
                collapsed = collapsed,
                focusIndex = groupFocus,
                appliedIndex = appliedIndex,
                focused = level == TvBrowseLevel.GROUPS,
                bgAlpha = if (overlayMode) 0.92f else 1f
            )
        }

        // ---- group options (long-press OK on a group) ----
        AnimatedVisibility(
            visible = optionsRow != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.CenterEnd)
        ) {
            val row = optionsRow
            if (row != null) {
                OptionsPanel(k = k, row = row, items = optionItems(row), selectedIndex = optionsIndex)
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Content: preview, programme info and the EPG grid
// ---------------------------------------------------------------------------------------------

@Composable
private fun BrowseContent(
    k: Float,
    player: ExoPlayer,
    playingChannel: ChannelEntity?,
    channels: List<ChannelEntity>,
    selectedRow: Int,
    focusChannel: ChannelEntity?,
    focusSlot: EpgSlot?,
    cursorMs: Long,
    originMs: Long,
    windowStart: Long,
    windowEnd: Long,
    now: Long,
    searchQuery: String,
    programsOf: (ChannelEntity) -> List<ProgramEntity>,
    onPreviewBounds: (Rect) -> Unit
) {
    val dpPerMs = (BROWSE_HALF_HOUR_PX * k) / HALF_HOUR_MS
    // opens already positioned on the selected row; later jumps (group switch) are instant, single steps animate
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (selectedRow - 3).coerceAtLeast(0))
    val prevRow = remember { intArrayOf(selectedRow, channels.size) }
    LaunchedEffect(selectedRow, channels.size) {
        val jump = kotlin.math.abs(selectedRow - prevRow[0]) > 2 || prevRow[1] != channels.size
        prevRow[0] = selectedRow
        prevRow[1] = channels.size
        listState.followTarget((selectedRow - 3).coerceAtLeast(0), jump)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // ----- preview + info of the focused programme -----
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height((TOP_SECTION_PX * k).dp)
                    .padding(start = (CONTENT_PAD_PX * k).dp, top = (PREVIEW_TOP_PX * k).dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(width = (PREVIEW_W_PX * k).dp, height = (PREVIEW_H_PX * k).dp)
                        .onGloballyPositioned { c ->
                            onPreviewBounds(Rect(c.positionInRoot(), c.size.toSize()))
                        }
                        .clip(RoundedCornerShape((16 * k).dp))
                        .background(Color.Black)
                ) {
                    // Intentionally empty: the single, shared video surface owned by HomeScreen is
                    // resized onto this slot. Creating a second PlayerView here is what caused the
                    // black flashes when switching between this page and full-screen playback.
                }
                Spacer(Modifier.width((45 * k).dp))
                Column(modifier = Modifier.weight(1f).padding(end = (48 * k).dp)) {
                    if (focusSlot != null) {
                        Text(
                            focusSlot.title,
                            color = Color.White,
                            fontSize = (46 * k).sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height((8 * k).dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "${fmtClock(focusSlot.startMs)} — ${fmtClock(focusSlot.endMs)}",
                                color = Color.White.copy(alpha = 0.7f),
                                fontSize = (30 * k).sp,
                                maxLines = 1
                            )
                            if (focusSlot.contains(now)) {
                                Spacer(Modifier.width((20 * k).dp))
                                MiniProgress(focusSlot.progress(now), k)
                                Spacer(Modifier.width((20 * k).dp))
                                Text(
                                    "${focusSlot.minutesLeft(now)} min",
                                    color = Color.White.copy(alpha = 0.7f),
                                    fontSize = (30 * k).sp,
                                    maxLines = 1
                                )
                            }
                        }
                        focusSlot.description?.let {
                            Spacer(Modifier.height((14 * k).dp))
                            Text(
                                it,
                                color = Color.White.copy(alpha = 0.7f),
                                fontSize = (28 * k).sp,
                                lineHeight = (34 * k).sp,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            // ----- time axis -----
            Box(Modifier.fillMaxWidth().height((BROWSE_HEADER_PX * k).dp)) {
                Text(
                    SimpleDateFormat("EEE, d MMM, ", Locale.getDefault()).format(Date(now)) +
                        SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(now)).lowercase(Locale.getDefault()),
                    color = DateBlue,
                    fontSize = (30 * k).sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    modifier = Modifier.align(Alignment.CenterStart).padding(start = (CONTENT_PAD_PX * k).dp)
                )
                for (i in 0..5) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .offset(x = ((CHANNEL_COL_END_PX + i * BROWSE_HALF_HOUR_PX - 70f) * k).dp)
                            .width((140 * k).dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            fmtClock(originMs + i * HALF_HOUR_MS),
                            color = Color.White.copy(alpha = 0.85f),
                            fontSize = (28 * k).sp,
                            maxLines = 1
                        )
                    }
                }
                if (searchQuery.isNotBlank()) {
                    Text(
                        "Search: $searchQuery",
                        color = Color.White,
                        fontSize = (26 * k).sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.align(Alignment.CenterEnd).padding(end = (32 * k).dp).width((380 * k).dp)
                    )
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.25f)))

            // ----- channel rows -----
            if (channels.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "No channels in this group",
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = (32 * k).sp
                    )
                }
            } else {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    itemsIndexed(channels, key = { _, c -> c.id }) { i, ch ->
                        BrowseRow(
                            k = k,
                            channel = ch,
                            number = ch.channelNumber.takeIf { it > 0 } ?: (i + 1),
                            selected = i == selectedRow,
                            playing = ch.id == playingChannel?.id,
                            programs = programsOf(ch),
                            windowStart = windowStart,
                            windowEnd = windowEnd,
                            cursorMs = cursorMs,
                            originMs = originMs,
                            dpPerMs = dpPerMs
                        )
                    }
                }
            }
        }

        // ----- "now" marker -----
        if (now in originMs..(originMs + GUIDE_VISIBLE_MS) && channels.isNotEmpty()) {
            val x = (CHANNEL_COL_END_PX * k).dp + ((now - originMs) * dpPerMs).dp
            val top = ((TOP_SECTION_PX + BROWSE_HEADER_PX - 8) * k).dp
            Box(
                Modifier
                    .offset(x = x - (1 * k).dp, y = top)
                    .fillMaxHeight()
                    .width((2 * k).dp)
                    .background(BrowseBlue.copy(alpha = 0.8f))
            )
            Box(
                Modifier
                    .offset(x = x - (6 * k).dp, y = top - (6 * k).dp)
                    .size((12 * k).dp)
                    .clip(CircleShape)
                    .background(BrowseBlue)
            )
        }
    }
}

@Composable
private fun BrowseRow(
    k: Float,
    channel: ChannelEntity,
    number: Int,
    selected: Boolean,
    playing: Boolean,
    programs: List<ProgramEntity>,
    windowStart: Long,
    windowEnd: Long,
    cursorMs: Long,
    originMs: Long,
    dpPerMs: Float
) {
    val slots = remember(programs, windowStart, windowEnd) { buildSlots(programs, windowStart, windowEnd) }
    val selIdx = if (selected) slots.indexAt(cursorMs) else -1

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height((BROWSE_ROW_PX * k).dp)
            .padding(start = (CONTENT_PAD_PX * k).dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // number + logo + name (+ playing marker)
        Row(
            modifier = Modifier.width(((CHANNEL_COL_END_PX - CONTENT_PAD_PX) * k).dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.width((54 * k).dp), contentAlignment = Alignment.Center) {
                Text(number.toString(), color = Color.White, fontSize = (30 * k).sp, maxLines = 1)
            }
            Spacer(Modifier.width((12 * k).dp))
            Box(Modifier.size(width = (100 * k).dp, height = (60 * k).dp), contentAlignment = Alignment.Center) {
                if (!channel.logoUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = channel.logoUrl,
                        contentDescription = channel.name,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
            Spacer(Modifier.width((16 * k).dp))
            Text(
                channel.name,
                color = Color.White,
                fontSize = (30 * k).sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (channel.isFavorite) {
                Icon(
                    Icons.Default.Star,
                    contentDescription = "Favorite",
                    tint = Color(0xFFFFC107),
                    modifier = Modifier.padding(horizontal = (6 * k).dp).size((28 * k).dp)
                )
            }
            if (playing) {
                Icon(
                    Icons.Default.PlayArrow,
                    contentDescription = "Playing",
                    tint = BrowseBlue,
                    modifier = Modifier.padding(horizontal = (10 * k).dp).size((36 * k).dp)
                )
            }
        }

        // programme cells
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
                        .background(if (isSel) CellFocused else CellBg)
                        .padding(horizontal = (14 * k).dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Text(
                        s.title,
                        color = when {
                            isSel -> Color.White
                            s.program == null -> Color.White.copy(alpha = 0.4f)
                            else -> Color.White.copy(alpha = 0.85f)
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
}

// ---------------------------------------------------------------------------------------------
// Rail
// ---------------------------------------------------------------------------------------------

private class RailEntry(val label: String, val icon: ImageVector)

private val RAIL_ENTRIES = listOf(
    RailEntry("Search", Icons.Default.Search),
    RailEntry("TV", Icons.Default.Tv),
    RailEntry("Favorites", Icons.Default.Star),
    RailEntry("Settings", Icons.Default.Settings)
)

@Composable
private fun RailPanel(
    width: androidx.compose.ui.unit.Dp,
    k: Float,
    expanded: Boolean,
    focused: Boolean,
    selectedIndex: Int,
    bgAlpha: Float = 1f
) {
    Box(
        modifier = Modifier
            .width(width)
            .fillMaxHeight()
            .clipToBounds()
            .background(RailBg.copy(alpha = bgAlpha))
    ) {
        // fixed-width inner layout so labels are simply revealed while the rail widens
        Box(modifier = Modifier.requiredWidth((RAIL_EXPANDED_PX * k).dp).fillMaxHeight()) {
            // logo: "tv" always sits at the top of the rail; "Buddy" is revealed as the rail widens
            val logoSize by animateFloatAsState(if (expanded) 60f else 62f, tween(220), label = "logoSize")
            val logoStart by animateDpAsState(((if (expanded) 40 else 26) * k).dp, tween(220), label = "logoStart")
            Row(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = logoStart, top = (40 * k).dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "tv",
                    color = BrowseBlue,
                    fontWeight = FontWeight.Bold,
                    fontSize = (logoSize * k).sp,
                    maxLines = 1,
                    softWrap = false
                )
                AnimatedVisibility(
                    visible = expanded,
                    enter = expandHorizontally(tween(220)) + fadeIn(tween(220)),
                    exit = shrinkHorizontally(tween(220)) + fadeOut(tween(120))
                ) {
                    Text(
                        text = "Buddy",
                        color = Color(0xFFD7DCE2),
                        fontWeight = FontWeight.Normal,
                        fontSize = (logoSize * k).sp,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }

            // Search / TV / Favorites
            Column(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .offset(y = (70 * k).dp)
            ) {
                for (i in 0..2) RailItem(k, RAIL_ENTRIES[i], expanded, focused, i == selectedIndex, i == 1)
            }

            // Settings (bottom)
            Box(modifier = Modifier.align(Alignment.BottomStart).padding(bottom = (32 * k).dp)) {
                RailItem(k, RAIL_ENTRIES[3], expanded, focused, selectedIndex == 3, false)
            }
        }
    }
}

@Composable
private fun RailItem(
    k: Float,
    entry: RailEntry,
    expanded: Boolean,
    railFocused: Boolean,
    isSelected: Boolean,
    isCurrentPage: Boolean
) {
    val highlighted = expanded && railFocused && isSelected
    val iconTint by animateColorAsState(
        when {
            highlighted -> Color.Black
            isCurrentPage -> Color.White
            else -> Color.White.copy(alpha = if (expanded) 0.9f else 0.45f)
        },
        tween(220), label = "railIconTint"
    )
    val pillColor by animateColorAsState(
        if (highlighted) PillFocused else if (expanded && isCurrentPage) PillApplied else Color.Transparent,
        tween(220), label = "railPill"
    )
    // sizes follow the rail width, so items grow / shrink with it instead of popping
    val itemStart by animateDpAsState(((if (expanded) 32 else 0) * k).dp, tween(220), label = "railItemStart")
    val itemWidth by animateDpAsState(((if (expanded) 376f else RAIL_COLLAPSED_PX) * k).dp, tween(220), label = "railItemWidth")
    val leadSpace by animateDpAsState(((if (expanded) 24 else 38) * k).dp, tween(220), label = "railLead")
    // labels fade in once the rail has started to open, and disappear quickly when it shrinks
    val labelAlpha by animateFloatAsState(
        if (expanded) 1f else 0f,
        if (expanded) tween(200, delayMillis = 80) else tween(80),
        label = "railLabelAlpha"
    )
    Row(
        modifier = Modifier
            .padding(start = itemStart, top = (8 * k).dp, bottom = (8 * k).dp)
            .width(itemWidth)
            .height((80 * k).dp)
            .clip(RoundedCornerShape((14 * k).dp))
            .background(pillColor),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Spacer(Modifier.width(leadSpace))
        Icon(
            entry.icon,
            contentDescription = entry.label,
            tint = iconTint,
            modifier = Modifier.size((38 * k).dp)
        )
        Spacer(Modifier.width((22 * k).dp))
        Text(
            entry.label,
            color = if (highlighted) Color.Black else Color.White,
            fontSize = (33 * k).sp,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.alpha(labelAlpha)
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Groups panel
// ---------------------------------------------------------------------------------------------

@Composable
private fun GroupsPanel(
    width: androidx.compose.ui.unit.Dp,
    k: Float,
    rows: List<GroupRow>,
    collapsed: List<Long>,
    focusIndex: Int,
    appliedIndex: Int,
    focused: Boolean,
    bgAlpha: Float = 1f
) {
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (focusIndex - 3).coerceAtLeast(0))
    LaunchedEffect(focusIndex, focused) {
        if (focused) listState.followTarget((focusIndex - 3).coerceAtLeast(0), jump = false)
    }
    Box(
        modifier = Modifier
            .width(width)
            .fillMaxHeight()
            .clipToBounds()
            .background(GroupsBg.copy(alpha = bgAlpha))
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.requiredWidth((GROUPS_PX * k).dp).fillMaxHeight(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = (24 * k).dp)
        ) {
            itemsIndexed(rows) { i, row ->
                val isFocus = focused && i == focusIndex
                // While the panel has focus only the focused pill is shown; the grid follows it live, so a
                // second "applied" pill would just lag behind and look like two focuses.
                val isApplied = !focused && i == appliedIndex && row.kind != RowKind.HEADER
                val isHeader = row.kind == RowKind.HEADER
                val fg = if (isFocus) Color.Black else Color.White
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = (32 * k).dp, end = (24 * k).dp, top = (2 * k).dp, bottom = (2 * k).dp)
                        .height((74 * k).dp)
                        .clip(RoundedCornerShape((14 * k).dp))
                        .background(
                            when {
                                isFocus -> PillFocused
                                isApplied -> PillApplied
                                else -> Color.Transparent
                            }
                        )
                        .padding(start = (if (isHeader) 24 else 48).times(k).dp, end = (20 * k).dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        row.title,
                        color = fg,
                        fontSize = (if (isHeader) 33 else 32).times(k).sp,
                        fontWeight = if (isHeader) FontWeight.Medium else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (isHeader) {
                        val isCollapsed = sectionKey(row.playlistId) in collapsed
                        Icon(
                            if (isCollapsed) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp,
                            contentDescription = null,
                            tint = fg.copy(alpha = 0.8f),
                            modifier = Modifier.size((36 * k).dp)
                        )
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Group options
// ---------------------------------------------------------------------------------------------

@Composable
private fun OptionsPanel(k: Float, row: GroupRow, items: List<OptionItem>, selectedIndex: Int) {
    Column(
        modifier = Modifier
            .width((700 * k).dp)
            .fillMaxHeight()
            .background(OptionsBg)
    ) {
        Text(
            "${row.playlistName} • ${row.title}",
            color = Color.White,
            fontSize = (40 * k).sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .background(OptionsHeaderBg)
                .padding(horizontal = (32 * k).dp, vertical = (28 * k).dp)
        )
        Spacer(Modifier.height((12 * k).dp))
        items.forEachIndexed { i, item ->
            val sel = i == selectedIndex
            val fg = if (sel) Color.Black else Color.White
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = (16 * k).dp, vertical = (3 * k).dp)
                    .clip(RoundedCornerShape((12 * k).dp))
                    .background(if (sel) PillFocused else Color.Transparent)
                    .padding(horizontal = (24 * k).dp, vertical = (18 * k).dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(item.title, color = fg, fontSize = (30 * k).sp, maxLines = 1)
                    item.subtitle?.let {
                        Text(it, color = fg.copy(alpha = 0.65f), fontSize = (24 * k).sp, maxLines = 1)
                    }
                }
                item.toggle?.let { on ->
                    Switch(
                        checked = on,
                        onCheckedChange = null,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = BrowseBlue,
                            checkedTrackColor = BrowseBlue.copy(alpha = 0.4f),
                            uncheckedThumbColor = Color(0xFF9AA0A6),
                            uncheckedTrackColor = Color(0xFF3C4043)
                        )
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Search dialog (opened from the rail)
// ---------------------------------------------------------------------------------------------

@Composable
fun TvSearchDialog(
    query: String,
    onQueryChange: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val focusRequester = remember { FocusRequester() }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF1F2327))
                .padding(28.dp)
        ) {
            LaunchedEffect(Unit) {
                try { focusRequester.requestFocus() } catch (_: Exception) {}
            }
            Text("Search channels", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                placeholder = { Text("Channel or group name") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onDismiss() }),
                colors = iptvTextFieldColors(),
                modifier = Modifier.fillMaxWidth().focusRequester(focusRequester)
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                if (query.isNotEmpty()) {
                    TextButton(onClick = { onQueryChange("") }) { Text("Clear") }
                }
                TextButton(onClick = onDismiss) { Text("Done") }
            }
        }
    }
}
