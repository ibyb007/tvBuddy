package com.example.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.entity.ChannelEntity
import com.example.data.local.entity.PlaylistEntity
import com.example.data.model.PlaylistType
import com.example.data.model.SpoofPreset
import com.example.data.model.SpoofProfile
import com.example.data.repository.TvBuddyRepository
import com.example.player.AspectMode
import com.example.player.StreamInfo
import com.example.player.TrackInfo
import com.example.player.TvBuddyPlayerManager
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
    val allCurrentPrograms: StateFlow<List<com.example.data.local.entity.ProgramEntity>> =
        repository.allCurrentPrograms.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    // Filtered categories
    val categories: StateFlow<List<String>> = combine(rawChannels) { list ->
        val groups = list[0].map { it.groupTitle }.distinct().filter { it.isNotBlank() }.sorted()
        listOf("All", "★ Favorites") + groups
    }.stateIn(viewModelScope, SharingStarted.Lazily, listOf("All", "★ Favorites"))

    // Filtered channels
    val visibleChannels: StateFlow<List<ChannelEntity>> = combine(
        rawChannels,
        _selectedCategory,
        _searchQuery
    ) { channels, category, query ->
        channels.filter { channel ->
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

    init {
        viewModelScope.launch {
            repository.initializeDefaultsIfNeeded()
        }
    }

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
        playerManager.playChannel(channel, _globalSpoof.value)
        viewModelScope.launch {
            repository.recordHistory(channel)
        }
    }

    fun playNextChannel() {
        val current = currentChannel.value ?: return
        val list = visibleChannels.value
        val currentIndex = list.indexOfFirst { it.id == current.id }
        if (currentIndex != -1 && list.isNotEmpty()) {
            val nextIndex = (currentIndex + 1) % list.size
            playChannel(list[nextIndex])
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
        viewModelScope.launch {
            repository.toggleFavorite(channel.id, channel.isFavorite)
        }
    }

    fun cycleAspect() {
        playerManager.cycleAspectMode()
    }

    fun setAspect(mode: AspectMode) {
        playerManager.setAspectMode(mode)
    }

    fun addM3uPlaylist(
        name: String,
        source: String,
        isRawContent: Boolean = false,
        preset: SpoofPreset = SpoofPreset.TIVIMATE
    ) {
        viewModelScope.launch {
            _isLoading.value = true
            val result = repository.addM3uPlaylist(
                name = name,
                sourceUrlOrContent = source,
                isRawContent = isRawContent,
                spoofPreset = preset
            )
            _isLoading.value = false
            result.fold(
                onSuccess = { count ->
                    _uiMessage.value = UiMessage(text = "Imported $count channels successfully!")
                },
                onFailure = { err ->
                    _uiMessage.value = UiMessage(text = "Failed to load M3U: ${err.localizedMessage}", isError = true)
                }
            )
        }
    }

    fun addXtreamPlaylist(
        name: String,
        serverUrl: String,
        user: String,
        pass: String,
        preset: SpoofPreset = SpoofPreset.TIVIMATE
    ) {
        viewModelScope.launch {
            _isLoading.value = true
            val result = repository.addXtreamPlaylist(name, serverUrl, user, pass, preset)
            _isLoading.value = false
            result.fold(
                onSuccess = { count ->
                    _uiMessage.value = UiMessage(text = "Connected! Loaded $count Xtream channels.")
                },
                onFailure = { err ->
                    _uiMessage.value = UiMessage(text = "Xtream connection error: ${err.localizedMessage}", isError = true)
                }
            )
        }
    }

    fun addStalkerPlaylist(
        name: String,
        portalUrl: String,
        mac: String,
        preset: SpoofPreset = SpoofPreset.TIVIMATE
    ) {
        viewModelScope.launch {
            _isLoading.value = true
            val result = repository.addStalkerPlaylist(name, portalUrl, mac, preset)
            _isLoading.value = false
            result.fold(
                onSuccess = { count ->
                    _uiMessage.value = UiMessage(text = "Stalker portal linked! Loaded $count channels.")
                },
                onFailure = { err ->
                    _uiMessage.value = UiMessage(text = "Stalker handshake error: ${err.localizedMessage}", isError = true)
                }
            )
        }
    }

    fun addJsonPlaylist(
        name: String,
        source: String,
        isRawContent: Boolean = false,
        preset: SpoofPreset = SpoofPreset.TIVIMATE
    ) {
        viewModelScope.launch {
            _isLoading.value = true
            val result = repository.addJsonPlaylist(name, source, isRawContent, preset)
            _isLoading.value = false
            result.fold(
                onSuccess = { count ->
                    _uiMessage.value = UiMessage(text = "JSON playlist parsed: $count channels added.")
                },
                onFailure = { err ->
                    _uiMessage.value = UiMessage(text = "JSON parsing error: ${err.localizedMessage}", isError = true)
                }
            )
        }
    }

    fun deletePlaylist(playlist: PlaylistEntity) {
        viewModelScope.launch {
            repository.deletePlaylist(playlist)
            _uiMessage.value = UiMessage(text = "Deleted playlist: ${playlist.name}")
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

    fun getScheduleForChannel(epgChannelId: String) = repository.getScheduleForChannel(epgChannelId)

    fun importXmltv(source: String, isRaw: Boolean) {
        viewModelScope.launch {
            _isLoading.value = true
            val result = repository.importXmltv(source, isRaw, _globalSpoof.value.getUserAgent())
            _isLoading.value = false
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

    fun closePlayer() {
        playerManager.closePlayer()
    }

    fun pausePlayer() {
        playerManager.pause()
    }

    fun dismissMessage() {
        _uiMessage.value = null
    }

    override fun onCleared() {
        super.onCleared()
        playerManager.release()
    }
}
