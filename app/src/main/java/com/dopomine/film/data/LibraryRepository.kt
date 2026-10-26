package com.dopomine.film.data

import androidx.compose.runtime.Immutable
import com.dopomine.film.data.db.AppDatabase
import com.dopomine.film.data.db.ProgressEntity
import com.dopomine.film.data.db.toFavorite
import com.dopomine.film.data.db.toHistory
import com.dopomine.film.data.db.toMovie
import com.dopomine.film.data.model.Movie
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlin.math.roundToInt

@Immutable
data class HistoryItem(
    val movie: Movie,
    val watchedAt: Long,
    val positionSec: Int,
    val durationSec: Int,
    val lastSeason: Int?,
    val lastEpisode: Int?,
) {
    val progress: Float
        get() = if (durationSec > 0) (positionSec.toFloat() / durationSec).coerceIn(0f, 1f) else 0f
}

/** Favorites, watch history and playback positions stored on the device. */
class LibraryRepository(private val db: AppDatabase) {

    val favorites: Flow<List<Movie>> = db.favorites().observeAll().map { list -> list.map { it.toMovie() } }

    val history: Flow<List<HistoryItem>> = db.history().observeAll().map { list ->
        list.map { HistoryItem(it.toMovie(), it.watchedAt, it.positionSec, it.durationSec, it.lastSeason, it.lastEpisode) }
    }

    fun isFavorite(id: Int): Flow<Boolean> = db.favorites().observeIsFavorite(id)

    suspend fun toggleFavorite(movie: Movie): Boolean {
        val dao = db.favorites()
        return if (dao.isFavorite(movie.id)) {
            dao.delete(movie.id)
            false
        } else {
            dao.insert(movie.toFavorite())
            true
        }
    }

    /** Called when the player opens: moves the title to the top of "continue watching". */
    suspend fun markOpened(movie: Movie) {
        val existing = db.history().get(movie.id)
        db.history().upsert(
            movie.toHistory().copy(
                positionSec = existing?.positionSec ?: 0,
                durationSec = existing?.durationSec ?: 0,
                lastSeason = existing?.lastSeason,
                lastEpisode = existing?.lastEpisode,
                ratingKp = movie.ratingKp ?: existing?.ratingKp,
                ratingImdb = movie.ratingImdb ?: existing?.ratingImdb,
                title = if (movie.title.startsWith("Кинопоиск #") && existing != null) existing.title else movie.title,
            )
        )
    }

    suspend fun lastEpisode(movieId: Int): Pair<Int, Int>? {
        val h = db.history().get(movieId) ?: return null
        return if (h.lastSeason != null && h.lastEpisode != null) h.lastSeason to h.lastEpisode else null
    }

    suspend fun saveProgress(movieId: Int, item: String, positionMs: Long, durationMs: Long, season: Int? = null, episode: Int? = null) {
        val duration = (durationMs / 1000.0).roundToInt()
        if (duration <= 0) return
        val position = (positionMs / 1000.0).roundToInt().coerceIn(0, duration)
        val now = System.currentTimeMillis()
        val finished = duration - position < 90
        if (finished) {
            db.progress().delete(movieId, item)
        } else {
            db.progress().upsert(ProgressEntity(movieId, item, position, duration, now))
        }
        db.history().updateProgress(movieId, if (finished) duration else position, duration, season, episode, now)
    }

    /** Saved position (seconds) of one video, or null. */
    suspend fun position(movieId: Int, item: String): Int? = db.progress().get(movieId, item)?.positionSec

    suspend fun removeFromHistory(id: Int) {
        db.history().delete(id)
        db.progress().deleteForMovie(id)
    }

    suspend fun clearHistory() {
        db.history().clear()
        db.progress().clear()
    }
}
