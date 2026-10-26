package com.dopomine.film.data.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

// Numeric fields of this API are not consistent (numbers, strings like "8.5", "99%" or "1999-2003"),
// so they are read as raw JSON primitives and parsed leniently.

@Serializable
data class CountryDto(val country: String? = null)

@Serializable
data class GenreDto(val genre: String? = null)

@Serializable
data class FilmItemDto(
    val kinopoiskId: Int? = null,
    val filmId: Int? = null,
    val imdbId: String? = null,
    val nameRu: String? = null,
    val nameEn: String? = null,
    val nameOriginal: String? = null,
    val countries: List<CountryDto> = emptyList(),
    val genres: List<GenreDto> = emptyList(),
    val ratingKinopoisk: JsonPrimitive? = null,
    val ratingImdb: JsonPrimitive? = null,
    val rating: JsonPrimitive? = null,
    val year: JsonPrimitive? = null,
    val type: String? = null,
    val posterUrl: String? = null,
    val posterUrlPreview: String? = null,
    val coverUrl: String? = null,
    val description: String? = null,
)

@Serializable
data class PagedFilmsDto(
    val total: Int = 0,
    val totalPages: Int = 0,
    val items: List<FilmItemDto> = emptyList(),
)

@Serializable
data class KeywordSearchDto(
    val keyword: String? = null,
    val pagesCount: Int = 0,
    val searchFilmsCountResult: Int? = null,
    val films: List<FilmItemDto> = emptyList(),
)

@Serializable
data class FilmDto(
    val kinopoiskId: Int,
    val imdbId: String? = null,
    val nameRu: String? = null,
    val nameEn: String? = null,
    val nameOriginal: String? = null,
    val posterUrl: String? = null,
    val posterUrlPreview: String? = null,
    val coverUrl: String? = null,
    val logoUrl: String? = null,
    val ratingKinopoisk: JsonPrimitive? = null,
    val ratingKinopoiskVoteCount: JsonPrimitive? = null,
    val ratingImdb: JsonPrimitive? = null,
    val ratingImdbVoteCount: JsonPrimitive? = null,
    val webUrl: String? = null,
    val year: JsonPrimitive? = null,
    val filmLength: JsonPrimitive? = null,
    val slogan: String? = null,
    val description: String? = null,
    val shortDescription: String? = null,
    val type: String? = null,
    val ratingAgeLimits: String? = null,
    val countries: List<CountryDto> = emptyList(),
    val genres: List<GenreDto> = emptyList(),
    val startYear: JsonPrimitive? = null,
    val endYear: JsonPrimitive? = null,
    val serial: Boolean? = null,
    val completed: Boolean? = null,
)

@Serializable
data class EpisodeDto(
    val seasonNumber: Int = 0,
    val episodeNumber: Int = 0,
    val nameRu: String? = null,
    val nameEn: String? = null,
    val synopsis: String? = null,
    val releaseDate: String? = null,
)

@Serializable
data class SeasonDto(val number: Int = 0, val episodes: List<EpisodeDto> = emptyList())

@Serializable
data class SeasonsDto(val total: Int = 0, val items: List<SeasonDto> = emptyList())

@Serializable
data class SimilarsDto(val total: Int = 0, val items: List<FilmItemDto> = emptyList())

@Serializable
data class StaffDto(
    val staffId: Int = 0,
    val nameRu: String? = null,
    val nameEn: String? = null,
    val description: String? = null,
    val posterUrl: String? = null,
    val professionKey: String? = null,
)

@Serializable
data class FilterGenreDto(val id: Int = 0, val genre: String? = null)

@Serializable
data class FilterCountryDto(val id: Int = 0, val country: String? = null)

@Serializable
data class FiltersDto(
    val genres: List<FilterGenreDto> = emptyList(),
    val countries: List<FilterCountryDto> = emptyList(),
)

@Serializable
data class QuotaDto(val value: Int = 0, val used: Int = 0)

@Serializable
data class ApiKeyDto(
    val totalQuota: QuotaDto? = null,
    val dailyQuota: QuotaDto? = null,
    val accountType: String? = null,
)

fun JsonPrimitive?.asDoubleOrNull(): Double? {
    val raw = this?.contentOrNull?.trim() ?: return null
    if (raw.isEmpty() || raw.endsWith("%")) return null
    return raw.replace(',', '.').toDoubleOrNull()?.takeIf { it > 0.0 }
}

fun JsonPrimitive?.asIntOrNull(): Int? {
    val raw = this?.contentOrNull?.trim() ?: return null
    raw.toIntOrNull()?.let { return it }
    raw.toDoubleOrNull()?.let { return it.toInt() }
    // "1999-2003" style ranges: take the first year
    return Regex("\\d{4}").find(raw)?.value?.toIntOrNull()
}
