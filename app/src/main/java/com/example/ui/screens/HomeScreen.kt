package com.example.ui.screens

import com.example.data.epg.EpgMatcher
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
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.zIndex
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.MainViewModel
import com.example.ui.components.ChannelListItem
import com.example.ui.components.MobileGlassNavBar
import com.example.ui.components.MobilePlaylistPanel
import com.example.ui.components.MobileSection
import com.example.ui.components.MobileVodPlayer
import com.example.ui.components.MobileVodSection
import com.example.ui.components.StartupEmptyState
import com.example.ui.components.TvBrowseLevel
import com.example.ui.components.TvBrowseScreen
import com.example.ui.components.TvPlayerOverlay
import com.example.ui.components.TvSearchDialog
import com.example.ui.components.TvSharedVideoSurface
import com.example.ui.components.VideoPlayerSurface
import com.example.ui.dialogs.AddPlaylistDialog
import com.example.ui.dialogs.DrmDebuggerDialog
import com.example.ui.dialogs.EpgGuideDialog
import com.example.ui.dialogs.PlaylistManagerDialog
import com.example.ui.dialogs.QualityPickerDialog
import com.example.ui.dialogs.SpoofSettingsDialog
import com.example.data.model.toSpoofProfile
import com.example.ui.dialogs.TiviSettingsRoute
import com.example.ui.dialogs.TrackSelectionDialog
import com.example.ui.dialogs.iptvTextFieldColors
import com.example.ui.theme.AccentCyan
import com.example.ui.theme.DarkBackground
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.DarkSurfaceElevated
import com.example.ui.theme.DarkSurfaceVariant
import com.example.ui.theme.StatusDrm
import kotlinx.coroutines.delay

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
    // TV: channels of the group the playing channel was started from (numbering + UP/DOWN zapping)
    val playingList by viewModel.playingList.collectAsStateWithLifecycle()
    // TV page: number of each channel = its position inside the selected group (independent of the search box)
    val selectionChannels by viewModel.selectionChannels.collectAsStateWithLifecycle()
    val browseNumbering = remember(selectionChannels) {
        selectionChannels.withIndex().associate { (i, c) -> c.id to i + 1 }
    }
    val categories by viewModel.categories.collectAsStateWithLifecycle()
    val vodLoading by viewModel.vodLoading.collectAsStateWithLifecycle()
    val selectedCategory by viewModel.selectedCategory.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val playlists by viewModel.allPlaylists.collectAsStateWithLifecycle()
    val globalSpoof by viewModel.globalSpoof.collectAsStateWithLifecycle()
    val uiMessage by viewModel.uiMessage.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val allCurrentPrograms by viewModel.allCurrentPrograms.collectAsStateWithLifecycle()
    val recentChannels by viewModel.recentChannels.collectAsStateWithLifecycle()
    val allChannelGroups by viewModel.allChannelGroups.collectAsStateWithLifecycle()
    // rarely change and are backed by lazy database flows: collected up front so the settings page opens with data
    val allEpgSources by viewModel.allEpgSources.collectAsStateWithLifecycle()
    val allPlaylistEpgCrossRefs by viewModel.allPlaylistEpgCrossRefs.collectAsStateWithLifecycle()
    val epgSettings by viewModel.epgSettings.collectAsStateWithLifecycle()
    val playlistsSorting by viewModel.playlistsSorting.collectAsStateWithLifecycle()
    val audioBoostProfile by viewModel.audioBoostProfile.collectAsStateWithLifecycle()
    var spoofPlaylistId by remember { mutableStateOf<Long?>(null) }
    val tvSelection by viewModel.tvSelection.collectAsStateWithLifecycle()
    val tvGroupSections by viewModel.tvGroupSections.collectAsStateWithLifecycle()
    val favOnlyGroups by viewModel.favOnlyGroups.collectAsStateWithLifecycle()
    val nameSortedGroups by viewModel.nameSortedGroups.collectAsStateWithLifecycle()
    val collapsedSections by viewModel.collapsedSections.collectAsStateWithLifecycle()
    val playContext by viewModel.playContext.collectAsStateWithLifecycle()
    val mobilePlaylistId by viewModel.mobilePlaylistId.collectAsStateWithLifecycle()
    val navbarOpacity by viewModel.navbarOpacity.collectAsStateWithLifecycle()

    // Player state
    val currentChannel by viewModel.currentChannel.collectAsStateWithLifecycle()
    val currentChannelLive by viewModel.currentChannelLive.collectAsStateWithLifecycle()
    val isPlaying by viewModel.isPlaying.collectAsStateWithLifecycle()
    val isBuffering by viewModel.isBuffering.collectAsStateWithLifecycle()
    val playerError by viewModel.playerError.collectAsStateWithLifecycle()
    val streamInfo by viewModel.streamInfo.collectAsStateWithLifecycle()
    val aspectMode by viewModel.aspectMode.collectAsStateWithLifecycle()
    val videoQuality by viewModel.videoQuality.collectAsStateWithLifecycle()
    val audioTracks by viewModel.audioTracks.collectAsStateWithLifecycle()
    val subtitleTracks by viewModel.subtitleTracks.collectAsStateWithLifecycle()

    // Active program for current playing channel
    val currentProgram = remember(currentChannel, allCurrentPrograms) {
        currentChannel?.let { ch ->
            EpgMatcher.currentProgramOf(ch, allCurrentPrograms)
        }
    }

    // Dialogs state
    var showAddDialog by remember { mutableStateOf(false) }
    var showSpoofDialog by remember { mutableStateOf(false) }
    var showDrmDebugger by remember { mutableStateOf(false) }
    var showPlaylistManager by remember { mutableStateOf(false) }
    var settingsRoute by remember { mutableStateOf<TiviSettingsRoute>(TiviSettingsRoute.Main) }
    // Opens the settings drawer directly on the requested screen
    val openSettings: (TiviSettingsRoute) -> Unit = { route ->
        settingsRoute = route
        showPlaylistManager = true
    }
    var showAudioDialog by remember { mutableStateOf(false) }
    var showSubtitleDialog by remember { mutableStateOf(false) }
    var showQualityDialog by remember { mutableStateOf(false) }
    var showEpgGuideDialog by remember { mutableStateOf(false) }
    var showMoreMenu by remember { mutableStateOf(false) }
    // Phone: floating navbar (TV / Movies / Shows) and its playlist picker
    var mobileSection by remember { mutableStateOf(MobileSection.LIVE) }
    var showMobilePlaylists by remember { mutableStateOf(false) }
    // Phone: Movies / Shows only appear when a playlist has them (same rule as the TV rail); the top bar needs this too
    val hasMovies = playlists.any { it.isActive && it.movieCount > 0 }
    val hasShows = playlists.any { it.isActive && it.showCount > 0 }
    val section = when {
        mobileSection == MobileSection.MOVIES && !hasMovies -> MobileSection.LIVE
        mobileSection == MobileSection.SHOWS && !hasShows -> MobileSection.LIVE
        else -> mobileSection
    }
    val isVodSection = section != MobileSection.LIVE
    // the movie / show that is open in the player right now (null while nothing or live TV plays)
    val vodPlaying = currentChannel?.takeIf { it.contentType != com.example.data.model.ContentType.TV }
    // Phone Movies / Shows: the poster whose details page is open (kept while the movie plays, so BACK returns to it)
    var mobileVodDetailId by remember { mutableStateOf<Long?>(null) }
    // Phone: favourite flag of the playing movie (the player's copy of the item never refreshes)
    var mobileVodFavOverride by remember { mutableStateOf<Pair<Long, Boolean>?>(null) }
    LaunchedEffect(currentChannel?.id) { mobileVodFavOverride = null }
    // Switching between TV / Movies / Shows stops what is playing (live TV <-> movie) and closes any details page
    LaunchedEffect(section) {
        mobileVodDetailId = null
        val cur = currentChannel
        if (!isTvMode && cur != null) {
            val curIsVod = cur.contentType != com.example.data.model.ContentType.TV
            if ((isVodSection && !curIsVod) || (!isVodSection && curIsVod)) viewModel.closePlayer()
        }
    }

    // TV mode: TiviMate-style page (preview + EPG, playlist groups panel, rail). null = full-screen playback.
    var tvBrowse by remember { mutableStateOf<TvBrowseLevel?>(null) }
    // true when the TV page was opened from full-screen playback (LEFT, LEFT): video stays full-screen behind it
    var tvBrowseOverlay by remember { mutableStateOf(false) }
    LaunchedEffect(tvBrowse) { if (tvBrowse == null) tvBrowseOverlay = false }
    // Opening the TV page while a channel plays starts on the group that channel was started from
    LaunchedEffect(tvBrowse != null) { if (tvBrowse != null) viewModel.syncSelectionToPlayContext() }
    // true while the group options panel is open: the TV page must then draw above the preview video
    var tvOptionsOpen by remember { mutableStateOf(false) }
    var showTvSearch by remember { mutableStateOf(false) }
    // Movies / Shows page opened from the TV rail (MOVIE or SHOW), null = closed
    var vodType by remember { mutableStateOf<String?>(null) }
    // bumped to rebuild the TV page on a fresh level (rail "TV" / "Favorites" chosen from the Movies / Shows page)
    // the movie that was being watched: the Movies page reopens on its details after BACK from playback
    var vodDetailId by remember { mutableStateOf<Long?>(null) }
    // a movie was started from the Movies page and is still being opened
    var vodPlayPending by remember { mutableStateOf(false) }
    // Live TV stops when the Movies / Shows page opens (TiviMate does the same)
    LaunchedEffect(vodType) {
        val cur = currentChannel
        if (vodType != null && cur != null && cur.contentType == com.example.data.model.ContentType.TV) viewModel.closePlayer()
    }
    LaunchedEffect(vodPlayPending, currentChannel?.id) {
        val c = currentChannel
        if (vodPlayPending && c != null && c.contentType != com.example.data.model.ContentType.TV) {
            vodPlayPending = false
            vodType = null
            tvBrowse = null
        }
    }
    LaunchedEffect(vodPlayPending) { if (vodPlayPending) { delay(20_000); vodPlayPending = false } }
    var browseEpoch by remember { mutableIntStateOf(0) }

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

    // TV mode: resume the channel that was playing in the previous launch. If nothing was played
    // last time this does nothing and the TV overlay opens the channel list instead.
    LaunchedEffect(isTvMode, channels.isNotEmpty()) {
        if (isTvMode && currentChannel == null && channels.isNotEmpty()) {
            viewModel.resumeLastPlayback(channels)
        }
    }

    // TV mode: nothing is playing (first launch / nothing to resume) -> open the TV page instead of a black screen.
    LaunchedEffect(isTvMode, currentChannel == null, channels.isNotEmpty()) {
        if (isTvMode && currentChannel == null && channels.isNotEmpty() && tvBrowse == null) {
            delay(500)   // give a pending "resume last channel" a moment to start first
            if (currentChannel == null && tvBrowse == null) tvBrowse = TvBrowseLevel.GUIDE
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
    val shouldInterceptBack = !isInPipMode && (isMobileFullscreen || currentChannel != null || isTvMode || isSearchActive || searchQuery.isNotEmpty() || mobileVodDetailId != null)

    // Leaves TV mode: closes the app on a real TV, goes back to the phone UI otherwise
    val exitTv: () -> Unit = {
        tvBrowse = null
        if (isTvDevice) {
            viewModel.closePlayer()
            hostActivity?.finish()
        } else {
            viewModel.setTvMode(false)
            viewModel.closePlayer()
        }
    }

    // While the TV page is shown it handles BACK itself (guide -> groups -> rail -> exit)
    BackHandler(enabled = shouldInterceptBack && tvBrowse == null) {
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
                // BACK from full-screen playback reveals the TV page (a movie goes back to its Movies / Shows page); otherwise leave
                val playing = currentChannelLive ?: currentChannel
                if (playing != null && playing.contentType != com.example.data.model.ContentType.TV) {
                    viewModel.closePlayer()
                    vodDetailId = playing.id
                    vodType = playing.contentType
                    tvBrowse = TvBrowseLevel.GUIDE
                } else if (currentChannel != null) tvBrowse = TvBrowseLevel.GUIDE else exitTv()
            }
            currentChannel != null -> {
                // a movie that was opened from a details page: BACK returns to that page (mobileVodDetailId is kept)
                viewModel.closePlayer()
            }
            mobileVodDetailId != null -> {
                mobileVodDetailId = null
            }
        }
    }

    // Avoid flashing the "no content" screen while the database is still loading
    // (waits for the real first database result instead of a fixed timer; the timeout is only a safety net)
    val dataLoaded by viewModel.dataLoaded.collectAsStateWithLifecycle()
    var dbSettled by remember { mutableStateOf(false) }
    LaunchedEffect(dataLoaded) {
        if (dataLoaded) {
            kotlinx.coroutines.delay(250)   // let the derived channel list catch up
            dbSettled = true
        } else {
            kotlinx.coroutines.delay(6000)
            dbSettled = true
        }
    }

    if (isTvMode && channels.isEmpty() && currentChannel == null && tvBrowse == null) {
        // First launch / nothing to watch: ask for a playlist, never auto-play
        if (dbSettled) {
            com.example.ui.components.TvEmptyState(
                onAddPlaylist = { showAddDialog = true },
                onOpenPlaylists = { openSettings(TiviSettingsRoute.Playlists) },
                onOpenSpoofSettings = { showSpoofDialog = true },
                onOpenEpgSettings = { openSettings(TiviSettingsRoute.Epg) }
            )
        } else {
            Box(modifier = Modifier.fillMaxSize().background(Color(0xFF14181B)))
        }
    } else if (isTvMode) {
        // TV Fullscreen Leanback Mode (TiviMate-style)
        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            val player = viewModel.playerManager.getPlayer()
            val epgByChannel by viewModel.epgByChannel.collectAsStateWithLifecycle()
            val browseLevel = tvBrowse
            // where the TV page's preview window currently is (written by TvBrowseScreen, read only in layout)
            val previewBounds = remember { mutableStateOf<Rect?>(null) }

            if (browseLevel != null) {
                // TV page: preview + EPG grid, playlist groups panel and rail (BACK from playback / LEFT from channel list)
                key(browseEpoch) { TvBrowseScreen(
                    initialLevel = browseLevel,
                    player = player,
                    playingChannel = currentChannel,
                    channels = channels,
                    sections = tvGroupSections,
                    selection = tvSelection,
                    playContext = playContext,
                    collapsedSections = collapsedSections,
                    onSetSectionCollapsed = { key, c -> viewModel.setSectionCollapsed(key, c) },
                    epgByChannel = epgByChannel,
                    favoriteOnlyGroups = favOnlyGroups,
                    nameSortedGroups = nameSortedGroups,
                    searchQuery = searchQuery,
                    onSelectGroup = { viewModel.selectTvGroup(it) },
                    numbering = browseNumbering,
                    onPlayChannel = { ch ->
                        viewModel.playChannelFromSelection(ch)
                        tvBrowse = null
                    },
                    onToggleFavorite = { viewModel.toggleFavorite(it) },
                    onToggleGroupFavoritesOnly = { id, title -> viewModel.toggleGroupFavoritesOnly(id, title) },
                    onToggleGroupNameSort = { id, title -> viewModel.toggleGroupNameSort(id, title) },
                    onHideGroup = { id, title -> viewModel.hideGroup(id, title) },
                    onManageGroups = { id -> openSettings(TiviSettingsRoute.ManageGroups(id)) },
                    onOpenSearch = { showTvSearch = true },
                    onClearSearch = { viewModel.setSearchQuery("") },
                    onOpenSettings = { openSettings(TiviSettingsRoute.Main) },
                    onExit = exitTv,
                    hasMovies = playlists.any { it.isActive && it.movieCount > 0 },
                    hasShows = playlists.any { it.isActive && it.showCount > 0 },
                    onOpenVod = { vodDetailId = null; vodType = it },
                    focusSuspended = vodType != null,
                    onPreviewBounds = { previewBounds.value = it },
                    overlayMode = tvBrowseOverlay,
                    onDismiss = { tvBrowse = null },
                    onOptionsVisible = { tvOptionsOpen = it },
                    // normal TV page sits under the video (which only shows in its preview slot);
                    // the overlay page must sit ABOVE the full-screen video
                    modifier = Modifier.fillMaxSize().zIndex(if (tvBrowseOverlay || tvOptionsOpen) 2f else 0f)
                ) }
            }

            // Movies / Shows page: above the TV page and the preview video
            vodType?.let { type -> key(type) {
                val startDetailId = remember { vodDetailId }
                LaunchedEffect(Unit) { vodDetailId = null }
                com.example.ui.components.TvVodScreen(
                    contentType = type,
                    itemsFlow = remember(type) { viewModel.vodChannels(type) },
                    groups = allChannelGroups,
                    playlists = playlists,
                    onPlay = { item, startMs ->
                        viewModel.playVod(item, startMs)
                        vodDetailId = item.id        // BACK from the movie returns to its details (now with Resume)
                        // the Movies page stays up until the movie is really the current item (see vodPlayPending below)
                        vodPlayPending = true
                    },
                    historyFlow = remember(type) { viewModel.vodHistory(type) },
                    resumeOf = { viewModel.vodResumeMs(it) },
                    onRemoveHistory = { viewModel.removeFromHistory(it) },
                    onOpenTrailer = { viewModel.openTrailer(it) },
                    onOpenExternal = { viewModel.openInExternalPlayer(it) },
                    initialDetailId = startDetailId,
                    onToggleMyList = { viewModel.toggleVodFavorite(it) },
                    onHideGroup = { id, title -> viewModel.setGroupVisibility(id, title, false, type) },
                    onBack = { vodType = null },
                    onExit = exitTv,
                    focusSuspended = showTvSearch || showPlaylistManager,
                    onRailSelect = { id ->
                        when (id) {
                            com.example.ui.components.RailId.MOVIES -> vodType = com.example.data.model.ContentType.MOVIE
                            com.example.ui.components.RailId.SHOWS -> vodType = com.example.data.model.ContentType.SHOW
                            com.example.ui.components.RailId.SETTINGS -> openSettings(TiviSettingsRoute.Main)
                            else -> {
                                // TV / Favorites / Search: back to the TV page
                                if (id == com.example.ui.components.RailId.FAVORITES) {
                                    viewModel.selectTvGroup(com.example.ui.TvGroupSelection(null, com.example.ui.TvGroupKind.FAVORITES))
                                }
                                if (id == com.example.ui.components.RailId.SEARCH) showTvSearch = true
                                viewModel.watchLastTv()      // live TV was stopped for the Movies page: start it again for the TV page
                                vodType = null
                                tvBrowse = TvBrowseLevel.GUIDE
                                browseEpoch++
                            }
                        }
                    },
                    loadInfo = { viewModel.loadVodInfo(it) },
                    onOpenGroup = { id, title -> viewModel.openVodGroup(id, type, title) },
                    onNeedMoreAll = { viewModel.loadMoreVodAll(type) },
                    loading = vodLoading,
                    modifier = Modifier.fillMaxSize().zIndex(4f)
                )
            } }

            // One surface for both the preview window and full-screen playback: it is resized, never
            // recreated, so switching between the two never goes black.
            if (currentChannel != null) {
                TvSharedVideoSurface(
                    player = player,
                    aspectMode = aspectMode,
                    browsing = browseLevel != null && !tvBrowseOverlay,
                    previewBounds = previewBounds,
                    modifier = Modifier.fillMaxSize().zIndex(1f)
                )
            }

            val nowPlaying = currentChannelLive ?: currentChannel
            if (browseLevel == null && nowPlaying != null && nowPlaying.contentType != com.example.data.model.ContentType.TV) {
                // movie: seek bar, long-press seeking, TV / Audio boost / Video / Audio / Subtitles / Settings bar
                com.example.ui.components.TvVodPlayerOverlay(
                    channel = nowPlaying,
                    isPlaying = isPlaying,
                    isBuffering = isBuffering,
                    errorMessage = playerError,
                    streamInfo = streamInfo,
                    videoQuality = videoQuality,
                    audioBoostProfile = audioBoostProfile,
                    audioTracks = audioTracks,
                    subtitleTracks = subtitleTracks,
                    positionMs = { viewModel.playerManager.positionMs() },
                    durationMs = { viewModel.playerManager.durationMs() },
                    onSeekTo = { viewModel.playerManager.seekToMs(it) },
                    onTogglePlayPause = { viewModel.playerManager.togglePlayPause() },
                    onRetry = { viewModel.playVod(nowPlaying) },
                    onWatchTv = { viewModel.watchLastTv() },
                    onCycleAudioBoost = { viewModel.cycleAudioBoost() },
                    onOpenQualityPicker = { showQualityDialog = true },
                    onSelectAudioTrack = { viewModel.playerManager.selectTrack(it) },
                    onDisableAudio = { viewModel.playerManager.disableAudio() },
                    onOpenSubtitles = { showSubtitleDialog = true },
                    onOpenSettings = { openSettings(TiviSettingsRoute.Main) },
                    modifier = Modifier.fillMaxSize().zIndex(2f)
                )
            } else if (browseLevel == null) {
                TvPlayerOverlay(
                    channel = currentChannelLive ?: currentChannel,
                    isPlaying = isPlaying,
                    isBuffering = isBuffering,
                    errorMessage = playerError,
                    aspectMode = aspectMode,
                    streamInfo = streamInfo,
                    spoofProfile = globalSpoof,
                    videoQuality = videoQuality,
                    currentProgram = currentProgram,
                    channelsList = playingList,
                    onTogglePlayPause = { viewModel.playerManager.togglePlayPause() },
                    onNextChannel = { viewModel.playNextChannel() },
                    onPreviousChannel = { viewModel.playPreviousChannel() },
                    onSelectChannel = { viewModel.playChannelFromPlayer(it) },
                    onRecallPrevious = { viewModel.recallPreviousChannel() },
                    onToggleFavorite = { viewModel.toggleFavorite(it) },
                    onCycleAspect = { viewModel.cycleAspect() },
                    onOpenQualityPicker = { showQualityDialog = true },
                    onOpenEpgGuide = { showEpgGuideDialog = true },
                    audioBoostProfile = audioBoostProfile,
                    onCycleAudioBoost = { viewModel.cycleAudioBoost() },
                    onOpenAudioTracks = { showAudioDialog = true },
                    audioTracks = audioTracks,
                    onSelectAudioTrack = { viewModel.playerManager.selectTrack(it) },
                    onDisableAudio = { viewModel.playerManager.disableAudio() },
                    onOpenSubtitles = { showSubtitleDialog = true },
                    epgByChannel = epgByChannel,
                    recentChannels = recentChannels,
                    onRemoveRecent = { viewModel.removeFromHistory(it) },
                    onClearRecent = { viewModel.clearHistory() },
                    playlists = playlists,
                    onAddPlaylist = { showAddDialog = true },
                    onClosePlayer = exitTv,
                    onOpenSpoofSettings = { showSpoofDialog = true },
                    onOpenPlaylists = { openSettings(TiviSettingsRoute.Playlists) },
                    onOpenPlaylistSettings = { id -> openSettings(TiviSettingsRoute.PlaylistDetail(id)) },
                    onOpenEpgSettings = { openSettings(TiviSettingsRoute.Epg) },
                    onOpenSettings = { openSettings(TiviSettingsRoute.Main) },
                    onOpenGroups = {
                        tvBrowseOverlay = true
                        tvBrowse = TvBrowseLevel.GROUPS
                    },
                    modifier = Modifier.fillMaxSize().zIndex(2f)   // above the shared video
                )
            }
        }
    } else {
        // Mobile Touch Mode
        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            containerColor = DarkBackground,
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
                                // Search
                                IconButton(
                                    onClick = { isSearchActive = !isSearchActive },
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(Icons.Default.Search, contentDescription = "Search", tint = Color.White)
                                }

                                // Movies / Shows keep only the search button: no EPG guide, no overflow menu
                                if (!isVodSection) {
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
                                                openSettings(TiviSettingsRoute.Main)
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
                                }   // !isVodSection
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
                                    placeholder = {
                                        Text(
                                            when (section) {
                                                MobileSection.MOVIES -> "Search movies..."
                                                MobileSection.SHOWS -> "Search shows..."
                                                else -> "Search channel or category..."
                                            }
                                        )
                                    },
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
            // nothing to watch yet (no playlist, or an empty one): the start page replaces chips, list and buttons
            val mobileStartup = !isLoading && (playlists.isEmpty() ||
                (channels.isEmpty() && searchQuery.isEmpty() && selectedCategory == "All" &&
                    mobilePlaylistId == null && !hasMovies && !hasShows))
            val showNavBar = dbSettled && !mobileStartup && !isMobileFullscreen && !isInPipMode && !showPlaylistManager
            val navInset = androidx.compose.foundation.layout.WindowInsets.navigationBars
                .asPaddingValues().calculateBottomPadding()
            val listBottomPadding = if (showNavBar) 84.dp + navInset else navInset

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    // edge to edge at the bottom: only the top bar is kept clear, content scrolls under the system navigation bar
                    .padding(if (isMobileFullscreen || isInPipMode) androidx.compose.foundation.layout.PaddingValues(0.dp)
                             else androidx.compose.foundation.layout.PaddingValues(top = paddingValues.calculateTopPadding()))
            ) {
            Column(modifier = Modifier.fillMaxSize()) {
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
                        if (vodPlaying != null) {
                            MobileVodPlayer(
                                player = player,
                                channel = mobileVodFavOverride?.takeIf { it.first == vodPlaying.id }
                                    ?.let { vodPlaying.copy(isFavorite = it.second) } ?: vodPlaying,
                                isPlaying = isPlaying,
                                isBuffering = isBuffering,
                                errorMessage = playerError,
                                aspectMode = aspectMode,
                                streamInfo = streamInfo,
                                videoQuality = videoQuality,
                                audioBoostProfile = audioBoostProfile,
                                audioTracks = audioTracks,
                                positionMs = { viewModel.playerManager.positionMs() },
                                durationMs = { viewModel.playerManager.durationMs() },
                                onSeekTo = { viewModel.playerManager.seekToMs(it) },
                                onTogglePlayPause = { viewModel.playerManager.togglePlayPause() },
                                onToggleMyList = {
                                    val now = mobileVodFavOverride?.takeIf { it.first == vodPlaying.id }?.second ?: vodPlaying.isFavorite
                                    viewModel.toggleVodFavorite(vodPlaying.copy(isFavorite = now))
                                    mobileVodFavOverride = vodPlaying.id to !now
                                },
                                onCycleAspect = { viewModel.cycleAspect() },
                                onCycleAudioBoost = { viewModel.cycleAudioBoost() },
                                onOpenQualityPicker = { showQualityDialog = true },
                                onOpenAudioTracks = { showAudioDialog = true },
                                onOpenSubtitles = { showSubtitleDialog = true },
                                onRetry = { viewModel.playVod(vodPlaying) },
                                // BACK / close: the movie stops and its details page (mobileVodDetailId) comes back
                                onClose = { viewModel.closePlayer() },
                                isInPipMode = isInPipMode,
                                isFullscreen = isMobileFullscreen,
                                onEnterPip = onEnterPip,
                                onToggleFullscreen = { isMobileFullscreen = !isMobileFullscreen }
                            )
                        } else TvBuddyPlayerScreen(
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
                            onOpenSpoofSettings = { showSpoofDialog = true },
                            audioBoostProfile = audioBoostProfile,
                            onCycleAudioBoost = { viewModel.cycleAudioBoost() },
                            audioTracks = audioTracks,
                            onOpenSettings = { openSettings(TiviSettingsRoute.Main) },
                            channelsList = selectionChannels,
                            channelNumbering = browseNumbering,
                            allPrograms = allCurrentPrograms,
                            groupSections = tvGroupSections,
                            groupSelection = com.example.ui.components.MobileGroupSelection(mobilePlaylistId, selectedCategory),
                            onSelectChannel = { viewModel.playChannel(it) },
                            onSelectGroup = { pid, category ->
                                viewModel.selectMobilePlaylist(pid)
                                viewModel.selectCategory(category)
                            }
                        )
                    }
                }

                if (!isMobileFullscreen && !isInPipMode) {
                    if (!dbSettled && playlists.isEmpty()) {
                        // database still loading: plain background instead of flashing the start page
                        Box(modifier = Modifier.fillMaxWidth().weight(1f).background(DarkBackground))
                    } else if (mobileStartup) {
                        StartupEmptyState(
                            onAddPlaylist = { showAddDialog = true },
                            onOpenSettings = { openSettings(TiviSettingsRoute.Main) },
                            modifier = Modifier.weight(1f),
                            compact = true,
                            touchMode = true,
                            background = DarkBackground
                        )
                    } else if (isVodSection) {
                        // Movies / Shows: groups of the TV page as chips + poster grid; the selected poster's info sits in the player's place
                        val vodType = if (section == MobileSection.MOVIES) com.example.data.model.ContentType.MOVIE
                                      else com.example.data.model.ContentType.SHOW
                        MobileVodSection(
                            contentType = vodType,
                            itemsFlow = remember(vodType) { viewModel.vodChannels(vodType) },
                            historyFlow = remember(vodType) { viewModel.vodHistory(vodType) },
                            groups = allChannelGroups,
                            playlists = playlists,
                            playlistFilterId = mobilePlaylistId,
                            searchQuery = searchQuery,
                            detailId = mobileVodDetailId,
                            onDetailChange = { id ->
                                // opening another poster's details while a movie plays stops that movie
                                if (id != null && vodPlaying != null) viewModel.closePlayer()
                                mobileVodDetailId = id
                            },
                            playing = vodPlaying,
                            onPlay = { item, startMs -> viewModel.playVod(item, startMs) },
                            onToggleMyList = { viewModel.toggleVodFavorite(it) },
                            onOpenTrailer = { viewModel.openTrailer(it) },
                            onOpenExternal = { viewModel.openInExternalPlayer(it) },
                            onRemoveHistory = { viewModel.removeFromHistory(it) },
                            resumeOf = { viewModel.vodResumeMs(it) },
                            loadInfo = { viewModel.loadVodInfo(it) },
                            onOpenGroup = { id, title -> viewModel.openVodGroup(id, vodType, title) },
                            onNeedMoreAll = { viewModel.loadMoreVodAll(vodType) },
                            loading = vodLoading,
                            bottomPadding = listBottomPadding,
                            modifier = Modifier.weight(1f)
                        )
                    } else {
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

                        if (channels.isEmpty() && !isLoading) {
                            // search / group / playlist with nothing in it
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f)
                                    .padding(24.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(Icons.Default.Tv, contentDescription = null, tint = Color(0xFF64748B), modifier = Modifier.size(48.dp))
                                    Spacer(modifier = Modifier.height(10.dp))
                                    Text(
                                        text = if (searchQuery.isNotEmpty()) "No matching channels" else "No channels here",
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 15.sp
                                    )
                                }
                            }
                        } else {
                            // Channel List
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f),
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = listBottomPadding),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                items(channels, key = { it.id }) { item ->
                                    val channelProg = EpgMatcher.currentProgramOf(item, allCurrentPrograms)
                                    ChannelListItem(
                                        channel = item,
                                        isSelected = item.id == currentChannel?.id,
                                        currentProgram = channelProg,
                                        number = browseNumbering[item.id],
                                        onSelect = { viewModel.playChannel(item) },
                                        onToggleFavorite = { viewModel.toggleFavorite(item) }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Floating liquid-glass navbar + playlist picker
            if (showNavBar) {
                if (showMobilePlaylists) {
                    MobilePlaylistPanel(
                        playlists = playlists,
                        selectedPlaylistId = mobilePlaylistId,
                        onSelect = {
                            viewModel.selectMobilePlaylist(it)
                            showMobilePlaylists = false
                        },
                        onAddPlaylist = {
                            showMobilePlaylists = false
                            showAddDialog = true
                        },
                        onDismiss = { showMobilePlaylists = false },
                        modifier = Modifier.fillMaxSize(),
                        opacity = navbarOpacity
                    )
                }
                MobileGlassNavBar(
                    section = section,
                    hasMovies = hasMovies,
                    hasShows = hasShows,
                    playlistsOpen = showMobilePlaylists,
                    onSelectSection = {
                        mobileSection = it
                        showMobilePlaylists = false
                    },
                    onTogglePlaylists = { showMobilePlaylists = !showMobilePlaylists },
                    onOpenSettings = {
                        showMobilePlaylists = false
                        openSettings(TiviSettingsRoute.Main)
                    },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(bottom = 12.dp),
                    opacity = navbarOpacity
                )
            }
            }
        }
    }

    // Dialogs
    if (showAddDialog) {
        val importState by viewModel.importState.collectAsStateWithLifecycle()
        AddPlaylistDialog(
            isTvMode = isTvMode,
            onDismiss = {
                viewModel.resetImportState()
                showAddDialog = false
            },
            onAddM3u = { name, src, isRaw, spoof -> viewModel.addM3uPlaylist(name, src, isRaw, spoof) },
            onAddXtream = { name, srv, u, p, spoof, tv, vod -> viewModel.addXtreamPlaylist(name, srv, u, p, spoof, tv, vod) },
            onAddStalker = { name, portal, mac, spoof, adv ->
                viewModel.addStalkerPlaylist(
                    name, portal, mac, spoof,
                    adv.username, adv.password, adv.deviceId, adv.deviceId2, adv.serial,
                    adv.includeTv, adv.includeVod
                )
            },
            onAddJson = { name, src, isRaw, spoof -> viewModel.addJsonPlaylist(name, src, isRaw, spoof) },
            importState = importState,
            onFinishImport = { id, name -> viewModel.finishImport(id, name) },
            onDiscardImport = { id -> viewModel.discardImport(id) }
        )
    }

    if (showTvSearch) {
        TvSearchDialog(
            query = searchQuery,
            onQueryChange = { viewModel.setSearchQuery(it) },
            onDismiss = { showTvSearch = false }
        )
    }

    if (showEpgGuideDialog) {
        val epgWindow by viewModel.epgWindowPrograms.collectAsStateWithLifecycle()
        com.example.ui.components.MobileEpgGuide(
            channels = channels,
            programs = if (epgWindow.isNotEmpty()) epgWindow else allCurrentPrograms,
            playingId = currentChannel?.id,
            onPlayChannel = {
                viewModel.playChannel(it)
                showEpgGuideDialog = false
            },
            onDismiss = { showEpgGuideDialog = false }
        )
    }

    if (showQualityDialog) {
        val videoTracks by viewModel.videoTracks.collectAsStateWithLifecycle()
        QualityPickerDialog(
            videoTracks = videoTracks,
            onSelectTrack = { viewModel.selectVideoTrack(it) },
            onDismiss = { showQualityDialog = false },
            isTvMode = isTvMode
        )
    }

    if (showSpoofDialog) {
        SpoofSettingsDialog(
            currentProfile = globalSpoof,
            onDismiss = { showSpoofDialog = false },
            onSaveProfile = { preset, ua, headers -> viewModel.setSpoofPreset(preset, ua, headers) }
        )
    }

    spoofPlaylistId?.let { id ->
        playlists.firstOrNull { it.id == id }?.let { pl ->
            SpoofSettingsDialog(
                currentProfile = pl.toSpoofProfile(),
                onDismiss = { spoofPlaylistId = null },
                onSaveProfile = { preset, ua, headers -> viewModel.setPlaylistSpoof(pl.id, preset, ua, headers) }
            )
        }
    }

    if (showDrmDebugger) {
        DrmDebuggerDialog(
            onDismiss = { showDrmDebugger = false },
            onTestStream = { viewModel.playChannel(it) }
        )
    }

    if (showPlaylistManager) {
        // These flows change while playing (log lines, WARP state, EPG updates). Collected here, only while the
        // settings page is open, they no longer recompose the whole home screen during playback.
        val updateStates by viewModel.updateStates.collectAsStateWithLifecycle()
        val playbackLogEnabled by viewModel.playbackLogEnabled.collectAsStateWithLifecycle()
        val playbackLogCount by viewModel.playbackLogCount.collectAsStateWithLifecycle()
        val warpState by viewModel.warpState.collectAsStateWithLifecycle()
        PlaylistManagerDialog(
            playlists = playlists,
            onDismiss = { showPlaylistManager = false },
            onAddNewPlaylist = { showAddDialog = true },
            onDeletePlaylist = { viewModel.deletePlaylist(it) },
            initialRoute = settingsRoute,
            playlistsSorting = playlistsSorting,
            onChangePlaylistsSorting = { viewModel.setPlaylistsSorting(it) },
            onReorderPlaylists = { viewModel.reorderPlaylists(it) },
            onUpdatePlaylist = { viewModel.updatePlaylist(it) },
            allGroups = allChannelGroups,
            onSetAllGroupsVisibility = { id, visible -> viewModel.setAllGroupsVisibility(id, visible) },
            onSetGroupVisibility = { id, title, visible, type -> viewModel.setGroupVisibility(id, title, visible, type) },
            onSetGroupsVisibility = { id, changes, type -> viewModel.setGroupsVisibility(id, changes, type) },
            onReorderGroups = { id, titles, type -> viewModel.reorderGroups(id, titles, type) },
            onApplyPortalParams = { viewModel.applyPortalParams(it) },
            onLoadAccountInfo = { viewModel.refreshAccountInfo(it) },
            epgSources = allEpgSources,
            playlistEpgCrossRefs = allPlaylistEpgCrossRefs,
            onAddEpgSource = { viewModel.addEpgSource(it) },
            onDeleteEpgSource = { viewModel.deleteEpgSource(it) },
            onSetDefaultEpgSource = { viewModel.setDefaultEpgSource(it) },
            onTogglePlaylistEpgAssignment = { pl, src, assign -> viewModel.togglePlaylistEpgAssignment(pl, src, assign) },
            onReorderPlaylistEpgPriority = { pl, ids -> viewModel.reorderPlaylistEpgPriority(pl, ids) },
            epgSettings = epgSettings,
            onUpdateEpgSettings = { viewModel.updateEpgSettings(it) },
            onUpdateAllPlaylists = { viewModel.refreshAllPlaylists() },
            onRefreshSinglePlaylist = { viewModel.refreshPlaylist(it) },
            updateStates = updateStates,
            onClearUpdateStates = { viewModel.clearUpdateStates() },
            playbackLogEnabled = playbackLogEnabled,
            playbackLogCount = playbackLogCount,
            onTogglePlaybackLog = { viewModel.setPlaybackLogging(it) },
            onExportPlaybackLog = { viewModel.exportPlaybackLog() },
            onOpenSpoofEngine = { showSpoofDialog = true },
            audioBoostProfile = audioBoostProfile,
            onSelectAudioBoost = { viewModel.setAudioBoostProfile(it) },
            warpState = warpState,
            onToggleWarp = { viewModel.setWarpEnabled(it) },
            onEditPlaylistSpoof = { spoofPlaylistId = it.id },
            onTriggerUpdateEpg = { viewModel.triggerUpdateEpg() },
            onTriggerClearEpg = { viewModel.triggerClearEpg() },
            navbarOpacity = if (isTvMode) null else navbarOpacity,
            onNavbarOpacityChange = { viewModel.setNavbarOpacity(it) },
            // phone: full-screen settings page with the same floating navbar (Settings highlighted)
            mobileNav = if (isTvMode) null else ({
                MobileGlassNavBar(
                    section = section,
                    hasMovies = hasMovies,
                    hasShows = hasShows,
                    playlistsOpen = false,
                    settingsOpen = true,
                    onSelectSection = {
                        mobileSection = it
                        showPlaylistManager = false
                    },
                    onTogglePlaylists = {
                        showPlaylistManager = false
                        showMobilePlaylists = true
                    },
                    onOpenSettings = {},
                    opacity = navbarOpacity
                )
            })
        )
    }

    if (showAudioDialog) {
        TrackSelectionDialog(
            title = "Audio tracks",
            tracks = audioTracks,
            isAudio = true,
            onDisableAudio = { viewModel.playerManager.disableAudio() },
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
