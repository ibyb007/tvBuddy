package com.example.data.model

enum class PlaylistType(val title: String) {
    M3U("M3U / M3U8 Playlist"),
    XTREAM("Xtream Codes API"),
    STALKER("Stalker / MAC Portal"),
    RAW_JSON("Raw JSON Playlist")
}

data class Channel(
    val id: Long = 0,
    val playlistId: Long = 0,
    val streamId: String = "",
    val name: String,
    val streamUrl: String,
    val logoUrl: String? = null,
    val groupTitle: String = "All Channels",
    val channelNumber: Int = 0,
    val drmConfig: DrmConfig = DrmConfig(),
    val httpUserAgent: String? = null,
    val httpHeaders: Map<String, String> = emptyMap(),
    val isFavorite: Boolean = false,
    val currentProgram: String? = null
)
