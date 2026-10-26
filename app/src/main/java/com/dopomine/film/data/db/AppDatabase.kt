package com.dopomine.film.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [FavoriteEntity::class, HistoryEntity::class, ProgressEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun favorites(): FavoriteDao
    abstract fun history(): HistoryDao
    abstract fun progress(): ProgressDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "dopomine.db")
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
    }
}
