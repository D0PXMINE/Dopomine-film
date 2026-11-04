package com.dopomine.film.player.skip

import androidx.compose.runtime.Immutable
import com.dopomine.film.data.net.HttpStatusException
import com.dopomine.film.data.net.text
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import okhttp3.OkHttpClient
import java.io.IOException
import java.net.URLEncoder
import kotlin.math.abs

/** What an anime is looked up by when the catalog has no MyAnimeList ID for it. */
@Immutable
data class AnimeTitle(val title: String, val original: String?, val year: Int?, val series: Boolean)

/**
 * MyAnimeList IDs — the key AniSkip knows anime by — for titles the catalog has none for. Those
 * are often the most popular ones: Wikidata ties their Kinopoisk ID to the franchise, not to a
 * season. Shikimori's IDs are MyAnimeList's: its search finds the first season (or the film), its
 * "Sequel" links the following ones.
 */
class AnimeIds(private val http: OkHttpClient) {
    @Immutable
    data class Entry(val id: Int, val kind: String, val airedOn: String?, val episodes: Int, val names: List<String> = emptyList()) {
        val year: Int? get() = airedOn?.take(4)?.toIntOrNull()
    }

    /** A show's first season and the TV sequels known so far; [complete] once there are no more. */
    private class Chain(val entries: MutableList<Entry>, var complete: Boolean = false)

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val found = HashMap<AnimeTitle, Int?>()
    private val chains = HashMap<Int, Chain>()
    private val findLock = Mutex()
    private val chainLock = Mutex()

    /** The MyAnimeList ID of a show's first season, or of a film. */
    suspend fun find(title: AnimeTitle): Int? = findLock.withLock {
        if (title.year == null) return null // without a year a namesake is too easy to take
        if (title in found) return found[title]
        var failed = false
        var result: Int? = null
        for (query in listOfNotNull(title.title, title.original).map { it.trim() }.filter { it.length >= 2 }.distinct()) {
            val results = try {
                search(query)
            } catch (e: IOException) {
                failed = true
                continue
            }
            result = pick(title, query, results)
            if (result != null) break
        }
        // a failed request may succeed next time; "not found" stays
        if (result != null || !failed) found[title] = result
        result
    }

    /**
     * The entry and episode in it for the [seasonIndex]-th season of a show (0-based, in the
     * source's order) whose first season is [base]. [seasonSizes] are the source's episode counts
     * per season: a season is matched to MyAnimeList entries only when the counts add up, as a
     * source may count two "parts" as one season. Without counts only the first season is mapped.
     */
    suspend fun locate(base: Int, seasonIndex: Int, episode: Int, seasonSizes: List<Int>): Pair<Int, Int>? {
        if (seasonSizes.isEmpty() && seasonIndex != 0) return null
        val needed = if (seasonSizes.isEmpty()) episode else seasonSizes.take(seasonIndex + 1).sum()
        val chain = chain(base, needed)
        if (chain.isEmpty()) return null
        return map(chain, seasonIndex, episode, seasonSizes)
    }

    /** The first season and as many TV sequels as it takes to hold [episodes] episodes. */
    private suspend fun chain(base: Int, episodes: Int): List<Entry> = chainLock.withLock {
        val chain = chains[base] ?: Chain(mutableListOf(entry(base) ?: return emptyList())).also { chains[base] = it }
        while (!chain.complete && chain.entries.size < MAX_SEASONS && covered(chain.entries) < episodes) {
            val next = try {
                nextTv(chain.entries.last().id, chain.entries.map { it.id }.toSet())
            } catch (e: IOException) {
                break // asked again next time
            }
            if (next == null) chain.complete = true else chain.entries += next
        }
        chain.entries.toList()
    }

    private suspend fun entry(id: Int): Entry? =
        runCatching { get("/api/animes/$id") as? JsonObject }.getOrNull()?.let(::toEntry)

    /** The next TV season: a direct TV sequel, or one reached through a film or special between them. */
    private suspend fun nextTv(id: Int, seen: Set<Int>, depth: Int = 0): Entry? {
        val sequels = related(id).filter { it.id !in seen }
        sequels.filter { it.kind in TV_KINDS }.minByOrNull { it.airedOn ?: "9999" }?.let { return it }
        if (depth >= 2) return null
        for (other in sequels.sortedBy { it.airedOn ?: "9999" }) nextTv(other.id, seen + other.id, depth + 1)?.let { return it }
        return null
    }

    private suspend fun related(id: Int): List<Entry> {
        val array = get("/api/animes/$id/related") as? JsonArray ?: return emptyList()
        return array.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val sequel = (o["relation"] as? JsonPrimitive)?.contentOrNull == "Sequel" ||
                (o["relation_russian"] as? JsonPrimitive)?.contentOrNull == "Продолжение"
            if (!sequel) return@mapNotNull null
            (o["anime"] as? JsonObject)?.let(::toEntry)
        }
    }

    private suspend fun search(query: String): List<Entry> {
        val array = get("/api/animes?limit=20&search=" + URLEncoder.encode(query, "UTF-8")) as? JsonArray ?: return emptyList()
        return array.mapNotNull { (it as? JsonObject)?.let(::toEntry) }
    }

    /** One API call, on the second address if the first one fails; null when the site has no such thing. */
    private suspend fun get(path: String): JsonElement? {
        var error: IOException? = null
        for (host in HOSTS) {
            val text = try {
                http.text("https://$host$path", mapOf("Accept" to "application/json"))
            } catch (e: HttpStatusException) {
                if (e.code == 404) return null
                error = e
                continue
            } catch (e: IOException) {
                error = e
                continue
            }
            return runCatching { json.parseToJsonElement(text) }.getOrNull()
        }
        throw error ?: IOException("Shikimori is not reachable")
    }

    private fun toEntry(o: JsonObject): Entry? {
        val id = (o["id"] as? JsonPrimitive)?.intOrNull ?: return null
        fun str(key: String) = (o[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        return Entry(
            id = id,
            kind = str("kind").orEmpty(),
            airedOn = str("aired_on"),
            episodes = (o["episodes"] as? JsonPrimitive)?.intOrNull ?: 0,
            names = listOfNotNull(str("name"), str("russian")),
        )
    }

    companion object {
        private val HOSTS = listOf("shikimori.io", "shikimori.one")
        private const val MAX_SEASONS = 12
        val TV_KINDS = setOf("tv", "ona")

        private fun covered(list: List<Entry>): Int =
            if (list.any { it.episodes <= 0 }) Int.MAX_VALUE else list.sumOf { it.episodes }

        private fun norm(s: String) = s.lowercase().replace('ё', 'е').filter { it.isLetterOrDigit() }

        /**
         * Search results of the right kind and year. The first season is the one named exactly as
         * asked, else the earliest; a TV series wins over a web release of the same year (often
         * a promo or a spin-off).
         */
        fun pick(title: AnimeTitle, query: String, results: List<Entry>): Int? {
            val year = title.year ?: return null
            val kinds = if (title.series) TV_KINDS else setOf("movie")
            val wanted = norm(query)
            return results
                .filter { it.kind in kinds && it.year != null && abs(it.year!! - year) <= 1 }
                .minWithOrNull(
                    compareBy<Entry>(
                        { e -> if (e.names.any { norm(it) == wanted }) 0 else 1 },
                        { e -> if (e.kind == "tv" || !title.series) 0 else 1 },
                        { e -> e.airedOn ?: "9999" },
                    ),
                )?.id
        }

        /** See [locate]; an entry with 0 episodes is still airing and takes what is left. */
        fun map(chain: List<Entry>, seasonIndex: Int, episode: Int, seasonSizes: List<Int>): Pair<Int, Int>? {
            if (episode < 1) return null
            if (seasonSizes.isEmpty()) {
                // no idea how the source counts: only its first season, which may span "parts"
                return if (seasonIndex == 0) walk(chain, 0, chain.size, episode) else null
            }
            var at = 0
            seasonSizes.forEachIndexed { index, size ->
                val start = at
                var total = 0
                while (at < chain.size && total < size) {
                    total += chain[at].episodes.takeIf { it > 0 } ?: (size - total)
                    at++
                }
                // the source counts this season differently from MyAnimeList: no guessing.
                // Only its last season may be shorter: its dub is still coming out.
                val partial = index == seasonSizes.lastIndex && total > size
                if (total != size && !partial) return null
                if (index == seasonIndex) return walk(chain, start, at, episode)
            }
            return null
        }

        private fun walk(chain: List<Entry>, from: Int, until: Int, episode: Int): Pair<Int, Int>? {
            var left = episode
            for (i in from until until) {
                val n = chain[i].episodes.takeIf { it > 0 } ?: Int.MAX_VALUE
                if (left <= n) return chain[i].id to left
                left -= n
            }
            return null
        }
    }
}
