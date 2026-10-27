package com.dopomine.film.player.resolve

import com.dopomine.film.data.model.Movie
import com.dopomine.film.data.net.postForm
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.OkHttpClient

/**
 * AnimeVost — an anime dub team with a public API: search by name, then a playlist of MP4 files
 * (720p and 480p) per release. Each season is its own release ("… (второй сезон) / … 2nd Season"),
 * so a show is assembled from all of its TV releases in release order.
 */
class AnimevostResolver(private val http: OkHttpClient) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private data class Release(val id: Int, val ru: String?, val en: String?, val year: Int?, val type: String) {
        val isMovie get() = "фильм" in type.lowercase()
        val isTv get() = type.equals("ТВ", true) || type.equals("TV", true) || type.equals("ONA", true) || type.equals("WEB", true)
    }

    suspend fun resolve(movie: Movie, log: (String) -> Unit): Playable {
        var lastError: Exception? = null
        for (api in APIS) {
            try {
                return resolveOn(api, movie, log)
            } catch (e: ResolveException) {
                throw e
            } catch (e: Exception) {
                lastError = e
                log("  $api: ${e.message}")
            }
        }
        throw ResolveException("AnimeVost недоступен: ${lastError?.message}")
    }

    private suspend fun resolveOn(api: String, movie: Movie, log: (String) -> Unit): Playable {
        val found = LinkedHashMap<Int, Release>()
        for (query in AnimeTitles.queries(movie)) {
            log("AnimeVost: ищем «$query»")
            search(api, query).forEach { found.putIfAbsent(it.id, it) }
            if (found.values.any { AnimeTitles.match(movie, it.ru, it.en) == AnimeTitles.EXACT }) break
        }
        // exact names when there are any, otherwise releases that contain the title
        val levels = found.values.associateWith { AnimeTitles.match(movie, it.ru, it.en) }
        val best = levels.values.maxOrNull() ?: AnimeTitles.NONE
        val mine = if (best == AnimeTitles.NONE) emptyList() else levels.filterValues { it == best }.keys.toList()

        if (!movie.type.isSeries) {
            val release = mine.firstOrNull { it.isMovie && AnimeTitles.yearFits(movie, it.year) }
                ?: mine.singleOrNull { AnimeTitles.yearFits(movie, it.year) }
                ?: throw ResolveException("AnimeVost: этого аниме у них нет")
            log("AnimeVost: «${release.ru ?: release.en}» (${release.year ?: "?"})")
            val episodes = playlist(api, release.id, 1)
            val first = episodes.firstOrNull() ?: throw ResolveException("AnimeVost: нет серий")
            return if (episodes.size == 1) Playable.Single(NAME, first.stream)
            else Playable.Series(NAME, listOf(Season(1, episodes)))
        }

        // a show: its TV releases are the seasons, oldest first; the first one must fit the catalog year
        val tv = mine.filter { it.isTv }.sortedWith(compareBy({ it.year ?: 9999 }, { it.id }))
        if (tv.isEmpty() || !AnimeTitles.yearFits(movie, tv.first().year)) throw ResolveException("AnimeVost: этого аниме у них нет")
        log("AnimeVost: сезоны " + tv.joinToString { "«${it.ru ?: it.en}»" })
        val seasons = tv.take(MAX_SEASONS).mapIndexedNotNull { index, r ->
            runCatching { Season(index + 1, playlist(api, r.id, index + 1)) }.getOrNull()?.takeIf { it.episodes.isNotEmpty() }
        }
        if (seasons.isEmpty()) throw ResolveException("AnimeVost: нет серий")
        return Playable.Series(NAME, seasons.mapIndexed { i, s -> Season(i + 1, s.episodes.map { it.copy(season = i + 1) }) })
    }

    private suspend fun search(api: String, query: String): List<Release> {
        val root = runCatching { json.parseToJsonElement(http.postForm("$api/search", mapOf("name" to query))) as JsonObject }.getOrNull()
            ?: return emptyList()
        return (root["data"] as? JsonArray).orEmpty().mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val id = o.str("id")?.toIntOrNull() ?: return@mapNotNull null
            // "Провожающая в последний путь Фрирен / Sousou no Frieren [1-28 из 28]"
            val title = o.str("title")?.substringBefore(" [") ?: return@mapNotNull null
            Release(
                id = id,
                ru = title.substringBefore(" / ").trim().takeIf { it.isNotEmpty() },
                en = title.substringAfter(" / ", "").trim().takeIf { it.isNotEmpty() },
                year = o.str("year")?.take(4)?.toIntOrNull(),
                type = o.str("type").orEmpty(),
            )
        }
    }

    private suspend fun playlist(api: String, id: Int, season: Int): List<Episode> {
        val items = json.parseToJsonElement(http.postForm("$api/playlist", mapOf("id" to id.toString()))) as? JsonArray
            ?: throw ResolveException("AnimeVost: пустой плейлист")
        data class Entry(val name: String, val number: Int?, val stream: Stream)

        val entries = items.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val name = o.str("name").orEmpty()
            val variants = listOfNotNull(o.str("hd")?.let { Variant("720p", https(it)) }, o.str("std")?.let { Variant("480p", https(it)) })
            val best = variants.firstOrNull() ?: return@mapNotNull null
            Entry(name, EPISODE.find(name)?.groupValues?.get(1)?.toIntOrNull(), Stream(best.url, StreamFormat.PROGRESSIVE, variants = variants))
        }
        // regular episodes are "12 серия"; OVA and specials would clash with their numbers.
        // A film has a single "Фильм" entry instead, so then everything is taken in order.
        val regular = entries.filter { it.number != null }
        return if (regular.isNotEmpty()) {
            regular.map { Episode(season, it.number!!, null, it.stream) }.sortedBy { it.number }.distinctBy { it.number }
        } else {
            entries.mapIndexed { i, e -> Episode(season, i + 1, e.name.takeIf { entries.size > 1 }, e.stream) }
        }
    }

    /** Their files are served over HTTPS too; Android blocks plain HTTP. */
    private fun https(url: String) = if (url.startsWith("http://")) "https://" + url.removePrefix("http://") else url

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    companion object {
        const val NAME = "AnimeVost"
        private val APIS = listOf("https://api.animevost.org/v1", "https://api.animetop.info/v1")
        private val EPISODE = Regex("""^\s*(\d{1,4})\s*серия""", RegexOption.IGNORE_CASE)
        private const val MAX_SEASONS = 12
    }
}
