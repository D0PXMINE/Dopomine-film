package com.dopomine.film.data.model

import androidx.compose.runtime.Immutable

enum class ContentType {
    FILM, SERIES, MINI, SHOW;

    val isSeries: Boolean get() = this != FILM

    companion object {
        fun fromCode(code: Int) = entries.getOrElse(code) { FILM }
    }
}

/** A title from the catalog. [id] is the Kinopoisk film ID, which is also what the player needs. */
@Immutable
data class Movie(
    val id: Int,
    val title: String,
    val originalTitle: String? = null,
    val year: Int? = null,
    val endYear: Int? = null,
    val type: ContentType = ContentType.FILM,
    val animated: Boolean = false,
    val genres: List<String> = emptyList(),
    val countries: List<String> = emptyList(),
    val ratingKp: Double? = null,
    val votesKp: Int = 0,
    val ratingImdb: Double? = null,
    val votesImdb: Int = 0,
    val duration: Int? = null,
    val imdbId: String? = null,
    val seasons: Int? = null,
    val episodes: Int? = null,
    val wiki: String? = null,
) {
    /** Kinopoisk's own poster CDN works by film ID, no key needed. */
    val posterUrl: String get() = posterUrl(id)
    val posterLargeUrl: String get() = "https://st.kp.yandex.net/images/film_big/$id.jpg"

    companion object {
        fun posterUrl(id: Int) = "https://st.kp.yandex.net/images/film_iphone/iphone360_$id.jpg"
    }
}

@Immutable
data class Ratings(val kp: Double?, val kpVotes: Int, val imdb: Double?, val imdbVotes: Int)

@Immutable
data class MovieDetails(
    val movie: Movie,
    val description: String? = null,
    val directors: List<String> = emptyList(),
    val cast: List<String> = emptyList(),
)

@Immutable
data class PagedMovies(val items: List<Movie>, val page: Int, val hasMore: Boolean)
