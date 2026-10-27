package com.dopomine.film.player.resolve

import com.dopomine.film.data.model.Movie
import com.dopomine.film.data.net.text
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import okhttp3.OkHttpClient
import java.net.URLEncoder

/**
 * AniLiberty (AniLibria) — an anime dub team with an open API: every episode has plain HLS files
 * in 480p, 720p and 1080p. Releases carry MyAnimeList IDs, so a catalog title with one is matched
 * exactly; a release's franchise lists the other seasons in order.
 */
class AniLibertyResolver(private val http: OkHttpClient) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private data class Release(
        val id: Int,
        val type: String,
        val year: Int?,
        val names: List<String>,
        val mal: Int?,
    ) {
        val isMovie get() = type == "MOVIE"
        val isSeason get() = type in SEASON_TYPES
    }

    suspend fun resolve(movie: Movie, log: (String) -> Unit): Playable {
        var lastError: Exception? = null
        for (host in HOSTS) {
            try {
                return resolveOn(host, movie, log)
            } catch (e: ResolveException) {
                throw e
            } catch (e: Exception) {
                lastError = e
                log("  $host: ${e.message}")
            }
        }
        throw ResolveException("AniLiberty недоступен: ${lastError?.message}")
    }

    private suspend fun resolveOn(host: String, movie: Movie, log: (String) -> Unit): Playable {
        val found = LinkedHashMap<Int, Release>()
        var match: Release? = null
        for (query in AnimeTitles.queries(movie)) {
            log("AniLiberty: ищем «$query»")
            val url = "$host/api/v1/app/search/releases?query=" + URLEncoder.encode(query, "UTF-8")
            array(http.text(url, HEADERS)).mapNotNull(::release).forEach { found.putIfAbsent(it.id, it) }
            match = pick(movie, found.values)
            if (match != null && movie.malId != null && match.mal == movie.malId) break
        }
        val release = match ?: throw ResolveException("AniLiberty: этого аниме у них нет")
        log("AniLiberty: «${release.names.first()}» (${release.year ?: "?"}, ${release.type}${release.mal?.let { ", MAL $it" } ?: ""})")

        if (!movie.type.isSeries || release.isMovie) {
            val episodes = episodes(host, release.id)
            val first = episodes.firstOrNull() ?: throw ResolveException("AniLiberty: нет серий")
            return if (episodes.size == 1) Playable.Single(NAME, first.stream)
            else Playable.Series(NAME, listOf(Season(1, episodes)))
        }

        // a show: every TV release of the franchise is a season, in the franchise's order
        val chain = franchise(host, release.id).filter { it.isSeason }.ifEmpty { listOf(release) }
            .let { list -> if (list.any { it.id == release.id }) list else listOf(release) }
            .take(MAX_SEASONS)
        val seasons = coroutineScope {
            chain.mapIndexed { index, r -> async { runCatching { Season(index + 1, episodes(host, r.id, index + 1)) }.getOrNull() } }
                .awaitAll()
        }.filterNotNull().filter { it.episodes.isNotEmpty() }
        if (seasons.isEmpty()) throw ResolveException("AniLiberty: нет серий")
        log("AniLiberty: сезонов ${seasons.size}")
        return Playable.Series(NAME, seasons, startSeason = chain.indexOfFirst { it.id == release.id } + 1)
    }

    /** Exact MyAnimeList match first, then the same name and year, of a fitting kind. */
    private fun pick(movie: Movie, releases: Collection<Release>): Release? {
        movie.malId?.let { mal -> releases.firstOrNull { it.mal == mal }?.let { return it } }
        val fitting = releases.filter { (if (movie.type.isSeries) it.isSeason else it.isMovie) && AnimeTitles.yearFits(movie, it.year) }
        return fitting.map { it to AnimeTitles.match(movie, *it.names.toTypedArray()) }
            .filter { it.second > AnimeTitles.NONE }
            .maxByOrNull { it.second }?.first
    }

    private suspend fun franchise(host: String, releaseId: Int): List<Release> = runCatching {
        val first = array(http.text("$host/api/v1/anime/franchises/release/$releaseId", HEADERS)).firstOrNull() as? JsonObject
        (first?.get("franchise_releases") as? JsonArray).orEmpty()
            .mapNotNull { it as? JsonObject }
            .sortedBy { it.int("sort_order") ?: 0 }
            .mapNotNull { release(it["release"] ?: return@mapNotNull null) }
    }.getOrDefault(emptyList())

    private suspend fun episodes(host: String, releaseId: Int, season: Int = 1): List<Episode> {
        val root = json.parseToJsonElement(http.text("$host/api/v1/anime/releases/$releaseId", HEADERS)) as? JsonObject
            ?: throw ResolveException("AniLiberty: пустой ответ")
        val items = (root["episodes"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        if (items.isEmpty() && root.bool("is_blocked_by_copyrights") == true) {
            throw ResolveException("AniLiberty: убрано по требованию правообладателя")
        }
        val sorted = items.sortedBy { (it["ordinal"] as? JsonPrimitive)?.doubleOrNull ?: 0.0 }
        val wholeNumbers = sorted.map { (it["ordinal"] as? JsonPrimitive)?.doubleOrNull }
            .let { o -> o.all { it != null && it % 1.0 == 0.0 } && o.distinct().size == o.size }
        return sorted.mapIndexedNotNull { index, e ->
            val variants = listOf("1080p" to "hls_1080", "720p" to "hls_720", "480p" to "hls_480")
                .mapNotNull { (label, key) -> e.str(key)?.let { Variant(label, clean(it)) } }
            val best = variants.firstOrNull() ?: return@mapIndexedNotNull null
            val number = if (wholeNumbers) (e["ordinal"] as JsonPrimitive).doubleOrNull!!.toInt() else index + 1
            Episode(season, number, e.str("name"), Stream(best.url, StreamFormat.HLS, variants = variants))
        }
    }

    private fun release(el: JsonElement): Release? {
        val o = el as? JsonObject ?: return null
        val id = o.int("id") ?: return null
        val name = o["name"] as? JsonObject
        val names = listOfNotNull(name?.str("main"), name?.str("english"), name?.str("alternative"))
        if (names.isEmpty()) return null
        return Release(
            id = id,
            type = (o["type"] as? JsonObject)?.str("value").orEmpty(),
            year = o.int("year"),
            names = names,
            mal = (o["mal"] as? JsonObject)?.int("id"),
        )
    }

    private fun array(text: String): JsonArray = runCatching { json.parseToJsonElement(text) as JsonArray }.getOrDefault(JsonArray(emptyList()))

    /** The playlist itself has no ads; the query only switches ads on in their web player. */
    private fun clean(url: String) = url.substringBefore('?')

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.let { it.intOrNull ?: it.contentOrNull?.toIntOrNull() }
    private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull

    companion object {
        const val NAME = "AniLiberty"
        private val HOSTS = listOf("https://api.anilibria.app", "https://anilibria.top", "https://aniliberty.top")
        private val HEADERS = mapOf("Accept" to "application/json")
        private val SEASON_TYPES = setOf("TV", "ONA", "WEB")
        private const val MAX_SEASONS = 12
    }
}
