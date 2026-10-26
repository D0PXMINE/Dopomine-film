package com.dopomine.film.ui.components

import com.dopomine.film.data.model.ContentType
import com.dopomine.film.data.model.Movie
import java.util.Locale

object Format {
    fun rating(value: Double?): String = value?.let { String.format(Locale.US, "%.1f", it) } ?: "—"

    fun votes(value: Int?): String? {
        if (value == null || value <= 0) return null
        return when {
            value >= 1_000_000 -> String.format(Locale("ru"), "%.1f млн", value / 1_000_000.0)
            value >= 1_000 -> "${value / 1_000} тыс."
            else -> value.toString()
        }
    }

    fun minutes(value: Int?): String? {
        if (value == null || value <= 0) return null
        val h = value / 60
        val m = value % 60
        return when {
            h == 0 -> "$m мин"
            m == 0 -> "$h ч"
            else -> "$h ч $m мин"
        }
    }

    fun clock(totalSeconds: Int): String {
        val s = totalSeconds.coerceAtLeast(0)
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, sec) else String.format(Locale.US, "%d:%02d", m, sec)
    }

    fun typeLabel(type: ContentType) = when (type) {
        ContentType.FILM -> "фильм"
        ContentType.SERIES -> "сериал"
        ContentType.MINI -> "мини-сериал"
        ContentType.SHOW -> "шоу"
    }

    /** "2024 · драма, комедия" in lower case, like splicer's mono tag lines. */
    fun meta(movie: Movie, genres: Int = 2): String =
        listOfNotNull(
            movie.year?.toString(),
            movie.genres.take(genres).joinToString(", ") { it.lowercase() }.ifBlank { null },
        ).joinToString(" · ")

    /** "КП 8.5 · IMDb 8.7" */
    fun ratings(movie: Movie): String? =
        listOfNotNull(movie.ratingKp?.let { "КП ${rating(it)}" }, movie.ratingImdb?.let { "IMDb ${rating(it)}" })
            .joinToString(" · ").ifBlank { null }

    fun plural(n: Int, one: String, few: String, many: String): String {
        val mod10 = n % 10
        val mod100 = n % 100
        val word = when {
            mod10 == 1 && mod100 != 11 -> one
            mod10 in 2..4 && mod100 !in 12..14 -> few
            else -> many
        }
        return "$n $word"
    }

    fun seasons(n: Int) = plural(n, "сезон", "сезона", "сезонов")
    fun episodes(n: Int) = plural(n, "серия", "серии", "серий")
    fun titles(n: Int) = plural(n, "фильм", "фильма", "фильмов")
}
