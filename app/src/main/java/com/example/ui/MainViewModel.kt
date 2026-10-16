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
import com.example.data.model.SpoofPreset
import com.example.data.model.SpoofProfile
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
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

    val allPlaylists = repository.allPlaylists.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    private val rawChannels = repository.allChannels.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    val playbackHistory = repository.playbackHistory.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    val allEpgSources = repository.allEpgSources.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    val allPlaylistEpgCrossRefs = repository.allPlaylistEpgCrossRefs.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    val allChannelGroups = repository.allChannelGroups.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    /** Recently played channels, most recent first (unique). */
    val recentChannels: StateFlow<List<ChannelEntity>> = combine(repository.playbackHistory, rawChannels) { history, channels ->
        val byId = channels.associateBy { it.id }
        val byUrl = channels.associateBy { it.streamUrl }
        history.mapNotNull { byId[it.channelId] ?: byUrl[it.streamUrl] }
            .distinctBy { it.id }
            .take(20)
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
    val nameSortedGroups: StateFlow<Set<String>> = _nameSortedGroups.asStateFlow()

    private val _globalSpoof = MutableStateFlow(SpoofProfile(SpoofPreset.TIVIMATE))
    val globalSpoof: StateFlow<SpoofProfile> = _globalSpoof.asStateFlow()

    private val _uiMessage = MutableStateFlow<UiMessage?>(null)
    val uiMessage: StateFlow<UiMessage?> = _uiMessage.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    // Player state forwards
    val currentChannel: StateFlow<ChannelEntity?> = playerManager.currentChannel
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

    init {
        prefs.edit().remove(KEY_RESUME_ID).remove(KEY_RESUME_URL).apply()
        viewModelScope.launch {
            repository.initializeDefaultsIfNeeded()
        }
        repository.refreshStaleEpgs(_globalSpoof.value.getUserAgent())

        // Make sure every playlist has its group rows (older playlists were imported before groups were tracked),
        // so "Manage groups" always lists all groups of a playlist.
        viewModelScope.launch {
            combine(repository.allPlaylists, repository.allChannels, repository.allChannelGroups) { pls, chs, groups ->
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
        val target = channels.firstOrNull { it.id == pendingResumeId }
            ?: channels.firstOrNull { !pendingResumeUrl.isNullOrBlank() && it.streamUrl == pendingResumeUrl }
            ?: return
        playChannel(target)
    }

    // Filtered categories respecting active playlists and group visibility (with fallback if groups table hasn't synced yet)
    val categories: StateFlow<List<String>> = combine(
        rawChannels,
        allPlaylists,
        allChannelGroups
    ) { channels, playlists, groups ->
        val activePlaylistIds = if (playlists.isEmpty()) null else playlists.filter { it.isActive }.map { it.id }.toSet()
        val hiddenGroupKeys = groups.filter { !it.isVisible }.map { "${it.playlistId}_${it.groupTitle}" }.toSet()

        val filteredGroups = channels
            .filter { ch ->
                (activePlaylistIds == null || ch.playlistId in activePlaylistIds) &&
                    "${ch.playlistId}_${ch.groupTitle}" !in hiddenGroupKeys
            }
            .map { it.groupTitle }
            .distinct()
            .filter { it.isNotBlank() }
            .sorted()

        listOf("All", "★ Favorites") + filteredGroups
    }.stateIn(viewModelScope, SharingStarted.Lazily, listOf("All", "★ Favorites"))

    /** Groups of each active playlist for the TV groups panel (hidden groups left out). */
    val tvGroupSections: StateFlow<List<TvGroupSection>> = combine(
        rawChannels,
        allPlaylists,
        allChannelGroups
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

    private data class FilterState(
        val category: String,
        val query: String,
        val tvMode: Boolean,
        val selection: TvGroupSelection,
        val favOnly: Set<String>,
        val nameSorted: Set<String>
    )

    private val groupViewPrefs = combine(_favOnlyGroups, _nameSortedGroups) { fav, byName -> fav to byName }

    private val filterState = combine(
        _selectedCategory,
        _searchQuery,
        _isTvMode,
        _tvSelection,
        groupViewPrefs
    ) { category, query, tvMode, selection, viewPrefs ->
        FilterState(category, query, tvMode, selection, viewPrefs.first, viewPrefs.second)
    }

    // Filtered channels respecting active playlists and group visibility.
    // Phone mode filters by category chip; TV mode by the playlist/group picked in the groups panel.
    val visibleChannels: StateFlow<List<ChannelEntity>> = combine(
        rawChannels,
        allPlaylists,
        allChannelGroups,
        filterState
    ) { channels, playlists, groups, fs ->
        val activePlaylistIds = if (playlists.isEmpty()) null else playlists.filter { it.isActive }.map { it.id }.toSet()
        val hiddenGroupKeys = groups.filter { !it.isVisible }.map { "${it.playlistId}_${it.groupTitle}" }.toSet()

        // A selection that points at a playlist that no longer exists behaves like "all playlists"
        val sel = fs.selection.let { s ->
            if (s.playlistId != null && playlists.none { it.id == s.playlistId }) TvGroupSelection() else s
        }
        val selKey = if (sel.kind == TvGroupKind.GROUP && sel.playlistId != null) "${sel.playlistId}_${sel.groupTitle}" else null

        val filtered = channels.filter { channel ->
            if (activePlaylistIds != null && channel.playlistId !in activePlaylistIds) return@filter false
            if ("${channel.playlistId}_${channel.groupTitle}" in hiddenGroupKeys) return@filter false

            val matchesQuery = fs.query.isBlank() ||
                channel.name.contains(fs.query, ignoreCase = true) ||
                channel.groupTitle.contains(fs.query, ignoreCase = true)
            if (!matchesQuery) return@filter false

            if (fs.tvMode) {
                if (sel.playlistId != null && channel.playlistId != sel.playlistId) return@filter false
                when (sel.kind) {
                    TvGroupKind.ALL -> true
                    TvGroupKind.FAVORITES -> channel.isFavorite
                    TvGroupKind.GROUP ->
                        channel.groupTitle == sel.groupTitle && (selKey == null || selKey !in fs.favOnly || channel.isFavorite)
                }
            } else {
                when (fs.category) {
                    "All" -> true
                    "★ Favorites" -> channel.isFavorite
                    else -> channel.groupTitle.equals(fs.category, ignoreCase = true)
                }
            }
        }

        if (fs.tvMode && selKey != null && selKey in fs.nameSorted) {
            filtered.sortedBy { it.name.lowercase() }
        } else {
            filtered
        }
    }.flowOn(kotlinx.coroutines.Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

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
        _uiMessage.value = UiMessage(text = "Player spoofing set to: ${preset.displayName}")
    }

    fun playChannel(channel: ChannelEntity) {
        resumeHandled = true
        prefs.edit().putLong(KEY_RESUME_ID, channel.id).putString(KEY_RESUME_URL, channel.streamUrl).apply()
        playerManager.playChannel(channel, _globalSpoof.value)
        viewModelScope.launch { repository.recordHistory(channel) }
    }

    fun playNextChannel() {
        val current = currentChannel.value ?: return
        val list = visibleChannels.value
        val currentIndex = list.indexOfFirst { it.id == current.id }
        if (currentIndex != -1 && list.isNotEmpty()) {
            playChannel(list[(currentIndex + 1) % list.size])
        }
    }

    fun playPreviousChannel() {
        val current = currentChannel.value ?: return
        val list = visibleChannels.value
        val currentIndex = list.indexOfFirst { it.id == current.id }
        if (currentIndex != -1 && list.isNotEmpty()) {
            val prevIndex = if (currentIndex - 1 < 0) list.size - 1 else currentIndex - 1
            playChannel(list[prevIndex])
        }
    }

    fun toggleFavorite(channel: ChannelEntity) {
        viewModelScope.launch { repository.toggleFavorite(channel.id, channel.isFavorite) }
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
    fun setGroupVisibility(playlistId: Long, groupTitle: String, isVisible: Boolean) {
        viewModelScope.launch { repository.setGroupVisibility(playlistId, groupTitle, isVisible) }
    }

    fun setAllGroupsVisibility(playlistId: Long, isVisible: Boolean) {
        viewModelScope.launch { repository.setAllGroupsVisibility(playlistId, isVisible) }
    }

    fun reorderGroups(playlistId: Long, orderedTitles: List<String>) {
        viewModelScope.launch { repository.reorderGroups(playlistId, orderedTitles) }
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
        viewModelScope.launch {
            _isLoading.value = true
            val sources = allEpgSources.value.filter { it.isEnabled }
            if (sources.isEmpty()) {
                _isLoading.value = false
                _uiMessage.value = UiMessage(text = "No EPG sources available to update", isError = true)
                return@launch
            }
            var totalCount = 0
            val settings = _epgSettings.value
            for (src in sources) {
                val res = repository.importXmltv(
                    urlOrContent = src.url,
                    isRawText = false,
                    userAgent = _globalSpoof.value.getUserAgent(),
                    pastDaysToKeep = settings.pastDaysToKeep,
                    storeDescriptions = settings.storeProgramDescriptions
                )
                res.onSuccess { totalCount += it }
            }
            _isLoading.value = false
            _epgSettings.value = _epgSettings.value.copy(latestUpdateStatus = repository.getLatestEpgStatus())
            _uiMessage.value = UiMessage(text = "EPG updated successfully ($totalCount programs)")
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
    fun addM3uPlaylist(name: String, source: String, isRawContent: Boolean = false, preset: SpoofPreset = SpoofPreset.TIVIMATE) {
        viewModelScope.launch {
            _isLoading.value = true
            val result = repository.addM3uPlaylist(name, source, isRawContent, preset)
            _isLoading.value = false
            result.fold(
                onSuccess = { count -> _uiMessage.value = UiMessage(text = "Imported $count channels successfully!") },
                onFailure = { err -> _uiMessage.value = UiMessage(text = "Failed to load M3U: ${err.localizedMessage}", isError = true) }
            )
        }
    }

    fun addXtreamPlaylist(name: String, serverUrl: String, user: String, pass: String, preset: SpoofPreset = SpoofPreset.TIVIMATE) {
        viewModelScope.launch {
            _isLoading.value = true
            val result = repository.addXtreamPlaylist(name, serverUrl, user, pass, preset)
            _isLoading.value = false
            result.fold(
                onSuccess = { count -> _uiMessage.value = UiMessage(text = "Connected! Loaded $count Xtream channels.") },
                onFailure = { err -> _uiMessage.value = UiMessage(text = "Xtream connection error: ${err.localizedMessage}", isError = true) }
            )
        }
    }

    fun addStalkerPlaylist(name: String, portalUrl: String, mac: String, preset: SpoofPreset = SpoofPreset.TIVIMATE) {
        viewModelScope.launch {
            _isLoading.value = true
            val result = repository.addStalkerPlaylist(name, portalUrl, mac, preset)
            _isLoading.value = false
            result.fold(
                onSuccess = { count -> _uiMessage.value = UiMessage(text = "Stalker portal linked! Loaded $count channels.") },
                onFailure = { err -> _uiMessage.value = UiMessage(text = "Stalker handshake error: ${err.localizedMessage}", isError = true) }
            )
        }
    }

    fun addJsonPlaylist(name: String, source: String, isRawContent: Boolean = false, preset: SpoofPreset = SpoofPreset.TIVIMATE) {
        viewModelScope.launch {
            _isLoading.value = true
            val result = repository.addJsonPlaylist(name, source, isRawContent, preset)
            _isLoading.value = false
            result.fold(
                onSuccess = { count -> _uiMessage.value = UiMessage(text = "JSON playlist parsed: $count channels added.") },
                onFailure = { err -> _uiMessage.value = UiMessage(text = "JSON parsing error: ${err.localizedMessage}", isError = true) }
            )
        }
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

    fun closePlayer() { playerManager.closePlayer() }
    fun pausePlayer() { playerManager.pause() }
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
        const val KEY_GROUP_FAV_ONLY = "tv_group_fav_only"
        const val KEY_GROUP_NAME_SORT = "tv_group_name_sort"
    }
}
