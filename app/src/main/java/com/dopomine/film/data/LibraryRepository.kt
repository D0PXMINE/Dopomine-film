package com.dopomine.film.data

import com.dopomine.film.data.db.AppDatabase
import com.dopomine.film.data.db.ProgressEntity
import com.dopomine.film.data.db.toFavorite
import com.dopomine.film.data.db.toHistory
import com.dopomine.film.data.db.toMovie
import com.dopomine.film.data.model.Movie
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlin.math.abs
import kotlin.math.roundToInt

@Immutable
data class HistoryItem(val movie: Movie, val watchedAt: Long, val positionSec: Int, val durationSec: Int) {
    val progress: Float
        get() = if (durationSec > 0) (positionSec.toFloat() / durationSec).coerceIn(0f, 1f) else 0f
}

/** Favorites, watch history and playback positions stored on the device. */
class LibraryRepository(private val db: AppDatabase) {

    val favorites: Flow<List<Movie>> = db.favorites().observeAll().map { list -> list.map { it.toMovie() } }

    val history: Flow<List<HistoryItem>> = db.history().observeAll().map { list ->
        list.map { HistoryItem(it.toMovie(), it.watchedAt, it.positionSec, it.durationSec) }
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

    suspend fun removeFavorite(id: Int) = db.favorites().delete(id)

    /** Called when the player opens: moves the title to the top of "continue watching". */
    suspend fun markOpened(movie: Movie) {
        val existing = db.history().get(movie.id)
        db.history().upsert(
            movie.toHistory().copy(
                positionSec = existing?.positionSec ?: 0,
                durationSec = existing?.durationSec ?: 0,
                // keep artwork we already know if the caller passed a lighter object
                posterUrl = movie.posterUrl ?: existing?.posterUrl,
                coverUrl = movie.coverUrl ?: existing?.coverUrl,
                ratingKp = movie.ratingKp ?: existing?.ratingKp,
                ratingImdb = movie.ratingImdb ?: existing?.ratingImdb,
            )
        )
    }

    suspend fun saveProgress(movieId: Int, positionSec: Double, durationSec: Double) {
        val duration = durationSec.roundToInt()
        val position = positionSec.roundToInt().coerceIn(0, duration)
        if (duration <= 0) return
        val now = System.currentTimeMillis()
        val finished = duration - position < 90
        val key = findProgress(movieId, durationSec)?.durationSec ?: duration
        if (finished) {
            db.progress().delete(movieId, key)
        } else {
            db.progress().upsert(ProgressEntity(movieId, key, position, now))
        }
        db.history().updateProgress(movieId, if (finished) duration else position, duration, now)
    }

    /** Saved position for a video of this title whose duration matches (±5 s). */
    suspend fun findProgress(movieId: Int, durationSec: Double): ProgressEntity? =
        db.progress().forMovie(movieId).firstOrNull { abs(it.durationSec - durationSec) <= 5.0 }

    suspend fun removeFromHistory(id: Int) {
        db.history().delete(id)
        db.progress().deleteForMovie(id)
    }

    suspend fun clearHistory() {
        db.history().clear()
        db.progress().clear()
    }
}
