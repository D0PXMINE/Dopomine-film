package com.dopomine.film.data.catalog

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.dopomine.film.data.model.ContentType
import com.dopomine.film.data.model.Movie
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The offline catalog: a SQLite snapshot built by tools/catalog/build_catalog.py from Wikidata
 * and Kinopoisk ratings. It ships in the APK and can be replaced by a newer download.
 */
class CatalogDb(context: Context) {
    private val app = context.applicationContext
    private val dir = File(app.filesDir, "catalog").apply { mkdirs() }
    val file = File(dir, "catalog.db")
    val pendingFile = File(dir, "catalog-new.db")
    private val mutex = Mutex()
    @Volatile private var db: SQLiteDatabase? = null

    suspend fun database(): SQLiteDatabase = db ?: mutex.withLock {
        db ?: withContext(Dispatchers.IO) { open() }.also { db = it }
    }

    private fun open(): SQLiteDatabase {
        // a downloaded update is swapped in before the database is opened
        if (pendingFile.exists()) {
            if (isCompatible(pendingFile)) pendingFile.renameTo(file) else pendingFile.delete()
        }
        val bundled = bundledVersion()
        val installed = if (file.exists() && isCompatible(file)) versionOf(file) else null
        if (installed == null || (bundled != null && bundled > installed)) {
            copyBundled()
        }
        return SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY)
    }

    private fun bundledVersion(): String? =
        runCatching { app.assets.open("catalog/version.txt").bufferedReader().use { it.readText().trim() } }.getOrNull()

    private fun copyBundled() {
        val tmp = File(dir, "catalog.tmp")
        app.assets.open("catalog/catalog.db").use { input -> tmp.outputStream().use { input.copyTo(it, 64 * 1024) } }
        tmp.renameTo(file)
    }

    /** Version stamp of a catalog file, or null if the file is not a valid catalog. */
    fun versionOf(f: File): String? = runCatching {
        SQLiteDatabase.openDatabase(f.path, null, SQLiteDatabase.OPEN_READONLY).use { d ->
            d.rawQuery("SELECT value FROM meta WHERE key = 'version'", null).use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }
    }.getOrNull()

    /** A valid catalog with the columns this app reads (older snapshots lack `mal`). */
    fun isCompatible(f: File): Boolean = runCatching {
        SQLiteDatabase.openDatabase(f.path, null, SQLiteDatabase.OPEN_READONLY).use { d ->
            val schema = d.rawQuery("SELECT value FROM meta WHERE key = 'schema'", null)
                .use { c -> if (c.moveToFirst()) c.getString(0).toIntOrNull() else null } ?: 1
            val version = d.rawQuery("SELECT value FROM meta WHERE key = 'version'", null).use { c -> c.moveToFirst() }
            version && schema >= SCHEMA
        }
    }.getOrDefault(false)

    /**
     * Of the [n] most-voted titles in the current catalog, the share that still has ratings in
     * [f]: a snapshot built while Kinopoisk throttled the builder loses them for big titles.
     */
    suspend fun ratedShare(f: File, n: Int = 1000): Double = withContext(Dispatchers.IO) {
        val top = database().rawQuery("SELECT kp FROM titles ORDER BY kp_votes DESC LIMIT $n", null).use { c ->
            buildList { while (c.moveToNext()) add(c.getInt(0)) }
        }
        if (top.isEmpty()) return@withContext 1.0
        val rated = SQLiteDatabase.openDatabase(f.path, null, SQLiteDatabase.OPEN_READONLY).use { d ->
            d.rawQuery("SELECT COUNT(*) FROM titles WHERE kp_votes > 0 AND kp IN (${top.joinToString(",")})", null)
                .use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
        }
        rated.toDouble() / top.size
    }

    suspend fun version(): String? = withContext(Dispatchers.IO) {
        database().rawQuery("SELECT value FROM meta WHERE key = 'version'", null).use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }

    suspend fun count(): Int = withContext(Dispatchers.IO) {
        database().rawQuery("SELECT COUNT(*) FROM titles", null).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
    }

    suspend fun query(where: String, args: List<Any?>, orderBy: String, limit: Int, offset: Int): List<Movie> =
        withContext(Dispatchers.IO) {
            val sql = "SELECT $COLUMNS FROM titles WHERE $where ORDER BY $orderBy LIMIT $limit OFFSET $offset"
            database().rawQuery(sql, args.map { it?.toString() }.toTypedArray()).use { c -> c.toMovies() }
        }

    suspend fun byId(id: Int): Movie? = query("kp = ?", listOf(id), "kp", 1, 0).firstOrNull()

    suspend fun byIds(ids: List<Int>): Map<Int, Movie> {
        if (ids.isEmpty()) return emptyMap()
        val marks = ids.joinToString(",") { "?" }
        return query("kp IN ($marks)", ids, "kp", ids.size, 0).associateBy { it.id }
    }

    fun close() {
        db?.close()
        db = null
    }

    private fun Cursor.toMovies(): List<Movie> {
        val out = ArrayList<Movie>(count)
        while (moveToNext()) {
            val genresMask = getLong(7)
            out += Movie(
                id = getInt(0),
                title = getString(1),
                originalTitle = getStringOrNull(2),
                year = getIntOrNull(3),
                endYear = getIntOrNull(4),
                type = ContentType.fromCode(getInt(5)),
                animated = getInt(6) == 1,
                genres = Genres.names(genresMask),
                countries = getStringOrNull(8)?.split(", ")?.filter { it.isNotBlank() } ?: emptyList(),
                ratingKp = getDoubleOrNull(9)?.takeIf { it > 0 },
                votesKp = getInt(10),
                ratingImdb = getDoubleOrNull(11)?.takeIf { it > 0 },
                votesImdb = getInt(12),
                duration = getIntOrNull(13),
                imdbId = getStringOrNull(14),
                seasons = getIntOrNull(15),
                episodes = getIntOrNull(16),
                wiki = getStringOrNull(17),
                malId = getIntOrNull(18),
            )
        }
        return out
    }

    private fun Cursor.getStringOrNull(i: Int) = if (isNull(i)) null else getString(i)
    private fun Cursor.getIntOrNull(i: Int) = if (isNull(i)) null else getInt(i)
    private fun Cursor.getDoubleOrNull(i: Int) = if (isNull(i)) null else getDouble(i)

    companion object {
        private const val COLUMNS =
            "kp, title, original, year, end_year, type, animated, genres, countries, kp_rating, kp_votes, " +
                "imdb_rating, imdb_votes, duration, imdb, seasons, episodes, wiki, mal"

        /** Catalog schema this app reads; matches SCHEMA_VERSION in build_catalog.py. */
        const val SCHEMA = 2
    }
}
