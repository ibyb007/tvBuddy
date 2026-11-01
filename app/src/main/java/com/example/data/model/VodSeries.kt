package com.example.data.model

/** One episode of a show, as the provider lists it. */
data class VodEpisode(
    val id: String,
    val season: Int,
    val number: Int,
    val title: String,
    val plot: String = "",
    val imageUrl: String = "",
    /** Human readable, e.g. "45m". */
    val duration: String = "",
    /** Xtream: the direct link. Stalker: the portal command that has to be exchanged for a link. */
    val source: String,
    /** Stalker only: the `series=` number sent with the command; 0 when the command is one file already. */
    val stalkerSeries: Int = 0
) {
    /** "E3. Title" (or just "E3" when the provider sent no title). */
    val label: String get() = if (title.isBlank()) "E$number" else "E$number. $title"
}

data class VodSeason(val number: Int, val name: String, val episodes: List<VodEpisode>)

/**
 * An episode that is playing is a copy of its show with a NEGATIVE id built from the show's id, the season and the episode
 * (episodes are not stored in the database). The show's id can be read back from it.
 */
object EpisodeIds {
    private const val BASE = 1_000_000L

    fun of(showId: Long, season: Int, number: Int): Long =
        -(showId * BASE + season.coerceIn(0, 999) * 1000L + number.coerceIn(0, 999))

    fun isEpisode(id: Long): Boolean = id < 0

    /** The show's id when [id] is an episode id, else null. */
    fun showIdOf(id: Long): Long? = if (id < 0) (-id) / BASE else null
}
