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
import androidx.compose.foundation.layout.fillMaxHeight
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
    data class ReorderGroups(val playlistId: Long) : TiviSettingsRoute()
    data object Epg : TiviSettingsRoute()
    data object EpgSources : TiviSettingsRoute()
    data object AddEpgSourceUrl : TiviSettingsRoute()
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

    val settled: Boolean get() = claimed

    fun enter(route: TiviSettingsRoute, restoring: Boolean) {
        currentRoute = route
        restoreTitle = if (restoring) lastByRoute[route] else null
        claimed = false
    }

    fun onFocused(title: String) {
        currentRoute?.let { lastByRoute[it] = title }
        claimed = true
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
    is TiviSettingsRoute.Playlists, is TiviSettingsRoute.Epg -> listOf(TiviSettingsRoute.Main, route)
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
    is TiviSettingsRoute.ReorderGroups -> listOf(TiviSettingsRoute.ManageGroups(route.playlistId), route)
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
    onSetGroupVisibility: (Long, String, Boolean) -> Unit = { _, _, _ -> },
    onReorderGroups: (Long, List<String>) -> Unit = { _, _ -> },
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
    onOpenSpoofEngine: () -> Unit = {},
    onEditPlaylistSpoof: (PlaylistEntity) -> Unit = {}
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

    fun navigateBack() {
        if (navStack.size > 1) {
            navStack.removeAt(navStack.lastIndex)
            // land on the row that opened the screen we just left
            focusMemory.enter(navStack.last(), true)
        } else {
            onDismiss()
        }
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
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .fillMaxHeight()
                        .width(380.dp)
                        .background(TiviDrawerBg)
                        .pointerInput(Unit) { detectTapGestures(onTap = { }) } // Consume taps inside drawer
                ) {
                    AnimatedContent(
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
                                onOpenEpg = { navigateTo(TiviSettingsRoute.Epg) }
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
                                        spoofLabel = playlist.toSpoofProfile().preset.displayName,
                                        onEditSpoof = { onEditPlaylistSpoof(playlist) },
                                        updateState = updateStates[UpdateKeys.playlist(playlist.id)],
                                        onRefreshPlaylist = { onRefreshSinglePlaylist(playlist.id) },
                                        onDeletePlaylist = {
                                            onDeletePlaylist(playlist)
                                            navigateBack()
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
                                    ManageGroupsSubMenu(
                                        playlist = playlist,
                                        groups = playlistGroups,
                                        onUpdatePlaylistEntity = onUpdatePlaylist,
                                        onReorderGroups = {
                                            navigateTo(TiviSettingsRoute.ReorderGroups(playlist.id))
                                        },
                                        onShowAllGroups = { onSetAllGroupsVisibility(playlist.id, true) },
                                        onHideAllGroups = { onSetAllGroupsVisibility(playlist.id, false) },
                                        onToggleGroup = { groupTitle, visible ->
                                            onSetGroupVisibility(playlist.id, groupTitle, visible)
                                        }
                                    )
                                }
                            }

                            is TiviSettingsRoute.ReorderGroups -> {
                                val playlist = playlists.find { it.id == route.playlistId }
                                if (playlist != null) {
                                    val playlistGroups = allGroups
                                        .filter { it.playlistId == playlist.id }
                                        .sortedBy { it.sortOrder }
                                    ReorderGroupsSubMenu(
                                        groups = playlistGroups,
                                        onSaveOrder = { orderedTitles ->
                                            onReorderGroups(playlist.id, orderedTitles)
                                        }
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

                            is TiviSettingsRoute.AddEpgSourceUrl -> Unit
                        }
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
    onOpenEpg: () -> Unit
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
                    subtitle = "Channels: ${pl.channelCount}",
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
            item {
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
    groups: List<ChannelGroupEntity>,
    onUpdatePlaylistEntity: (PlaylistEntity) -> Unit,
    onReorderGroups: () -> Unit,
    onShowAllGroups: () -> Unit,
    onHideAllGroups: () -> Unit,
    onToggleGroup: (String, Boolean) -> Unit
) {
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
        DrawerHeader(title = "Manage groups")
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
                    onClick = onShowAllGroups
                )
            }
            item {
                TiviMenuRow(
                    title = "Hide all groups",
                    onClick = onHideAllGroups
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

            items(sortedGroups, key = { "${it.playlistId}_${it.groupTitle}" }) { group ->
                TiviSwitchRow(
                    title = group.groupTitle,
                    checked = group.isVisible,
                    onCheckedChange = { visible ->
                        onToggleGroup(group.groupTitle, visible)
                    }
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
    leadingIcon: (@Composable (Boolean) -> Unit)? = null,
    trailingContent: (@Composable (Boolean) -> Unit)? = null,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val memory = LocalDrawerFocus.current
    val requester = focusRequester ?: remember { FocusRequester() }
    val active = enabled && !busy

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

    val bgColor = if (isFocused) TiviFocusBg else Color.Transparent
    val titleColor = when {
        !active -> TiviSecondaryText.copy(alpha = 0.55f)
        isFocused -> TiviFocusText
        else -> TiviPrimaryText
    }
    val subColor = when {
        isError && isFocused -> Color(0xFFB3261E)
        isError -> Color(0xFFFF8A80)
        !active -> TiviSecondaryText.copy(alpha = 0.45f)
        isFocused -> TiviFocusText.copy(alpha = 0.75f)
        else -> TiviSecondaryText
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(bgColor)
            .focusRequester(requester)
            .onFocusChanged { if (it.isFocused) memory?.onFocused(title) }
            .focusable(enabled = active, interactionSource = interactionSource)
            .clickable(
                enabled = active,
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
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
            leadingIcon(isFocused)
            Spacer(modifier = Modifier.width(14.dp))
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = titleColor,
                fontSize = 15.sp,
                fontWeight = if (isFocused) FontWeight.Medium else FontWeight.Normal,
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
            trailingContent(isFocused)
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
    onClick: () -> Unit
) {
    val requester = remember { FocusRequester() }
    val running = state?.running == true
    var wasRunning by remember { mutableStateOf(false) }
    LaunchedEffect(running) {
        if (wasRunning && !running) {
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
        busy = running,
        isError = state?.isError == true,
        focusRequester = requester,
        onClick = { if (!running) onClick() }
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
