package com.dopomine.film.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface FavoriteDao {
    @Query("SELECT * FROM favorites ORDER BY addedAt DESC")
    fun observeAll(): Flow<List<FavoriteEntity>>

    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE id = :id)")
    fun observeIsFavorite(id: Int): Flow<Boolean>

    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE id = :id)")
    suspend fun isFavorite(id: Int): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: FavoriteEntity)

    @Query("DELETE FROM favorites WHERE id = :id")
    suspend fun delete(id: Int)
}

@Dao
interface HistoryDao {
    @Query("SELECT * FROM history ORDER BY watchedAt DESC LIMIT 100")
    fun observeAll(): Flow<List<HistoryEntity>>

    @Query("SELECT * FROM history WHERE id = :id")
    suspend fun get(id: Int): HistoryEntity?

    @Upsert
    suspend fun upsert(entity: HistoryEntity)

    @Query("UPDATE history SET positionSec = :position, durationSec = :duration, watchedAt = :now WHERE id = :id")
    suspend fun updateProgress(id: Int, position: Int, duration: Int, now: Long)

    @Query("DELETE FROM history WHERE id = :id")
    suspend fun delete(id: Int)

    @Query("DELETE FROM history")
    suspend fun clear()
}

@Dao
interface ProgressDao {
    @Query("SELECT * FROM progress WHERE movieId = :movieId ORDER BY updatedAt DESC")
    suspend fun forMovie(movieId: Int): List<ProgressEntity>

    @Upsert
    suspend fun upsert(entity: ProgressEntity)

    @Query("DELETE FROM progress WHERE movieId = :movieId AND durationSec = :durationSec")
    suspend fun delete(movieId: Int, durationSec: Int)

    @Query("DELETE FROM progress WHERE movieId = :movieId")
    suspend fun deleteForMovie(movieId: Int)

    @Query("DELETE FROM progress")
    suspend fun clear()
}
