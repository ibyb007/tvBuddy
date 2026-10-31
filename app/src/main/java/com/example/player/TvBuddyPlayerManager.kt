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
    val type: Int, // C.TRACK_TYPE_AUDIO or C.TRACK_TYPE_TEXT
    val channelCount: Int = 0,   // audio: 1 mono, 2 stereo, 6 = 5.1 ... (0 = unknown)
    val bitrate: Int = 0         // bits per second (0 = unknown)
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

    // ---- Recovery state (auto-retry, background suspend / fresh restart) ----
    private var lastChannel: ChannelEntity? = null
    private var lastSpoof: SpoofProfile = SpoofProfile(SpoofPreset.TIVIMATE)
    private var retryCount = 0
    private var retryPending = false
    // container format detection: type in use, and a type forced after a failed guess
    private var activeType = C.CONTENT_TYPE_OTHER
    private var typeFallbackAllowed = false
    private var forcedType: Int? = null
    /** 0 = not tried, 1 = trying an ".m3u8" link as a plain media file, 2 = done (don't try again) */
    private var progressiveProbe = 0
    private var suspendedForBackground = false
    private var pausedByLifecycle = false
    private val retryRunnable = Runnable {
        retryPending = false
        lastChannel?.let { startPlayback(it, lastSpoof) }
    }
    private val resetRetryRunnable = Runnable { retryCount = 0 }

    // ---- Audio boost (LoudnessEnhancer / DynamicsProcessing on the player's audio session) ----
    private val boostPrefs = context.getSharedPreferences("tvbuddy_prefs", Context.MODE_PRIVATE)
    private val audioBoost = AudioBoostEngine()
    private val _audioBoostProfile = MutableStateFlow(
        AudioBoostProfile.fromName(boostPrefs.getString(KEY_AUDIO_BOOST, null))
    )
    val audioBoostProfile: StateFlow<AudioBoostProfile> = _audioBoostProfile.asStateFlow()

    fun setAudioBoostProfile(profile: AudioBoostProfile) {
        _audioBoostProfile.value = profile
        boostPrefs.edit().putString(KEY_AUDIO_BOOST, profile.name).apply()
        applyAudioBoost()
    }

    /** Off -> Rich -> Cinematic -> Dialogue -> Off. Returns the new profile. */
    fun cycleAudioBoostProfile(): AudioBoostProfile {
        val next = _audioBoostProfile.value.next()
        setAudioBoostProfile(next)
        return next
    }

    private fun applyAudioBoost(rebuild: Boolean = false) {
        val p = exoPlayer ?: return
        try {
            if (rebuild) audioBoost.rebuild(p.audioSessionId, _audioBoostProfile.value)
            else audioBoost.apply(p.audioSessionId, _audioBoostProfile.value)
        } catch (t: Throwable) {
            PlaybackLog.info("AUDIO", "Audio boost failed: ${t.message}")
        }
    }

    /**
     * After a (re)start the effects have to be created once the AudioTrack is running, otherwise they stay silent until
     * the boost mode is changed by hand. Rebuild right away and again shortly after, because on some devices the track
     * is only attached to the session a moment after playback reports "playing".
     */
    private val boostRebuildRunnable = Runnable {
        if (_audioBoostProfile.value != AudioBoostProfile.OFF) applyAudioBoost(rebuild = true)
    }

    private fun scheduleBoostRebuild() {
        if (_audioBoostProfile.value == AudioBoostProfile.OFF) return
        mainHandler.removeCallbacks(boostRebuildRunnable)
        applyAudioBoost(rebuild = true)
        mainHandler.postDelayed(boostRebuildRunnable, 700L)
        mainHandler.postDelayed(boostRebuildRunnable, 2500L)
    }

    /** True until the effects were rebuilt once for the current player / audio session (see [scheduleBoostRebuild]). */
    private var boostNeedsRebuild = true

    // ---- Loading watchdog: a stream that never gets ready is retried / reported instead of loading forever ----
    private val stallRunnable = Runnable {
        val p = exoPlayer ?: return@Runnable
        val ch = lastChannel
        // live TV only: a movie that buffers for a long time while seeking must never be restarted from the beginning
        if (p.playbackState != Player.STATE_BUFFERING || ch == null || suspendedForBackground ||
            ch.contentType != com.example.data.model.ContentType.TV) return@Runnable
        PlaybackLog.warn("STALL", "Still loading after ${STALL_TIMEOUT_MS / 1000}s")
        if (retryCount < MAX_RETRIES) {
            retryCount++
            mainHandler.removeCallbacks(retryRunnable)
            retryPending = true
            mainHandler.postDelayed(retryRunnable, 500L)
        } else {
            try { p.stop() } catch (_: Throwable) {}
            _isBuffering.value = false
            _isPlaying.value = false
            _errorMessage.value = "Playback Error: the stream did not start. Please try again or select another channel."
        }
    }

    private fun startStallWatchdog() {
        mainHandler.removeCallbacks(stallRunnable)
        mainHandler.postDelayed(stallRunnable, STALL_TIMEOUT_MS)
    }

    private fun cancelStallWatchdog() {
        mainHandler.removeCallbacks(stallRunnable)
    }

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

    private fun isTelevision(): Boolean {
        val uiMode = context.getSystemService(Context.UI_MODE_SERVICE) as? android.app.UiModeManager
        return uiMode?.currentModeType == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION ||
            context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK)
    }

    /** Channel order of PCM: FL FR FC LFE BL BR (SL SR). Centre / surrounds are folded into L/R, LFE dropped. */
    private fun stereoDownmix(): androidx.media3.common.audio.AudioProcessor {
        val mixer = androidx.media3.common.audio.ChannelMixingAudioProcessor()
        fun matrix(inCh: Int, coef: FloatArray) =
            androidx.media3.common.audio.ChannelMixingMatrix(inCh, 2, coef)
        mixer.putChannelMixingMatrix(matrix(1, floatArrayOf(1f, 1f)))
        mixer.putChannelMixingMatrix(matrix(2, floatArrayOf(1f, 0f, 0f, 1f)))
        // rows = input channel, columns = output (L, R)
        mixer.putChannelMixingMatrix(matrix(6, floatArrayOf(
            0.60f, 0f,      // FL
            0f, 0.60f,      // FR
            0.42f, 0.42f,   // FC
            0f, 0f,         // LFE
            0.42f, 0f,      // BL
            0f, 0.42f       // BR
        )))
        mixer.putChannelMixingMatrix(matrix(8, floatArrayOf(
            0.55f, 0f,
            0f, 0.55f,
            0.40f, 0.40f,
            0f, 0f,
            0.30f, 0f,      // BL
            0f, 0.30f,      // BR
            0.30f, 0f,      // SL
            0f, 0.30f       // SR
        )))
        return mixer
    }

    private fun createPlayer(): ExoPlayer {
        // Hardware decoders first; when the phone has none for the audio format (E-AC3 / E-AC3-JOC 5.1, AC3, DTS ...)
        // the FFmpeg software decoder takes over, and a failing codec falls back to the next one.
        val renderers = object : androidx.media3.exoplayer.DefaultRenderersFactory(context) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioOutputPlaybackParams: Boolean
            ): androidx.media3.exoplayer.audio.AudioSink? {
                val builder = androidx.media3.exoplayer.audio.DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioOutputPlaybackParameters(enableAudioOutputPlaybackParams)
                // Phones: the software (FFmpeg) decoder hands 5.1 / 7.1 PCM to AudioTrack, which dies on many phones
                // (AudioTrack write failed -6) and makes the stream stall and rebuffer. Mix it down to stereo first.
                if (!isTelevision()) builder.setAudioProcessors(arrayOf(stereoDownmix()))
                return builder.build()
            }
        }
            .setExtensionRendererMode(androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            .setEnableDecoderFallback(true)
        return ExoPlayer.Builder(context, renderers)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build().apply {
                playWhenReady = true
                audioBoost.apply(audioSessionId, _audioBoostProfile.value)
                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        PlaybackLog.info("STATE", PlaybackLog.stateName(playbackState) + " (playWhenReady=$playWhenReady)")
                        _isBuffering.value = (playbackState == Player.STATE_BUFFERING)
                        _isPlaying.value = (playbackState == Player.STATE_READY && playWhenReady)
                        if (playbackState == Player.STATE_BUFFERING) startStallWatchdog() else cancelStallWatchdog()
                        if (playbackState == Player.STATE_READY) {
                            if (_audioBoostProfile.value != AudioBoostProfile.OFF) {
                                if (boostNeedsRebuild) { boostNeedsRebuild = false; scheduleBoostRebuild() }
                                else applyAudioBoost()
                            }
                            _errorMessage.value = null
                            // only forget earlier failures once the stream has stayed up for a while
                            mainHandler.removeCallbacks(resetRetryRunnable)
                            mainHandler.postDelayed(resetRetryRunnable, STABLE_PLAY_MS)
                        }
                    }

                    override fun onIsPlayingChanged(playing: Boolean) {
                        _isPlaying.value = playing
                        // Safety net: on some devices the audio session id is only final once playback starts.
                        // Always re-apply (not only when no effect exists): after a relaunch the effect built in
                        // createPlayer() can be bound to an audio session that is replaced once the track is created,
                        // so it looked "active" while doing nothing until the profile was changed by hand.
                        if (playing && _audioBoostProfile.value != AudioBoostProfile.OFF) {
                            if (boostNeedsRebuild) { boostNeedsRebuild = false; scheduleBoostRebuild() }
                            else applyAudioBoost()
                        }
                    }

                    override fun onAudioSessionIdChanged(audioSessionId: Int) {
                        boostNeedsRebuild = true
                        try { audioBoost.rebuild(audioSessionId, _audioBoostProfile.value) } catch (_: Throwable) {}
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        try {
                            handlePlayerError(error)
                        } catch (t: Throwable) {
                            PlaybackLog.error("FAILED", "Error handler failed: ${t.message}")
                            _isBuffering.value = false
                            _isPlaying.value = false
                            _errorMessage.value = "Playback Error: ${error.errorCodeName}"
                        }
                    }

                    private fun handlePlayerError(error: PlaybackException) {
                        cancelStallWatchdog()
                        mainHandler.removeCallbacks(resetRetryRunnable)
                        if (PlaybackLog.isEnabled) {
                            PlaybackLog.error(
                                "ERROR",
                                "Player error on '${lastChannel?.name}' [${PlaybackLog.contentTypeName(activeType)}, attempt ${retryCount + 1}, " +
                                    "retryable=${isRetryable(error)}]: ${PlaybackLog.describePlaybackException(error)}"
                            )
                        }

                        // The plain-media guess below failed too: go back to HLS and let the normal retry logic run.
                        if (progressiveProbe == 1) {
                            progressiveProbe = 2
                            forcedType = null
                            typeFallbackAllowed = false
                        }
                        // An ".m3u8" link that answers with / redirects to a normal media file (providers do this for
                        // "event not found" / "activate" clips): it isn't a playlist, so play it as a regular file.
                        if (lastChannel != null && progressiveProbe == 0 && activeType == C.CONTENT_TYPE_HLS &&
                            forcedType == null && error.errorCode == PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED
                        ) {
                            PlaybackLog.warn("FALLBACK", "HLS link did not return a playlist, retrying as a plain media file")
                            progressiveProbe = 1
                            forcedType = C.CONTENT_TYPE_OTHER
                            _isBuffering.value = true
                            mainHandler.post { lastChannel?.let { startPlayback(it, lastSpoof) } }
                            return
                        }

                        // Unknown container (e.g. extension-less redirect link that is really HLS/DASH):
                        // try the next format right away instead of showing the extractor error.
                        if (typeFallbackAllowed && lastChannel != null &&
                            (error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ||
                                error.errorCode == PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED ||
                                error.errorCode == PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED)
                        ) {
                            val next = when (activeType) {
                                C.CONTENT_TYPE_OTHER -> C.CONTENT_TYPE_HLS
                                C.CONTENT_TYPE_HLS -> C.CONTENT_TYPE_DASH
                                else -> null
                            }
                            if (next != null) {
                                PlaybackLog.warn("FALLBACK", "Format guess ${PlaybackLog.contentTypeName(activeType)} failed to parse, retrying as ${PlaybackLog.contentTypeName(next)}")
                                forcedType = next
                                _isBuffering.value = true
                                mainHandler.post { lastChannel?.let { startPlayback(it, lastSpoof) } }
                                return
                            }
                        }

                        // Live stream moved on while we were away/stalled: jump back to the live edge.
                        if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                            PlaybackLog.warn("RECOVER", "Behind live window: jumping to live edge")
                            seekToDefaultPosition()
                            prepare()
                            return
                        }
                        // Network not ready yet after launch, provider still holding the old
                        // connection, expired token...: silently rebuild the source a few times
                        // instead of leaving the channel dead until the user zaps away.
                        if (lastChannel != null && isRetryable(error) && retryCount < MAX_RETRIES) {
                            retryCount++
                            PlaybackLog.warn("RETRY", "Auto retry $retryCount/$MAX_RETRIES in ${retryCount * RETRY_STEP_MS} ms")
                            retryPending = true
                            _isBuffering.value = true
                            _isPlaying.value = false
                            mainHandler.removeCallbacks(retryRunnable)
                            mainHandler.postDelayed(retryRunnable, retryCount * RETRY_STEP_MS)
                            return
                        }

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
                        PlaybackLog.error("FAILED", "Giving up on '${lastChannel?.name}' after $retryCount retries. Message shown to user: $message")
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

                    override fun onLoadError(
                        eventTime: AnalyticsListener.EventTime,
                        loadEventInfo: androidx.media3.exoplayer.source.LoadEventInfo,
                        mediaLoadData: androidx.media3.exoplayer.source.MediaLoadData,
                        error: java.io.IOException,
                        wasCanceled: Boolean
                    ) {
                        if (!PlaybackLog.isEnabled) return
                        try {
                            PlaybackLog.error(
                                "LOAD",
                                "Load error (dataType=${mediaLoadData.dataType}, canceled=$wasCanceled, " +
                                    "after ${loadEventInfo.loadDurationMs} ms, ${loadEventInfo.bytesLoaded} bytes) " +
                                    "uri=${PlaybackLog.redactUrl(loadEventInfo.uri.toString())}",
                                error
                            )
                        } catch (_: Throwable) {
                        }
                    }

                    override fun onDrmSessionManagerError(eventTime: AnalyticsListener.EventTime, error: Exception) {
                        PlaybackLog.error("DRM", "DRM session manager error", error)
                    }

                    override fun onAudioSinkError(eventTime: AnalyticsListener.EventTime, audioSinkError: Exception) {
                        PlaybackLog.error("AUDIO", "Audio sink error", audioSinkError)
                    }

                    override fun onAudioCodecError(eventTime: AnalyticsListener.EventTime, audioCodecError: Exception) {
                        PlaybackLog.error("AUDIO", "Audio codec error", audioCodecError)
                    }

                    override fun onVideoCodecError(eventTime: AnalyticsListener.EventTime, videoCodecError: Exception) {
                        PlaybackLog.error("VIDEO", "Video codec error", videoCodecError)
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

    private fun isRetryable(error: PlaybackException): Boolean {
        val drmFatal = error.errorCode == PlaybackException.ERROR_CODE_DRM_SCHEME_UNSUPPORTED ||
            error.errorCode == PlaybackException.ERROR_CODE_DRM_PROVISIONING_FAILED ||
            error.errorCode == PlaybackException.ERROR_CODE_DRM_DEVICE_REVOKED ||
            error.message?.contains("crypto", ignoreCase = true) == true
        return !drmFatal
    }

    private fun cancelRetry() {
        retryPending = false
        mainHandler.removeCallbacks(retryRunnable)
        mainHandler.removeCallbacks(resetRetryRunnable)
        mainHandler.removeCallbacks(stallRunnable)
    }

    /** True while the current channel is playing, buffering or about to be retried automatically. */
    fun isStreamActive(): Boolean {
        if (suspendedForBackground || _errorMessage.value != null) return false
        if (retryPending) return true
        val p = exoPlayer ?: return false
        return p.playbackState == Player.STATE_READY || p.playbackState == Player.STATE_BUFFERING
    }

    // ---- Activity lifecycle hooks ----

    /** Activity paused (not PiP): just pause, remember that WE paused so onResume can continue. */
    fun pauseForLifecycle() {
        val p = exoPlayer ?: return
        if (p.playWhenReady) {
            p.pause()
            pausedByLifecycle = true
        }
    }

    fun resumeFromLifecyclePause() {
        if (pausedByLifecycle && !suspendedForBackground) {
            pausedByLifecycle = false
            exoPlayer?.play()
        }
    }

    /**
     * App left the screen. A paused live stream is worthless when the user comes back (stale
     * playlist, expired token, dead socket) and it keeps a provider connection slot busy, so the
     * stream is torn down. The channel stays selected and is re-opened fresh by [resumeFromBackground].
     */
    fun suspendForBackground() {
        cancelRetry()
        val p = exoPlayer ?: return
        if (_currentChannel.value == null) return
        suspendedForBackground = true
        pausedByLifecycle = false
        p.stop()
        p.clearMediaItems()
        _isPlaying.value = false
        _isBuffering.value = false
    }

    /** App is visible again: start the channel from scratch at the live edge. */
    fun resumeFromBackground() {
        if (!suspendedForBackground) return
        suspendedForBackground = false
        val channel = lastChannel ?: return
        retryCount = 0
        boostNeedsRebuild = true
        startPlayback(channel, lastSpoof)
    }

    private companion object {
        val CONTAINER_EXTENSIONS = listOf(
            ".ts", ".mp4", ".m4v", ".mkv", ".flv", ".webm", ".aac", ".mp3", ".avi", ".mov", ".mpg", ".mpeg"
        )
        const val MAX_RETRIES = 5
        const val RETRY_STEP_MS = 1_500L
        const val STABLE_PLAY_MS = 10_000L
        const val STALL_TIMEOUT_MS = 30_000L
        const val FPS_SAMPLE_MS = 2000L
        const val KEY_AUDIO_BOOST = "audio_boost_profile"
        val STANDARD_FPS = listOf(23.976f, 24f, 25f, 29.97f, 30f, 50f, 59.94f, 60f)
    }

    fun playChannel(
        channel: ChannelEntity,
        globalSpoof: SpoofProfile = SpoofProfile(SpoofPreset.TIVIMATE)
    ) {
        cancelRetry()
        retryCount = 0
        forcedType = null
        progressiveProbe = 0
        suspendedForBackground = false
        pausedByLifecycle = false
        lastChannel = channel
        lastSpoof = globalSpoof
        startPlayback(channel, globalSpoof)
    }

    /**
     * Never lets an exception escape: this runs from key handlers and from posted retries on the main thread, where an
     * uncaught exception would close the whole app. A stream that cannot even be set up is reported like any failure.
     */
    private fun startPlayback(
        channel: ChannelEntity,
        globalSpoof: SpoofProfile
    ) {
        try {
            startPlaybackUnsafe(channel, globalSpoof)
        } catch (t: Throwable) {
            PlaybackLog.error("FAILED", "Could not start '${channel.name}': ${t.javaClass.simpleName}: ${t.message}")
            try { exoPlayer?.stop() } catch (_: Throwable) {}
            retryPending = false
            _isBuffering.value = false
            _isPlaying.value = false
            _errorMessage.value = "Playback Error: could not open this stream (${t.message ?: t.javaClass.simpleName})."
        }
    }

    private fun startPlaybackUnsafe(
        channel: ChannelEntity,
        globalSpoof: SpoofProfile
    ) {
        retryPending = false
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
        PlaybackLog.info(
            "START",
            "Channel '${channel.name}' (id=${channel.id}, playlist=${channel.playlistId}) url=${PlaybackLog.redactUrl(channel.streamUrl)} " +
                "drm=${channel.drmType} spoof=${globalSpoof.preset.displayName} userAgent=$userAgent " +
                "warp=[${com.example.data.network.WarpManager.describe()}] " +
                "headers=${headers.keys.joinToString()} forcedType=${forcedType?.let { PlaybackLog.contentTypeName(it) } ?: "auto"}"
        )

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
                        PlaybackLog.error("DRM", "ClearKey crypto scheme not supported on this device")
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
                    PlaybackLog.error("DRM", "${drmType.title} crypto scheme not supported on this device")
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
        val mediaSource = createMediaSource(uri, mediaItem, httpDataSourceFactory, drmSessionManagerProvider, forcedType)

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
        drmSessionManagerProvider: DrmSessionManagerProvider? = null,
        forcedType: Int? = null
    ): MediaSource {
        val path = uri.toString().lowercase()
        val pathOnly = (uri.path ?: "").lowercase()
        val hasContainerExt = CONTAINER_EXTENSIONS.any { pathOnly.endsWith(it) }
        val type = forcedType ?: when {
            path.contains(".m3u8") || path.contains("type=m3u") -> C.CONTENT_TYPE_HLS
            path.contains(".mpd") -> C.CONTENT_TYPE_DASH
            path.startsWith("rtsp://") -> C.CONTENT_TYPE_RTSP
            else -> C.CONTENT_TYPE_OTHER
        }
        activeType = type
        PlaybackLog.info("SOURCE", "Using ${PlaybackLog.contentTypeName(type)} media source (forced=${forcedType != null})")
        // Links such as short/redirect URLs have no extension, so the real format is unknown:
        // if the first guess fails to parse, onPlayerError tries the next format automatically.
        typeFallbackAllowed = forcedType != null || (type == C.CONTENT_TYPE_OTHER && !hasContainerExt)

        return when (type) {
            C.CONTENT_TYPE_HLS -> {
                // Chunkless preparation only reads the master playlist, so language / channel layout
                // of audio muxed into the segments stays unknown ("Audio 1 (und)"). Letting ExoPlayer
                // load the first segment exposes the real tracks (e.g. "Hindi, Stereo").
                val factory = HlsMediaSource.Factory(dataSourceFactory)
                    .setAllowChunklessPreparation(false)
                if (drmSessionManagerProvider != null) {
                    factory.setDrmSessionManagerProvider(drmSessionManagerProvider)
                }
                factory.createMediaSource(mediaItem)
            }
            C.CONTENT_TYPE_DASH -> {
                val factory = DashMediaSource.Factory(dataSourceFactory)
                if (drmSessionManagerProvider != null) {
                    factory.setDrmSessionManagerProvider(drmSessionManagerProvider)
                }
                factory.createMediaSource(mediaItem)
            }
            C.CONTENT_TYPE_RTSP -> {
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
                    type = trackType,
                    channelCount = if (format.channelCount > 0) format.channelCount else 0,
                    bitrate = if (format.bitrate > 0) format.bitrate else 0
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
            val params = player.trackSelectionParameters
                .buildUpon()
                .setOverrideForType(
                    TrackSelectionOverride(trackGroup, listOf(trackInfo.trackIndex))
                )
            // picking an audio track also un-mutes audio if it was switched off
            if (trackInfo.type == C.TRACK_TYPE_AUDIO) params.setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
            player.trackSelectionParameters = params.build()
        }
    }

    /** Switches audio off ("Off" row of the audio panel). */
    fun disableAudio() {
        val player = exoPlayer ?: return
        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
            .build()
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

    /** Playback position / length of the current item (movies); duration is 0 while unknown or for live streams. */
    fun positionMs(): Long = exoPlayer?.currentPosition ?: 0L

    fun durationMs(): Long = exoPlayer?.duration?.takeIf { it != androidx.media3.common.C.TIME_UNSET && it > 0 } ?: 0L

    fun seekToMs(ms: Long) {
        exoPlayer?.seekTo(ms.coerceAtLeast(0L))
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
        cancelRetry()
        exoPlayer?.stop()
        _isPlaying.value = false
        _isBuffering.value = false
    }

    fun closePlayer() {
        suspendedForBackground = false
        pausedByLifecycle = false
        lastChannel = null
        stop()
        _currentChannel.value = null
        _errorMessage.value = null
    }

    fun release() {
        cancelRetry()
        mainHandler.removeCallbacks(fpsRunnable)
        mainHandler.removeCallbacks(boostRebuildRunnable)
        mainHandler.removeCallbacks(stallRunnable)
        exoPlayer?.stop()
        exoPlayer?.release()
        exoPlayer = null
        audioBoost.release()
        _isPlaying.value = false
        _isBuffering.value = false
        _currentChannel.value = null
    }
}
