package com.example.player

import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.LoudnessEnhancer
import android.os.Build
import android.util.Log

/**
 * Audio boost profiles (ported from the Vega app).
 *
 * ExoPlayer's own volume is clamped to [0, 1] and can only attenuate, so real gain is applied
 * post-decode with Android's [LoudnessEnhancer]. "Cinematic" uses a multiband [DynamicsProcessing]
 * chain (API 28+) that lifts quiet detail instead of applying a flat gain.
 */
enum class AudioBoostProfile(
    val title: String,
    val shortTitle: String,
    val description: String,
    val gainDb: Float,
    val cinematic: Boolean = false
) {
    OFF(
        "Standard (Off)", "Off",
        "Default audio pass-through without digital preamp boost.",
        0f
    ),
    RICH(
        "Rich & Immersive (+12 dB)", "Rich",
        "Large volume gain across dialogue, score, ambient noise and loud peaks.",
        12f
    ),
    CINEMATIC(
        "Cinematic Sound (+13 dB)", "Cinematic",
        "Louder than Rich, plus extra lift for quiet detail like wind, whispers and distant chatter. Loud peaks are compressed.",
        13f, cinematic = true
    ),
    DIALOGUE(
        "Dialogue Boost / Night Mode (+6 dB)", "Dialogue",
        "Moderate gain for clearer speech at low volume.",
        6f
    );

    fun next(): AudioBoostProfile = entries[(ordinal + 1) % entries.size]

    companion object {
        fun fromName(name: String?): AudioBoostProfile =
            entries.firstOrNull { it.name == name } ?: OFF
    }
}

/** Owns the audio effects attached to one ExoPlayer audio session. */
internal class AudioBoostEngine {
    private var loudness: LoudnessEnhancer? = null
    private var dynamics: DynamicsProcessing? = null
    private var dynamicsSession = -1
    private var loudnessSession = -1

    fun apply(audioSessionId: Int, profile: AudioBoostProfile) {
        // 0 / negative = not allocated yet; the manager re-applies once playback starts.
        if (audioSessionId <= 0) return

        if (profile.gainDb <= 0f) {
            disable()
            return
        }
        if (profile.cinematic && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            applyCinematic(audioSessionId, profile.gainDb)
        ) {
            // Cinematic replaces the flat gain stage; the two must never stack.
            disableLoudness()
            return
        }
        disableDynamics()
        applyLoudness(audioSessionId, profile.gainDb)
    }

    private fun applyLoudness(session: Int, gainDb: Float) {
        try {
            if (loudness != null && loudnessSession != session) {
                runCatching { loudness?.release() }
                loudness = null
            }
            val effect = loudness ?: LoudnessEnhancer(session).also {
                loudness = it
                loudnessSession = session
            }
            effect.setTargetGain((gainDb.coerceIn(0f, 30f) * 100).toInt())
            effect.setEnabled(true)
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to apply audio boost: ${t.message}")
        }
    }

    /**
     * Level-dependent boost: quiet detail and dialogue get the full gain, loud content is
     * compressed under full scale so it can't hard-clip. Per band the ratio is derived from the
     * requested gain and a ceiling, so asking for more gain squeezes dynamics instead of clipping.
     */
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.P)
    private fun applyCinematic(session: Int, gainDb: Float): Boolean {
        return try {
            if (dynamics != null && dynamicsSession != session) {
                runCatching { dynamics?.release() }
                dynamics = null
            }
            val baseGainDb = gainDb.coerceIn(0f, 14f)

            val mbcCutoff = floatArrayOf(120f, 500f, 2000f, 6000f, 20000f)
            val mbcLift = floatArrayOf(3f, 9f, 12f, 7f, 2f)
            val mbcThresh = floatArrayOf(-42f, -40f, -40f, -40f, -44f)
            val mbcCeiling = floatArrayOf(-12f, -6f, -6f, -6f, -10f)
            val mbcAttack = floatArrayOf(8f, 4f, 3f, 2f, 1.5f)
            val mbcRelease = floatArrayOf(250f, 200f, 160f, 140f, 120f)
            val mbcGate = floatArrayOf(-72f, -70f, -68f, -66f, -64f)
            val eqCutoff = floatArrayOf(60f, 150f, 400f, 1000f, 3000f, 8000f, 20000f)
            val eqGain = floatArrayOf(-2f, -1f, 0f, 0f, 0.5f, 0.5f, 0f)

            val dp = dynamics ?: run {
                val config = DynamicsProcessing.Config.Builder(
                    DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                    2,
                    true, eqCutoff.size,
                    true, mbcCutoff.size,
                    false, 0,
                    true
                ).build()
                DynamicsProcessing(0, session, config).also {
                    dynamics = it
                    dynamicsSession = session
                }
            }

            for (i in eqCutoff.indices) {
                dp.setPreEqBandAllChannelsTo(i, DynamicsProcessing.EqBand(true, eqCutoff[i], eqGain[i]))
            }
            for (i in mbcCutoff.indices) {
                val makeup = baseGainDb + mbcLift[i]
                val headroom = mbcCeiling[i] - makeup - mbcThresh[i]
                val ratio = (if (headroom > 1f) -mbcThresh[i] / headroom else 8f).coerceIn(1.5f, 8f)
                dp.setMbcBandAllChannelsTo(
                    i,
                    DynamicsProcessing.MbcBand(
                        true, mbcCutoff[i], mbcAttack[i], mbcRelease[i],
                        ratio, mbcThresh[i], 14f, mbcGate[i], 1.3f, 0f, makeup
                    )
                )
            }
            // Safety net only; the bands already hold peaks near -6 dBFS.
            dp.setLimiterAllChannelsTo(
                DynamicsProcessing.Limiter(true, true, 0, 0.6f, 120f, 20f, -4f, 0f)
            )
            dp.setEnabled(true)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "Cinematic boost unavailable, using plain gain: ${t.message}")
            runCatching { dynamics?.release() }
            dynamics = null
            false
        }
    }

    private fun disableLoudness() {
        runCatching { loudness?.setEnabled(false) }
    }

    private fun disableDynamics() {
        runCatching { dynamics?.setEnabled(false) }
    }

    fun disable() {
        disableLoudness()
        disableDynamics()
    }

    val isActive: Boolean
        get() = (loudness?.getEnabled() == true) || (dynamics?.getEnabled() == true)

    fun release() {
        runCatching { loudness?.release() }
        runCatching { dynamics?.release() }
        loudness = null
        dynamics = null
    }

    private companion object {
        const val TAG = "AudioBoost"
    }
}
