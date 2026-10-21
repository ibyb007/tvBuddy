package com.example.data.model

/** Rich details of a movie or show (what the provider's panel scraped from TMDB / IMDb). Every field may be blank. */
data class VodInfo(
    val plot: String = "",
    val cast: String = "",
    val director: String = "",
    val genre: String = "",
    val year: String = "",
    /** Human readable, e.g. "1h 55m". */
    val duration: String = "",
    val rating: String = "",
    /** 16:9 fanart used as the page background. */
    val backdropUrl: String = "",
    val trailer: String = ""
) {
    val isEmpty: Boolean get() = plot.isBlank() && cast.isBlank() && director.isBlank() && genre.isBlank() &&
        year.isBlank() && duration.isBlank() && backdropUrl.isBlank()

    /** "2019 • 1h 55m • Komedie, Actie" */
    val metaLine: String get() = listOf(year, duration, genre).filter { it.isNotBlank() }.joinToString("  •  ")

    companion object {
        fun formatMinutes(minutes: Int): String = when {
            minutes <= 0 -> ""
            minutes < 60 -> "${minutes}m"
            else -> "${minutes / 60}h ${minutes % 60}m"
        }

        /** Provider ratings come as "5.9", "6.8000", "0" or "": only a real, positive score is kept. */
        fun cleanRating(raw: String?): String {
            val d = raw?.trim()?.replace(',', '.')?.toDoubleOrNull() ?: return ""
            return if (d > 0.0) String.format(java.util.Locale.US, "%.1f", d) else ""
        }
    }
}
