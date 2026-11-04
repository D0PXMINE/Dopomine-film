package com.dopomine.film.data.catalog

import android.util.LruCache
import com.dopomine.film.data.appCall
import com.dopomine.film.data.model.Description
import com.dopomine.film.data.model.Movie
import com.dopomine.film.data.model.PagedMovies
import com.dopomine.film.data.model.People

/** Everything about titles: offline catalog first, a few keyless web sources for the rest. */
class CatalogRepository(
    private val db: CatalogDb,
    private val ratings: RatingsClient,
    private val wiki: WikiClient,
    private val wikidata: WikidataClient,
    private val shikimori: ShikimoriClient,
) {
    private val descriptions = LruCache<Int, Description>(128)
    private val people = LruCache<Int, People>(128)
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

    fun cachedDescription(id: Int): Description? = descriptions.get(id)
    fun cachedPeople(id: Int): People? = people.get(id)

    /**
     * A description in Russian: Shikimori's synopsis for anime, else the lead of the Wikipedia
     * article. Only found descriptions are remembered, so a refused request is retried next time.
     */
    suspend fun description(movie: Movie): Description? {
        descriptions.get(movie.id)?.let { return it }
        val article = movie.wiki ?: if (isStub(movie)) null else onlineArticle(movie.id)
        val found = movie.malId?.let { mal -> shikimori.description(mal)?.let { Description(it, "Shikimori") } }
            ?: article?.let { wiki.summary(it)?.let { text -> Description(text, "Википедии") } }
        if (found != null) descriptions.put(movie.id, found)
        return found
    }

    /** Directors and cast from Wikidata. */
    suspend fun people(id: Int): People {
        people.get(id)?.let { return it }
        val (directors, cast) = runCatching { wikidata.people(id) }.getOrNull() ?: return People()
        return People(directors, cast).also { if (directors.isNotEmpty() || cast.isNotEmpty()) people.put(id, it) }
    }

    /** The title with fresh Kinopoisk and IMDb ratings, when they could be fetched. */
    suspend fun withFreshRatings(movie: Movie): Movie {
        val r = ratings.get(movie.id) ?: return movie
        return movie.copy(
            ratingKp = r.kp ?: movie.ratingKp, votesKp = maxOf(r.kpVotes, movie.votesKp),
            ratingImdb = r.imdb ?: movie.ratingImdb, votesImdb = maxOf(r.imdbVotes, movie.votesImdb),
        )
    }

    /** The catalog already says when a title has no article; only titles it lacks are looked up. */
    private suspend fun onlineArticle(id: Int): String? =
        if (db.byId(id) != null) null else runCatching { wikidata.byKinopoiskId(id)?.wiki }.getOrNull()

    private fun isStub(movie: Movie) = movie.title.startsWith("Кинопоиск #")

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
