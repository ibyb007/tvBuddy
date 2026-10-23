package com.example.ui.dialogs

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import com.example.data.model.toSpoofProfile
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import com.example.data.local.entity.ChannelGroupEntity
import com.example.data.model.ContentType
import com.example.data.model.PlaylistType
import com.example.data.local.entity.EpgSourceEntity
import com.example.data.local.entity.PlaylistEntity
import com.example.data.local.entity.PlaylistEpgCrossRef

// ---- UI State Models for Global EPG Settings ----

data class EpgGlobalSettingsState(
    val pastDaysToKeep: Int = 3,
    val storeProgramDescriptions: Boolean = true,
    val updateIntervalHours: Int = 24,
    val updateOnAppStart: Boolean = false,
    val updateOnPlaylistsChange: Boolean = false,
    val latestUpdateStatus: String = "No EPG updates yet"
)

// ---- Internal Navigation Routes for the TiviMate Drawer ----

sealed class TiviSettingsRoute {
    data object Main : TiviSettingsRoute()
    data object Playlists : TiviSettingsRoute()
    data object ReorderPlaylists : TiviSettingsRoute()
    data class PlaylistDetail(val playlistId: Long) : TiviSettingsRoute()
    data class PlaylistEpgSources(val playlistId: Long) : TiviSettingsRoute()
    data class PlaylistEpgPriority(val playlistId: Long) : TiviSettingsRoute()
    data class ManageGroups(val playlistId: Long) : TiviSettingsRoute()
    /** Groups of one content type (TV, movies, shows) of a portal playlist that also carries VOD. */
    data class ManageGroupsType(val playlistId: Long, val contentType: String) : TiviSettingsRoute()
    data class ReorderGroups(val playlistId: Long, val contentType: String = ContentType.TV) : TiviSettingsRoute()
    /** Stalker / Xtream portal parameters (server, MAC, login, device ids, what to include). */
    data class PortalParams(val playlistId: Long) : TiviSettingsRoute()
    data object Epg : TiviSettingsRoute()
    data object EpgSources : TiviSettingsRoute()
    data object AddEpgSourceUrl : TiviSettingsRoute()
    data object AudioBoost : TiviSettingsRoute()
}

/** State of an inline "Update ..." row: spinner while [running], then a result [message]. */
data class UpdateRowState(
    val running: Boolean = false,
    val message: String? = null,
    val isError: Boolean = false
)

object UpdateKeys {
    const val ALL_PLAYLISTS = "playlists:all"
    const val EPG = "epg"
    fun playlist(id: Long) = "playlist:$id"
}

/** Screen shown in the drawer + its depth in the back stack (depth decides the slide direction). */
private data class NavEntry(val route: TiviSettingsRoute, val depth: Int)

/**
 * Remembers which row was focused on every screen so BACK lands on the row that opened the child
 * screen, and decides which row of a newly shown screen takes focus.
 */
private class DrawerFocusMemory {
    private val lastByRoute = HashMap<TiviSettingsRoute, String>()
    private var currentRoute: TiviSettingsRoute? = null
    private var restoreTitle: String? = null
    private var claimed = false
    private var focusedRows = 0

    /** True while any row of the drawer holds focus (observable, so the drawer can recover lost focus). */
    var hasFocus by mutableStateOf(false)
        private set

    val settled: Boolean get() = claimed

    fun enter(route: TiviSettingsRoute, restoring: Boolean) {
        currentRoute = route
        restoreTitle = if (restoring) lastByRoute[route] else null
        claimed = false
    }

    /** Shows [route] and puts focus on the row called [title] (null = first row), e.g. the next playlist after a delete. */
    fun enterAt(route: TiviSettingsRoute, title: String?) {
        currentRoute = route
        restoreTitle = title
        claimed = false
    }

    fun onFocused(title: String) {
        currentRoute?.let { lastByRoute[it] = title }
        claimed = true
    }

    fun rowGainedFocus() {
        focusedRows++
        hasFocus = true
    }

    fun rowLostFocus() {
        focusedRows = (focusedRows - 1).coerceAtLeast(0)
        hasFocus = focusedRows > 0
    }

    /** True for the single row that should grab focus when its screen appears. */
    fun claim(title: String): Boolean {
        if (claimed) return false
        val want = restoreTitle
        return if (want == null || want == title) { claimed = true; true } else false
    }
}

private val LocalDrawerFocus = compositionLocalOf<DrawerFocusMemory?> { null }

/**
 * The pages above [route] in the settings flow, so BACK walks the same path the user took
 * (Settings > Playlists > playlist) instead of closing the whole drawer.
 */
private fun routeChain(route: TiviSettingsRoute): List<TiviSettingsRoute> = when (route) {
    is TiviSettingsRoute.Main -> listOf(route)
    is TiviSettingsRoute.Playlists, is TiviSettingsRoute.Epg, is TiviSettingsRoute.AudioBoost -> listOf(TiviSettingsRoute.Main, route)
    is TiviSettingsRoute.ReorderPlaylists, is TiviSettingsRoute.PlaylistDetail ->
        listOf(TiviSettingsRoute.Main, TiviSettingsRoute.Playlists, route)
    is TiviSettingsRoute.PlaylistEpgSources ->
        listOf(TiviSettingsRoute.Main, TiviSettingsRoute.Playlists, TiviSettingsRoute.PlaylistDetail(route.playlistId), route)
    is TiviSettingsRoute.PlaylistEpgPriority ->
        listOf(
            TiviSettingsRoute.Main, TiviSettingsRoute.Playlists, TiviSettingsRoute.PlaylistDetail(route.playlistId),
            TiviSettingsRoute.PlaylistEpgSources(route.playlistId), route
        )
    // opened as a shortcut from the TV groups panel: BACK returns straight to it
    is TiviSettingsRoute.ManageGroups -> listOf(route)
    is TiviSettingsRoute.ManageGroupsType -> listOf(TiviSettingsRoute.ManageGroups(route.playlistId), route)
    is TiviSettingsRoute.ReorderGroups -> listOf(TiviSettingsRoute.ManageGroups(route.playlistId), route)
    is TiviSettingsRoute.PortalParams ->
        listOf(TiviSettingsRoute.Main, TiviSettingsRoute.Playlists, TiviSettingsRoute.PlaylistDetail(route.playlistId), route)
    is TiviSettingsRoute.EpgSources -> listOf(TiviSettingsRoute.Main, TiviSettingsRoute.Epg, route)
    is TiviSettingsRoute.AddEpgSourceUrl ->
        listOf(TiviSettingsRoute.Main, TiviSettingsRoute.Epg, TiviSettingsRoute.EpgSources, route)
}

private val TiviDrawerBg = Color(0xEB181B20)
private val TiviModalBg = Color(0xFF1E2229)
private val TiviFocusBg = Color(0xFFE8EAED)
private val TiviFocusText = Color(0xFF14161A)
private val TiviPrimaryText = Color(0xFFF1F3F4)
private val TiviSecondaryText = Color(0xFF9AA0A6)
private val TiviAccentBlue = Color(0xFF4285F4)

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun PlaylistManagerDialog(
    playlists: List<PlaylistEntity>,
    onDismiss: () -> Unit,
    onAddNewPlaylist: () -> Unit,
    onDeletePlaylist: (PlaylistEntity) -> Unit,
    // Extended TiviMate Playlists & EPG parameters (with defaults for drop-in compatibility)
    initialRoute: TiviSettingsRoute = TiviSettingsRoute.Main,
    playlistsSorting: String = "By name",
    onChangePlaylistsSorting: (String) -> Unit = {},
    onReorderPlaylists: (List<Long>) -> Unit = {},
    onUpdateAllPlaylists: () -> Unit = {},
    onUpdatePlaylist: (PlaylistEntity) -> Unit = {},
    onRefreshSinglePlaylist: (Long) -> Unit = {},
    allGroups: List<ChannelGroupEntity> = emptyList(),
    onSetAllGroupsVisibility: (Long, Boolean) -> Unit = { _, _ -> },
    onSetGroupVisibility: (Long, String, Boolean, String) -> Unit = { _, _, _, _ -> },
    onSetGroupsVisibility: ((Long, Map<String, Boolean>, String) -> Unit)? = null,
    onReorderGroups: (Long, List<String>, String) -> Unit = { _, _, _ -> },
    onApplyPortalParams: (PlaylistEntity) -> Unit = {},
    onLoadAccountInfo: (Long) -> Unit = {},
    epgSources: List<EpgSourceEntity> = emptyList(),
    playlistEpgCrossRefs: List<PlaylistEpgCrossRef> = emptyList(),
    onAddEpgSource: (String) -> Unit = {},
    onDeleteEpgSource: (EpgSourceEntity) -> Unit = {},
    onSetDefaultEpgSource: (Long) -> Unit = {},
    onTogglePlaylistEpgAssignment: (Long, Long, Boolean) -> Unit = { _, _, _ -> },
    onReorderPlaylistEpgPriority: (Long, List<Long>) -> Unit = { _, _ -> },
    epgSettings: EpgGlobalSettingsState = EpgGlobalSettingsState(),
    onUpdateEpgSettings: (EpgGlobalSettingsState) -> Unit = {},
    onTriggerUpdateEpg: () -> Unit = {},
    onTriggerClearEpg: () -> Unit = {},
    updateStates: Map<String, UpdateRowState> = emptyMap(),
    onClearUpdateStates: () -> Unit = {},
    playbackLogEnabled: Boolean = false,
    playbackLogCount: Int = 0,
    onTogglePlaybackLog: (Boolean) -> Unit = {},
    onExportPlaybackLog: () -> Unit = {},
    onOpenSpoofEngine: () -> Unit = {},
    audioBoostProfile: com.example.player.AudioBoostProfile = com.example.player.AudioBoostProfile.OFF,
    onSelectAudioBoost: (com.example.player.AudioBoostProfile) -> Unit = {},
    warpState: com.example.data.network.WarpManager.State = com.example.data.network.WarpManager.State(),
    onToggleWarp: (Boolean) -> Unit = {},
    onEditPlaylistSpoof: (PlaylistEntity) -> Unit = {},
    // Phone only: opacity of the floating navbar (null = no such setting, e.g. TV mode)
    navbarOpacity: Float? = null,
    onNavbarOpacityChange: (Float) -> Unit = {},
    // Phone only: the floating bottom navbar, drawn over the full-screen settings page
    mobileNav: (@Composable () -> Unit)? = null,
    // Phone only: bottom system-bar inset measured by the host screen (a Dialog window reports 0 for it)
    navBarInset: androidx.compose.ui.unit.Dp = 0.dp
) {
    val navStack = remember { mutableStateListOf<TiviSettingsRoute>().apply { addAll(routeChain(initialRoute)) } }
    val currentRoute = navStack.lastOrNull() ?: TiviSettingsRoute.Main
    val currentEntry = NavEntry(currentRoute, navStack.size)
    val focusMemory = remember { DrawerFocusMemory().also { it.enter(currentRoute, false) } }

    DisposableEffect(Unit) { onDispose { onClearUpdateStates() } }

    fun navigateTo(route: TiviSettingsRoute) {
        focusMemory.enter(route, false)
        navStack.add(route)
    }

    fun navigateBack(focusTitle: String? = null, focusFirst: Boolean = false) {
        if (navStack.size > 1) {
            navStack.removeAt(navStack.lastIndex)
            // land on the row that opened the screen we just left (or an explicit row, e.g. after a delete)
            if (focusTitle != null || focusFirst) focusMemory.enterAt(navStack.last(), focusTitle)
            else focusMemory.enter(navStack.last(), true)
        } else {
            onDismiss()
        }
    }

    fun commitGroupVisibility(playlistId: Long, contentType: String, changes: Map<String, Boolean>) {
        if (changes.isEmpty()) return
        val batch = onSetGroupsVisibility
        if (batch != null) batch(playlistId, changes, contentType)
        else changes.forEach { (title, visible) -> onSetGroupVisibility(playlistId, title, visible, contentType) }
    }

    // epgSourceId -> names of playlists using it (so a source extracted from one playlist is visible everywhere)
    val epgUsedBy = remember(playlists, playlistEpgCrossRefs) {
        playlistEpgCrossRefs
            .groupBy({ it.epgSourceId }, { ref -> playlists.find { it.id == ref.playlistId }?.name })
            .mapValues { (_, names) -> names.filterNotNull().joinToString(", ") }
    }

    // Sub-dialogs for inline text/number edits
    var textEditDialogState by remember { mutableStateOf<TextEditPrompt?>(null) }

    Dialog(
        onDismissRequest = { navigateBack() },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        CompositionLocalProvider(LocalDrawerFocus provides focusMemory) {
        BackHandler {
            navigateBack()
        }

        // Rows claim focus themselves (first row, or the row we came from on BACK). This is only
        // the safety net for screens whose rows do not (e.g. the URL entry screen).
        val focusManager = LocalFocusManager.current
        LaunchedEffect(currentEntry) {
            delay(450)
            if (!focusMemory.settled) {
                if (!focusManager.moveFocus(FocusDirection.Enter)) {
                    focusManager.moveFocus(FocusDirection.Down)
                }
            }
        }

        // If focus gets lost while the drawer is in use (e.g. the focused row was removed), put it back
        // on a row instead of leaving the drawer with nothing focused.
        val windowFocused = LocalWindowInfo.current.isWindowFocused
        LaunchedEffect(focusMemory.hasFocus, currentEntry, textEditDialogState != null, windowFocused) {
            if (!focusMemory.hasFocus && focusMemory.settled && windowFocused &&
                textEditDialogState == null && currentRoute !is TiviSettingsRoute.AddEpgSourceUrl
            ) {
                delay(300)
                if (!focusMemory.hasFocus) {
                    if (!focusManager.moveFocus(FocusDirection.Enter)) {
                        focusManager.moveFocus(FocusDirection.Down)
                    }
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.25f))
                .pointerInput(Unit) { detectTapGestures(onTap = { onDismiss() }) }
        ) {
            if (currentRoute is TiviSettingsRoute.AddEpgSourceUrl) {
                // Full-screen TiviMate EPG URL entry screen
                AddEpgUrlScreen(
                    onConfirm = { url ->
                        if (url.isNotBlank()) {
                            onAddEpgSource(url.trim())
                        }
                        navigateBack()
                    },
                    onCancel = { navigateBack() }
                )
            } else {
                // Right-side TiviMate Slide-Over Settings Drawer
                val phone = mobileNav != null
                Box(
                    modifier = (if (phone) Modifier.fillMaxSize() else Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(380.dp))
                        .background(TiviDrawerBg)
                        .pointerInput(Unit) { detectTapGestures(onTap = { }) } // Consume taps inside drawer
                ) {
                    AnimatedContent(
                        modifier = if (phone) Modifier
                            .statusBarsPadding()
                            // keep the last rows clear of the floating navbar
                            .padding(bottom = 84.dp + navBarInset)
                        else Modifier,
                        targetState = currentEntry,
                        transitionSpec = {
                            val forward = targetState.depth >= initialState.depth
                            val dir = if (forward) 1 else -1
                            // old screen leaves quickly, new one fades in right after: never two screens of text on top of each other
                            (slideInHorizontally(tween(220)) { w -> dir * w / 6 } + fadeIn(tween(180, delayMillis = 70)))
                                .togetherWith(slideOutHorizontally(tween(160)) { w -> -dir * w / 6 } + fadeOut(tween(90)))
                                .using(SizeTransform(clip = false))
                        },
                        label = "TiviMateDrawerTransition"
                    ) { entry ->
                        val route = entry.route
                        when (route) {
                            is TiviSettingsRoute.Main -> MainSettingsMenu(
                                onOpenPlaylists = { navigateTo(TiviSettingsRoute.Playlists) },
                                onOpenSpoofEngine = onOpenSpoofEngine,
                                onOpenEpg = { navigateTo(TiviSettingsRoute.Epg) },
                                audioBoostProfile = audioBoostProfile,
                                onOpenAudioBoost = { navigateTo(TiviSettingsRoute.AudioBoost) },
                                warpState = warpState,
                                onToggleWarp = onToggleWarp,
                                logEnabled = playbackLogEnabled,
                                logCount = playbackLogCount,
                                onToggleLog = onTogglePlaybackLog,
                                onExportLog = onExportPlaybackLog,
                                navbarOpacity = navbarOpacity,
                                onNavbarOpacityChange = onNavbarOpacityChange
                            )

                            is TiviSettingsRoute.Playlists -> PlaylistsSubMenu(
                                playlists = playlists,
                                playlistsSorting = playlistsSorting,
                                onSelectPlaylist = { pl -> navigateTo(TiviSettingsRoute.PlaylistDetail(pl.id)) },
                                onAddPlaylist = {
                                    onDismiss()
                                    onAddNewPlaylist()
                                },
                                onUpdateAllPlaylists = onUpdateAllPlaylists,
                                updateState = updateStates[UpdateKeys.ALL_PLAYLISTS],
                                onCycleSorting = {
                                    val next = if (playlistsSorting == "By name") "Custom" else "By name"
                                    onChangePlaylistsSorting(next)
                                },
                                onReorderPlaylists = { navigateTo(TiviSettingsRoute.ReorderPlaylists) }
                            )

                            is TiviSettingsRoute.ReorderPlaylists -> ReorderPlaylistsSubMenu(
                                playlists = playlists,
                                onSaveOrder = onReorderPlaylists
                            )

                            is TiviSettingsRoute.PlaylistDetail -> {
                                val playlist = playlists.find { it.id == route.playlistId }
                                if (playlist != null) {
                                    val assignedCount = playlistEpgCrossRefs.count { it.playlistId == playlist.id }
                                    PlaylistDetailSubMenu(
                                        playlist = playlist,
                                        assignedEpgCount = assignedCount,
                                        onUpdatePlaylistEntity = onUpdatePlaylist,
                                        onEditName = {
                                            textEditDialogState = TextEditPrompt(
                                                title = "Playlist name",
                                                initialValue = playlist.name,
                                                onConfirm = { newName ->
                                                    if (newName.isNotBlank()) {
                                                        onUpdatePlaylist(playlist.copy(name = newName.trim()))
                                                    }
                                                }
                                            )
                                        },
                                        onEditUrl = {
                                            textEditDialogState = TextEditPrompt(
                                                title = "Playlist URL",
                                                initialValue = playlist.sourceUrl,
                                                onConfirm = { newUrl ->
                                                    if (newUrl.isNotBlank()) {
                                                        onUpdatePlaylist(playlist.copy(sourceUrl = newUrl.trim()))
                                                    }
                                                }
                                            )
                                        },
                                        onEditUserAgent = {
                                            textEditDialogState = TextEditPrompt(
                                                title = "User-Agent",
                                                initialValue = playlist.customUserAgent ?: "",
                                                onConfirm = { ua ->
                                                    onUpdatePlaylist(playlist.copy(customUserAgent = ua.trim().ifBlank { null }))
                                                }
                                            )
                                        },
                                        onOpenPlaylistEpgSources = {
                                            navigateTo(TiviSettingsRoute.PlaylistEpgSources(playlist.id))
                                        },
                                        onOpenManageGroups = {
                                            navigateTo(TiviSettingsRoute.ManageGroups(playlist.id))
                                        },
                                        onOpenPortalParams = {
                                            navigateTo(TiviSettingsRoute.PortalParams(playlist.id))
                                        },
                                        spoofLabel = playlist.toSpoofProfile().preset.displayName,
                                        onEditSpoof = { onEditPlaylistSpoof(playlist) },
                                        updateState = updateStates[UpdateKeys.playlist(playlist.id)],
                                        onRefreshPlaylist = { onRefreshSinglePlaylist(playlist.id) },
                                        onDeletePlaylist = {
                                            // focus goes to the next playlist (or the previous one when the last was deleted)
                                            val ordered = if (playlistsSorting.equals("By name", ignoreCase = true)) {
                                                playlists.sortedBy { it.name.lowercase() }
                                            } else playlists
                                            val idx = ordered.indexOfFirst { it.id == playlist.id }
                                            val target = ordered.getOrNull(idx + 1) ?: ordered.getOrNull(idx - 1)
                                            navigateBack(focusTitle = target?.name, focusFirst = true)
                                            onDeletePlaylist(playlist)
                                        }
                                    )
                                }
                            }

                            is TiviSettingsRoute.PlaylistEpgSources -> {
                                val playlist = playlists.find { it.id == route.playlistId }
                                if (playlist != null) {
                                    val assignedIds = playlistEpgCrossRefs
                                        .filter { it.playlistId == playlist.id }
                                        .sortedBy { it.priority }
                                        .map { it.epgSourceId }

                                    PlaylistEpgSourcesSubMenu(
                                        playlist = playlist,
                                        allSources = epgSources,
                                        assignedSourceIds = assignedIds,
                                        onManageSources = { navigateTo(TiviSettingsRoute.EpgSources) },
                                        onChangePriority = {
                                            navigateTo(TiviSettingsRoute.PlaylistEpgPriority(playlist.id))
                                        },
                                        onToggleAssignment = { sourceId, assign ->
                                            onTogglePlaylistEpgAssignment(playlist.id, sourceId, assign)
                                        }
                                    )
                                }
                            }

                            is TiviSettingsRoute.PlaylistEpgPriority -> {
                                val playlist = playlists.find { it.id == route.playlistId }
                                if (playlist != null) {
                                    val assignedSources = playlistEpgCrossRefs
                                        .filter { it.playlistId == playlist.id }
                                        .sortedBy { it.priority }
                                        .mapNotNull { ref -> epgSources.find { it.id == ref.epgSourceId } }

                                    PlaylistEpgPrioritySubMenu(
                                        playlistName = playlist.name,
                                        assignedSources = assignedSources,
                                        onSaveOrder = { orderedIds ->
                                            onReorderPlaylistEpgPriority(playlist.id, orderedIds)
                                        }
                                    )
                                }
                            }

                            is TiviSettingsRoute.ManageGroups -> {
                                val playlist = playlists.find { it.id == route.playlistId }
                                if (playlist != null) {
                                    val playlistGroups = allGroups.filter { it.playlistId == playlist.id }
                                    val types = ContentType.all.filter { t -> playlistGroups.any { it.contentType == t } }
                                    if (types.any { it != ContentType.TV }) {
                                        // portal with movies / shows: pick TV, Movies or Shows first
                                        ManageGroupTypesSubMenu(
                                            playlistName = playlist.name,
                                            types = types,
                                            onSelectType = { type ->
                                                navigateTo(TiviSettingsRoute.ManageGroupsType(playlist.id, type))
                                            }
                                        )
                                    } else {
                                        ManageGroupsSubMenu(
                                            playlist = playlist,
                                            contentType = ContentType.TV,
                                            groups = playlistGroups.filter { it.contentType == ContentType.TV },
                                            onUpdatePlaylistEntity = onUpdatePlaylist,
                                            onReorderGroups = {
                                                navigateTo(TiviSettingsRoute.ReorderGroups(playlist.id, ContentType.TV))
                                            },
                                            onCommitGroupVisibility = { changes ->
                                                commitGroupVisibility(playlist.id, ContentType.TV, changes)
                                            }
                                        )
                                    }
                                }
                            }

                            is TiviSettingsRoute.ManageGroupsType -> {
                                val playlist = playlists.find { it.id == route.playlistId }
                                if (playlist != null) {
                                    ManageGroupsSubMenu(
                                        playlist = playlist,
                                        contentType = route.contentType,
                                        groups = allGroups.filter { it.playlistId == playlist.id && it.contentType == route.contentType },
                                        onUpdatePlaylistEntity = onUpdatePlaylist,
                                        onReorderGroups = {
                                            navigateTo(TiviSettingsRoute.ReorderGroups(playlist.id, route.contentType))
                                        },
                                        onCommitGroupVisibility = { changes ->
                                            commitGroupVisibility(playlist.id, route.contentType, changes)
                                        }
                                    )
                                }
                            }

                            is TiviSettingsRoute.ReorderGroups -> {
                                val playlist = playlists.find { it.id == route.playlistId }
                                if (playlist != null) {
                                    val playlistGroups = allGroups
                                        .filter { it.playlistId == playlist.id && it.contentType == route.contentType }
                                        .sortedBy { it.sortOrder }
                                    ReorderGroupsSubMenu(
                                        groups = playlistGroups,
                                        onSaveOrder = { orderedTitles ->
                                            onReorderGroups(playlist.id, orderedTitles, route.contentType)
                                        }
                                    )
                                }
                            }

                            is TiviSettingsRoute.PortalParams -> {
                                val playlist = playlists.find { it.id == route.playlistId }
                                if (playlist != null) {
                                    LaunchedEffect(playlist.id) { onLoadAccountInfo(playlist.id) }
                                    PortalParamsSubMenu(
                                        playlist = playlist,
                                        updateState = updateStates[UpdateKeys.playlist(playlist.id)],
                                        onEditText = { title, initial, confirm ->
                                            textEditDialogState = TextEditPrompt(title, initial, confirm)
                                        },
                                        onApply = onApplyPortalParams
                                    )
                                }
                            }

                            is TiviSettingsRoute.Epg -> EpgSettingsSubMenu(
                                state = epgSettings,
                                onOpenEpgSources = { navigateTo(TiviSettingsRoute.EpgSources) },
                                onChangeState = onUpdateEpgSettings,
                                onUpdateEpg = onTriggerUpdateEpg,
                                updateState = updateStates[UpdateKeys.EPG],
                                onClearEpg = onTriggerClearEpg
                            )

                            is TiviSettingsRoute.EpgSources -> EpgSourcesSubMenu(
                                sources = epgSources,
                                usedBy = epgUsedBy,
                                onAddSource = { navigateTo(TiviSettingsRoute.AddEpgSourceUrl) },
                                onSelectSource = { src ->
                                    onSetDefaultEpgSource(src.id)
                                },
                                onDeleteSource = onDeleteEpgSource
                            )

                            is TiviSettingsRoute.AudioBoost -> AudioBoostSubMenu(
                                selected = audioBoostProfile,
                                onSelect = onSelectAudioBoost
                            )

                            is TiviSettingsRoute.AddEpgSourceUrl -> Unit
                        }
                    }
                    if (mobileNav != null) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = navBarInset + 12.dp)
                        ) { mobileNav() }
                    }
                }
            }
        }

        // Inline Edit Dialog for Name / URL / User-Agent
        textEditDialogState?.let { prompt ->
            var currentText by remember(prompt) { mutableStateOf(prompt.initialValue) }
            AlertDialog(
                onDismissRequest = { textEditDialogState = null },
                containerColor = TiviModalBg,
                titleContentColor = TiviPrimaryText,
                title = { Text(prompt.title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold) },
                text = {
                    OutlinedTextField(
                        value = currentText,
                        onValueChange = { currentText = it },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = TiviPrimaryText,
                            unfocusedTextColor = TiviPrimaryText,
                            focusedBorderColor = TiviAccentBlue,
                            unfocusedBorderColor = TiviSecondaryText
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            prompt.onConfirm(currentText)
                            textEditDialogState = null
                        }
                    ) {
                        Text("OK", color = TiviAccentBlue)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { textEditDialogState = null }) {
                        Text("Cancel", color = TiviSecondaryText)
                    }
                }
            )
        }
        }
    }
}

private data class TextEditPrompt(
    val title: String,
    val initialValue: String,
    val onConfirm: (String) -> Unit
)

// ============================================================================
// 1. MAIN SETTINGS MENU ("Settings")
// ============================================================================

@Composable
private fun MainSettingsMenu(
    onOpenPlaylists: () -> Unit,
    onOpenSpoofEngine: () -> Unit,
    onOpenEpg: () -> Unit,
    audioBoostProfile: com.example.player.AudioBoostProfile,
    onOpenAudioBoost: () -> Unit,
    warpState: com.example.data.network.WarpManager.State,
    onToggleWarp: (Boolean) -> Unit,
    logEnabled: Boolean,
    logCount: Int,
    onToggleLog: (Boolean) -> Unit,
    onExportLog: () -> Unit,
    navbarOpacity: Float? = null,
    onNavbarOpacityChange: (Float) -> Unit = {}
) {
    Column(modifier = Modifier.fillMaxSize()) {
        DrawerHeader(title = "Settings")
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            item {
                TiviMenuRow(
                    title = "Playlists",
                    subtitle = "Add, remove and manage playlists",
                    onClick = onOpenPlaylists
                )
            }
            item {
                TiviMenuRow(
                    title = "Player spoofing engine",
                    subtitle = "Choose the player signature sent to providers",
                    onClick = onOpenSpoofEngine
                )
            }
            item {
                TiviMenuRow(
                    title = "EPG",
                    subtitle = "EPG sources and update options",
                    onClick = onOpenEpg
                )
            }
            item {
                TiviMenuRow(
                    title = "Audio boost",
                    subtitle = audioBoostProfile.title,
                    onClick = onOpenAudioBoost
                )
            }
            if (navbarOpacity != null) {
                item {
                    NavbarOpacityRow(value = navbarOpacity, onValueChange = onNavbarOpacityChange)
                }
            }
            item {
                val starting = warpState.phase == com.example.data.network.WarpManager.Phase.STARTING
                TiviSwitchRow(
                    title = "WARP mode",
                    subtitle = when (warpState.phase) {
                        com.example.data.network.WarpManager.Phase.STARTING -> "Connecting to Cloudflare WARP..."
                        com.example.data.network.WarpManager.Phase.RUNNING -> "Connected. App traffic goes through Cloudflare WARP"
                        com.example.data.network.WarpManager.Phase.ERROR -> "Failed: ${warpState.message ?: "could not start"}"
                        com.example.data.network.WarpManager.Phase.OFF ->
                            if (warpState.enabled) "Enabled, not running" else "Route app traffic through Cloudflare WARP"
                    },
                    checked = warpState.enabled,
                    onCheckedChange = { if (!starting) onToggleWarp(it) }
                )
            }
            item {
                TiviSwitchRow(
                    title = "Playback log (logcat)",
                    subtitle = if (logEnabled) "Recording playback errors ($logCount entries). Turning off clears the log"
                    else "Record playback errors and their reasons",
                    checked = logEnabled,
                    onCheckedChange = onToggleLog
                )
            }
            if (logEnabled) {
                item {
                    TiviMenuRow(
                        title = "Export playback log",
                        subtitle = if (logCount > 0) "Save as a .txt file in Downloads" else "Nothing recorded yet",
                        enabled = logCount > 0,
                        onClick = onExportLog
                    )
                }
            }
        }
    }
}

/** Phone only: slider for the opacity of the floating navbar (10% - 100%). */
@Composable
private fun NavbarOpacityRow(value: Float, onValueChange: (Float) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Navbar opacity",
                color = TiviPrimaryText,
                fontSize = 18.sp,
                modifier = Modifier.weight(1f)
            )
            Text(text = "${(value * 100).toInt()}%", color = TiviSecondaryText, fontSize = 16.sp)
        }
        Slider(
            value = value,
            onValueChange = { onValueChange((Math.round(it * 20f) / 20f).coerceIn(0.1f, 1f)) },
            valueRange = 0.1f..1f,
            colors = SliderDefaults.colors(
                thumbColor = TiviAccentBlue,
                activeTrackColor = TiviAccentBlue,
                inactiveTrackColor = TiviSecondaryText.copy(alpha = 0.3f)
            )
        )
        Text(
            text = "Floating bottom bar on the phone",
            color = TiviSecondaryText,
            fontSize = 13.sp
        )
    }
}

// ============================================================================
// 1b. AUDIO BOOST SUBMENU ("Audio boost")
// ============================================================================

@Composable
private fun AudioBoostSubMenu(
    selected: com.example.player.AudioBoostProfile,
    onSelect: (com.example.player.AudioBoostProfile) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        DrawerHeader(title = "Audio boost")
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            items(com.example.player.AudioBoostProfile.entries.toList()) { profile ->
                TiviMenuRow(
                    title = profile.title,
                    subtitle = profile.description,
                    onClick = { onSelect(profile) },
                    trailingContent = { isFocused ->
                        if (profile == selected) {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = "Selected",
                                tint = if (isFocused) TiviFocusText else TiviAccentBlue
                            )
                        }
                    }
                )
            }
        }
    }
}

// ============================================================================
// 2. PLAYLISTS SUBMENU ("Playlists")
// ============================================================================

@Composable
private fun PlaylistsSubMenu(
    playlists: List<PlaylistEntity>,
    playlistsSorting: String,
    onSelectPlaylist: (PlaylistEntity) -> Unit,
    onAddPlaylist: () -> Unit,
    onUpdateAllPlaylists: () -> Unit,
    updateState: UpdateRowState?,
    onCycleSorting: () -> Unit,
    onReorderPlaylists: () -> Unit
) {
    val displayedPlaylists = remember(playlists, playlistsSorting) {
        if (playlistsSorting.equals("By name", ignoreCase = true)) {
            playlists.sortedBy { it.name.lowercase() }
        } else {
            playlists
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        DrawerHeader(title = "Playlists")
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            items(displayedPlaylists, key = { it.id }) { pl ->
                TiviMenuRow(
                    title = pl.name,
                    subtitle = if (pl.movieCount > 0 || pl.showCount > 0)
                        "Channels: ${pl.channelCount}, movies: ${pl.movieCount}, shows: ${pl.showCount}"
                    else "Channels: ${pl.channelCount}",
                    leadingIcon = { focused ->
                        if (pl.isActive) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = "Enabled",
                                tint = if (focused) TiviFocusText else TiviAccentBlue,
                                modifier = Modifier.size(20.dp)
                            )
                        } else {
                            Spacer(modifier = Modifier.size(20.dp))
                        }
                    },
                    onClick = { onSelectPlaylist(pl) }
                )
            }

            item { Spacer(modifier = Modifier.height(8.dp)) }

            item {
                TiviMenuRow(
                    title = "Add playlist",
                    onClick = onAddPlaylist
                )
            }
            item {
                TiviUpdateRow(
                    title = "Update all playlists",
                    state = updateState,
                    onClick = onUpdateAllPlaylists
                )
            }
            item {
                TiviMenuRow(
                    title = "Playlists sorting",
                    subtitle = playlistsSorting,
                    onClick = onCycleSorting
                )
            }
            item {
                TiviMenuRow(
                    title = "Reorder playlists",
                    onClick = onReorderPlaylists
                )
            }
        }
    }
}

// ============================================================================
// 3. REORDER PLAYLISTS SUBMENU ("Reorder playlists")
// ============================================================================

@Composable
private fun ReorderPlaylistsSubMenu(
    playlists: List<PlaylistEntity>,
    onSaveOrder: (List<Long>) -> Unit
) {
    var mutableList by remember(playlists) { mutableStateOf(playlists) }

    Column(modifier = Modifier.fillMaxSize()) {
        DrawerHeader(title = "Reorder playlists")
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            itemsIndexed(mutableList, key = { _, item -> item.id }) { index, item ->
                TiviReorderRow(
                    title = item.name,
                    canMoveUp = index > 0,
                    canMoveDown = index < mutableList.lastIndex,
                    onMoveUp = {
                        if (index > 0) {
                            val copy = mutableList.toMutableList()
                            val moved = copy.removeAt(index)
                            copy.add(index - 1, moved)
                            mutableList = copy
                            onSaveOrder(copy.map { it.id })
                        }
                    },
                    onMoveDown = {
                        if (index < mutableList.lastIndex) {
                            val copy = mutableList.toMutableList()
                            val moved = copy.removeAt(index)
                            copy.add(index + 1, moved)
                            mutableList = copy
                            onSaveOrder(copy.map { it.id })
                        }
                    }
                )
            }
        }
    }
}

// ============================================================================
// 4. PER-PLAYLIST DETAIL SUBMENU (e.g., "My")
// ============================================================================

@Composable
private fun PlaylistDetailSubMenu(
    playlist: PlaylistEntity,
    assignedEpgCount: Int,
    onUpdatePlaylistEntity: (PlaylistEntity) -> Unit,
    onEditName: () -> Unit,
    onEditUrl: () -> Unit,
    onEditUserAgent: () -> Unit,
    onOpenPlaylistEpgSources: () -> Unit,
    onOpenManageGroups: () -> Unit,
    onOpenPortalParams: () -> Unit,
    spoofLabel: String,
    onEditSpoof: () -> Unit,
    updateState: UpdateRowState?,
    onRefreshPlaylist: () -> Unit,
    onDeletePlaylist: () -> Unit
) {
    // Read extended properties safely via reflection or default helpers so it compiles before & after PlaylistEntity update
    val logosPriority = remember(playlist) { playlist.readStringProp("logosPriority", "Prefer logos from playlist") }
    val catchUpType = remember(playlist) { playlist.readStringProp("catchUpType", "None") }
    val updateIntervalHours = remember(playlist) { playlist.readIntProp("updateIntervalHours", 24) }
    val updateOnStart = remember(playlist) { playlist.readBooleanProp("updateOnStart", true) }

    Column(modifier = Modifier.fillMaxSize()) {
        DrawerHeader(title = playlist.name)
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            item {
                TiviSwitchRow(
                    title = "Enable playlist",
                    checked = playlist.isActive,
                    onCheckedChange = { enabled ->
                        onUpdatePlaylistEntity(playlist.copy(isActive = enabled))
                    }
                )
            }
            item {
                TiviMenuRow(
                    title = "Playlist name",
                    subtitle = playlist.name,
                    onClick = onEditName
                )
            }
            val isPortal = playlist.type == PlaylistType.STALKER || playlist.type == PlaylistType.XTREAM
            // portals keep their server address with the other portal parameters
            if (!isPortal) item {
                TiviMenuRow(
                    title = "Playlist URL",
                    subtitle = playlist.sourceUrl,
                    onClick = onEditUrl
                )
            }
            item {
                val epgSummary = when (assignedEpgCount) {
                    0 -> "Not assigned"
                    1 -> "1 source"
                    else -> "$assignedEpgCount sources"
                }
                TiviMenuRow(
                    title = "EPG sources",
                    subtitle = epgSummary,
                    onClick = onOpenPlaylistEpgSources
                )
            }
            item {
                TiviMenuRow(
                    title = "Logos priority",
                    subtitle = logosPriority,
                    onClick = {
                        val next = when (logosPriority) {
                            "Prefer logos from playlist" -> "Prefer logos from EPG"
                            else -> "Prefer logos from playlist"
                        }
                        onUpdatePlaylistEntity(playlist.withUpdatedProp("logosPriority", next))
                    }
                )
            }
            item {
                TiviMenuRow(
                    title = "Catch-up",
                    subtitle = catchUpType,
                    onClick = {
                        val options = listOf("None", "Auto", "Xtream Codes", "Flussonic")
                        val next = options[(options.indexOf(catchUpType) + 1) % options.size]
                        onUpdatePlaylistEntity(playlist.withUpdatedProp("catchUpType", next))
                    }
                )
            }
            item {
                TiviMenuRow(
                    title = "User-Agent",
                    subtitle = playlist.customUserAgent?.ifBlank { "Not set" } ?: "Not set",
                    onClick = onEditUserAgent
                )
            }
            item {
                TiviMenuRow(
                    title = "Player spoofing engine",
                    subtitle = spoofLabel,
                    onClick = onEditSpoof
                )
            }
            if (isPortal) item {
                TiviMenuRow(
                    title = if (playlist.type == PlaylistType.STALKER) "Stalker Portal parameters" else "Xtream Codes parameters",
                    onClick = onOpenPortalParams
                )
            }
            item {
                TiviMenuRow(
                    title = "Manage groups",
                    onClick = onOpenManageGroups
                )
            }

            item {
                DrawerSectionHeader(title = "Update options")
            }

            item {
                TiviMenuRow(
                    title = "Update interval, hours",
                    subtitle = if (updateIntervalHours <= 0) "Off" else updateIntervalHours.toString(),
                    onClick = {
                        val intervals = listOf(0, 2, 6, 12, 24, 48)
                        val next = intervals[(intervals.indexOf(updateIntervalHours) + 1) % intervals.size]
                        onUpdatePlaylistEntity(playlist.withUpdatedProp("updateIntervalHours", next))
                    }
                )
            }
            item {
                TiviSwitchRow(
                    title = "Update on app start",
                    checked = updateOnStart,
                    onCheckedChange = { enabled ->
                        onUpdatePlaylistEntity(playlist.withUpdatedProp("updateOnStart", enabled))
                    }
                )
            }
            item {
                TiviUpdateRow(
                    title = "Update playlist",
                    state = updateState,
                    onClick = onRefreshPlaylist
                )
            }
            item {
                TiviMenuRow(
                    title = "Delete playlist",
                    onClick = onDeletePlaylist
                )
            }
        }
    }
}

// ============================================================================
// 5. PER-PLAYLIST EPG SOURCES ("EPG sources (My)")
// ============================================================================

@Composable
private fun PlaylistEpgSourcesSubMenu(
    playlist: PlaylistEntity,
    allSources: List<EpgSourceEntity>,
    assignedSourceIds: List<Long>,
    onManageSources: () -> Unit,
    onChangePriority: () -> Unit,
    onToggleAssignment: (Long, Boolean) -> Unit
) {
    val assignedSources = remember(allSources, assignedSourceIds) {
        assignedSourceIds.mapNotNull { id -> allSources.find { it.id == id } }
    }
    val unassignedSources = remember(allSources, assignedSourceIds) {
        allSources.filter { it.id !in assignedSourceIds }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        DrawerHeader(title = "EPG sources (${playlist.name})")
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            item {
                TiviMenuRow(
                    title = "Manage sources",
                    onClick = onManageSources
                )
            }
            item {
                TiviMenuRow(
                    title = "Change sources priority",
                    enabled = assignedSources.size > 1,
                    onClick = onChangePriority
                )
            }

            if (assignedSources.isNotEmpty()) {
                item { DrawerSectionHeader(title = "Assigned sources") }
                items(assignedSources, key = { "assigned_${it.id}" }) { src ->
                    val label = if (src.isDefault) "Default (${src.name})" else src.name
                    TiviSwitchRow(
                        title = label,
                        subtitle = src.url,
                        checked = true,
                        onCheckedChange = { checked -> onToggleAssignment(src.id, checked) }
                    )
                }
            }

            if (unassignedSources.isNotEmpty()) {
                item { DrawerSectionHeader(title = "Unassigned sources") }
                items(unassignedSources, key = { "unassigned_${it.id}" }) { src ->
                    val label = if (src.isDefault) "Default (${src.name})" else src.name
                    TiviSwitchRow(
                        title = label,
                        subtitle = src.url,
                        checked = false,
                        onCheckedChange = { checked -> onToggleAssignment(src.id, checked) }
                    )
                }
            }
        }
    }
}

@Composable
private fun PlaylistEpgPrioritySubMenu(
    playlistName: String,
    assignedSources: List<EpgSourceEntity>,
    onSaveOrder: (List<Long>) -> Unit
) {
    var mutableList by remember(assignedSources) { mutableStateOf(assignedSources) }

    Column(modifier = Modifier.fillMaxSize()) {
        DrawerHeader(title = "Change sources priority ($playlistName)")
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            itemsIndexed(mutableList, key = { _, item -> item.id }) { index, src ->
                TiviReorderRow(
                    title = src.name,
                    subtitle = src.url,
                    canMoveUp = index > 0,
                    canMoveDown = index < mutableList.lastIndex,
                    onMoveUp = {
                        if (index > 0) {
                            val copy = mutableList.toMutableList()
                            val moved = copy.removeAt(index)
                            copy.add(index - 1, moved)
                            mutableList = copy
                            onSaveOrder(copy.map { it.id })
                        }
                    },
                    onMoveDown = {
                        if (index < mutableList.lastIndex) {
                            val copy = mutableList.toMutableList()
                            val moved = copy.removeAt(index)
                            copy.add(index + 1, moved)
                            mutableList = copy
                            onSaveOrder(copy.map { it.id })
                        }
                    }
                )
            }
        }
    }
}

// ============================================================================
// 6. MANAGE GROUPS SUBMENU ("Manage groups")
// ============================================================================

@Composable
private fun ManageGroupsSubMenu(
    playlist: PlaylistEntity,
    contentType: String,
    groups: List<ChannelGroupEntity>,
    onUpdatePlaylistEntity: (PlaylistEntity) -> Unit,
    onReorderGroups: () -> Unit,
    onCommitGroupVisibility: (Map<String, Boolean>) -> Unit
) {
    // Switches flip instantly in local state. The database (which makes the whole channel list behind the
    // drawer recompute) is only written once, when this screen is left, so toggling stays smooth and BACK
    // is never competing with a burst of list rebuilds.
    val pending: SnapshotStateMap<String, Boolean> = remember(playlist.id, contentType) { mutableStateMapOf() }
    val latestGroups by rememberUpdatedState(groups)
    val latestCommit by rememberUpdatedState(onCommitGroupVisibility)
    DisposableEffect(playlist.id, contentType) {
        onDispose {
            val stored = latestGroups.associate { it.groupTitle to it.isVisible }
            val changes = pending.filter { (title, visible) -> stored[title] != null && stored[title] != visible }
            latestCommit(changes.toMap())
        }
    }
    val groupsSorting = remember(playlist) { playlist.readStringProp("groupsSorting", "By order in playlist") }
    val showNewlyAdded = remember(playlist) { playlist.readBooleanProp("showNewlyAddedGroups", true) }

    val sortedGroups = remember(groups, groupsSorting) {
        when (groupsSorting) {
            "By name" -> groups.sortedBy { it.groupTitle.lowercase() }
            "Manual" -> groups.sortedBy { it.sortOrder }
            else -> groups.sortedBy { it.originalOrder }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        DrawerHeader(title = if (contentType == ContentType.TV) "Manage groups" else "Manage groups  •  ${ContentType.label(contentType)}")
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            item {
                TiviMenuRow(
                    title = "Groups sorting",
                    subtitle = groupsSorting,
                    onClick = {
                        val modes = listOf("By order in playlist", "By name", "Manual")
                        val next = modes[(modes.indexOf(groupsSorting) + 1) % modes.size]
                        onUpdatePlaylistEntity(playlist.withUpdatedProp("groupsSorting", next))
                    }
                )
            }
            item {
                TiviMenuRow(
                    title = "Reorder groups",
                    onClick = onReorderGroups
                )
            }
            item {
                TiviMenuRow(
                    title = "Show all groups",
                    onClick = { groups.forEach { pending[it.groupTitle] = true } }
                )
            }
            item {
                TiviMenuRow(
                    title = "Hide all groups",
                    onClick = { groups.forEach { pending[it.groupTitle] = false } }
                )
            }
            item {
                TiviSwitchRow(
                    title = "Show newly added groups",
                    checked = showNewlyAdded,
                    onCheckedChange = { enabled ->
                        onUpdatePlaylistEntity(playlist.withUpdatedProp("showNewlyAddedGroups", enabled))
                    }
                )
            }

            item { DrawerSectionHeader(title = "Groups") }

            items(sortedGroups, key = { "${it.playlistId}_${it.contentType}_${it.groupTitle}" }) { group ->
                val shown = pending[group.groupTitle] ?: group.isVisible
                TiviSwitchRow(
                    title = group.groupTitle,
                    checked = shown,
                    onCheckedChange = { visible -> pending[group.groupTitle] = visible }
                )
            }
        }
    }
}

/** First step of "Manage groups" for a portal that also has movies / shows: TV, Movies, Shows. */
@Composable
private fun ManageGroupTypesSubMenu(
    playlistName: String,
    types: List<String>,
    onSelectType: (String) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        DrawerHeader(title = "Manage groups")
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            items(types, key = { it }) { type ->
                TiviMenuRow(
                    title = ContentType.label(type),
                    onClick = { onSelectType(type) }
                )
            }
        }
    }
}

/**
 * Stalker Portal / Xtream Codes parameters: server, MAC, login, device ids and which content to fetch.
 * Edits stay local until "Apply changes", which saves them and processes the playlist again.
 */
@Composable
private fun PortalParamsSubMenu(
    playlist: PlaylistEntity,
    updateState: UpdateRowState?,
    onEditText: (title: String, initial: String, onConfirm: (String) -> Unit) -> Unit,
    onApply: (PlaylistEntity) -> Unit
) {
    var draft by remember(playlist.id) { mutableStateOf(playlist) }
    val isStalker = playlist.type == PlaylistType.STALKER

    fun differs(a: PlaylistEntity, b: PlaylistEntity) =
        a.sourceUrl != b.sourceUrl || a.macAddress != b.macAddress || a.username != b.username ||
            a.password != b.password || a.deviceId != b.deviceId || a.deviceId2 != b.deviceId2 ||
            a.serialNumber != b.serialNumber || a.includeTv != b.includeTv || a.includeVod != b.includeVod

    val dirty = differs(draft, playlist) && (draft.includeTv || draft.includeVod)
    val running = updateState?.running == true

    @Composable
    fun TextParam(title: String, value: String?, secret: Boolean = false, onChange: (String) -> Unit) {
        TiviMenuRow(
            title = title,
            subtitle = when {
                value.isNullOrBlank() -> "Not set"
                secret -> "•".repeat(minOf(value.length, 12))
                else -> value
            },
            onClick = { onEditText(title, value.orEmpty()) { onChange(it.trim()) } }
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        DrawerHeader(title = if (isStalker) "Stalker Portal parameters" else "Xtream Codes parameters")
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            item { TextParam("Server address", draft.sourceUrl) { if (it.isNotBlank()) draft = draft.copy(sourceUrl = it) } }
            if (isStalker) {
                item { TextParam("MAC address", draft.macAddress) { if (it.isNotBlank()) draft = draft.copy(macAddress = it.uppercase()) } }
            }
            item { TextParam("Username", draft.username) { draft = draft.copy(username = it.ifBlank { null }) } }
            item { TextParam("Password", draft.password, secret = true) { draft = draft.copy(password = it.ifBlank { null }) } }
            if (isStalker) {
                item { TextParam("Device ID", draft.deviceId) { draft = draft.copy(deviceId = it.ifBlank { null }) } }
                item { TextParam("Device ID 2", draft.deviceId2) { draft = draft.copy(deviceId2 = it.ifBlank { null }) } }
                item { TextParam("Serial number", draft.serialNumber) { draft = draft.copy(serialNumber = it.ifBlank { null }) } }
            }
            item {
                TiviSwitchRow(
                    title = "Include TV channels",
                    checked = draft.includeTv,
                    onCheckedChange = { draft = draft.copy(includeTv = it) }
                )
            }
            item {
                TiviSwitchRow(
                    title = "Include VOD",
                    checked = draft.includeVod,
                    onCheckedChange = { draft = draft.copy(includeVod = it) }
                )
            }
            item {
                TiviUpdateRow(
                    title = "Apply changes",
                    state = updateState,
                    enabled = dirty || running,
                    onClick = { onApply(draft) }
                )
            }
            item {
                TiviMenuRow(
                    title = "Expiration date",
                    subtitle = playlist.expirationDate ?: "Unknown",
                    enabled = false,
                    onClick = {}
                )
            }
        }
    }
}

@Composable
private fun ReorderGroupsSubMenu(
    groups: List<ChannelGroupEntity>,
    onSaveOrder: (List<String>) -> Unit
) {
    var mutableList by remember(groups) { mutableStateOf(groups) }

    Column(modifier = Modifier.fillMaxSize()) {
        DrawerHeader(title = "Reorder groups")
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            itemsIndexed(mutableList, key = { _, g -> g.groupTitle }) { index, group ->
                TiviReorderRow(
                    title = group.groupTitle,
                    canMoveUp = index > 0,
                    canMoveDown = index < mutableList.lastIndex,
                    onMoveUp = {
                        if (index > 0) {
                            val copy = mutableList.toMutableList()
                            val moved = copy.removeAt(index)
                            copy.add(index - 1, moved)
                            mutableList = copy
                            onSaveOrder(copy.map { it.groupTitle })
                        }
                    },
                    onMoveDown = {
                        if (index < mutableList.lastIndex) {
                            val copy = mutableList.toMutableList()
                            val moved = copy.removeAt(index)
                            copy.add(index + 1, moved)
                            mutableList = copy
                            onSaveOrder(copy.map { it.groupTitle })
                        }
                    }
                )
            }
        }
    }
}

// ============================================================================
// 7. GLOBAL EPG SUBMENU ("EPG")
// ============================================================================

@Composable
private fun EpgSettingsSubMenu(
    state: EpgGlobalSettingsState,
    onOpenEpgSources: () -> Unit,
    onChangeState: (EpgGlobalSettingsState) -> Unit,
    onUpdateEpg: () -> Unit,
    updateState: UpdateRowState?,
    onClearEpg: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        DrawerHeader(title = "EPG")
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            item {
                TiviMenuRow(
                    title = "EPG sources",
                    onClick = onOpenEpgSources
                )
            }
            item {
                TiviMenuRow(
                    title = "Past days to keep EPG",
                    subtitle = state.pastDaysToKeep.toString(),
                    onClick = {
                        val options = listOf(1, 2, 3, 5, 7, 14)
                        val next = options[(options.indexOf(state.pastDaysToKeep) + 1) % options.size]
                        onChangeState(state.copy(pastDaysToKeep = next))
                    }
                )
            }
            item {
                TiviSwitchRow(
                    title = "Store program descriptions",
                    checked = state.storeProgramDescriptions,
                    onCheckedChange = { checked ->
                        onChangeState(state.copy(storeProgramDescriptions = checked))
                    }
                )
            }

            item {
                DrawerSectionHeader(title = "Update options")
            }

            item {
                TiviMenuRow(
                    title = "Update interval, hours",
                    subtitle = if (state.updateIntervalHours <= 0) "Off" else state.updateIntervalHours.toString(),
                    onClick = {
                        val options = listOf(0, 2, 6, 12, 24, 48)
                        val next = options[(options.indexOf(state.updateIntervalHours) + 1) % options.size]
                        onChangeState(state.copy(updateIntervalHours = next))
                    }
                )
            }
            item {
                TiviSwitchRow(
                    title = "Update on app start",
                    checked = state.updateOnAppStart,
                    onCheckedChange = { checked ->
                        onChangeState(state.copy(updateOnAppStart = checked))
                    }
                )
            }
            item {
                TiviSwitchRow(
                    title = "Update on playlists change",
                    checked = state.updateOnPlaylistsChange,
                    onCheckedChange = { checked ->
                        onChangeState(state.copy(updateOnPlaylistsChange = checked))
                    }
                )
            }
            item {
                TiviUpdateRow(
                    title = "Update EPG",
                    state = updateState,
                    onClick = onUpdateEpg
                )
            }
            item {
                TiviMenuRow(
                    title = "Clear EPG",
                    onClick = onClearEpg
                )
            }

            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 14.dp)
                ) {
                    Text(
                        text = "Latest update status",
                        color = TiviSecondaryText,
                        fontSize = 13.sp
                    )
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        text = state.latestUpdateStatus,
                        color = TiviSecondaryText.copy(alpha = 0.85f),
                        fontSize = 12.sp,
                        lineHeight = 16.sp
                    )
                }
            }
        }
    }
}

// ============================================================================
// 8. GLOBAL EPG SOURCES SUBMENU ("EPG sources")
// ============================================================================

@Composable
private fun EpgSourcesSubMenu(
    sources: List<EpgSourceEntity>,
    usedBy: Map<Long, String>,
    onAddSource: () -> Unit,
    onSelectSource: (EpgSourceEntity) -> Unit,
    onDeleteSource: (EpgSourceEntity) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        DrawerHeader(title = "EPG sources")
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            items(sources, key = { it.id }) { src ->
                val titleText = if (src.isDefault) "${src.name} (default)" else src.name
                val location = if (src.sourceType.equals("STALKER", ignoreCase = true)) "Stalker Portal" else src.url
                val users = usedBy[src.id].orEmpty()
                val subtitleText = if (users.isBlank()) "Not assigned \u2022 $location" else "Used by $users \u2022 $location"
                TiviMenuRow(
                    title = titleText,
                    subtitle = subtitleText,
                    leadingIcon = { focused ->
                        if (src.isEnabled) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = "Active",
                                tint = if (focused) TiviFocusText else TiviAccentBlue,
                                modifier = Modifier.size(20.dp)
                            )
                        } else {
                            Spacer(modifier = Modifier.size(20.dp))
                        }
                    },
                    trailingContent = { focused ->
                        IconButton(
                            onClick = { onDeleteSource(src) },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "Delete source",
                                tint = if (focused) TiviFocusText else TiviSecondaryText,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    },
                    onClick = { onSelectSource(src) }
                )
            }

            item { Spacer(modifier = Modifier.height(6.dp)) }

            item {
                TiviMenuRow(
                    title = "Add source",
                    onClick = onAddSource
                )
            }

            item {
                Text(
                    text = "EPG sources should be assigned in the playlist settings",
                    color = TiviSecondaryText,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 14.dp)
                )
            }
        }
    }
}

// ============================================================================
// 9. FULL-SCREEN EPG URL ENTRY ("EPG URL")
// ============================================================================

@Composable
private fun AddEpgUrlScreen(
    onConfirm: (String) -> Unit,
    onCancel: () -> Unit
) {
    var urlText by remember { mutableStateOf("") }
    val clipboardManager = LocalClipboardManager.current

    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(TiviModalBg)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {}
            )
            .padding(horizontal = 48.dp, vertical = 36.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Left Column: Icon + Title + Description
        Row(
            modifier = Modifier.weight(1.1f),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Link,
                contentDescription = null,
                tint = TiviPrimaryText,
                modifier = Modifier.size(72.dp)
            )
            Spacer(modifier = Modifier.width(24.dp))
            Column {
                Text(
                    text = "EPG URL",
                    color = TiviPrimaryText,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Light
                )
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "Enter EPG URL. XMLTV format is only supported.",
                    color = TiviSecondaryText,
                    fontSize = 13.sp,
                    lineHeight = 18.sp
                )
                Spacer(modifier = Modifier.height(14.dp))
                Text(
                    text = "EPG source should be assigned in the playlist settings.",
                    color = TiviSecondaryText,
                    fontSize = 13.sp,
                    lineHeight = 18.sp
                )
            }
        }

        // Center Column: URL Input + Paste from clipboard
        Column(
            modifier = Modifier
                .weight(1.2f)
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.Center
        ) {
            val inputInteraction = remember { MutableInteractionSource() }
            val isInputFocused by inputInteraction.collectIsFocusedAsState()

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (isInputFocused) TiviFocusBg else Color.Transparent)
                    .border(
                        width = 1.dp,
                        color = if (isInputFocused) TiviFocusBg else TiviSecondaryText.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(4.dp)
                    )
                    .padding(horizontal = 14.dp, vertical = 12.dp)
            ) {
                BasicTextField(
                    value = urlText,
                    onValueChange = { urlText = it },
                    singleLine = true,
                    interactionSource = inputInteraction,
                    textStyle = TextStyle(
                        color = if (isInputFocused) TiviFocusText else TiviPrimaryText,
                        fontSize = 15.sp
                    ),
                    cursorBrush = SolidColor(if (isInputFocused) TiviFocusText else TiviAccentBlue),
                    decorationBox = { innerTextField ->
                        if (urlText.isEmpty()) {
                            Text(
                                text = "Enter URL",
                                color = if (isInputFocused) TiviFocusText.copy(alpha = 0.7f) else TiviSecondaryText,
                                fontSize = 15.sp
                            )
                        }
                        innerTextField()
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            TiviMenuRow(
                title = "Paste from clipboard",
                onClick = {
                    val clip = clipboardManager.getText()?.text
                    if (!clip.isNullOrBlank()) {
                        urlText = clip.trim()
                    }
                }
            )
        }

        // Right Column: OK / Cancel Actions
        Column(
            modifier = Modifier.width(140.dp),
            verticalArrangement = Arrangement.Center
        ) {
            TiviMenuRow(
                title = "OK",
                onClick = { onConfirm(urlText) }
            )
            Spacer(modifier = Modifier.height(4.dp))
            TiviMenuRow(
                title = "Cancel",
                onClick = onCancel
            )
        }
    }
}

// ============================================================================
// REUSABLE TIVIMATE DRAWER UI BUILDING BLOCKS
// ============================================================================

@Composable
private fun DrawerHeader(title: String) {
    Text(
        text = title,
        color = TiviPrimaryText,
        fontSize = 20.sp,
        fontWeight = FontWeight.Medium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 14.dp)
    )
}

@Composable
private fun DrawerSectionHeader(title: String) {
    Text(
        text = title,
        color = TiviAccentBlue,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 6.dp)
    )
}

@Composable
private fun TiviMenuRow(
    title: String,
    subtitle: String? = null,
    enabled: Boolean = true,
    busy: Boolean = false,
    isError: Boolean = false,
    focusRequester: FocusRequester? = null,
    onFocusChange: ((Boolean) -> Unit)? = null,
    leadingIcon: (@Composable (Boolean) -> Unit)? = null,
    trailingContent: (@Composable (Boolean) -> Unit)? = null,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val memory = LocalDrawerFocus.current
    val requester = focusRequester ?: remember { FocusRequester() }
    val active = enabled
    // a busy row keeps focus (so it is not thrown to another row) but looks unfocused and ignores clicks
    val showFocus = isFocused && !busy
    val hadFocus = remember { booleanArrayOf(false) }
    DisposableEffect(Unit) {
        onDispose { if (hadFocus[0]) { hadFocus[0] = false; memory?.rowLostFocus() } }
    }

    // first row of a new screen / the row we came from on BACK takes focus right away
    val claimsFocus = remember { active && memory?.claim(title) == true }
    LaunchedEffect(Unit) {
        if (claimsFocus) {
            repeat(4) {
                try {
                    requester.requestFocus()
                    return@LaunchedEffect
                } catch (_: Exception) {
                    delay(60)
                }
            }
        }
    }

    val bgColor = if (showFocus) TiviFocusBg else Color.Transparent
    val titleColor = when {
        !active || busy -> TiviSecondaryText.copy(alpha = 0.55f)
        showFocus -> TiviFocusText
        else -> TiviPrimaryText
    }
    val subColor = when {
        isError && showFocus -> Color(0xFFB3261E)
        isError -> Color(0xFFFF8A80)
        !active || busy -> TiviSecondaryText.copy(alpha = 0.45f)
        showFocus -> TiviFocusText.copy(alpha = 0.75f)
        else -> TiviSecondaryText
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(bgColor)
            .focusRequester(requester)
            .onFocusChanged {
                if (it.isFocused) {
                    if (!hadFocus[0]) { hadFocus[0] = true; memory?.rowGainedFocus() }
                    memory?.onFocused(title)
                } else if (hadFocus[0]) {
                    hadFocus[0] = false
                    memory?.rowLostFocus()
                }
                onFocusChange?.invoke(it.isFocused)
            }
            .focusable(enabled = active, interactionSource = interactionSource)
            .clickable(
                enabled = active,
                interactionSource = interactionSource,
                indication = null,
                onClick = { if (!busy) onClick() }
            )
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = TiviSecondaryText
            )
            Spacer(modifier = Modifier.width(14.dp))
        } else if (leadingIcon != null) {
            leadingIcon(showFocus)
            Spacer(modifier = Modifier.width(14.dp))
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = titleColor,
                fontSize = 15.sp,
                fontWeight = if (showFocus) FontWeight.Medium else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (!subtitle.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    color = subColor,
                    fontSize = 12.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        if (trailingContent != null) {
            Spacer(modifier = Modifier.width(8.dp))
            trailingContent(showFocus)
        }
    }
}

/**
 * "Update ..." action row: while it runs the row is dimmed and shows a spinner on the left; when it
 * finishes the result is shown under the title and the row gets focus back.
 */
@Composable
private fun TiviUpdateRow(
    title: String,
    state: UpdateRowState?,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val requester = remember { FocusRequester() }
    val memory = LocalDrawerFocus.current
    val running = state?.running == true
    var wasRunning by remember { mutableStateOf(false) }
    var rowFocused by remember { mutableStateOf(false) }
    LaunchedEffect(running) {
        // The row keeps focus while it runs (it only looks unfocused, UP/DOWN move to the neighbours).
        // When it finishes, take focus back only if it still has it, or if nothing else has it:
        // never pull focus away from a row the user has moved to meanwhile.
        if (wasRunning && !running && (rowFocused || memory?.hasFocus != true)) {
            for (attempt in 0 until 4) {
                delay(60)
                try {
                    requester.requestFocus()
                    break
                } catch (_: Exception) {
                }
            }
        }
        wasRunning = running
    }
    TiviMenuRow(
        title = title,
        subtitle = state?.message,
        enabled = enabled,
        busy = running,
        isError = state?.isError == true,
        focusRequester = requester,
        onFocusChange = { rowFocused = it },
        onClick = onClick
    )
}

@Composable
private fun TiviSwitchRow(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    TiviMenuRow(
        title = title,
        subtitle = subtitle,
        onClick = { onCheckedChange(!checked) },
        trailingContent = { isFocused ->
            Switch(
                checked = checked,
                onCheckedChange = null,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = TiviAccentBlue,
                    checkedTrackColor = TiviAccentBlue.copy(alpha = 0.45f),
                    uncheckedThumbColor = if (isFocused) TiviFocusText else TiviSecondaryText,
                    uncheckedTrackColor = Color.Gray.copy(alpha = 0.35f)
                )
            )
        }
    )
}

@Composable
private fun TiviReorderRow(
    title: String,
    subtitle: String? = null,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit
) {
    TiviMenuRow(
        title = title,
        subtitle = subtitle,
        onClick = {
            if (canMoveUp) onMoveUp() else if (canMoveDown) onMoveDown()
        },
        trailingContent = { isFocused ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = onMoveUp,
                    enabled = canMoveUp,
                    modifier = Modifier.size(30.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ArrowUpward,
                        contentDescription = "Move Up",
                        tint = if (!canMoveUp) Color.Gray.copy(alpha = 0.3f)
                        else if (isFocused) TiviFocusText else TiviPrimaryText,
                        modifier = Modifier.size(18.dp)
                    )
                }
                IconButton(
                    onClick = onMoveDown,
                    enabled = canMoveDown,
                    modifier = Modifier.size(30.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ArrowDownward,
                        contentDescription = "Move Down",
                        tint = if (!canMoveDown) Color.Gray.copy(alpha = 0.3f)
                        else if (isFocused) TiviFocusText else TiviPrimaryText,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    )
}

// ============================================================================
// SAFE PROPERTY ACCESSORS (Bridges old & updated PlaylistEntity seamlessly)
// ============================================================================

private fun PlaylistEntity.readStringProp(name: String, default: String): String = when (name) {
    "logosPriority" -> logosPriority
    "catchUpType" -> catchUpType
    "groupsSorting" -> groupsSorting
    else -> default
}

private fun PlaylistEntity.readIntProp(name: String, default: Int): Int = when (name) {
    "updateIntervalHours" -> updateIntervalHours
    else -> default
}

private fun PlaylistEntity.readBooleanProp(name: String, default: Boolean): Boolean = when (name) {
    "updateOnStart" -> updateOnStart
    "showNewlyAddedGroups" -> showNewlyAddedGroups
    else -> default
}

private fun PlaylistEntity.withUpdatedProp(name: String, value: Any?): PlaylistEntity = when (name) {
    "logosPriority" -> (value as? String)?.let { copy(logosPriority = it) } ?: this
    "catchUpType" -> (value as? String)?.let { copy(catchUpType = it) } ?: this
    "groupsSorting" -> (value as? String)?.let { copy(groupsSorting = it) } ?: this
    "updateIntervalHours" -> (value as? Int)?.let { copy(updateIntervalHours = it) } ?: this
    "updateOnStart" -> (value as? Boolean)?.let { copy(updateOnStart = it) } ?: this
    "showNewlyAddedGroups" -> (value as? Boolean)?.let { copy(showNewlyAddedGroups = it) } ?: this
    else -> this
}
