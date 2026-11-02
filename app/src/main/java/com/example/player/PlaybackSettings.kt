package com.example.player

/** How much video is downloaded ahead. Bigger = fewer pauses on a stream that stutters, slower channel start. */
enum class BufferSize(
    val title: String,
    /** seconds of video needed before playback (re)starts / kept at least / at most */
    val minMs: Int,
    val maxMs: Int,
    val startMs: Int,
    val rebufferMs: Int,
    /** memory cap of the buffer; -1 = ExoPlayer default */
    val maxBytes: Int
) {
    NONE("None", 2_000, 5_000, 500, 1_000, 8 * 1024 * 1024),
    SMALL("Small", 5_000, 15_000, 1_500, 3_000, 16 * 1024 * 1024),
    MEDIUM("Medium", 15_000, 50_000, 2_500, 5_000, -1),
    LARGE("Large", 40_000, 90_000, 4_000, 8_000, 96 * 1024 * 1024),
    VERY_LARGE("Very large", 60_000, 150_000, 6_000, 12_000, 160 * 1024 * 1024);

    companion object {
        fun fromName(name: String?) = entries.firstOrNull { it.name == name } ?: MEDIUM
    }
}

enum class DecoderMode(val title: String) {
    HARDWARE("Hardware"),
    SOFTWARE("Software");

    companion object {
        fun fromName(name: String?) = entries.firstOrNull { it.name == name } ?: HARDWARE
    }
}

data class PlaybackSettings(
    val bufferSize: BufferSize = BufferSize.MEDIUM,
    val audioDecoder: DecoderMode = DecoderMode.HARDWARE,
    val videoDecoder: DecoderMode = DecoderMode.HARDWARE
)
