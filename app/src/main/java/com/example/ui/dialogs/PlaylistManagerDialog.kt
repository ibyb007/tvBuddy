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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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

private val TiviDrawerBg = Color(0xEB181B20)
private val TiviModalBg = Color(0xFF1E2229)
private val TiviFocusBg = Color(0xFFE8EAED)
private val TiviFocusText = Color(0xFF14161A)
private val TiviPrimaryText = Color(0xFFF1F3F4)
private val TiviSecondaryText = Color(0xFF9AA0A6)
private val TiviAccentBlue = Color(0xFF4285F4)

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
    onTriggerClearEpg: () -> Unit = {}
) {
    val navStack = remember { mutableStateListOf(initialRoute) }
    val currentRoute = navStack.lastOrNull() ?: TiviSettingsRoute.Main

    fun navigateTo(route: TiviSettingsRoute) {
        navStack.add(route)
    }

    fun navigateBack() {
        if (navStack.size > 1) {
            navStack.removeAt(navStack.lastIndex)
        } else {
            onDismiss()
        }
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
        BackHandler {
            navigateBack()
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.25f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss
                )
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
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {} // Consume clicks inside drawer
                        )
                ) {
                    AnimatedContent(
                        targetState = currentRoute,
                        transitionSpec = {
                            (slideInHorizontally { width -> width / 3 } + fadeIn())
                                .togetherWith(slideOutHorizontally { width -> -width / 3 } + fadeOut())
                        },
                        label = "TiviMateDrawerTransition"
                    ) { route ->
                        when (route) {
                            is TiviSettingsRoute.Main -> MainSettingsMenu(
                                onOpenPlaylists = { navigateTo(TiviSettingsRoute.Playlists) },
                                onOpenEpg = { navigateTo(TiviSettingsRoute.Epg) }
                            )

                            is TiviSettingsRoute.Playlists -> PlaylistsSubMenu(
                                playlists = playlists,
                                playlistsSorting = playlistsSorting,
