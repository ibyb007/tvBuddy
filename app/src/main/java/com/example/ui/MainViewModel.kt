package com.example.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.entity.ChannelEntity
import com.example.data.local.entity.ChannelGroupEntity
import com.example.data.local.entity.EpgSourceEntity
import com.example.data.local.entity.PlaylistEpgCrossRef
import com.example.data.local.entity.PlaylistEntity
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class UiMessage(
    val id: Long = System.currentTimeMillis(),
    val text: String,
    val isError: Boolean = false
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

    init {
        prefs.edit().remove(KEY_RESUME_ID).remove(KEY_RESUME_URL).apply()
        viewModelScope.launch {
            repository.initializeDefaultsIfNeeded()
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

    private val _selectedCategory = MutableStateFlow("All")
    val selectedCategory: StateFlow<String> = _selectedCategory.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _isTvMode = MutableStateFlow(false)
    val isTvMode: StateFlow<Boolean> = _isTvMode.asStateFlow()

    private val _globalSpoof = MutableStateFlow(SpoofProfile(SpoofPreset.TIVIMATE))
    val globalSpoof: StateFlow<SpoofProfile> = _globalSpoof.asStateFlow()

    private val _uiMessage = MutableStateFlow<UiMessage?>(null)
    val uiMessage: StateFlow<UiMessage?> = _uiMessage.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

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
    val allCurrentPrograms: StateFlow<List<com.example.data.local.entity.ProgramEntity>> =
        repository.allCurrentPrograms as StateFlow<List<com.example.data.local.entity.ProgramEntity>>

    // Filtered categories respecting active playlists and group visibility
    val categories: StateFlow<List<String>> = combine(
        rawChannels,
        allPlaylists,
        allChannelGroups
    ) { channels, playlists, groups ->
        val activePlaylistIds = playlists.filter { it.isActive }.map { it.id }.toSet()
        val visibleGroupKeys = groups.filter { it.isVisible }.map { "${it.playlistId}_${it.groupTitle}" }.toSet()

        val filteredGroups = channels
            .filter { it.playlistId in activePlaylistIds && "${it.playlistId}_${it.groupTitle}" in visibleGroupKeys }
            .map { it.groupTitle }
            .distinct()
            .filter { it.isNotBlank() }
            .sorted()

        listOf("All", "★ Favorites") + filteredGroups
    }.stateIn(viewModelScope, SharingStarted.Lazily, listOf("All", "★ Favorites"))

    // Filtered channels respecting active playlists and group visibility
    val visibleChannels: StateFlow<List<ChannelEntity>> = combine(
        rawChannels,
        allPlaylists,
        allChannelGroups,
        _selectedCategory,
        _searchQuery
    ) { channels, playlists, groups, category, query ->
        val activePlaylistIds = playlists.filter { it.isActive }.map { it.id }.toSet()
        val visibleGroupKeys = groups.filter { it.isVisible }.map { "${it.playlistId}_${it.groupTitle}" }.toSet()

        channels.filter { channel ->
            if (channel.playlistId !in activePlaylistIds) return@filter false
            if ("${channel.playlistId}_${channel.groupTitle}" !in visibleGroupKeys) return@filter false

            val matchesCategory = when (category) {
                "All" -> true
                "★ Favorites" -> channel.isFavorite
                else -> channel.groupTitle.equals(category, ignoreCase = true)
            }
            val matchesQuery = query.isBlank() ||
                channel.name.contains(query, ignoreCase = true) ||
                channel.groupTitle.contains(query, ignoreCase = true)
            matchesCategory && matchesQuery
        }
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    fun selectCategory(category: String) {
        _selectedCategory.value = category
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
    }
}
