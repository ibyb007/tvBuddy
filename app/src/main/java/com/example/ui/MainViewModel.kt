package com.example.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.entity.ChannelEntity
import com.example.data.local.entity.ChannelGroupEntity
import com.example.data.local.entity.EpgSourceEntity
import com.example.data.local.entity.PlaylistEpgCrossRef
import com.example.data.local.entity.PlaylistEntity
import com.example.data.local.entity.ProgramEntity
import com.example.data.model.ContentType
import com.example.data.model.SpoofPreset
import com.example.data.model.SpoofProfile
import com.example.data.model.toSpoofProfile
import com.example.ui.dialogs.UpdateKeys
import com.example.ui.dialogs.UpdateRowState
import com.example.data.repository.TvBuddyRepository
import com.example.player.AspectMode
import com.example.player.StreamInfo
import com.example.player.TrackInfo
import com.example.player.TvBuddyPlayerManager
import com.example.ui.dialogs.EpgGlobalSettingsState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class UiMessage(
    val id: Long = System.currentTimeMillis(),
    val text: String,
    val isError: Boolean = false
)

/** What the TV groups panel has applied: all channels / favorites / one group, optionally limited to one playlist. */
enum class TvGroupKind { ALL, FAVORITES, GROUP }

data class TvGroupSelection(
    val playlistId: Long? = null,          // null = all playlists
    val kind: TvGroupKind = TvGroupKind.ALL,
    val groupTitle: String? = null         // only for kind == GROUP
)

/** One playlist with its visible groups, in the order the groups panel lists them. */
data class TvGroupSection(
    val playlistId: Long,
    val name: String,
    val groups: List<String>
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = TvBuddyRepository(application)
    val playerManager = TvBuddyPlayerManager(application)

    // true once the database delivered its first playlists / channels (so the UI never shows "no playlist" while loading)
    private val _playlistsLoaded = MutableStateFlow(false)
    private val _channelsLoaded = MutableStateFlow(false)
    val dataLoaded: StateFlow<Boolean> = combine(_playlistsLoaded, _channelsLoaded) { p, c -> p && c }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val allPlaylists = repository.allPlaylists.onEach { _playlistsLoaded.value = true }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    private val rawChannels = repository.allChannels.onEach { _channelsLoaded.value = true }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val playbackHistory = repository.playbackHistory.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    val allEpgSources = repository.allEpgSources.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    val allPlaylistEpgCrossRefs = repository.allPlaylistEpgCrossRefs.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    /** Groups of every content type (TV, movies, shows): what "Manage groups" lists. */
    val allChannelGroups = repository.allChannelGroups.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    /** Live TV groups only: movie / show groups must never hide or reorder live channels. */
    private val liveGroups = repository.liveChannelGroups.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    /** Recently played channels, most recent first (unique). */
    val recentChannels: StateFlow<List<ChannelEntity>> = combine(repository.playbackHistory, rawChannels) { history, channels ->
        val byId = channels.associateBy { it.id }
        val byUrl = channels.associateBy { it.streamUrl }
        history.mapNotNull { byId[it.channelId] ?: byUrl[it.streamUrl] }
            .distinctBy { it.id }
            .take(10)
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    private val epgPrefs = application.getSharedPreferences("tvbuddy_epg_settings", android.content.Context.MODE_PRIVATE)

    private val _epgSettings = MutableStateFlow(
        EpgGlobalSettingsState(
            pastDaysToKeep = epgPrefs.getInt("past_days", 3),
            storeProgramDescriptions = epgPrefs.getBoolean("store_desc", true),
            updateIntervalHours = epgPrefs.getInt("update_interval", 24),
            updateOnAppStart = epgPrefs.getBoolean("update_on_start", false),
            updateOnPlaylistsChange = epgPrefs.getBoolean("update_on_pl_change", false),
            latestUpdateStatus = repository.getLatestEpgStatus()
        )
    )
    val epgSettings: StateFlow<EpgGlobalSettingsState> = _epgSettings.asStateFlow()

    private val _playlistsSorting = MutableStateFlow("By name")
    val playlistsSorting: StateFlow<String> = _playlistsSorting.asStateFlow()

    private val prefs = application.getSharedPreferences("tvbuddy_prefs", android.content.Context.MODE_PRIVATE)
    private val pendingResumeId: Long = prefs.getLong(KEY_RESUME_ID, -1L)
    private val pendingResumeUrl: String? = prefs.getString(KEY_RESUME_URL, null)
    private var resumeHandled = false

    private val _selectedCategory = MutableStateFlow("All")
    val selectedCategory: StateFlow<String> = _selectedCategory.asStateFlow()

    /** Phone mode: opacity of the floating navbar and its playlist panel (0.1 - 1.0), saved across launches. */
    private val _navbarOpacity = MutableStateFlow(prefs.getFloat("navbar_opacity", 0.7f).coerceIn(0.1f, 1f))
    val navbarOpacity: StateFlow<Float> = _navbarOpacity.asStateFlow()

    fun setNavbarOpacity(value: Float) {
        val v = value.coerceIn(0.1f, 1f)
        _navbarOpacity.value = v
        prefs.edit().putFloat("navbar_opacity", v).apply()
    }

    /** Phone mode: playlist picked in the floating navbar (null = all playlists). */
    private val _mobilePlaylistId = MutableStateFlow<Long?>(null)
    val mobilePlaylistId: StateFlow<Long?> = _mobilePlaylistId.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _isTvMode = MutableStateFlow(false)
    val isTvMode: StateFlow<Boolean> = _isTvMode.asStateFlow()

    // ---- TV groups panel state (selection + per-group view options) ----
    private val _tvSelection = MutableStateFlow(loadTvSelection())
    val tvSelection: StateFlow<TvGroupSelection> = _tvSelection.asStateFlow()

    /** Keys ("playlistId_groupTitle") of groups that only show favourite channels. */
    private val _favOnlyGroups = MutableStateFlow(prefs.getStringSet(KEY_GROUP_FAV_ONLY, emptySet()).orEmpty().toSet())
    val favOnlyGroups: StateFlow<Set<String>> = _favOnlyGroups.asStateFlow()

    /** Keys of groups whose channels are sorted by name instead of playlist order. */
    private val _nameSortedGroups = MutableStateFlow(prefs.getStringSet(KEY_GROUP_NAME_SORT, emptySet()).orEmpty().toSet())

    // Playlists the user collapsed in the TV groups panel (key = playlist id, -1 = "All playlists"); remembered across launches
    private val _collapsedSections = MutableStateFlow(
        prefs.getStringSet(KEY_COLLAPSED_SECTIONS, emptySet()).orEmpty().mapNotNull { it.toLongOrNull() }.toSet()
    )
    val collapsedSections: StateFlow<Set<Long>> = _collapsedSections.asStateFlow()

    fun setSectionCollapsed(key: Long, collapsed: Boolean) {
        val cur = _collapsedSections.value
        val next = if (collapsed) cur + key else cur - key
        if (next == cur) return
        _collapsedSections.value = next
        prefs.edit().putStringSet(KEY_COLLAPSED_SECTIONS, next.map { it.toString() }.toSet()).apply()
    }
    val nameSortedGroups: StateFlow<Set<String>> = _nameSortedGroups.asStateFlow()

    private val _globalSpoof = MutableStateFlow(SpoofProfile(SpoofPreset.TIVIMATE))
    val globalSpoof: StateFlow<SpoofProfile> = _globalSpoof.asStateFlow()

    private val _uiMessage = MutableStateFlow<UiMessage?>(null)
    val uiMessage: StateFlow<UiMessage?> = _uiMessage.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    // Player state forwards
    val currentChannel: StateFlow<ChannelEntity?> = playerManager.currentChannel

    /** The playing channel re-read from the database, so favourite state etc. is always current. */
    val currentChannelLive: StateFlow<ChannelEntity?> = combine(playerManager.currentChannel, rawChannels) { cur, all ->
        cur?.let { c -> all.firstOrNull { it.id == c.id } ?: c }
    }.stateIn(viewModelScope, SharingStarted.Lazily, null)
    val isPlaying: StateFlow<Boolean> = playerManager.isPlaying
    val isBuffering: StateFlow<Boolean> = playerManager.isBuffering
    val playerError: StateFlow<String?> = playerManager.errorMessage
    val streamInfo: StateFlow<StreamInfo> = playerManager.streamInfo
    val aspectMode: StateFlow<AspectMode> = playerManager.aspectMode
    val videoQuality: StateFlow<com.example.player.VideoQuality> = playerManager.videoQuality
    val availableQualities: StateFlow<List<com.example.player.VideoQuality>> = playerManager.availableQualities
    val audioTracks: StateFlow<List<TrackInfo>> = playerManager.audioTracks
    val subtitleTracks: StateFlow<List<TrackInfo>> = playerManager.subtitleTracks
    val videoTracks: StateFlow<List<com.example.player.VideoTrackOption>> = playerManager.videoTracks
    val allCurrentPrograms: StateFlow<List<ProgramEntity>> =
        repository.allCurrentPrograms.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    /** Schedule window (past few hours .. next day) for the TV guide; only collected while the TV UI is shown. */
    val epgWindowPrograms: StateFlow<List<ProgramEntity>> =
        repository.epgWindowPrograms.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * The guide grouped per (lower-case) EPG channel id, each list sorted by start time. This used to be computed
     * inside composition on the main thread (twice: TV page and player overlay) - with a big guide that froze the
     * UI for a moment whenever a screen opened or the EPG refreshed. Now it is built once, on a background thread,
     * and shared. conflate(): while a rebuild runs, only the newest guide snapshot is kept.
     */
    val epgByChannel: StateFlow<Map<String, List<ProgramEntity>>> =
        combine(epgWindowPrograms, allCurrentPrograms) { window, current ->
            (if (window.isNotEmpty()) window else current)
                .groupBy { it.epgChannelId.lowercase() }
                .mapValues { (_, v) -> v.sortedBy { it.startTimeMs } }
        }
            .conflate()
            .flowOn(kotlinx.coroutines.Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    init {
        prefs.edit().remove(KEY_RESUME_ID).remove(KEY_RESUME_URL).apply()
        viewModelScope.launch {
            repository.initializeDefaultsIfNeeded()
        }
        repository.refreshStaleEpgs(_globalSpoof.value.getUserAgent())

        // Warm the logo cache in the background a few seconds after the channel list settles
        viewModelScope.launch {
            repository.allChannels.collectLatest { chs ->
                if (chs.isEmpty()) return@collectLatest
                kotlinx.coroutines.delay(4_000)
                LogoLoader.prefetch(getApplication<Application>(), chs.mapNotNull { it.logoUrl })
            }
        }

        // Make sure every playlist has its group rows (older playlists were imported before groups were tracked),
        // so "Manage groups" always lists all groups of a playlist.
        viewModelScope.launch {
            combine(repository.allPlaylists, repository.allChannels, repository.liveChannelGroups) { pls, chs, groups ->
                Triple(pls, chs, groups)
            }.flowOn(kotlinx.coroutines.Dispatchers.Default).collect { (pls, chs, groups) ->
                if (chs.isEmpty()) return@collect
                val known = groups.groupBy { it.playlistId }.mapValues { e -> e.value.map { it.groupTitle }.toSet() }
                for (pl in pls) {
                    val plChannels = chs.filter { it.playlistId == pl.id }
                    val titles = plChannels.map { it.groupTitle }.filter { it.isNotBlank() }.toSet()
                    if (titles.isNotEmpty() && !known[pl.id].orEmpty().containsAll(titles)) {
                        repository.syncGroupsForPlaylist(pl.id, plChannels, pl.showNewlyAddedGroups)
                    }
                }
            }
        }
    }

    fun resumeLastPlayback(channels: List<ChannelEntity>) {
        if (resumeHandled || channels.isEmpty() || currentChannel.value != null) return
        resumeHandled = true
        if (pendingResumeId < 0L && pendingResumeUrl.isNullOrBlank()) return
        // look in the visible list first, then in ALL channels: the last played channel may sit in a
        // group that is not currently selected/visible, and it must still resume.
        fun find(list: List<ChannelEntity>) = list.firstOrNull { it.id == pendingResumeId }
            ?: list.firstOrNull { !pendingResumeUrl.isNullOrBlank() && it.streamUrl == pendingResumeUrl }
        val target = find(channels) ?: find(rawChannels.value) ?: return
        playChannel(target)
        // the groups panel must open on the group this channel plays from, not on whatever was browsed last
        selectTvGroup(_playContext.value)
    }

    /** Groups of each active playlist for the TV groups panel (hidden groups left out). */
    val tvGroupSections: StateFlow<List<TvGroupSection>> = combine(
        rawChannels,
        allPlaylists,
        liveGroups
    ) { channels, playlists, groups ->
        val titlesByPlaylist = channels.groupBy { it.playlistId }
            .mapValues { (_, list) -> list.map { it.groupTitle }.filter { it.isNotBlank() }.distinct() }
        val groupsByPlaylist = groups.groupBy { it.playlistId }
        playlists.filter { it.isActive }.map { pl ->
            val known = groupsByPlaylist[pl.id].orEmpty()
            val have = titlesByPlaylist[pl.id].orEmpty()
            val haveSet = have.toSet()
            val knownTitles = known.map { it.groupTitle }.toSet()
            val hidden = known.filter { !it.isVisible }.map { it.groupTitle }.toSet()
            val sortedKnown = when (pl.groupsSorting) {
                "By name" -> known.sortedBy { it.groupTitle.lowercase() }
                "Manual" -> known.sortedBy { it.sortOrder }
                else -> known.sortedBy { it.originalOrder }
            }
            val ordered = sortedKnown.map { it.groupTitle }.filter { it in haveSet } +
                have.filter { it !in knownTitles }
            TvGroupSection(pl.id, pl.name, ordered.filter { it !in hidden })
        }
    }.flowOn(kotlinx.coroutines.Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    /**
     * Phone mode: the group chips. The same live TV groups the TV page shows (portal / Xtream / M3U order, hidden
     * groups and inactive playlists left out), so Stalker portals and Xtream accounts get their groups there too.
     */
    val categories: StateFlow<List<String>> = combine(tvGroupSections, _mobilePlaylistId) { sections, plId ->
        val shown = if (plId == null) sections else sections.filter { it.playlistId == plId }
        listOf("All", "★ Favorites") + shown.flatMap { it.groups }.distinct()
    }
        .stateIn(viewModelScope, SharingStarted.Lazily, listOf("All", "★ Favorites"))

    private data class FilterState(
        val category: String,
        val query: String,
        val tvMode: Boolean,
        val selection: TvGroupSelection,
        val favOnly: Set<String>,
        val nameSorted: Set<String>,
        val mobilePlaylistId: Long? = null
    )

    private val groupViewPrefs = combine(_favOnlyGroups, _nameSortedGroups) { fav, byName -> fav to byName }

    private val modeState = combine(_isTvMode, _mobilePlaylistId) { tvMode, plId -> tvMode to plId }

    private val filterState = combine(
        _selectedCategory,
        _searchQuery,
        modeState,
        _tvSelection,
        groupViewPrefs
    ) { category, query, mode, selection, viewPrefs ->
        FilterState(category, query, mode.first, selection, viewPrefs.first, viewPrefs.second, mode.second)
    }

    /** Applies a TV group selection (playlist / favourites / group, fav-only + name-sort options) to the channel list. */
    private fun applySelection(
        channels: List<ChannelEntity>,
        playlists: List<PlaylistEntity>,
        groups: List<ChannelGroupEntity>,
        selection: TvGroupSelection,
        favOnly: Set<String>,
        nameSorted: Set<String>
    ): List<ChannelEntity> {
        val activePlaylistIds = if (playlists.isEmpty()) null else playlists.filter { it.isActive }.map { it.id }.toSet()
        // playlist id -> hidden group titles: a set lookup per channel instead of building a "id_title" string
        // for every channel of the playlist on every group switch (tens of thousands of allocations)
        val hiddenByPlaylist: Map<Long, Set<String>> = groups.filter { !it.isVisible }
            .groupBy({ it.playlistId }, { it.groupTitle })
            .mapValues { (_, titles) -> titles.toHashSet() }

        // A selection that points at a playlist that no longer exists behaves like "all playlists"
        val sel = selection.let { s ->
            if (s.playlistId != null && playlists.none { it.id == s.playlistId }) TvGroupSelection() else s
        }
        val selKey = if (sel.kind == TvGroupKind.GROUP && sel.playlistId != null) "${sel.playlistId}_${sel.groupTitle}" else null

        val filtered = channels.filter { channel ->
            if (activePlaylistIds != null && channel.playlistId !in activePlaylistIds) return@filter false
            if (hiddenByPlaylist[channel.playlistId]?.contains(channel.groupTitle) == true) return@filter false
            if (sel.playlistId != null && channel.playlistId != sel.playlistId) return@filter false
            when (sel.kind) {
                TvGroupKind.ALL -> true
                TvGroupKind.FAVORITES -> channel.isFavorite
                TvGroupKind.GROUP ->
                    channel.groupTitle == sel.groupTitle && (selKey == null || selKey !in favOnly || channel.isFavorite)
            }
        }
        return if (selKey != null && selKey in nameSorted) filtered.sortedBy { it.name.lowercase() } else filtered
    }

    /**
     * The channels of the current selection (TV: group picked in the groups panel; phone: category chip),
     * WITHOUT the search filter. A channel's number is its 1-based position in this list.
     */
    val selectionChannels: StateFlow<List<ChannelEntity>> = combine(
        rawChannels,
        allPlaylists,
        liveGroups,
        filterState
    ) { channels, playlists, groups, fs ->
        if (fs.tvMode) {
            applySelection(channels, playlists, groups, fs.selection, fs.favOnly, fs.nameSorted)
        } else {
            // phone mode: category chip
            val all = applySelection(channels, playlists, groups, TvGroupSelection(playlistId = fs.mobilePlaylistId), emptySet(), emptySet())
            when (fs.category) {
                "All" -> all
                "★ Favorites" -> all.filter { it.isFavorite }
                else -> all.filter { it.groupTitle.equals(fs.category, ignoreCase = true) }
            }
        }
    }.flowOn(kotlinx.coroutines.Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    // Filtered channels respecting active playlists and group visibility, plus the search box.
    val visibleChannels: StateFlow<List<ChannelEntity>> = combine(selectionChannels, _searchQuery) { list, query ->
        if (query.isBlank()) list
        else list.filter { it.name.contains(query, ignoreCase = true) || it.groupTitle.contains(query, ignoreCase = true) }
    }.flowOn(kotlinx.coroutines.Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    // ---- Playing context (TV) ----
    // The list the playing channel was started from. Its numbering ("5" in Sports) and the UP/DOWN zapping
    // follow it, independent of whatever group is merely being browsed in the groups panel.
    private val _playContext = MutableStateFlow(loadPlayContext())
    val playContext: StateFlow<TvGroupSelection> = _playContext.asStateFlow()

    /** Channels of the playing context (no search filter). Falls back to all channels when the playing channel isn't in it. */
    val playingList: StateFlow<List<ChannelEntity>> = combine(
        rawChannels,
        allPlaylists,
        liveGroups,
        combine(_playContext, groupViewPrefs, playerManager.currentChannel) { c, v, cur -> Triple(c, v, cur) }
    ) { channels, playlists, groups, (ctx, viewPrefs, cur) ->
        val inContext = applySelection(channels, playlists, groups, ctx, viewPrefs.first, viewPrefs.second)
        if (cur == null || inContext.any { it.id == cur.id }) inContext
        else applySelection(channels, playlists, groups, TvGroupSelection(), emptySet(), emptySet())
    }.flowOn(kotlinx.coroutines.Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    /** Makes [selection] the list used for numbering / zapping of the playing channel. */
    fun setPlayContext(selection: TvGroupSelection) {
        if (_playContext.value == selection) return
        _playContext.value = selection
        prefs.edit()
            .putLong(KEY_CTX_PLAYLIST, selection.playlistId ?: -1L)
            .putString(KEY_CTX_KIND, selection.kind.name)
            .putString(KEY_CTX_GROUP, selection.groupTitle)
            .apply()
    }

    /** Plays a channel picked from the TV page: the group being browsed becomes the playing context. */
    fun playChannelFromSelection(channel: ChannelEntity) {
        setPlayContext(_tvSelection.value)
        playChannelIfNeeded(channel)
    }

    /** Opening the TV page from playback starts on the group the playing channel belongs to. */
    fun syncSelectionToPlayContext() {
        if (currentChannel.value == null) return
        val ctx = _playContext.value
        // the playlist the channel plays from is always shown expanded when the page opens (collapsed ones stay collapsed)
        setSectionCollapsed(ctx.playlistId ?: -1L, false)
        selectTvGroup(ctx)
    }

    private fun loadPlayContext(): TvGroupSelection {
        if (!prefs.contains(KEY_CTX_KIND)) return loadTvSelection()
        val pid = prefs.getLong(KEY_CTX_PLAYLIST, -1L).takeIf { it >= 0L }
        val kind = runCatching { TvGroupKind.valueOf(prefs.getString(KEY_CTX_KIND, null) ?: "ALL") }
            .getOrDefault(TvGroupKind.ALL)
        val group = prefs.getString(KEY_CTX_GROUP, null)
        return if (kind == TvGroupKind.GROUP && (pid == null || group == null)) TvGroupSelection()
        else TvGroupSelection(pid, kind, group)
    }

    init {
        // Drop a stored selection whose playlist / group has been deleted, hidden or deactivated
        viewModelScope.launch {
            combine(tvGroupSections, _tvSelection) { sections, sel -> sections to sel }
                .collect { (sections, sel) ->
                    if (sections.isEmpty()) return@collect
                    val pid = sel.playlistId ?: return@collect
                    val section = sections.firstOrNull { it.playlistId == pid }
                    when {
                        section == null -> selectTvGroup(TvGroupSelection())
                        sel.kind == TvGroupKind.GROUP && sel.groupTitle !in section.groups ->
                            selectTvGroup(TvGroupSelection(pid, TvGroupKind.ALL))
                    }
                }
        }
    }

    fun selectCategory(category: String) {
        _selectedCategory.value = category
    }

    /** Phone navbar: show one playlist (null = all playlists). Group chips go back to "All". */
    fun selectMobilePlaylist(playlistId: Long?) {
        _mobilePlaylistId.value = playlistId
        _selectedCategory.value = "All"
    }

    // ---- TV groups panel actions ----
    fun selectTvGroup(selection: TvGroupSelection) {
        if (_tvSelection.value == selection) return
        _tvSelection.value = selection
        prefs.edit()
            .putLong(KEY_SEL_PLAYLIST, selection.playlistId ?: -1L)
            .putString(KEY_SEL_KIND, selection.kind.name)
            .putString(KEY_SEL_GROUP, selection.groupTitle)
            .apply()
    }

    fun toggleGroupFavoritesOnly(playlistId: Long, groupTitle: String) {
        val key = "${playlistId}_$groupTitle"
        val next = _favOnlyGroups.value.let { if (key in it) it - key else it + key }
        _favOnlyGroups.value = next
        prefs.edit().putStringSet(KEY_GROUP_FAV_ONLY, next).apply()
    }

    fun toggleGroupNameSort(playlistId: Long, groupTitle: String) {
        val key = "${playlistId}_$groupTitle"
        val next = _nameSortedGroups.value.let { if (key in it) it - key else it + key }
        _nameSortedGroups.value = next
        prefs.edit().putStringSet(KEY_GROUP_NAME_SORT, next).apply()
    }

    /** Hides a group (same flag as "Manage groups") and falls back to the playlist's channels if it was selected. */
    fun hideGroup(playlistId: Long, groupTitle: String) {
        setGroupVisibility(playlistId, groupTitle, false)
        val sel = _tvSelection.value
        if (sel.kind == TvGroupKind.GROUP && sel.playlistId == playlistId && sel.groupTitle == groupTitle) {
            selectTvGroup(TvGroupSelection(playlistId, TvGroupKind.ALL))
        }
    }

    private fun loadTvSelection(): TvGroupSelection {
        val pid = prefs.getLong(KEY_SEL_PLAYLIST, -1L).takeIf { it >= 0L }
        val kind = runCatching { TvGroupKind.valueOf(prefs.getString(KEY_SEL_KIND, null) ?: "ALL") }
            .getOrDefault(TvGroupKind.ALL)
        val group = prefs.getString(KEY_SEL_GROUP, null)
        return if (kind == TvGroupKind.GROUP && (pid == null || group == null)) TvGroupSelection()
        else TvGroupSelection(pid, kind, group)
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun setTvMode(tvMode: Boolean) {
        _isTvMode.value = tvMode
    }

    fun setSpoofPreset(preset: SpoofPreset, customUserAgent: String = "", customHeaders: Map<String, String> = emptyMap()) {
        _globalSpoof.value = SpoofProfile(preset, customUserAgent, customHeaders)
        // the signature lives in each playlist now: this global choice applies to all of them
        // (a single playlist can still be changed afterwards from its own page)
        viewModelScope.launch {
            repository.setAllPlaylistsSpoof(preset, customUserAgent, customHeaders)
            restartCurrentChannel(null, _globalSpoof.value)
        }
        _uiMessage.value = UiMessage(text = "Player spoofing set to: ${preset.displayName}")
    }

    /** Player spoofing engine of one playlist (playlist page > Player spoofing engine). */
    fun setPlaylistSpoof(playlistId: Long, preset: SpoofPreset, customUserAgent: String = "", customHeaders: Map<String, String> = emptyMap()) {
        viewModelScope.launch {
            repository.setPlaylistSpoof(playlistId, preset, customUserAgent, customHeaders)
            restartCurrentChannel(playlistId, SpoofProfile(preset, customUserAgent, customHeaders))
            _uiMessage.value = UiMessage(text = "Player spoofing for this playlist: ${preset.displayName}")
        }
    }

    /** Re-opens the playing channel so a new spoofing engine takes effect immediately. */
    private suspend fun restartCurrentChannel(onlyPlaylistId: Long?, profile: SpoofProfile) {
        val cur = currentChannel.value ?: return
        if (onlyPlaylistId != null && cur.playlistId != onlyPlaylistId) return
        val latest = repository.getChannel(cur.id) ?: cur
        playerManager.playChannel(latest, allPlaylists.value.firstOrNull { it.id == latest.playlistId }?.toSpoofProfile() ?: profile)
    }

    // ---- Inline "Update ..." rows (spinner + result message) ----
    private val _updateStates = MutableStateFlow<Map<String, UpdateRowState>>(emptyMap())
    val updateStates: StateFlow<Map<String, UpdateRowState>> = _updateStates.asStateFlow()

    private fun setUpdateState(key: String, state: UpdateRowState) {
        _updateStates.update { it + (key to state) }
    }

    private fun isUpdateRunning(key: String) = _updateStates.value[key]?.running == true

    /** Forget finished results (running updates keep going) when the settings page is closed. */
    fun clearUpdateStates() {
        _updateStates.update { map -> map.filterValues { it.running } }
    }

    fun refreshPlaylist(playlistId: Long) {
        val key = UpdateKeys.playlist(playlistId)
        if (isUpdateRunning(key)) return
        setUpdateState(key, UpdateRowState(running = true))
        viewModelScope.launch {
            repository.refreshPlaylist(playlistId) { msg -> setUpdateState(key, UpdateRowState(running = true, message = msg)) }.fold(
                onSuccess = { setUpdateState(key, UpdateRowState(message = "The playlist is successfully updated")) },
                onFailure = { setUpdateState(key, UpdateRowState(message = "Update failed: ${it.localizedMessage ?: "unknown error"}", isError = true)) }
            )
        }
    }

    fun refreshAllPlaylists() {
        val key = UpdateKeys.ALL_PLAYLISTS
        if (isUpdateRunning(key)) return
        setUpdateState(key, UpdateRowState(running = true))
        viewModelScope.launch {
            val list = allPlaylists.value
            if (list.isEmpty()) {
                setUpdateState(key, UpdateRowState(message = "No playlists to update", isError = true))
                return@launch
            }
            var ok = 0
            val failures = mutableListOf<String>()
            for (pl in list) {
                repository.refreshPlaylist(pl.id) { msg -> setUpdateState(key, UpdateRowState(running = true, message = "${pl.name}: $msg")) }.fold(
                    onSuccess = { ok++ },
                    onFailure = { failures += "${pl.name}: ${it.localizedMessage ?: "error"}" }
                )
            }
            setUpdateState(
                key,
                if (failures.isEmpty()) UpdateRowState(message = "All playlists are successfully updated")
                else UpdateRowState(message = "$ok updated, ${failures.size} failed (${failures.first()})", isError = true)
            )
        }
    }

    // Previous channel (+ the group it was started from) for the RIGHT key; survives group / playlist switches
    private var previousEntry: Pair<ChannelEntity, TvGroupSelection>? = null
    private var currentEntryContext: TvGroupSelection = _playContext.value

    /** RIGHT key: back to the last played channel, in whatever group / playlist it was. */
    fun recallPreviousChannel() {
        val (channel, ctx) = previousEntry ?: return
        setPlayContext(ctx)
        playChannel(channel)
    }

    /**
     * Channel picked while watching (recent strip, channel list, guide, zapping). If it isn't part of the
     * playing group, the playing group becomes the channel's own group, so numbering, zapping and the TV page follow it.
     */
    fun playChannelFromPlayer(channel: ChannelEntity) {
        if (_isTvMode.value && !contextContains(channel)) {
            setPlayContext(
                if (playingList.value.any { it.id == channel.id }) TvGroupSelection()   // "All channels" fallback list
                else TvGroupSelection(channel.playlistId, TvGroupKind.GROUP, channel.groupTitle)
            )
        }
        playChannel(channel)
    }

    private fun contextContains(channel: ChannelEntity): Boolean =
        applySelection(
            rawChannels.value, allPlaylists.value, liveGroups.value,
            _playContext.value, _favOnlyGroups.value, _nameSortedGroups.value
        ).any { it.id == channel.id }

    /** The live channel that was on before a movie was started: the TV button of the movie player returns to it. */
    private var lastTvChannel: ChannelEntity? = null

    fun playChannel(channel: ChannelEntity) {
        stopVodProgress()   // save where the previous movie was left, before the player moves on
        // movies must not overwrite "previous channel" or the channel resumed at the next launch
        if (channel.contentType == ContentType.TV) {
            currentChannel.value?.let { cur ->
                if (cur.id != channel.id && cur.contentType == ContentType.TV) previousEntry = cur to currentEntryContext
            }
            currentEntryContext = _playContext.value
            lastTvChannel = channel
            prefs.edit().putLong(KEY_RESUME_ID, channel.id).putString(KEY_RESUME_URL, channel.streamUrl).apply()
        }
        resumeHandled = true
        playerManager.playChannel(
            channel,
            allPlaylists.value.firstOrNull { it.id == channel.playlistId }?.toSpoofProfile() ?: _globalSpoof.value
        )
        viewModelScope.launch { repository.recordHistory(channel) }
        // Xtream / Stalker: ask the portal for this channel's guide when no imported XMLTV covers it
        if (channel.contentType == ContentType.TV) viewModelScope.launch { repository.ensureChannelEpg(channel) }
        startVodProgress(channel)
    }

    /** Plays [channel] unless it is already playing/buffering/retrying; a dead or stale stream is restarted. */
    fun playChannelIfNeeded(channel: ChannelEntity) {
        if (channel.id != currentChannel.value?.id || !playerManager.isStreamActive()) playChannel(channel)
    }

    /** TV zaps inside the playing context (wraps around: UP on #1 -> last, DOWN on last -> #1); phone uses the visible list. */
    private fun zap(delta: Int) {
        val list = if (_isTvMode.value) playingList.value else visibleChannels.value
        if (list.isEmpty()) return
        val current = currentChannel.value ?: return
        val currentIndex = list.indexOfFirst { it.id == current.id }
        val next = if (currentIndex == -1) 0 else (currentIndex + delta + list.size) % list.size
        playChannel(list[next])
    }

    fun playNextChannel() = zap(+1)

    fun playPreviousChannel() = zap(-1)

    fun toggleFavorite(channel: ChannelEntity) {
        // use the latest stored state: callers may hold a stale copy (e.g. the player's current channel)
        val latest = rawChannels.value.firstOrNull { it.id == channel.id } ?: channel
        viewModelScope.launch { repository.toggleFavorite(latest.id, latest.isFavorite) }
    }

    fun cycleAspect() { playerManager.cycleAspectMode() }
    fun setAspect(mode: AspectMode) { playerManager.setAspectMode(mode) }

    // ---- Playlist Actions ----
    fun updatePlaylist(playlist: PlaylistEntity) {
        viewModelScope.launch {
            repository.updatePlaylist(playlist)
            _uiMessage.value = UiMessage(text = "Updated playlist: ${playlist.name}")
        }
    }

    fun deletePlaylist(playlist: PlaylistEntity) {
        viewModelScope.launch {
            repository.deletePlaylist(playlist)
            _uiMessage.value = UiMessage(text = "Deleted playlist: ${playlist.name}")
        }
    }

    fun reorderPlaylists(orderedIds: List<Long>) {
        viewModelScope.launch { repository.reorderPlaylists(orderedIds) }
    }

    fun setPlaylistsSorting(sorting: String) {
        _playlistsSorting.value = sorting
    }

    // ---- Group Actions ----
    fun setGroupVisibility(playlistId: Long, groupTitle: String, isVisible: Boolean, contentType: String = ContentType.TV) {
        viewModelScope.launch { repository.setGroupVisibility(playlistId, groupTitle, isVisible, contentType) }
    }

    // ---- Audio boost (Settings > Audio boost, TV long-OK menu) ----
    val audioBoostProfile: StateFlow<com.example.player.AudioBoostProfile> = playerManager.audioBoostProfile

    fun setAudioBoostProfile(profile: com.example.player.AudioBoostProfile) {
        playerManager.setAudioBoostProfile(profile)
        showBoostToast(profile)
    }

    fun cycleAudioBoost() {
        showBoostToast(playerManager.cycleAudioBoostProfile())
    }

    private fun showBoostToast(profile: com.example.player.AudioBoostProfile) {
        // the settings drawer / action bar cover the message area, so use a toast
        android.widget.Toast.makeText(
            getApplication<Application>(), "Audio Boost: ${profile.title}", android.widget.Toast.LENGTH_SHORT
        ).show()
    }

    // ---- WARP mode (Settings > WARP mode) ----
    val warpState: StateFlow<com.example.data.network.WarpManager.State> = com.example.data.network.WarpManager.state

    fun setWarpEnabled(enabled: Boolean) {
        com.example.data.network.WarpManager.setEnabled(enabled)
        if (enabled) {
            // reopen the playing channel once the tunnel is up so it streams through WARP
            viewModelScope.launch {
                val result = com.example.data.network.WarpManager.state
                    .first { it.phase != com.example.data.network.WarpManager.Phase.STARTING }
                if (result.phase == com.example.data.network.WarpManager.Phase.RUNNING) {
                    restartCurrentChannel(null, _globalSpoof.value)
                } else if (result.phase == com.example.data.network.WarpManager.Phase.ERROR) {
                    _uiMessage.value = UiMessage(text = "WARP: ${result.message ?: "failed to start"}", isError = true)
                }
            }
        } else {
            viewModelScope.launch { restartCurrentChannel(null, _globalSpoof.value) }
        }
    }

    // ---- Playback log (Settings > Playback log) ----
    val playbackLogEnabled: StateFlow<Boolean> = com.example.player.PlaybackLog.enabled
    val playbackLogCount: StateFlow<Int> = com.example.player.PlaybackLog.entryCount

    /** ON starts a fresh recording, OFF discards what was recorded. */
    fun setPlaybackLogging(enabled: Boolean) {
        com.example.player.PlaybackLog.setEnabled(enabled)
        _uiMessage.value = UiMessage(
            text = if (enabled) "Playback log is recording. Play the failing channel, then export it."
            else "Playback log turned off and cleared"
        )
    }

    fun exportPlaybackLog() {
        val app = getApplication<Application>()
        viewModelScope.launch {
            val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                com.example.player.PlaybackLog.exportToFile(app)
            }
            val text = result.fold(
                onSuccess = { "Playback log saved: $it" },
                onFailure = { "Could not save the log: ${it.localizedMessage ?: "unknown error"}" }
            )
            // the settings drawer covers the normal message area, so also show a toast
            android.widget.Toast.makeText(app, text, android.widget.Toast.LENGTH_LONG).show()
            _uiMessage.value = UiMessage(text = text, isError = result.isFailure)
        }
    }

    fun setGroupsVisibility(playlistId: Long, changes: Map<String, Boolean>, contentType: String = ContentType.TV) {
        viewModelScope.launch { repository.setGroupsVisibility(playlistId, changes, contentType) }
    }

    fun setAllGroupsVisibility(playlistId: Long, isVisible: Boolean, contentType: String = ContentType.TV) {
        viewModelScope.launch { repository.setAllGroupsVisibility(playlistId, isVisible, contentType) }
    }

    fun reorderGroups(playlistId: Long, orderedTitles: List<String>, contentType: String = ContentType.TV) {
        viewModelScope.launch { repository.reorderGroups(playlistId, orderedTitles, contentType) }
    }

    // ---- EPG Source Actions ----
    fun addEpgSource(url: String) {
        viewModelScope.launch {
            repository.addEpgSource(url)
            _uiMessage.value = UiMessage(text = "Added EPG source successfully")
        }
    }

    fun deleteEpgSource(source: EpgSourceEntity) {
        viewModelScope.launch {
            repository.deleteEpgSource(source)
            _uiMessage.value = UiMessage(text = "Removed EPG source")
        }
    }

    fun setDefaultEpgSource(id: Long) {
        viewModelScope.launch {
            repository.setDefaultEpgSource(id)
            _uiMessage.value = UiMessage(text = "Set as default EPG source")
        }
    }

    fun togglePlaylistEpgAssignment(playlistId: Long, epgSourceId: Long, assign: Boolean) {
        viewModelScope.launch { repository.togglePlaylistEpgAssignment(playlistId, epgSourceId, assign) }
    }

    fun reorderPlaylistEpgPriority(playlistId: Long, orderedSourceIds: List<Long>) {
        viewModelScope.launch { repository.reorderPlaylistEpgPriority(playlistId, orderedSourceIds) }
    }

    // ---- Global EPG Settings Actions ----
    fun updateEpgSettings(newState: EpgGlobalSettingsState) {
        _epgSettings.value = newState
        epgPrefs.edit()
            .putInt("past_days", newState.pastDaysToKeep)
            .putBoolean("store_desc", newState.storeProgramDescriptions)
            .putInt("update_interval", newState.updateIntervalHours)
            .putBoolean("update_on_start", newState.updateOnAppStart)
            .putBoolean("update_on_pl_change", newState.updateOnPlaylistsChange)
            .apply()
    }

    fun importXmltv(source: String, isRaw: Boolean) {
        viewModelScope.launch {
            _isLoading.value = true
            val settings = _epgSettings.value
            val result = repository.importXmltv(
                urlOrContent = source,
                isRawText = isRaw,
                userAgent = _globalSpoof.value.getUserAgent(),
                pastDaysToKeep = settings.pastDaysToKeep,
                storeDescriptions = settings.storeProgramDescriptions
            )
            _isLoading.value = false
            _epgSettings.value = _epgSettings.value.copy(latestUpdateStatus = repository.getLatestEpgStatus())
            result.fold(
                onSuccess = { count ->
                    _uiMessage.value = UiMessage(text = "Imported $count EPG programs successfully!")
                },
                onFailure = { err ->
                    _uiMessage.value = UiMessage(text = "EPG Import error: ${err.localizedMessage}", isError = true)
                }
            )
        }
    }

    fun triggerUpdateEpg() {
        val key = UpdateKeys.EPG
        if (isUpdateRunning(key)) return
        setUpdateState(key, UpdateRowState(running = true))
        viewModelScope.launch {
            val sources = allEpgSources.value.filter { it.isEnabled }
            if (sources.isEmpty()) {
                setUpdateState(key, UpdateRowState(message = "No EPG sources available to update", isError = true))
                return@launch
            }
            var totalCount = 0
            var failed = 0
            val settings = _epgSettings.value
            for (src in sources) {
                val res = repository.importXmltv(
                    urlOrContent = src.url,
                    isRawText = false,
                    userAgent = _globalSpoof.value.getUserAgent(),
                    pastDaysToKeep = settings.pastDaysToKeep,
                    storeDescriptions = settings.storeProgramDescriptions
                )
                res.onSuccess { totalCount += it }.onFailure { failed++ }
            }
            _epgSettings.value = _epgSettings.value.copy(latestUpdateStatus = repository.getLatestEpgStatus())
            setUpdateState(
                key,
                if (failed == sources.size) UpdateRowState(message = "EPG update failed", isError = true)
                else UpdateRowState(message = "EPG updated successfully ($totalCount programs)")
            )
        }
    }

    fun triggerClearEpg() {
        viewModelScope.launch {
            repository.clearAllEpg()
            _epgSettings.value = _epgSettings.value.copy(latestUpdateStatus = repository.getLatestEpgStatus())
            _uiMessage.value = UiMessage(text = "EPG cache cleared")
        }
    }

    // ---- Playlist Import Actions ----
    private val _importState = MutableStateFlow<ImportUiState>(ImportUiState.Idle)
    val importState: StateFlow<ImportUiState> = _importState.asStateFlow()

    private fun runImport(failPrefix: String, block: suspend (onProgress: (String) -> Unit) -> Result<TvBuddyRepository.ImportResult>) {
        if (_importState.value is ImportUiState.Processing) return
        _importState.value = ImportUiState.Processing()
        viewModelScope.launch {
            block { msg ->
                if (_importState.value is ImportUiState.Processing) _importState.value = ImportUiState.Processing(msg)
            }.fold(
                onSuccess = { _importState.value = ImportUiState.Done(it.playlistId, it.name, it.counts) },
                onFailure = { _importState.value = ImportUiState.Failed("$failPrefix: ${it.localizedMessage ?: "unknown error"}") }
            )
        }
    }

    fun addM3uPlaylist(name: String, source: String, isRawContent: Boolean = false, preset: SpoofPreset = SpoofPreset.TIVIMATE) =
        runImport("Failed to load M3U") { repository.addM3uPlaylist(name, source, isRawContent, preset) }

    fun addXtreamPlaylist(
        name: String, serverUrl: String, user: String, pass: String,
        preset: SpoofPreset = SpoofPreset.TIVIMATE,
        includeTv: Boolean = true, includeVod: Boolean = false
    ) = runImport("Xtream connection error") {
        repository.addXtreamPlaylist(name, serverUrl, user, pass, preset, includeTv, includeVod)
    }

    fun addStalkerPlaylist(
        name: String, portalUrl: String, mac: String,
        preset: SpoofPreset = SpoofPreset.TIVIMATE,
        username: String = "", password: String = "",
        deviceId: String = "", deviceId2: String = "", serial: String = "",
        includeTv: Boolean = true, includeVod: Boolean = false
    ) = runImport("Stalker portal error") { progress ->
        repository.addStalkerPlaylist(name, portalUrl, mac, preset, username, password, deviceId, deviceId2, serial, includeTv, includeVod, progress)
    }

    fun addJsonPlaylist(name: String, source: String, isRawContent: Boolean = false, preset: SpoofPreset = SpoofPreset.TIVIMATE) =
        runImport("JSON parsing error") { repository.addJsonPlaylist(name, source, isRawContent, preset) }

    /** "Done" on the processed screen: keeps the playlist (under the name typed there). */
    fun finishImport(playlistId: Long, name: String) {
        viewModelScope.launch {
            val pl = repository.getPlaylist(playlistId)
            if (pl != null && name.isNotBlank() && name.trim() != pl.name) {
                repository.updatePlaylist(pl.copy(name = name.trim()))
            }
            _importState.value = ImportUiState.Idle
        }
    }

    /** "Back" on the processed screen: the playlist that was just added is discarded again. */
    fun discardImport(playlistId: Long) {
        viewModelScope.launch {
            repository.getPlaylist(playlistId)?.let { repository.deletePlaylist(it) }
            _importState.value = ImportUiState.Idle
        }
    }

    fun resetImportState() {
        if (_importState.value !is ImportUiState.Processing) _importState.value = ImportUiState.Idle
    }

    /** "Apply changes" in the portal parameters: saves them and processes the playlist again with them. */
    fun applyPortalParams(updated: PlaylistEntity) {
        val key = UpdateKeys.playlist(updated.id)
        if (isUpdateRunning(key)) return
        setUpdateState(key, UpdateRowState(running = true))
        viewModelScope.launch {
            repository.updatePlaylist(updated)
            repository.refreshPlaylist(updated.id) { msg -> setUpdateState(key, UpdateRowState(running = true, message = msg)) }.fold(
                onSuccess = { setUpdateState(key, UpdateRowState(message = "Changes applied, playlist is updated")) },
                onFailure = { setUpdateState(key, UpdateRowState(message = "Update failed: ${it.localizedMessage ?: "unknown error"}", isError = true)) }
            )
        }
    }

    // ---- Movies / Shows (VOD) ----
    /** All movies or all shows of every playlist; collected only while the Movies / Shows page is open. */
    fun vodChannels(contentType: String): kotlinx.coroutines.flow.Flow<List<ChannelEntity>> = repository.vodChannels(contentType)

    /** The "History" group of the Movies / Shows page. */
    fun vodHistory(contentType: String): kotlinx.coroutines.flow.Flow<List<ChannelEntity>> = repository.vodHistory(contentType)

    // ---- resume points of movies (stored per item, cleared when it is (nearly) finished) ----
    private fun posKey(id: Long) = "vod_pos_$id"

    /** Where the movie was left (0 = never started / finished). */
    fun vodResumeMs(channel: ChannelEntity): Long = prefs.getLong(posKey(channel.id), 0L)

    private var vodProgressJob: kotlinx.coroutines.Job? = null
    private var vodProgressChannel: ChannelEntity? = null

    private fun saveVodProgress(channel: ChannelEntity) {
        val pos = playerManager.positionMs()
        val dur = playerManager.durationMs()
        if (dur <= 0L) return
        if (dur - pos < 60_000L || pos > dur * 0.95) prefs.edit().remove(posKey(channel.id)).apply()
        else if (pos > 10_000L) prefs.edit().putLong(posKey(channel.id), pos).apply()
    }

    /** Saves the resume point of the movie that is playing now and stops tracking it. */
    private fun stopVodProgress() {
        vodProgressJob?.cancel()
        vodProgressJob = null
        vodProgressChannel?.let { saveVodProgress(it) }
        vodProgressChannel = null
    }

    private fun startVodProgress(channel: ChannelEntity) {
        if (channel.contentType == ContentType.TV) return
        vodProgressChannel = channel
        vodProgressJob = viewModelScope.launch {
            while (true) {
                kotlinx.coroutines.delay(5_000)
                saveVodProgress(channel)
            }
        }
    }

    /** Opens the movie's YouTube trailer (the provider sends an id or a full link). */
    fun openTrailer(trailer: String) {
        val app = getApplication<Application>()
        val t = trailer.trim()
        if (t.isBlank()) return
        val url = if (t.startsWith("http", ignoreCase = true)) t else "https://www.youtube.com/watch?v=$t"
        try {
            app.startActivity(
                android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: Exception) {
            android.widget.Toast.makeText(app, "No app can open the trailer", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    /** Hands the movie's stream to another player app. */
    fun openInExternalPlayer(channel: ChannelEntity) {
        val app = getApplication<Application>()
        viewModelScope.launch {
            repository.resolvePlayable(channel).fold(
                onSuccess = { playable ->
                    try {
                        app.startActivity(
                            android.content.Intent(android.content.Intent.ACTION_VIEW)
                                .setDataAndType(android.net.Uri.parse(playable.streamUrl), "video/*")
                                .putExtra("title", playable.name)
                                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                        repository.recordHistory(playable)
                    } catch (_: Exception) {
                        android.widget.Toast.makeText(app, "No external player installed", android.widget.Toast.LENGTH_SHORT).show()
                    }
                },
                onFailure = {
                    android.widget.Toast.makeText(app, it.localizedMessage ?: "Can't play this", android.widget.Toast.LENGTH_LONG).show()
                }
            )
        }
    }

    // Movies / shows come from the portal when a group is opened (see TvBuddyRepository.loadVodGroup)
    private val vodLoadCount = java.util.concurrent.atomic.AtomicInteger(0)
    private val _vodLoading = MutableStateFlow(false)
    /** True while a group (or the "All" list) is being downloaded. */
    val vodLoading: StateFlow<Boolean> = _vodLoading.asStateFlow()
    private var vodGroupJob: kotlinx.coroutines.Job? = null
    private var vodAllJob: kotlinx.coroutines.Job? = null

    private fun trackVodLoading(block: suspend () -> Unit): kotlinx.coroutines.Job = viewModelScope.launch {
        _vodLoading.value = vodLoadCount.incrementAndGet() > 0
        try { block() } finally { _vodLoading.value = vodLoadCount.decrementAndGet() > 0 }
    }

    /** A movie / show group got the focus: fetch what is missing of it (the previous group's download is dropped). */
    fun openVodGroup(playlistId: Long, type: String, title: String) {
        vodGroupJob?.cancel()
        vodGroupJob = trackVodLoading { repository.loadVodGroup(playlistId, type, title) }
    }

    /** "All movies" / "All shows" is being scrolled towards its end: fetch the next pages. */
    fun loadMoreVodAll(type: String) {
        if (vodAllJob?.isActive == true) return
        vodAllJob = trackVodLoading { repository.loadMoreVodAll(type) }
    }

    /** TV button of the movie player: straight back to the last live channel. */
    fun watchLastTv() {
        val ch = lastTvChannel
            ?: pendingResumeId.takeIf { it >= 0 }?.let { id -> rawChannels.value.firstOrNull { it.id == id } }
            ?: recentChannels.value.firstOrNull()
            ?: rawChannels.value.firstOrNull()
        if (ch == null) {
            android.widget.Toast.makeText(getApplication<Application>(), "No TV channel to play", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        playChannel(ch)
    }

    suspend fun loadVodInfo(channel: ChannelEntity) = repository.loadVodInfo(channel)

    fun refreshAccountInfo(playlistId: Long) {
        viewModelScope.launch { repository.refreshExpiration(playlistId) }
    }

    fun toggleVodFavorite(channel: ChannelEntity) {
        viewModelScope.launch { repository.toggleFavorite(channel.id, channel.isFavorite) }
    }

    /** Opens a movie in the player (Stalker movies first get their real link from the portal). */
    fun playVod(channel: ChannelEntity, startMs: Long = 0L) {
        val app = getApplication<Application>()
        viewModelScope.launch {
            repository.resolvePlayable(channel).fold(
                onSuccess = {
                    playChannel(it)
                    if (startMs <= 0L) prefs.edit().remove(posKey(channel.id)).apply()
                    else launch {
                        // jump to the resume point as soon as the stream knows its length
                        var waited = 0
                        while (playerManager.durationMs() <= 0L && waited < 40) { kotlinx.coroutines.delay(500); waited++ }
                        kotlinx.coroutines.delay(300)
                        playerManager.seekToMs(startMs)
                    }
                },
                onFailure = {
                    android.widget.Toast.makeText(app, it.localizedMessage ?: "Can't play this", android.widget.Toast.LENGTH_LONG).show()
                }
            )
        }
    }

    /** Removes one channel from the recent / history strip. */
    fun removeFromHistory(channel: ChannelEntity) {
        prefs.edit().remove(posKey(channel.id)).apply()
        viewModelScope.launch { repository.removeHistory(channel.id) }
    }

    fun clearHistory() {
        viewModelScope.launch {
            repository.clearHistory()
            _uiMessage.value = UiMessage(text = "Playback history cleared")
        }
    }

    fun setVideoQuality(quality: com.example.player.VideoQuality) {
        playerManager.setVideoQuality(quality)
        _uiMessage.value = UiMessage(text = "Quality set to: ${quality.title}")
    }

    fun selectVideoTrack(option: com.example.player.VideoTrackOption?) {
        playerManager.selectVideoTrack(option)
    }

    fun getScheduleForChannel(epgChannelId: String) = repository.getScheduleForChannel(epgChannelId)

    fun closePlayer() {
        stopVodProgress()
        playerManager.closePlayer()
    }
    fun pausePlayer() { playerManager.pause() }

    // Activity lifecycle (see MainActivity)
    fun onAppPaused() { playerManager.pauseForLifecycle() }
    fun onAppStopped() { playerManager.suspendForBackground() }
    fun onAppStarted() { playerManager.resumeFromBackground() }
    fun onAppResumed() { playerManager.resumeFromLifecyclePause() }
    fun dismissMessage() { _uiMessage.value = null }

    override fun onCleared() {
        super.onCleared()
        playerManager.release()
    }

    private companion object {
        const val KEY_RESUME_ID = "resume_channel_id"
        const val KEY_RESUME_URL = "resume_stream_url"
        const val KEY_SEL_PLAYLIST = "tv_sel_playlist"
        const val KEY_SEL_KIND = "tv_sel_kind"
        const val KEY_SEL_GROUP = "tv_sel_group"
        const val KEY_CTX_PLAYLIST = "tv_ctx_playlist"
        const val KEY_CTX_KIND = "tv_ctx_kind"
        const val KEY_CTX_GROUP = "tv_ctx_group"
        const val KEY_GROUP_FAV_ONLY = "tv_group_fav_only"
        const val KEY_GROUP_NAME_SORT = "tv_group_name_sort"
        const val KEY_COLLAPSED_SECTIONS = "tv_collapsed_sections"
    }
}
