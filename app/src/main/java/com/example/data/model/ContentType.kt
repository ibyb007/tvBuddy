package com.example.data.model

/** Kind of item a playlist entry is. Live TV stays "TV"; portals can also deliver movies and shows (VOD). */
object ContentType {
    const val TV = "TV"
    const val MOVIE = "MOVIE"
    const val SHOW = "SHOW"

    val all = listOf(TV, MOVIE, SHOW)

    /** Plural label used in menus ("Movies", "Shows") and on the "Playlist is processed" screen. */
    fun label(type: String): String = when (type) {
        MOVIE -> "Movies"
        SHOW -> "Shows"
        else -> "TV"
    }
}

/** What one import / refresh of a playlist produced, per content type. */
data class ImportCounts(val tv: Int = 0, val movies: Int = 0, val shows: Int = 0) {
    val hasVod: Boolean get() = movies > 0 || shows > 0
}
