package com.dopomine.film.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.dopomine.film.data.model.ContentType
import com.dopomine.film.data.model.Movie

@Entity(tableName = "favorites")
data class FavoriteEntity(
    @PrimaryKey val id: Int,
    val title: String,
    val originalTitle: String?,
    val year: Int?,
    val posterUrl: String?,
    val ratingKp: Double?,
    val ratingImdb: Double?,
    val type: String,
    val genres: String,
    val addedAt: Long,
)

@Entity(tableName = "history")
data class HistoryEntity(
    @PrimaryKey val id: Int,
    val title: String,
    val originalTitle: String?,
    val year: Int?,
    val posterUrl: String?,
    val coverUrl: String?,
    val ratingKp: Double?,
    val ratingImdb: Double?,
    val type: String,
    val genres: String,
    val watchedAt: Long,
    val positionSec: Int = 0,
    val durationSec: Int = 0,
    /** Last episode played (series from a source with an episode list). */
    val lastSeason: Int? = null,
    val lastEpisode: Int? = null,
)

/**
 * Playback position of one video of a title. [item] identifies it: "movie", "s1e3"
 * for an episode, or "d5400" (duration in seconds) when the source gives no episode list.
 */
@Entity(tableName = "progress", primaryKeys = ["movieId", "item"])
data class ProgressEntity(
    val movieId: Int,
    val item: String,
    val positionSec: Int,
    val durationSec: Int,
    val updatedAt: Long,
)

private const val SEP = "|"

private fun typeOf(name: String) = runCatching { ContentType.valueOf(name) }.getOrDefault(ContentType.FILM)

fun Movie.toFavorite(now: Long = System.currentTimeMillis()) = FavoriteEntity(
    id = id,
    title = title,
    originalTitle = originalTitle,
    year = year,
    posterUrl = posterUrl,
    ratingKp = ratingKp,
    ratingImdb = ratingImdb,
    type = type.name,
    genres = genres.joinToString(SEP),
    addedAt = now,
)

fun FavoriteEntity.toMovie() = Movie(
    id = id,
    title = title,
    originalTitle = originalTitle,
    year = year,
    ratingKp = ratingKp,
    ratingImdb = ratingImdb,
    type = typeOf(type),
    genres = genres.split(SEP).filter { it.isNotBlank() },
)

fun Movie.toHistory(now: Long = System.currentTimeMillis()) = HistoryEntity(
    id = id,
    title = title,
    originalTitle = originalTitle,
    year = year,
    posterUrl = posterUrl,
    coverUrl = null,
    ratingKp = ratingKp,
    ratingImdb = ratingImdb,
    type = type.name,
    genres = genres.joinToString(SEP),
    watchedAt = now,
)

fun HistoryEntity.toMovie() = Movie(
    id = id,
    title = title,
    originalTitle = originalTitle,
    year = year,
    ratingKp = ratingKp,
    ratingImdb = ratingImdb,
    type = typeOf(type),
    genres = genres.split(SEP).filter { it.isNotBlank() },
)
