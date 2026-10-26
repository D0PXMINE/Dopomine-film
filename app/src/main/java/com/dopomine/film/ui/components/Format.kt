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
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%d:%02d", m, s)
    }

    fun typeLabel(type: ContentType) = if (type == ContentType.SERIES) "Сериал" else "Фильм"

    /** "2024 · Драма, Комедия" */
    fun subtitle(movie: Movie, genres: Int = 2): String =
        listOfNotNull(movie.year?.toString(), movie.genres.take(genres).joinToString(", ").ifBlank { null })
            .joinToString(" · ")

    fun seasons(count: Int): String {
        val mod10 = count % 10
        val mod100 = count % 100
        val word = when {
            mod10 == 1 && mod100 != 11 -> "сезон"
            mod10 in 2..4 && mod100 !in 12..14 -> "сезона"
            else -> "сезонов"
        }
        return "$count $word"
    }

    fun episodes(count: Int): String {
        val mod10 = count % 10
        val mod100 = count % 100
        val word = when {
            mod10 == 1 && mod100 != 11 -> "серия"
            mod10 in 2..4 && mod100 !in 12..14 -> "серии"
            else -> "серий"
        }
        return "$count $word"
    }
}
