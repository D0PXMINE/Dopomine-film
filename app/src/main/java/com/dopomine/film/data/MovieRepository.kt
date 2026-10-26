package com.dopomine.film.data

import android.util.LruCache
import com.dopomine.film.data.api.KinopoiskApi
import com.dopomine.film.data.model.ApiKeyInfo
import com.dopomine.film.data.model.Episode
import com.dopomine.film.data.model.ListSource
import com.dopomine.film.data.model.Movie
import com.dopomine.film.data.model.MovieDetails
import com.dopomine.film.data.model.PagedMovies
import com.dopomine.film.data.model.Person
import com.dopomine.film.data.model.Season
import com.dopomine.film.data.model.toDetails
import com.dopomine.film.data.model.toMovie
import com.dopomine.film.data.model.toPerson
import com.dopomine.film.data.settings.SettingsRepository

class MovieRepository(
    private val api: KinopoiskApi,
    private val settings: SettingsRepository,
) {
    private val detailsCache = LruCache<Int, MovieDetails>(64)
    private val listCache = LruCache<String, PagedMovies>(128)

    /** The filter endpoint never returns more than 20 pages. */
    private val maxFilterPages = 20

    private suspend fun ensureKey() {
        if (settings.apiKey().isBlank()) throw AppException(ErrorKind.NO_KEY)
    }

    suspend fun list(source: ListSource, page: Int = 1): PagedMovies {
        val cacheKey = "$source#$page"
        listCache.get(cacheKey)?.let { return it }
        ensureKey()
        val result = apiCall {
            when (source) {
                is ListSource.Collection -> {
                    val dto = api.collection(source.type, page)
                    PagedMovies(dto.items.mapNotNull { it.toMovie() }.distinctBy { it.id }, page, dto.totalPages)
                }
                is ListSource.Filter -> {
                    val dto = api.films(
                        type = source.type,
                        order = source.order,
                        genre = source.genre,
                        country = source.country,
                        yearFrom = source.yearFrom,
                        yearTo = source.yearTo,
                        ratingFrom = source.ratingFrom,
                        page = page,
                    )
                    PagedMovies(
                        dto.items.mapNotNull { it.toMovie() }.distinctBy { it.id },
                        page,
                        minOf(dto.totalPages, maxFilterPages),
                    )
                }
            }
        }
        listCache.put(cacheKey, result)
        return result
    }

    /**
     * Title search. The filter endpoint returns both ratings and is sorted by popularity;
     * the keyword endpoint is fuzzier and is used when the first one finds nothing.
     */
    suspend fun search(query: String, page: Int = 1): PagedMovies {
        ensureKey()
        return apiCall {
            val byFilter = api.films(type = "ALL", order = "NUM_VOTE", keyword = query, page = page)
            val items = byFilter.items.mapNotNull { it.toMovie() }.distinctBy { it.id }
            if (items.isNotEmpty() || page > 1) {
                PagedMovies(items, page, minOf(byFilter.totalPages, maxFilterPages))
            } else {
                val byKeyword = api.searchByKeyword(query, page)
                PagedMovies(byKeyword.films.mapNotNull { it.toMovie() }.distinctBy { it.id }, page, byKeyword.pagesCount)
            }
        }
    }

    fun cachedDetails(id: Int): MovieDetails? = detailsCache.get(id)

    suspend fun details(id: Int): MovieDetails {
        detailsCache.get(id)?.let { return it }
        ensureKey()
        val details = apiCall { api.film(id).toDetails() }
        detailsCache.put(id, details)
        return details
    }

    suspend fun similars(id: Int): List<Movie> {
        ensureKey()
        return apiCall { api.similars(id).items.mapNotNull { it.toMovie() }.distinctBy { it.id } }
    }

    suspend fun staff(id: Int): List<Person> {
        ensureKey()
        return apiCall { api.staff(id).mapNotNull { it.toPerson() } }
    }

    suspend fun seasons(id: Int): List<Season> {
        ensureKey()
        return apiCall {
            api.seasons(id).items
                .filter { it.episodes.isNotEmpty() }
                .distinctBy { it.number }
                .map { season ->
                    Season(
                        number = season.number,
                        episodes = season.episodes.map {
                            Episode(
                                season = it.seasonNumber,
                                number = it.episodeNumber,
                                title = it.nameRu?.takeIf(String::isNotBlank) ?: it.nameEn?.takeIf(String::isNotBlank),
                                releaseDate = it.releaseDate,
                            )
                        },
                    )
                }
        }
    }

    suspend fun keyInfo(key: String): ApiKeyInfo = apiCall {
        val dto = api.keyInfo(key, key)
        ApiKeyInfo(
            accountType = dto.accountType ?: "FREE",
            dailyUsed = dto.dailyQuota?.used ?: 0,
            dailyLimit = dto.dailyQuota?.value ?: 0,
        )
    }

    fun clearMemoryCache() {
        detailsCache.evictAll()
        listCache.evictAll()
    }
}
