package com.dopomine.film.data.model

import androidx.compose.runtime.Immutable
import com.dopomine.film.data.api.FilmDto
import com.dopomine.film.data.api.FilmItemDto
import com.dopomine.film.data.api.StaffDto
import com.dopomine.film.data.api.asDoubleOrNull
import com.dopomine.film.data.api.asIntOrNull

enum class ContentType {
    FILM, SERIES;

    companion object {
        fun fromApi(type: String?): ContentType = when (type) {
            "TV_SERIES", "MINI_SERIES", "TV_SHOW" -> SERIES
            else -> FILM
        }
    }
}

@Immutable
data class Movie(
    val id: Int,
    val title: String,
    val originalTitle: String? = null,
    val year: Int? = null,
    val posterUrl: String? = null,
    val posterLargeUrl: String? = null,
    val coverUrl: String? = null,
    val ratingKp: Double? = null,
    val ratingImdb: Double? = null,
    val type: ContentType = ContentType.FILM,
    val genres: List<String> = emptyList(),
    val countries: List<String> = emptyList(),
    val description: String? = null,
)

@Immutable
data class MovieDetails(
    val movie: Movie,
    val posterFullUrl: String?,
    val logoUrl: String?,
    val ratingKpVotes: Int?,
    val ratingImdbVotes: Int?,
    val slogan: String?,
    val lengthMinutes: Int?,
    val ageLimit: String?,
    val startYear: Int?,
    val endYear: Int?,
    val completed: Boolean?,
    val imdbId: String?,
    val webUrl: String?,
)

@Immutable
data class Person(
    val id: Int,
    val name: String,
    val role: String?,
    val photoUrl: String?,
    val profession: String,
)

@Immutable
data class Episode(
    val season: Int,
    val number: Int,
    val title: String?,
    val releaseDate: String?,
)

@Immutable
data class Season(val number: Int, val episodes: List<Episode>)

@Immutable
data class PagedMovies(val items: List<Movie>, val page: Int, val totalPages: Int) {
    val hasMore: Boolean get() = page < totalPages
}

@Immutable
data class ApiKeyInfo(val accountType: String, val dailyUsed: Int, val dailyLimit: Int)

private fun pickTitle(ru: String?, en: String?, original: String?): String =
    listOf(ru, en, original).firstOrNull { !it.isNullOrBlank() }?.trim() ?: "Без названия"

private fun originalFor(title: String, en: String?, original: String?): String? =
    listOf(original, en).firstOrNull { !it.isNullOrBlank() && it.trim() != title }?.trim()

fun FilmItemDto.toMovie(): Movie? {
    val id = kinopoiskId ?: filmId ?: return null
    val title = pickTitle(nameRu, nameEn, nameOriginal)
    return Movie(
        id = id,
        title = title,
        originalTitle = originalFor(title, nameEn, nameOriginal),
        year = year.asIntOrNull(),
        posterUrl = posterUrlPreview ?: posterUrl,
        posterLargeUrl = posterUrl ?: posterUrlPreview,
        coverUrl = coverUrl,
        ratingKp = (ratingKinopoisk ?: rating).asDoubleOrNull(),
        ratingImdb = ratingImdb.asDoubleOrNull(),
        type = ContentType.fromApi(type),
        genres = genres.mapNotNull { it.genre?.takeIf(String::isNotBlank) },
        countries = countries.mapNotNull { it.country?.takeIf(String::isNotBlank) },
        description = description,
    )
}

fun FilmDto.toDetails(): MovieDetails {
    val title = pickTitle(nameRu, nameEn, nameOriginal)
    val movie = Movie(
        id = kinopoiskId,
        title = title,
        originalTitle = originalFor(title, nameEn, nameOriginal),
        year = year.asIntOrNull() ?: startYear.asIntOrNull(),
        posterUrl = posterUrlPreview ?: posterUrl,
        posterLargeUrl = posterUrl ?: posterUrlPreview,
        coverUrl = coverUrl,
        ratingKp = ratingKinopoisk.asDoubleOrNull(),
        ratingImdb = ratingImdb.asDoubleOrNull(),
        type = if (serial == true) ContentType.SERIES else ContentType.fromApi(type),
        genres = genres.mapNotNull { it.genre?.takeIf(String::isNotBlank) },
        countries = countries.mapNotNull { it.country?.takeIf(String::isNotBlank) },
        description = description ?: shortDescription,
    )
    return MovieDetails(
        movie = movie,
        posterFullUrl = posterUrl ?: posterUrlPreview,
        logoUrl = logoUrl,
        ratingKpVotes = ratingKinopoiskVoteCount.asIntOrNull(),
        ratingImdbVotes = ratingImdbVoteCount.asIntOrNull(),
        slogan = slogan?.takeIf { it.isNotBlank() && it != "-" },
        lengthMinutes = filmLength.asIntOrNull(),
        ageLimit = ratingAgeLimits?.removePrefix("age")?.takeIf { it.isNotBlank() }?.let { "$it+" },
        startYear = startYear.asIntOrNull(),
        endYear = endYear.asIntOrNull(),
        completed = completed,
        imdbId = imdbId,
        webUrl = webUrl,
    )
}

fun StaffDto.toPerson(): Person? {
    val name = listOf(nameRu, nameEn).firstOrNull { !it.isNullOrBlank() } ?: return null
    return Person(
        id = staffId,
        name = name,
        role = description?.takeIf { it.isNotBlank() },
        photoUrl = posterUrl,
        profession = professionKey ?: "",
    )
}
