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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.local.entity.ChannelEntity
import com.example.ui.MainViewModel
import com.example.ui.components.ChannelListItem
import com.example.ui.components.MobilePlayerView
import com.example.ui.components.TvPlayerOverlay
import com.example.ui.components.VideoPlayerSurface
import com.example.ui.dialogs.AddPlaylistDialog
import com.example.ui.dialogs.DrmDebuggerDialog
import com.example.ui.dialogs.PlaylistManagerDialog
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

    // Player state
    val currentChannel by viewModel.currentChannel.collectAsStateWithLifecycle()
    val isPlaying by viewModel.isPlaying.collectAsStateWithLifecycle()
    val isBuffering by viewModel.isBuffering.collectAsStateWithLifecycle()
    val playerError by viewModel.playerError.collectAsStateWithLifecycle()
    val streamInfo by viewModel.streamInfo.collectAsStateWithLifecycle()
    val aspectMode by viewModel.aspectMode.collectAsStateWithLifecycle()
    val audioTracks by viewModel.audioTracks.collectAsStateWithLifecycle()
    val subtitleTracks by viewModel.subtitleTracks.collectAsStateWithLifecycle()

    // Dialogs state
    var showAddDialog by remember { mutableStateOf(false) }
    var showSpoofDialog by remember { mutableStateOf(false) }
    var showDrmDebugger by remember { mutableStateOf(false) }
    var showPlaylistManager by remember { mutableStateOf(false) }
    var showAudioDialog by remember { mutableStateOf(false) }
    var showSubtitleDialog by remember { mutableStateOf(false) }

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

    // Auto-play first channel if in TV mode and no channel playing yet
    LaunchedEffect(isTvMode, channels) {
        if (isTvMode && currentChannel == null && channels.isNotEmpty()) {
            viewModel.playChannel(channels.first())
        }
    }

    BackHandler(enabled = isMobileFullscreen || (isTvMode && currentChannel != null)) {
        if (isMobileFullscreen) {
            isMobileFullscreen = false
        } else if (isTvMode) {
            viewModel.setTvMode(false)
        }
    }

    if (isTvMode) {
        // TV Fullscreen Leanback Mode
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
                channelsList = channels,
                onTogglePlayPause = { viewModel.playerManager.togglePlayPause() },
                onNextChannel = { viewModel.playNextChannel() },
                onPreviousChannel = { viewModel.playPreviousChannel() },
                onSelectChannel = { viewModel.playChannel(it) },
                onToggleFavorite = { viewModel.toggleFavorite(it) },
                onCycleAspect = { viewModel.cycleAspect() },
                onOpenAudioTracks = { showAudioDialog = true },
                onOpenSubtitles = { showSubtitleDialog = true },
                onClosePlayer = { viewModel.setTvMode(false) },
                modifier = Modifier.fillMaxSize()
            )
        }
    } else {
        // Mobile Touch Mode
        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            containerColor = DarkBackground,
            topBar = {
                if (!isMobileFullscreen) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(DarkSurface)
                            .padding(horizontal = 16.dp, vertical = 10.dp)
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

                            // Action Icons
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                // Search
                                IconButton(onClick = { isSearchActive = !isSearchActive }) {
                                    Icon(Icons.Default.Search, contentDescription = "Search", tint = Color.White)
                                }

                                // Player Spoofing
                                IconButton(
                                    onClick = { showSpoofDialog = true },
                                    modifier = Modifier.testTag("spoof_settings_btn")
                                ) {
                                    Icon(Icons.Default.Security, contentDescription = "Player Spoofing", tint = AccentCyan)
                                }

                                // DRM Tester
                                IconButton(
                                    onClick = { showDrmDebugger = true },
                                    modifier = Modifier.testTag("drm_debugger_btn")
                                ) {
                                    Icon(Icons.Default.Lock, contentDescription = "DRM Tester", tint = StatusDrm)
                                }

                                // Playlists Manager
                                IconButton(
                                    onClick = { showPlaylistManager = true },
                                    modifier = Modifier.testTag("playlist_manager_btn")
                                ) {
                                    Icon(Icons.Default.Folder, contentDescription = "Playlists", tint = Color.White)
                                }

                                // Add (+)
                                IconButton(
                                    onClick = { showAddDialog = true },
                                    modifier = Modifier.testTag("add_playlist_btn")
                                ) {
                                    Icon(Icons.Default.Add, contentDescription = "Add Playlist", tint = AccentCyan)
                                }

                                // TV Mode Switcher
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = DarkSurfaceElevated,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable { viewModel.setTvMode(true) }
                                        .border(1.dp, Color(0xFF475569), RoundedCornerShape(8.dp))
                                        .padding(horizontal = 8.dp, vertical = 6.dp)
                                        .testTag("tv_mode_btn")
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.Tv, contentDescription = null, tint = AccentCyan, modifier = Modifier.size(14.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("TV", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = AccentCyan)
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
                    .padding(paddingValues)
            ) {
                // If a channel is selected, show the Mobile Player
                if (currentChannel != null) {
                    val player = viewModel.playerManager.getPlayer()
                    MobilePlayerView(
                        player = player,
                        channel = currentChannel,
                        isPlaying = isPlaying,
                        isBuffering = isBuffering,
                        errorMessage = playerError,
                        aspectMode = aspectMode,
                        streamInfo = streamInfo,
                        spoofProfile = globalSpoof,
                        isFullscreen = isMobileFullscreen,
                        onToggleFullscreen = { isMobileFullscreen = !isMobileFullscreen },
                        onTogglePlayPause = { viewModel.playerManager.togglePlayPause() },
                        onNextChannel = { viewModel.playNextChannel() },
                        onPreviousChannel = { viewModel.playPreviousChannel() },
                        onToggleFavorite = { viewModel.toggleFavorite(it) },
                        onCycleAspect = { viewModel.cycleAspect() },
                        onOpenAudioTracks = { showAudioDialog = true },
                        onOpenSubtitles = { showSubtitleDialog = true },
                        onRetry = { currentChannel?.let { viewModel.playChannel(it) } }
                    )
                }

                if (!isMobileFullscreen) {
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
                                    TextButton(
                                        onClick = { showAddDialog = true },
                                        colors = androidx.compose.material3.ButtonDefaults.textButtonColors(contentColor = AccentCyan)
                                    ) {
                                        Text("+ Add Playlist")
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
                                ChannelListItem(
                                    channel = item,
                                    isSelected = item.id == currentChannel?.id,
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
