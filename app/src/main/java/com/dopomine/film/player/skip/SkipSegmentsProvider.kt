package com.dopomine.film.player.skip

import com.dopomine.film.data.net.text
import kotlinx.coroutines.async
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

/**
 * Intro, recap and credits timestamps from open community databases (no keys):
 * AniSkip for anime (by MyAnimeList ID), TheIntroDB and IntroDB for shows and films (by IMDb ID).
 */
class SkipSegmentsProvider(private val http: OkHttpClient) {
    private val cache = HashMap<String, List<Segment>>()

    /** All plausible segments, best source first; empty when nobody has this video. */
    suspend fun fetch(request: SkipRequest, log: (String) -> Unit = {}): List<Segment> {
        val key = listOf(request.imdbId, request.malId, request.season, request.episode).joinToString(":")
        synchronized(cache) { cache[key] }?.let { return SegmentRules.merge(request.durationMs, it) }
        val found = coroutineScope {
            val aniSkip = async { attempt("AniSkip", log) { aniSkip(request) } }
            val theIntroDb = async { attempt("TheIntroDB", log) { theIntroDb(request) } }
            val introDb = async { attempt("IntroDB", log) { introDb(request) } }
            aniSkip.await() + theIntroDb.await() + introDb.await()
        }
        synchronized(cache) { cache[key] = found }
        return SegmentRules.merge(request.durationMs, found)
    }

    private suspend fun attempt(name: String, log: (String) -> Unit, block: suspend () -> List<Segment>?): List<Segment> =
        try {
            block().orEmpty().also { if (it.isNotEmpty()) log("$name: " + it.joinToString { s -> s.kind.title.lowercase() }) }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            emptyList() // 404 = nothing known; any other failure just means one source less
        }

    private suspend fun aniSkip(r: SkipRequest): List<Segment>? {
        val mal = r.malId ?: return null
        val episode = r.episode ?: 1
        val length = if (r.durationMs > 0) "&episodeLength=${r.durationMs / 1000}" else ""
        val url = "https://api.aniskip.com/v2/skip-times/$mal/$episode?types[]=op&types[]=ed&types[]=mixed-op&types[]=mixed-ed&types[]=recap$length"
        return parseAniSkip(http.text(url))
    }

    private suspend fun theIntroDb(r: SkipRequest): List<Segment>? {
        val imdb = r.imdbId ?: return null
        val episode = if (r.isEpisode) "&season=${r.season}&episode=${r.episode}" else ""
        return parseTheIntroDb(http.text("https://api.theintrodb.org/v2/media?imdb_id=$imdb$episode"))
    }

    private suspend fun introDb(r: SkipRequest): List<Segment>? {
        val imdb = r.imdbId ?: return null
        val query = if (r.isEpisode) "season=${r.season}&episode=${r.episode}" else "is_movie=true"
        return parseIntroDb(http.text("https://api.introdb.app/segments?imdb_id=$imdb&$query"))
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        private fun parse(text: String): JsonElement? = runCatching { json.parseToJsonElement(text) }.getOrNull()
        private fun JsonObject.num(key: String): Double? = (this[key] as? JsonPrimitive)?.let { it.doubleOrNull ?: it.contentOrNull?.toDoubleOrNull() }
        private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.let { it.longOrNull ?: it.doubleOrNull?.toLong() }

        /** `{"found":true,"results":[{"interval":{"startTime":6.5,"endTime":96.5},"skipType":"op"}]}` */
        fun parseAniSkip(text: String): List<Segment> {
            val root = parse(text) as? JsonObject ?: return emptyList()
            return (root["results"] as? JsonArray).orEmpty().mapNotNull { el ->
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
                Segment(kind, (start * 1000).toLong(), (end * 1000).toLong(), "AniSkip")
            }
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
