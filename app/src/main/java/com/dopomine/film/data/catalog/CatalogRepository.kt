package com.dopomine.film.data.catalog

import android.util.LruCache
import com.dopomine.film.data.appCall
import com.dopomine.film.data.model.Movie
import com.dopomine.film.data.model.MovieDetails
import com.dopomine.film.data.model.PagedMovies
import com.dopomine.film.data.model.Ratings
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/** Everything about titles: offline catalog first, a few keyless web sources for the rest. */
class CatalogRepository(
    private val db: CatalogDb,
    private val ratings: RatingsClient,
    private val wiki: WikiClient,
    private val wikidata: WikidataClient,
) {
    private val details = LruCache<Int, MovieDetails>(64)
    private val pageSize = 30

    suspend fun page(query: CatalogQuery, page: Int = 1, size: Int = pageSize): PagedMovies = appCall {
        val (where, args) = query.where()
        val items = db.query(where, args, query.sort.sql, size + 1, (page - 1) * size)
        PagedMovies(items.take(size), page, items.size > size)
    }

    /** Offline title search: every word must match the Russian or original title. */
    suspend fun search(text: String, page: Int = 1): PagedMovies = appCall {
        val tokens = normalize(text).split(' ').filter { it.isNotBlank() }.take(6)
        if (tokens.isEmpty()) return@appCall PagedMovies(emptyList(), page, false)
        val where = tokens.joinToString(" AND ") { "search LIKE ?" }
        val args = tokens.map { "%$it%" }
        val prefix = normalize(text) + "%"
        val items = db.query(
            where, args + prefix,
            "CASE WHEN search LIKE ? THEN 0 ELSE 1 END, popularity DESC",
            pageSize + 1, (page - 1) * pageSize,
        )
        PagedMovies(items.take(pageSize), page, items.size > pageSize)
    }

    /** Titles Wikidata knows but the offline snapshot does not (new or obscure ones). */
    suspend fun searchOnline(text: String, exclude: Set<Int>): List<Movie> = appCall {
        val hits = wikidata.search(text).filter { it.first !in exclude }
        val known = db.byIds(hits.map { it.first })
        hits.map { (id, title) -> known[id] ?: Movie(id = id, title = title) }
    }

    /** A title by Kinopoisk ID: from the catalog, else from Wikidata, else a bare stub (still playable). */
    suspend fun movie(id: Int): Movie = appCall {
        db.byId(id) ?: wikidata.byKinopoiskId(id) ?: Movie(id = id, title = "Кинопоиск #$id")
    }

    fun cachedDetails(id: Int): MovieDetails? = details.get(id)

    suspend fun details(id: Int): MovieDetails = appCall {
        details.get(id)?.let { return@appCall it }
        coroutineScope {
            val base = movie(id)
            val fresh = async { ratings.get(id) }
            val people = async { runCatching { wikidata.people(id) }.getOrNull() }
            val text = async {
                val article = base.wiki ?: if (base.title.startsWith("Кинопоиск #")) null else wikidata.byKinopoiskId(id)?.wiki
                article?.let { wiki.summary(it) }
            }
            val r = fresh.await()
            val movie = if (r != null) {
                base.copy(
                    ratingKp = r.kp ?: base.ratingKp, votesKp = maxOf(r.kpVotes, base.votesKp),
                    ratingImdb = r.imdb ?: base.ratingImdb, votesImdb = maxOf(r.imdbVotes, base.votesImdb),
                )
            } else base
            val (directors, cast) = people.await() ?: (emptyList<String>() to emptyList())
            MovieDetails(movie, text.await(), directors, cast).also { details.put(id, it) }
        }
    }

    suspend fun ratings(id: Int): Ratings? = ratings.get(id)

    /** Same kind, overlapping genres, ordered by popularity. */
    suspend fun similar(movie: Movie, limit: Int = 20): List<Movie> = appCall {
        val mask = movie.genres.fold(0L) { acc, g -> runCatching { acc or Genres.bit(g) }.getOrDefault(acc) }
        if (mask == 0L) return@appCall emptyList()
        val kind = if (movie.type.isSeries) "type IN (1, 2)" else "type = 0"
        db.query(
            "$kind AND kp != ? AND (genres & ?) != 0 AND animated = ?",
            listOf(movie.id, mask, if (movie.animated) 1 else 0),
            // titles sharing more genres and close in time first, then popularity
            "ABS(COALESCE(year, 2000) - ${movie.year ?: 2000}) / 15 ASC, popularity DESC",
            limit, 0,
        )
    }

    suspend fun catalogInfo(): Pair<String?, Int> = appCall { db.version() to db.count() }

    companion object {
        fun normalize(text: String): String =
            text.lowercase().replace('ё', 'е').replace(Regex("[^0-9a-zа-я]+"), " ").trim()
    }
}
