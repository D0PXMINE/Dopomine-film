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
)

/** Playback position of one video (a film or one episode) identified by its duration. */
@Entity(tableName = "progress", primaryKeys = ["movieId", "durationSec"])
data class ProgressEntity(
    val movieId: Int,
    val durationSec: Int,
    val positionSec: Int,
    val updatedAt: Long,
)

private const val SEP = "|"

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
    posterUrl = posterUrl,
    ratingKp = ratingKp,
    ratingImdb = ratingImdb,
    type = runCatching { ContentType.valueOf(type) }.getOrDefault(ContentType.FILM),
    genres = genres.split(SEP).filter { it.isNotBlank() },
)

fun Movie.toHistory(now: Long = System.currentTimeMillis()) = HistoryEntity(
    id = id,
    title = title,
    originalTitle = originalTitle,
    year = year,
    posterUrl = posterUrl,
    coverUrl = coverUrl,
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
    posterUrl = posterUrl,
    coverUrl = coverUrl,
    ratingKp = ratingKp,
    ratingImdb = ratingImdb,
    type = runCatching { ContentType.valueOf(type) }.getOrDefault(ContentType.FILM),
    genres = genres.split(SEP).filter { it.isNotBlank() },
)
