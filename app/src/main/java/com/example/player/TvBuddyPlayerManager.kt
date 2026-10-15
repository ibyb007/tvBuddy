package com.example.player

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.drm.FrameworkMediaDrm
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.AspectRatioFrameLayout
import com.example.player.drm.ClearKeyHelper
import com.example.data.local.entity.ChannelEntity
import com.example.data.model.DrmConfig
import com.example.data.model.DrmType
import com.example.data.model.SpoofPreset
import com.example.data.model.SpoofProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.net.UnknownHostException

data class StreamInfo(
    val width: Int = 0,
    val height: Int = 0,
    val bitrate: Int = 0,
    val frameRate: Float = 0f,
    val mimeType: String = "",
    val isDrmProtected: Boolean = false,
    val drmType: String = "None"
)

/** One selectable video rendition (resolution + bitrate when the stream declares it). */
data class VideoTrackOption(
    val groupIndex: Int,
    val trackIndex: Int,
    val width: Int,
    val height: Int,
    val bitrate: Int,        // 0 when unknown
    val isSelected: Boolean  // true only when the user forced this rendition
)

data class TrackInfo(
    val id: String,
    val label: String,
    val language: String,
    val isSelected: Boolean,
    val trackGroupIndex: Int,
    val trackIndex: Int,
    val type: Int // C.TRACK_TYPE_AUDIO or C.TRACK_TYPE_TEXT
)

enum class AspectMode(val title: String, val resizeMode: Int) {
    FIT("Fit Screen", AspectRatioFrameLayout.RESIZE_MODE_FIT),
    FILL("Fill / Stretch", AspectRatioFrameLayout.RESIZE_MODE_FILL),
    ZOOM("Zoom / Crop", AspectRatioFrameLayout.RESIZE_MODE_ZOOM),
    FOUR_THREE("4:3 Standard", AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH),
    SIXTEEN_NINE("16:9 Widescreen", AspectRatioFrameLayout.RESIZE_MODE_FIT)
}

enum class VideoQuality(val title: String, val maxWidth: Int, val maxHeight: Int) {
    AUTO("Auto (Adaptive)", Int.MAX_VALUE, Int.MAX_VALUE),
    UHD_4K("4K UHD (2160p)", 3840, 2160),
    FHD_1080P("Full HD (1080p)", 1920, 1080),
    HD_720P("HD (720p)", 1280, 720),
    SD_480P("SD (480p)", 854, 480),
    DATA_SAVER_360P("Data Saver (360p)", 640, 360)
}

@OptIn(UnstableApi::class)
class TvBuddyPlayerManager(
    private val context: Context
) {
    private var exoPlayer: ExoPlayer? = null

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _isBuffering = MutableStateFlow(false)
    val isBuffering: StateFlow<Boolean> = _isBuffering.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _currentChannel = MutableStateFlow<ChannelEntity?>(null)
    val currentChannel: StateFlow<ChannelEntity?> = _currentChannel.asStateFlow()

    private val _streamInfo = MutableStateFlow(StreamInfo())
    val streamInfo: StateFlow<StreamInfo> = _streamInfo.asStateFlow()

    private val _aspectMode = MutableStateFlow(AspectMode.FIT)
    val aspectMode: StateFlow<AspectMode> = _aspectMode.asStateFlow()

    private val _videoQuality = MutableStateFlow(VideoQuality.AUTO)
    val videoQuality: StateFlow<VideoQuality> = _videoQuality.asStateFlow()

    // Quality tiers that the CURRENT channel can actually play (always contains AUTO).
    private val _availableQualities = MutableStateFlow(listOf(VideoQuality.AUTO))
    val availableQualities: StateFlow<List<VideoQuality>> = _availableQualities.asStateFlow()

    private val _audioTracks = MutableStateFlow<List<TrackInfo>>(emptyList())
    val audioTracks: StateFlow<List<TrackInfo>> = _audioTracks.asStateFlow()

    private val _subtitleTracks = MutableStateFlow<List<TrackInfo>>(emptyList())
    val subtitleTracks: StateFlow<List<TrackInfo>> = _subtitleTracks.asStateFlow()

    private val _videoTracks = MutableStateFlow<List<VideoTrackOption>>(emptyList())
    /** Every video rendition of the current stream, lowest first. */
    val videoTracks: StateFlow<List<VideoTrackOption>> = _videoTracks.asStateFlow()

    fun getPlayer(): ExoPlayer {
        return exoPlayer ?: createPlayer().also { exoPlayer = it }
    }

    private fun createPlayer(): ExoPlayer {
        return ExoPlayer.Builder(context)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build().apply {
                playWhenReady = true
                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        _isBuffering.value = (playbackState == Player.STATE_BUFFERING)
                        _isPlaying.value = (playbackState == Player.STATE_READY && playWhenReady)
                        if (playbackState == Player.STATE_READY) {
                            _errorMessage.value = null
                        }
                    }

                    override fun onIsPlayingChanged(playing: Boolean) {
                        _isPlaying.value = playing
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        _isBuffering.value = false
                        _isPlaying.value = false

                        val rootCause = error.cause
                        val message = when {
                            rootCause is HttpDataSource.InvalidResponseCodeException -> {
                                when (rootCause.responseCode) {
                                    403 -> "HTTP 403 Forbidden: Stream provider denied access or the token expired."
                                    404 -> "HTTP 404 Not Found: Stream URL is offline or unavailable. Please select another channel."
                                    else -> "HTTP ${rootCause.responseCode}: Stream blocked or unavailable."
                                }
                            }
                            rootCause is UnknownHostException -> {
                                "Unable to resolve stream server hostname. Please check internet connection."
                            }
                            error.errorCode == PlaybackException.ERROR_CODE_DRM_SCHEME_UNSUPPORTED ||
                            error.errorCode == PlaybackException.ERROR_CODE_DRM_PROVISIONING_FAILED ||
                            error.errorCode == PlaybackException.ERROR_CODE_DRM_DEVICE_REVOKED ||
                            error.message?.contains("crypto", ignoreCase = true) == true -> {
                                "DRM hardware crypto is not available in this virtual environment. Hardware Widevine/ClearKey playback is fully supported on physical Android TV and mobile devices."
                            }
                            else -> {
                                val detailedCause = rootCause?.localizedMessage ?: error.message
                                "Playback Error (${error.errorCodeName}): $detailedCause"
                            }
                        }
                        _errorMessage.value = message
                    }

                    override fun onTracksChanged(tracks: Tracks) {
                        updateAvailableTracks(tracks)
                    }

                    override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                        val current = _streamInfo.value
                        _streamInfo.value = current.copy(
                            width = videoSize.width,
                            height = videoSize.height
                        )
                    }
                })
                addAnalyticsListener(object : AnalyticsListener {
                    override fun onVideoInputFormatChanged(
                        eventTime: AnalyticsListener.EventTime,
                        format: Format,
                        decoderReuseEvaluation: androidx.media3.exoplayer.DecoderReuseEvaluation?
                    ) {
                        applyVideoFormat(format)
                    }
                })
                mainHandler.removeCallbacks(fpsRunnable)
                mainHandler.postDelayed(fpsRunnable, FPS_SAMPLE_MS)
            }
    }

    // ---- Frame rate / bitrate ----
    // Like TiviMate: bitrate only when the stream's video format declares it (no guessing from
    // network speed); frame rate from the format, else measured from the frames actually rendered.
    private val mainHandler = Handler(Looper.getMainLooper())
    private var declaredFps = false
    private var fpsLastCount = -1
    private var fpsLastTime = 0L
    private val fpsRunnable = object : Runnable {
        override fun run() {
            try { sampleFps() } catch (_: Throwable) {}
            mainHandler.postDelayed(this, FPS_SAMPLE_MS)
        }
    }

    private fun sampleFps() {
        val p = exoPlayer ?: return
        if (declaredFps || !p.isPlaying) { fpsLastCount = -1; return }
        val counters = p.videoDecoderCounters ?: run { fpsLastCount = -1; return }
        counters.ensureUpdated()
        val now = SystemClock.elapsedRealtime()
        val count = counters.renderedOutputBufferCount
        if (fpsLastCount in 0..count && now - fpsLastTime >= 1500) {
            val fps = (count - fpsLastCount) * 1000f / (now - fpsLastTime)
            if (fps in 10f..130f) {
                val snapped = STANDARD_FPS.minByOrNull { kotlin.math.abs(it - fps) }
                    ?.takeIf { kotlin.math.abs(it - fps) / it < 0.04f }
                    ?: (Math.round(fps * 10) / 10f)
                val current = _streamInfo.value
                if (current.frameRate != snapped) _streamInfo.value = current.copy(frameRate = snapped)
            }
        }
        fpsLastCount = count
        fpsLastTime = now
    }

    /** Picks up frame rate and bitrate of the video track that is actually being decoded. */
    private fun applyVideoFormat(format: Format) {
        val fps = if (format.frameRate > 0f) format.frameRate else 0f
        val bitrate = if (format.bitrate > 0) format.bitrate else 0
        if (fps > 0f) declaredFps = true
        val current = _streamInfo.value
        if (current.bitrate != bitrate || (fps > 0f && current.frameRate != fps)) {
            _streamInfo.value = current.copy(
                bitrate = bitrate,
                frameRate = if (fps > 0f) fps else current.frameRate
            )
        }
    }

    private companion object {
        const val FPS_SAMPLE_MS = 2000L
        val STANDARD_FPS = listOf(23.976f, 24f, 25f, 29.97f, 30f, 50f, 59.94f, 60f)
    }

    fun playChannel(
        channel: ChannelEntity,
        globalSpoof: SpoofProfile = SpoofProfile(SpoofPreset.TIVIMATE)
    ) {
        _currentChannel.value = channel
        _errorMessage.value = null
        _isBuffering.value = true
        _availableQualities.value = listOf(VideoQuality.AUTO)

        val player = getPlayer()

        // 1. Determine User-Agent & Headers
        val userAgent = channel.httpUserAgent?.ifBlank { null } ?: globalSpoof.getUserAgent()
        val headers = mutableMapOf<String, String>()
        headers.putAll(globalSpoof.getEffectiveHeaders())

        if (!channel.httpHeadersJson.isNullOrBlank()) {
            try {
                val json = JSONObject(channel.httpHeadersJson)
                val keys = json.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    headers[k] = json.getString(k)
                }
            } catch (_: Exception) {}
        }
        headers["User-Agent"] = userAgent

        // 2. Build HttpDataSource Factory with spoof headers
        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(userAgent)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)
            .setDefaultRequestProperties(headers)

        // 3. Build MediaItem & DRM
        val uri = Uri.parse(channel.streamUrl)
        val mediaItemBuilder = MediaItem.Builder().setUri(uri)

        val drmType = DrmType.fromString(channel.drmType)
        val isDrm = (drmType != DrmType.NONE)
        var drmSessionManagerProvider: DrmSessionManagerProvider? = null

        if (isDrm && drmType.uuid != null) {
            val drmHeaders = mutableMapOf<String, String>()
            drmHeaders.putAll(headers)

            if (!channel.drmHeadersJson.isNullOrBlank()) {
                try {
                    val dj = JSONObject(channel.drmHeadersJson)
                    val k = dj.keys()
                    while (k.hasNext()) {
                        val key = k.next()
                        drmHeaders[key] = dj.getString(key)
                    }
                } catch (_: Exception) {}
            }

            if (drmType == DrmType.CLEARKEY && !channel.drmKeyIdKey.isNullOrBlank()) {
                // High-performance offline/local ClearKey with user-provided KID:KEY pairs
                val clearKeySessionManager = ClearKeyHelper.createClearKeyDrmSessionManager(channel.drmKeyIdKey)
                if (clearKeySessionManager != null) {
                    drmSessionManagerProvider = DrmSessionManagerProvider { clearKeySessionManager }
                    mediaItemBuilder.setDrmConfiguration(
                        MediaItem.DrmConfiguration.Builder(C.CLEARKEY_UUID)
                            .setMultiSession(true)
                            .build()
                    )
                } else {
                    val isCryptoSupported = try {
                        FrameworkMediaDrm.isCryptoSchemeSupported(C.CLEARKEY_UUID)
                    } catch (_: Throwable) {
                        false
                    }
                    if (!isCryptoSupported) {
                        _isBuffering.value = false
                        _errorMessage.value = "ClearKey decryption is not provisioned in this environment's media framework. Standard on certified physical Android TV and mobile devices."
                        return
                    }
                }
            } else {
                val isCryptoSupported = try {
                    FrameworkMediaDrm.isCryptoSchemeSupported(drmType.uuid!!)
                } catch (_: Throwable) {
                    false
                }

                if (!isCryptoSupported) {
                    _isBuffering.value = false
                    _errorMessage.value = "${drmType.title} hardware crypto is not provisioned in this environment. Hardware DRM playback is supported on certified physical Android TV & mobile devices."
                    return
                }

                val drmBuilder = MediaItem.DrmConfiguration.Builder(drmType.uuid!!)
                    .setMultiSession(true)
                    .setLicenseRequestHeaders(drmHeaders)

                if (!channel.drmLicenseUrl.isNullOrBlank()) {
                    drmBuilder.setLicenseUri(Uri.parse(channel.drmLicenseUrl))
                }

                mediaItemBuilder.setDrmConfiguration(drmBuilder.build())
            }
        }

        val mediaItem = mediaItemBuilder.build()

        // 4. Create appropriate MediaSource
        val mediaSource = createMediaSource(uri, mediaItem, httpDataSourceFactory, drmSessionManagerProvider)

        declaredFps = false
        fpsLastCount = -1
        _videoTracks.value = emptyList()
        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
            .build()
        _streamInfo.value = StreamInfo(
            isDrmProtected = isDrm,
            drmType = if (isDrm) drmType.title else "None"
        )

        player.setMediaSource(mediaSource)
        player.prepare()
        player.play()
    }

    private fun createMediaSource(
        uri: Uri,
        mediaItem: MediaItem,
        dataSourceFactory: DefaultHttpDataSource.Factory,
        drmSessionManagerProvider: DrmSessionManagerProvider? = null
    ): MediaSource {
        val path = uri.toString().lowercase()
        return when {
            path.contains(".m3u8") || path.contains("type=m3u") -> {
                val factory = HlsMediaSource.Factory(dataSourceFactory)
                    .setAllowChunklessPreparation(true)
                if (drmSessionManagerProvider != null) {
                    factory.setDrmSessionManagerProvider(drmSessionManagerProvider)
                }
                factory.createMediaSource(mediaItem)
            }
            path.contains(".mpd") -> {
                val factory = DashMediaSource.Factory(dataSourceFactory)
                if (drmSessionManagerProvider != null) {
                    factory.setDrmSessionManagerProvider(drmSessionManagerProvider)
                }
                factory.createMediaSource(mediaItem)
            }
            path.startsWith("rtsp://") -> {
                RtspMediaSource.Factory()
                    .createMediaSource(mediaItem)
            }
            else -> {
                val factory = ProgressiveMediaSource.Factory(dataSourceFactory)
                if (drmSessionManagerProvider != null) {
                    factory.setDrmSessionManagerProvider(drmSessionManagerProvider)
                }
                factory.createMediaSource(mediaItem)
            }
        }
    }

    private fun updateAvailableTracks(tracks: Tracks) {
        val audio = mutableListOf<TrackInfo>()
        val subtitles = mutableListOf<TrackInfo>()
        val videoHeights = mutableSetOf<Int>()
        var selectedVideoFormat: Format? = null
        val videoOptions = mutableListOf<VideoTrackOption>()
        val forcedVideo = exoPlayer?.trackSelectionParameters?.overrides?.values
            ?.any { it.type == C.TRACK_TYPE_VIDEO } == true

        for (groupIndex in 0 until tracks.groups.size) {
            val group = tracks.groups[groupIndex]
            val trackType = group.type

            if (trackType == C.TRACK_TYPE_VIDEO) {
                for (trackIndex in 0 until group.length) {
                    if (!group.isTrackSupported(trackIndex)) continue
                    val vf = group.getTrackFormat(trackIndex)
                    if (vf.height > 0) videoHeights.add(vf.height)
                    if (group.isTrackSelected(trackIndex)) selectedVideoFormat = vf
                    if (vf.width > 0 && vf.height > 0) {
                        videoOptions.add(
                            VideoTrackOption(
                                groupIndex = groupIndex,
                                trackIndex = trackIndex,
                                width = vf.width,
                                height = vf.height,
                                bitrate = if (vf.bitrate > 0) vf.bitrate else 0,
                                isSelected = forcedVideo && group.isTrackSelected(trackIndex)
                            )
                        )
                    }
                }
                continue
            }

            for (trackIndex in 0 until group.length) {
                val isSelected = group.isTrackSelected(trackIndex)
                val format = group.getTrackFormat(trackIndex)
                val lang = format.language ?: "und"
                val label = format.label ?: when (trackType) {
                    C.TRACK_TYPE_AUDIO -> "Audio ${audio.size + 1} ($lang)"
                    C.TRACK_TYPE_TEXT -> "Subtitle ${subtitles.size + 1} ($lang)"
                    else -> "Track"
                }

                val trackInfo = TrackInfo(
                    id = format.id ?: "$groupIndex-$trackIndex",
                    label = label,
                    language = lang,
                    isSelected = isSelected,
                    trackGroupIndex = groupIndex,
                    trackIndex = trackIndex,
                    type = trackType
                )

                if (trackType == C.TRACK_TYPE_AUDIO) {
                    audio.add(trackInfo)
                } else if (trackType == C.TRACK_TYPE_TEXT) {
                    subtitles.add(trackInfo)
                }
            }
        }

        selectedVideoFormat?.let { applyVideoFormat(it) }
        _videoTracks.value = videoOptions.sortedWith(compareBy({ it.height }, { it.width }, { it.bitrate }))
        _audioTracks.value = audio
        _subtitleTracks.value = subtitles
        _availableQualities.value = computeAvailableQualities(videoHeights)
    }

    /**
     * Maps the real video track heights of the stream to quality tiers. A tier is offered only
     * when the stream has a rendition that falls into it (so no "4K" for a 1080p channel).
     * Streams with a single rendition have nothing to choose from, so only AUTO is returned.
     */
    private fun computeAvailableQualities(heights: Set<Int>): List<VideoQuality> {
        if (heights.size <= 1) return listOf(VideoQuality.AUTO)
        val tiers = VideoQuality.entries.filter { it != VideoQuality.AUTO }.sortedBy { it.maxHeight }
        val present = mutableSetOf<VideoQuality>()
        for (h in heights) {
            // Smallest tier that fits the height (10% tolerance for 1088p, 2176p etc.)
            val tier = tiers.firstOrNull { h <= (it.maxHeight * 1.1).toInt() } ?: tiers.last()
            present.add(tier)
        }
        val ordered = tiers.filter { it in present }.sortedByDescending { it.maxHeight }
        return listOf(VideoQuality.AUTO) + ordered
    }

    fun selectTrack(trackInfo: TrackInfo) {
        val player = exoPlayer ?: return
        val currentTracks = player.currentTracks
        if (trackInfo.trackGroupIndex < currentTracks.groups.size) {
            val trackGroup = currentTracks.groups[trackInfo.trackGroupIndex].mediaTrackGroup
            player.trackSelectionParameters = player.trackSelectionParameters
                .buildUpon()
                .setOverrideForType(
                    TrackSelectionOverride(trackGroup, listOf(trackInfo.trackIndex))
                )
                .build()
        }
    }

    /** Forces one rendition, or returns to automatic (adaptive) selection when [option] is null. */
    fun selectVideoTrack(option: VideoTrackOption?) {
        val player = exoPlayer ?: return
        val params = player.trackSelectionParameters.buildUpon()
        if (option == null) {
            params.clearOverridesOfType(C.TRACK_TYPE_VIDEO)
        } else {
            val groups = player.currentTracks.groups
            if (option.groupIndex >= groups.size) return
            params.setOverrideForType(
                TrackSelectionOverride(groups[option.groupIndex].mediaTrackGroup, listOf(option.trackIndex))
            )
        }
        player.trackSelectionParameters = params.build()
    }

    fun disableSubtitles() {
        val player = exoPlayer ?: return
        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()
    }

    fun setVideoQuality(quality: VideoQuality) {
        _videoQuality.value = quality
        val player = exoPlayer ?: return
        val params = player.trackSelectionParameters.buildUpon()
        if (quality == VideoQuality.AUTO) {
            params.clearVideoSizeConstraints()
        } else {
            params.setMaxVideoSize(quality.maxWidth, quality.maxHeight)
        }
        player.trackSelectionParameters = params.build()
    }

    fun cycleAspectMode() {
        val modes = AspectMode.entries.toTypedArray()
        val nextIndex = (_aspectMode.value.ordinal + 1) % modes.size
        _aspectMode.value = modes[nextIndex]
    }

    fun setAspectMode(mode: AspectMode) {
        _aspectMode.value = mode
    }

    fun togglePlayPause() {
        exoPlayer?.let {
            if (it.isPlaying) it.pause() else it.play()
        }
    }

    fun pause() {
        exoPlayer?.pause()
    }

    fun stop() {
        exoPlayer?.stop()
        _isPlaying.value = false
        _isBuffering.value = false
    }

    fun closePlayer() {
        stop()
        _currentChannel.value = null
        _errorMessage.value = null
    }

    fun release() {
        mainHandler.removeCallbacks(fpsRunnable)
        exoPlayer?.stop()
        exoPlayer?.release()
        exoPlayer = null
        _isPlaying.value = false
        _isBuffering.value = false
        _currentChannel.value = null
    }
}
