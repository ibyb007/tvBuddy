package com.example.ui.screens

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.MainViewModel
import com.example.ui.components.ChannelListItem
import com.example.ui.components.TvPlayerOverlay
import com.example.ui.components.VideoPlayerSurface
import com.example.ui.dialogs.AddPlaylistDialog
import com.example.ui.dialogs.DrmDebuggerDialog
import com.example.ui.dialogs.EpgGuideDialog
import com.example.ui.dialogs.PlaylistManagerDialog
import com.example.ui.dialogs.QualityPickerDialog
import com.example.ui.dialogs.SpoofSettingsDialog
import com.example.ui.dialogs.TrackSelectionDialog
import com.example.ui.dialogs.iptvTextFieldColors
import com.example.ui.theme.AccentCyan
import com.example.ui.theme.DarkBackground
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.DarkSurfaceElevated
import com.example.ui.theme.DarkSurfaceVariant
import com.example.ui.theme.StatusDrm

@Composable
fun HomeScreen(
    viewModel: MainViewModel,
    isInPipMode: Boolean = false,
    onEnterPip: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    val isTvMode by viewModel.isTvMode.collectAsStateWithLifecycle()
    val channels by viewModel.visibleChannels.collectAsStateWithLifecycle()
    val categories by viewModel.categories.collectAsStateWithLifecycle()
    val selectedCategory by viewModel.selectedCategory.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val playlists by viewModel.allPlaylists.collectAsStateWithLifecycle()
    val globalSpoof by viewModel.globalSpoof.collectAsStateWithLifecycle()
    val uiMessage by viewModel.uiMessage.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val allCurrentPrograms by viewModel.allCurrentPrograms.collectAsStateWithLifecycle()

    // Player state
    val currentChannel by viewModel.currentChannel.collectAsStateWithLifecycle()
    val isPlaying by viewModel.isPlaying.collectAsStateWithLifecycle()
    val isBuffering by viewModel.isBuffering.collectAsStateWithLifecycle()
    val playerError by viewModel.playerError.collectAsStateWithLifecycle()
    val streamInfo by viewModel.streamInfo.collectAsStateWithLifecycle()
    val aspectMode by viewModel.aspectMode.collectAsStateWithLifecycle()
    val videoQuality by viewModel.videoQuality.collectAsStateWithLifecycle()
    val availableQualities by viewModel.availableQualities.collectAsStateWithLifecycle()
    val audioTracks by viewModel.audioTracks.collectAsStateWithLifecycle()
    val subtitleTracks by viewModel.subtitleTracks.collectAsStateWithLifecycle()

    // Active program for current playing channel
    val currentProgram = remember(currentChannel, allCurrentPrograms) {
        currentChannel?.let { ch ->
            allCurrentPrograms.firstOrNull {
                it.epgChannelId.equals(ch.streamId, ignoreCase = true) ||
                it.epgChannelId.equals(ch.name, ignoreCase = true)
            }
        }
    }

    // Dialogs state
    var showAddDialog by remember { mutableStateOf(false) }
    var showSpoofDialog by remember { mutableStateOf(false) }
    var showDrmDebugger by remember { mutableStateOf(false) }
    var showPlaylistManager by remember { mutableStateOf(false) }
    var showAudioDialog by remember { mutableStateOf(false) }
    var showSubtitleDialog by remember { mutableStateOf(false) }
    var showQualityDialog by remember { mutableStateOf(false) }
    var showEpgGuideDialog by remember { mutableStateOf(false) }
    var showMoreMenu by remember { mutableStateOf(false) }

    // Search input toggle
    var isSearchActive by remember { mutableStateOf(false) }

    // Fullscreen mobile player toggle
    var isMobileFullscreen by remember { mutableStateOf(false) }

    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(uiMessage) {
        uiMessage?.let {
            snackbarHostState.showSnackbar(it.text)
            viewModel.dismissMessage()
        }
    }

    // Auto-play safe first channel if explicitly switched to TV mode
    LaunchedEffect(isTvMode) {
        if (isTvMode && currentChannel == null && channels.isNotEmpty()) {
            viewModel.playChannel(channels.first())
        }
    }

    // Leave fullscreen automatically when playback is closed
    LaunchedEffect(currentChannel) {
        if (currentChannel == null) isMobileFullscreen = false
    }

    // Fullscreen: hide system bars and let the phone rotate freely (portrait <-> landscape)
    // using the sensor, even if the system auto-rotate toggle is off.
    val hostContext = androidx.compose.ui.platform.LocalContext.current
    val hostActivity = hostContext.findActivity()
    val isTvDevice = remember(hostContext) {
        val uiMode = hostContext.getSystemService(android.content.Context.UI_MODE_SERVICE) as? android.app.UiModeManager
        uiMode?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
            hostContext.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK)
    }
    DisposableEffect(isMobileFullscreen, isTvMode, hostActivity) {
        val activity = hostActivity
        if (activity == null || isTvMode || !isMobileFullscreen) {
            onDispose { }
        } else {
            val window = activity.window
            val controller = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
            controller.systemBarsBehavior =
                androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
            onDispose {
                controller.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
                activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        }
    }

    // Intercept back button gestures properly:
    // 1. Exit fullscreen if in fullscreen
    // 2. Clear search query if typed
    // 3. Close search bar if active
    // 4. Close player / stop playback if a channel is playing
    // 5. Exit TV mode if in TV mode
    // 6. When no player is playing and on main home screen, let system exit app (pausing/stopping playback!)
    val shouldInterceptBack = !isInPipMode && (isMobileFullscreen || currentChannel != null || isTvMode || isSearchActive || searchQuery.isNotEmpty())

    BackHandler(enabled = shouldInterceptBack) {
        when {
            isMobileFullscreen -> {
                isMobileFullscreen = false
            }
            searchQuery.isNotEmpty() -> {
                viewModel.setSearchQuery("")
            }
            isSearchActive -> {
                isSearchActive = false
            }
            isTvMode -> {
                if (isTvDevice) {
                    viewModel.closePlayer()
                    hostActivity?.finish()
                } else {
                    viewModel.setTvMode(false)
                    viewModel.closePlayer()
                }
            }
            currentChannel != null -> {
                viewModel.closePlayer()
            }
        }
    }

    if (isTvMode && (channels.isNotEmpty() || currentChannel != null)) {
        // TV Fullscreen Leanback Mode (TiviMate-style)
        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            val player = viewModel.playerManager.getPlayer()
            VideoPlayerSurface(
                player = player,
                aspectMode = aspectMode,
                modifier = Modifier.fillMaxSize()
            )

            TvPlayerOverlay(
                channel = currentChannel,
                isPlaying = isPlaying,
                isBuffering = isBuffering,
                errorMessage = playerError,
                aspectMode = aspectMode,
                streamInfo = streamInfo,
                spoofProfile = globalSpoof,
                videoQuality = videoQuality,
                currentProgram = currentProgram,
                channelsList = channels,
                onTogglePlayPause = { viewModel.playerManager.togglePlayPause() },
                onNextChannel = { viewModel.playNextChannel() },
                onPreviousChannel = { viewModel.playPreviousChannel() },
                onSelectChannel = { viewModel.playChannel(it) },
                onToggleFavorite = { viewModel.toggleFavorite(it) },
                onCycleAspect = { viewModel.cycleAspect() },
                onOpenQualityPicker = { showQualityDialog = true },
                onOpenEpgGuide = { showEpgGuideDialog = true },
                onOpenAudioTracks = { showAudioDialog = true },
                onOpenSubtitles = { showSubtitleDialog = true },
                allPrograms = allCurrentPrograms,
                onClosePlayer = {
                    if (isTvDevice) {
                        viewModel.closePlayer()
                        hostActivity?.finish()
                    } else {
                        viewModel.setTvMode(false)
                        viewModel.closePlayer()
                    }
                },
                onOpenSpoofSettings = { showSpoofDialog = true },
                onOpenPlaylists = { showPlaylistManager = true },
                modifier = Modifier.fillMaxSize()
            )
        }
    } else {
        // Mobile Touch Mode
        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            containerColor = DarkBackground,
            floatingActionButton = {
                // Prominent Add Playlist button always visible on Mobile when not in fullscreen player
                if (!isMobileFullscreen && !isInPipMode && currentChannel == null) {
                    ExtendedFloatingActionButton(
                        onClick = { showAddDialog = true },
                        containerColor = AccentCyan,
                        contentColor = Color.Black,
                        icon = { Icon(Icons.Default.Add, contentDescription = null) },
                        text = { Text("Add Playlist", fontWeight = FontWeight.Bold) },
                        modifier = Modifier.testTag("fab_add_playlist")
                    )
                }
            },
            topBar = {
                if (!isMobileFullscreen && !isInPipMode) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(DarkSurface)
                            .statusBarsPadding() // keep the app bar clear of the status bar icons
                            .padding(horizontal = 14.dp, vertical = 10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            // Logo and Title
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(AccentCyan),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Tv,
                                        contentDescription = null,
                                        tint = Color.Black,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text(
                                        text = "tvBuddy",
                                        style = MaterialTheme.typography.titleLarge,
                                        color = Color.White,
                                        fontWeight = FontWeight.ExtraBold
                                    )
                                    Text(
                                        text = "IPTV Player • DRM Ready",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = AccentCyan
                                    )
                                }
                            }

                            // Primary Action Icons & Overflow Menu
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                // Prominent Add Playlist Button on Top Bar
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = AccentCyan,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable { showAddDialog = true }
                                        .padding(horizontal = 10.dp, vertical = 6.dp)
                                        .testTag("add_playlist_btn")
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.Add, contentDescription = null, tint = Color.Black, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Add", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                                    }
                                }

                                Spacer(modifier = Modifier.width(6.dp))

                                // Search
                                IconButton(
                                    onClick = { isSearchActive = !isSearchActive },
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(Icons.Default.Search, contentDescription = "Search", tint = Color.White)
                                }

                                // EPG Guide
                                IconButton(
                                    onClick = { showEpgGuideDialog = true },
                                    modifier = Modifier.size(36.dp).testTag("epg_guide_btn")
                                ) {
                                    Icon(Icons.Default.Schedule, contentDescription = "EPG Guide", tint = AccentCyan)
                                }

                                // More / Overflow Menu to prevent horizontal clipping
                                Box {
                                    IconButton(
                                        onClick = { showMoreMenu = true },
                                        modifier = Modifier.size(36.dp).testTag("more_menu_btn")
                                    ) {
                                        Icon(Icons.Default.MoreVert, contentDescription = "More Options", tint = Color.White)
                                    }

                                    DropdownMenu(
                                        expanded = showMoreMenu,
                                        onDismissRequest = { showMoreMenu = false },
                                        modifier = Modifier.background(DarkSurfaceElevated)
                                    ) {
                                        DropdownMenuItem(
                                            leadingIcon = { Icon(Icons.Default.Add, contentDescription = null, tint = AccentCyan) },
                                            text = { Text("Add Playlist", color = Color.White, fontWeight = FontWeight.Medium) },
                                            onClick = {
                                                showMoreMenu = false
                                                showAddDialog = true
                                            }
                                        )
                                        DropdownMenuItem(
                                            leadingIcon = { Icon(Icons.Default.Folder, contentDescription = null, tint = Color.White) },
                                            text = { Text("Playlists Manager", color = Color.White) },
                                            onClick = {
                                                showMoreMenu = false
                                                showPlaylistManager = true
                                            }
                                        )
                                        DropdownMenuItem(
                                            leadingIcon = { Icon(Icons.Default.Security, contentDescription = null, tint = AccentCyan) },
                                            text = { Text("Player Spoofing Engine", color = Color.White) },
                                            onClick = {
                                                showMoreMenu = false
                                                showSpoofDialog = true
                                            }
                                        )
                                        DropdownMenuItem(
                                            leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null, tint = StatusDrm) },
                                            text = { Text("DRM Stream Debugger", color = Color.White) },
                                            onClick = {
                                                showMoreMenu = false
                                                showDrmDebugger = true
                                            }
                                        )
                                        DropdownMenuItem(
                                            leadingIcon = { Icon(Icons.Default.Tv, contentDescription = null, tint = AccentCyan) },
                                            text = { Text("Switch to TV Mode", color = AccentCyan, fontWeight = FontWeight.Bold) },
                                            onClick = {
                                                showMoreMenu = false
                                                viewModel.setTvMode(true)
                                            }
                                        )
                                    }
                                }
                            }
                        }

                        // Search Bar expansion
                        AnimatedVisibility(visible = isSearchActive) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedTextField(
                                    value = searchQuery,
                                    onValueChange = { viewModel.setSearchQuery(it) },
                                    placeholder = { Text("Search channel or category...") },
                                    modifier = Modifier.weight(1f).testTag("search_channels_input"),
                                    singleLine = true,
                                    colors = iptvTextFieldColors()
                                )
                                if (searchQuery.isNotEmpty()) {
                                    IconButton(onClick = { viewModel.setSearchQuery("") }) {
                                        Icon(Icons.Default.Close, contentDescription = "Clear", tint = Color.LightGray)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(if (isMobileFullscreen || isInPipMode) androidx.compose.foundation.layout.PaddingValues(0.dp) else paddingValues)
            ) {
                // If a channel is selected, show the custom Composable Player Screen
                if (currentChannel != null) {
                    val player = viewModel.playerManager.getPlayer()
                    Box(
                        modifier = if (isMobileFullscreen || isInPipMode) {
                            Modifier.fillMaxSize()
                        } else {
                            Modifier.fillMaxWidth().height(240.dp)
                        }
                    ) {
                        TvBuddyPlayerScreen(
                            player = player,
                            channel = currentChannel,
                            isPlaying = isPlaying,
                            isBuffering = isBuffering,
                            errorMessage = playerError,
                            aspectMode = aspectMode,
                            streamInfo = streamInfo,
                            spoofProfile = globalSpoof,
                            videoQuality = videoQuality,
                            currentProgram = currentProgram,
                            isInPipMode = isInPipMode,
                            isFullscreen = isMobileFullscreen,
                            onEnterPip = onEnterPip,
                            onToggleFullscreen = { isMobileFullscreen = !isMobileFullscreen },
                            onTogglePlayPause = { viewModel.playerManager.togglePlayPause() },
                            onNextChannel = { viewModel.playNextChannel() },
                            onPreviousChannel = { viewModel.playPreviousChannel() },
                            onToggleFavorite = { viewModel.toggleFavorite(it) },
                            onCycleAspect = { viewModel.cycleAspect() },
                            onOpenQualityPicker = { showQualityDialog = true },
                            onOpenAudioTracks = { showAudioDialog = true },
                            onOpenSubtitles = { showSubtitleDialog = true },
                            onRetry = { currentChannel?.let { viewModel.playChannel(it) } },
                            onClosePlayer = { viewModel.closePlayer() },
                            onOpenSpoofSettings = { showSpoofDialog = true }
                        )
                    }
                }

                if (!isMobileFullscreen && !isInPipMode) {
                    // Category Chips
                    LazyRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp)
                    ) {
                        items(categories) { category ->
                            val isSelected = (selectedCategory == category)
                            FilterChip(
                                selected = isSelected,
                                onClick = { viewModel.selectCategory(category) },
                                label = {
                                    Text(
                                        text = category,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        fontSize = 12.sp
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

                    // Loading indicator
                    if (isLoading) {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(color = AccentCyan, modifier = Modifier.size(24.dp))
                                Spacer(modifier = Modifier.width(12.dp))
                                Text("Loading playlist streams...", color = Color.White, fontSize = 13.sp)
                            }
                        }
                    }

                    // Empty State or Channel List
                    if (channels.isEmpty() && !isLoading) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .padding(24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Default.Tv, contentDescription = null, tint = Color(0xFF64748B), modifier = Modifier.size(64.dp))
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = if (searchQuery.isNotEmpty()) "No matching channels" else "No channels in playlist",
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 16.sp
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "Add an M3U playlist, Xtream Codes, Stalker MAC Portal, or test DRM channels.",
                                    color = Color(0xFF94A3B8),
                                    fontSize = 12.sp
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = AccentCyan,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(8.dp))
                                            .clickable { showAddDialog = true }
                                            .padding(horizontal = 16.dp, vertical = 10.dp)
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(Icons.Default.Add, contentDescription = null, tint = Color.Black, modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text("+ Add Playlist", fontWeight = FontWeight.Bold, color = Color.Black)
                                        }
                                    }

                                    TextButton(
                                        onClick = { showDrmDebugger = true },
                                        colors = androidx.compose.material3.ButtonDefaults.textButtonColors(contentColor = StatusDrm)
                                    ) {
                                        Text("DRM Test")
                                    }
                                }
                            }
                        }
                    } else {
                        // Channel List
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            items(channels, key = { it.id }) { item ->
                                val channelProg = allCurrentPrograms.firstOrNull {
                                    it.epgChannelId.equals(item.streamId, ignoreCase = true) ||
                                    it.epgChannelId.equals(item.name, ignoreCase = true)
                                }
                                ChannelListItem(
                                    channel = item,
                                    isSelected = item.id == currentChannel?.id,
                                    currentProgram = channelProg,
                                    onSelect = { viewModel.playChannel(item) },
                                    onToggleFavorite = { viewModel.toggleFavorite(item) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Dialogs
    if (showAddDialog) {
        AddPlaylistDialog(
            onDismiss = { showAddDialog = false },
            onAddM3u = { name, src, isRaw, spoof -> viewModel.addM3uPlaylist(name, src, isRaw, spoof) },
            onAddXtream = { name, srv, u, p, spoof -> viewModel.addXtreamPlaylist(name, srv, u, p, spoof) },
            onAddStalker = { name, portal, mac, spoof -> viewModel.addStalkerPlaylist(name, portal, mac, spoof) },
            onAddJson = { name, src, isRaw, spoof -> viewModel.addJsonPlaylist(name, src, isRaw, spoof) }
        )
    }

    if (showEpgGuideDialog) {
        EpgGuideDialog(
            channels = channels,
            programs = allCurrentPrograms,
            onSelectChannel = { viewModel.playChannel(it) },
            onImportXmltv = { src, isRaw -> viewModel.importXmltv(src, isRaw) },
            onDismiss = { showEpgGuideDialog = false }
        )
    }

    if (showQualityDialog) {
        QualityPickerDialog(
            currentQuality = videoQuality,
            streamInfo = streamInfo,
            availableQualities = availableQualities,
            onSelectQuality = { viewModel.setVideoQuality(it) },
            onDismiss = { showQualityDialog = false }
        )
    }

    if (showSpoofDialog) {
        SpoofSettingsDialog(
            currentProfile = globalSpoof,
            onDismiss = { showSpoofDialog = false },
            onSaveProfile = { preset, ua, headers -> viewModel.setSpoofPreset(preset, ua, headers) }
        )
    }

    if (showDrmDebugger) {
        DrmDebuggerDialog(
            onDismiss = { showDrmDebugger = false },
            onTestStream = { viewModel.playChannel(it) }
        )
    }

    if (showPlaylistManager) {
        PlaylistManagerDialog(
            playlists = playlists,
            onDismiss = { showPlaylistManager = false },
            onAddNewPlaylist = { showAddDialog = true },
            onDeletePlaylist = { viewModel.deletePlaylist(it) }
        )
    }

    if (showAudioDialog) {
        TrackSelectionDialog(
            title = "Audio Tracks",
            tracks = audioTracks,
            onSelectTrack = { viewModel.playerManager.selectTrack(it) },
            onDismiss = { showAudioDialog = false }
        )
    }

    if (showSubtitleDialog) {
        TrackSelectionDialog(
            title = "Subtitles / Captions",
            tracks = subtitleTracks,
            isSubtitles = true,
            onSelectTrack = { viewModel.playerManager.selectTrack(it) },
            onDisableSubtitles = { viewModel.playerManager.disableSubtitles() },
            onDismiss = { showSubtitleDialog = false }
        )
    }
}

private fun android.content.Context.findActivity(): android.app.Activity? {
    var ctx: android.content.Context? = this
    while (ctx is android.content.ContextWrapper) {
        if (ctx is android.app.Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}
