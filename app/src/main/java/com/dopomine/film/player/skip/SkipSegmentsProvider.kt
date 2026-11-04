package com.dopomine.film.player.skip

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
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import kotlin.math.abs

/**
 * Intro, recap and credits timestamps from open community databases (no keys):
 * AniSkip for anime (by MyAnimeList ID, looked up on Shikimori when the catalog has none),
 * TheIntroDB and IntroDB for shows and films (by IMDb ID). When this very episode is not marked,
 * or which episode plays is unknown, the intro of neighbouring episodes is used if they agree.
 */
class SkipSegmentsProvider(private val http: OkHttpClient, private val animeIds: AnimeIds = AnimeIds(http)) {
    private val cache = HashMap<SkipRequest, List<Segment>>()

    /** All plausible segments, best source first; empty when nobody has this video. */
    suspend fun fetch(request: SkipRequest, log: (String) -> Unit = {}): List<Segment> {
        synchronized(cache) { cache[request] }?.let { return SegmentRules.merge(request.durationMs, it) }
        val season = request.season
        val episode = request.episode
        val found = when {
            // a show playing some season: one season's intro says nothing about another's
            request.series && season == null -> emptyList()
            // a caught stream of a show: which episode is unknown, but many shows have the same intro in every one
            request.series && episode == null -> agreedIntro(request, season!!, listOf(1, 2, 3), log)
            else -> {
                val exact = exact(request, episode, log)
                if (request.series && episode != null && exact.none { it.kind == SegmentKind.INTRO && SegmentRules.valid(it, request.durationMs) != null }) {
                    // the next-door episodes are the most alike; the first ones are the most often marked
                    exact + agreedIntro(request, season!!, listOf(episode - 1, episode + 1, 1, 2, 3).filter { it >= 1 && it != episode }.distinct().take(4), log)
                } else exact
            }
        }
        synchronized(cache) { cache[request] = found }
        return SegmentRules.merge(request.durationMs, found)
    }

    /** What the databases have for one episode (or the film), best source first. */
    private suspend fun exact(r: SkipRequest, episode: Int?, log: (String) -> Unit): List<Segment> = coroutineScope {
        val aniSkip = async { attempt("AniSkip", log) { aniSkip(r, episode) } }
        val theIntroDb = async { attempt("TheIntroDB", log) { theIntroDb(r, episode) } }
        val introDb = async { attempt("IntroDB", log) { introDb(r, episode) } }
        aniSkip.await() + theIntroDb.await() + introDb.await()
    }

    /**
     * The intro of other episodes of the season, when at least two of them agree to within a few
     * seconds (shows with a cold open of varying length do not, and get nothing).
     */
    private suspend fun agreedIntro(r: SkipRequest, season: Int, episodes: List<Int>, log: (String) -> Unit): List<Segment> {
        val intros = coroutineScope {
            episodes.map { e ->
                async {
                    val other = r.copy(season = season, episode = e)
                    exact(other, e, {}).filter { it.kind == SegmentKind.INTRO }
                        .firstNotNullOfOrNull { SegmentRules.valid(it, r.durationMs) }
                }
            }.awaitAll()
        }.filterNotNull()
        val agreed = consensus(intros) ?: return emptyList()
        log("Заставка по соседним сериям: ${agreed.startMs / 1000}–${agreed.endMs?.div(1000)} с")
        return listOf(agreed)
    }

    private suspend fun attempt(name: String, log: (String) -> Unit, block: suspend () -> List<Segment>?): List<Segment> =
        try {
            block().orEmpty().also { if (it.isNotEmpty()) log("$name: " + it.joinToString { s -> s.kind.title.lowercase() }) }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            emptyList() // 404 = nothing known; any other failure just means one source less
        }

    /** The MyAnimeList entry and episode in it: exact season ID, else the first season's and its sequels. */
    private suspend fun aniSkipTarget(r: SkipRequest, episode: Int?): Pair<Int, Int>? {
        if (r.malId != null) return r.malId to (episode ?: 1)
        val base = r.baseMalId ?: r.anime?.let { animeIds.find(it) } ?: return null
        if (!r.series) return base to 1
        return animeIds.locate(base, r.seasonIndex, episode ?: return null, r.seasonSizes)
    }

    private suspend fun aniSkip(r: SkipRequest, episode: Int?): List<Segment>? {
        val (mal, number) = aniSkipTarget(r, episode) ?: return null
        // every submission, whatever the length of the cut it was made on: asking for one length
        // returns nothing unless some submitter had exactly that cut
        val url = "https://api.aniskip.com/v2/skip-times/$mal/$number?types[]=op&types[]=ed&types[]=mixed-op&types[]=mixed-ed&types[]=recap&episodeLength=0"
        return parseAniSkip(http.text(url), r.durationMs)
    }

    private suspend fun theIntroDb(r: SkipRequest, episode: Int?): List<Segment>? {
        val imdb = r.imdbId ?: return null
        val query = if (r.series) "&season=${r.season ?: return null}&episode=${episode ?: return null}" else ""
        return parseTheIntroDb(http.text("https://api.theintrodb.org/v2/media?imdb_id=$imdb$query"))
    }

    private suspend fun introDb(r: SkipRequest, episode: Int?): List<Segment>? {
        val imdb = r.imdbId ?: return null
        val query = if (r.series) "season=${r.season ?: return null}&episode=${episode ?: return null}" else "is_movie=true"
        return parseIntroDb(http.text("https://api.introdb.app/segments?imdb_id=$imdb&$query"))
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        private fun parse(text: String): JsonElement? = runCatching { json.parseToJsonElement(text) }.getOrNull()
        private fun JsonObject.num(key: String): Double? = (this[key] as? JsonPrimitive)?.let { it.doubleOrNull ?: it.contentOrNull?.toDoubleOrNull() }
        private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.let { it.longOrNull ?: it.doubleOrNull?.toLong() }

        /**
         * `{"found":true,"results":[{"interval":{"startTime":6.5,"endTime":96.5},"skipType":"op","episodeLength":1566.5}]}`
         *
         * One segment per kind: the submission made on the cut closest to [durationMs]. An ending
         * that falls past the end of a shorter cut is moved by the difference (the part after it,
         * the preview, is what such cuts drop).
         */
        fun parseAniSkip(text: String, durationMs: Long = 0): List<Segment> {
            val root = parse(text) as? JsonObject ?: return emptyList()
            data class Hit(val kind: SegmentKind, val start: Long, val end: Long, val lengthMs: Long?)
            val hits = (root["results"] as? JsonArray).orEmpty().mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                val interval = o["interval"] as? JsonObject ?: return@mapNotNull null
                val kind = when ((o["skipType"] as? JsonPrimitive)?.contentOrNull) {
                    "op", "mixed-op" -> SegmentKind.INTRO
                    "ed", "mixed-ed" -> SegmentKind.CREDITS
                    "recap" -> SegmentKind.RECAP
                    else -> return@mapNotNull null
                }
                val start = interval.num("startTime") ?: return@mapNotNull null
                val end = interval.num("endTime") ?: return@mapNotNull null
                Hit(kind, (start * 1000).toLong(), (end * 1000).toLong(), o.num("episodeLength")?.takeIf { it > 0 }?.let { (it * 1000).toLong() })
            }
            return hits.groupBy { it.kind }.map { (kind, list) ->
                val best = if (durationMs > 0) list.minBy { h -> h.lengthMs?.let { abs(it - durationMs) } ?: Long.MAX_VALUE } else list.first()
                var start = best.start
                var end = best.end
                val shift = if (durationMs > 0 && best.lengthMs != null) durationMs - best.lengthMs else 0L
                if (kind == SegmentKind.CREDITS && shift < 0 && start >= durationMs - 5_000) {
                    start += shift
                    end += shift
                }
                Segment(kind, start, end, "AniSkip")
            }
        }

        /**
         * The intro most episodes agree on: at least two whose start and end differ by no more
         * than a few seconds; their middle values.
         */
        fun consensus(intros: List<Segment>, toleranceMs: Long = 4_000): Segment? {
            val group = intros.map { a ->
                intros.filter { b -> abs(a.startMs - b.startMs) <= toleranceMs && abs((a.endMs ?: 0) - (b.endMs ?: 0)) <= toleranceMs }
            }.maxByOrNull { it.size }?.takeIf { it.size >= 2 } ?: return null
            val starts = group.map { it.startMs }.sorted()
            val ends = group.mapNotNull { it.endMs }.sorted()
            return Segment(SegmentKind.INTRO, starts[starts.size / 2], ends.getOrNull(ends.size / 2), "${group.first().source}, по соседним сериям")
        }

        /** `{"intro":[{"start_ms":7615,"end_ms":105471}],"recap":[…],"credits":[{"start_ms":3368800,"end_ms":null}]}` */
        fun parseTheIntroDb(text: String): List<Segment> {
            val root = parse(text) as? JsonObject ?: return emptyList()
            return listOf("intro" to SegmentKind.INTRO, "recap" to SegmentKind.RECAP, "credits" to SegmentKind.CREDITS).flatMap { (key, kind) ->
                (root[key] as? JsonArray).orEmpty().mapNotNull { el ->
                    val o = el as? JsonObject ?: return@mapNotNull null
                    val start = o.long("start_ms")
                    val end = o.long("end_ms")
                    // an intro without a start begins with the video; credits without an end run to it
                    if (kind == SegmentKind.CREDITS && start == null) return@mapNotNull null
                    if (kind != SegmentKind.CREDITS && end == null) return@mapNotNull null
                    Segment(kind, start ?: 0L, end, "TheIntroDB")
                }
            }
        }

        /** `{"intro":{"start_ms":2500,"end_ms":58000},"recap":null,"outro":{…},"no_intro":false}` */
        fun parseIntroDb(text: String): List<Segment> {
            val root = parse(text) as? JsonObject ?: return emptyList()
            return listOf("intro" to SegmentKind.INTRO, "recap" to SegmentKind.RECAP, "outro" to SegmentKind.CREDITS).mapNotNull { (key, kind) ->
                if ((root["no_$key"] as? JsonPrimitive)?.booleanOrNull == true) return@mapNotNull null
                val o = root[key] as? JsonObject ?: return@mapNotNull null
                val start = o.long("start_ms") ?: o.num("start_sec")?.let { (it * 1000).toLong() } ?: return@mapNotNull null
                val end = o.long("end_ms") ?: o.num("end_sec")?.let { (it * 1000).toLong() }
                Segment(kind, start, end, "IntroDB")
            }
        }
    }
}
