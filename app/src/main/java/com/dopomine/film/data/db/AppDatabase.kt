package com.dopomine.film.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [FavoriteEntity::class, HistoryEntity::class, ProgressEntity::class],
    version = 2,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun favorites(): FavoriteDao
    abstract fun history(): HistoryDao
    abstract fun progress(): ProgressDao

    companion object {
        /** v2: positions are keyed by episode, history remembers the last episode. Favorites survive. */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE history ADD COLUMN lastSeason INTEGER")
                db.execSQL("ALTER TABLE history ADD COLUMN lastEpisode INTEGER")
                db.execSQL("DROP TABLE IF EXISTS progress")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS progress (movieId INTEGER NOT NULL, item TEXT NOT NULL, " +
                        "positionSec INTEGER NOT NULL, durationSec INTEGER NOT NULL, updatedAt INTEGER NOT NULL, " +
                        "PRIMARY KEY(movieId, item))"
                )
            }
        }

        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "dopomine.db")
                .addMigrations(MIGRATION_1_2)
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
    }
}
