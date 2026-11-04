package com.dopomine.film

import com.dopomine.film.player.skip.AnimeIds
import com.dopomine.film.player.skip.AnimeTitle
import com.dopomine.film.player.skip.Segment
import com.dopomine.film.player.skip.SegmentKind
import com.dopomine.film.player.skip.SegmentRules
import com.dopomine.film.player.skip.SkipRequest
import com.dopomine.film.player.skip.SkipSegmentsProvider
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLDecoder

class SkipSegmentsTest {
    private val hour = 3_600_000L

    @Test
    fun parsesAniSkip() {
        val text = """{"found":true,"results":[{"interval":{"startTime":6.576,"endTime":96.576},"skipType":"op","episodeLength":1566.5},
            {"interval":{"startTime":1460,"endTime":1550},"skipType":"ed"},{"interval":{"startTime":0,"endTime":40},"skipType":"recap"}],"statusCode":200}"""
        val segments = SkipSegmentsProvider.parseAniSkip(text)
        assertEquals(listOf(SegmentKind.INTRO, SegmentKind.CREDITS, SegmentKind.RECAP), segments.map { it.kind })
        assertEquals(6_576L, segments[0].startMs)
        assertEquals(96_576L, segments[0].endMs)
    }

    @Test
    fun parsesTheIntroDbWithOpenEnds() {
        val text = """{"tmdb_id":603,"type":"movie","intro":[{"start_ms":null,"end_ms":40000}],"credits":[{"start_ms":7726802,"end_ms":null}]}"""
        val segments = SkipSegmentsProvider.parseTheIntroDb(text)
        assertEquals(Segment(SegmentKind.INTRO, 0, 40_000, "TheIntroDB"), segments[0])
        assertEquals(Segment(SegmentKind.CREDITS, 7_726_802, null, "TheIntroDB"), segments[1])
    }

    @Test
    fun parsesIntroDbAndHonoursNoIntro() {
        val text = """{"imdb_id":"tt0903747","intro":{"start_sec":2.5,"end_sec":58,"start_ms":2500,"end_ms":58000},"recap":null,
            "outro":{"start_ms":3431000,"end_ms":3500000},"no_intro":true,"no_recap":true,"no_outro":false}"""
        val segments = SkipSegmentsProvider.parseIntroDb(text)
        assertEquals(listOf(SegmentKind.CREDITS), segments.map { it.kind })
        assertTrue(SkipSegmentsProvider.parseIntroDb("""{"error":"not found"}""").isEmpty())
        assertTrue(SkipSegmentsProvider.parseAniSkip("garbage").isEmpty())
    }

    @Test
    fun mergeTakesTheFirstPlausibleSourcePerKind() {
        val source = listOf(Segment(SegmentKind.INTRO, 1_000, 89_000, "AniLiberty"))
        val database = listOf(
            Segment(SegmentKind.INTRO, 6_000, 96_000, "AniSkip"),
            Segment(SegmentKind.CREDITS, 1_460_000, 1_550_000, "AniSkip"),
        )
        val merged = SegmentRules.merge(1_560_000, source, database)
        assertEquals(listOf("AniLiberty", "AniSkip"), merged.map { it.source })
    }

    @Test
    fun dropsImplausibleSegments() {
        // credits in the first half, a 3-second intro and a 10-minute intro come from some other cut
        assertNull(SegmentRules.valid(Segment(SegmentKind.CREDITS, 600_000, null, "x"), hour))
        assertNull(SegmentRules.valid(Segment(SegmentKind.INTRO, 0, 3_000, "x"), hour))
        assertNull(SegmentRules.valid(Segment(SegmentKind.INTRO, 0, 600_000, "x"), hour))
        // an end past the video is clamped
        assertEquals(hour, SegmentRules.valid(Segment(SegmentKind.CREDITS, 3_300_000, 4_000_000, "x"), hour)!!.endMs)
        // and the next valid source is used instead of a bad one
        val merged = SegmentRules.merge(hour, listOf(Segment(SegmentKind.INTRO, 0, 600_000, "bad")), listOf(Segment(SegmentKind.INTRO, 30_000, 90_000, "good")))
        assertEquals("good", merged.single().source)
    }

    @Test
    fun aniSkipTakesTheSubmissionOfTheClosestCut() {
        val text = """{"found":true,"results":[
            {"interval":{"startTime":10,"endTime":100},"skipType":"op","episodeLength":1420},
            {"interval":{"startTime":70,"endTime":160},"skipType":"op","episodeLength":1440.4},
            {"interval":{"startTime":1430,"endTime":1500},"skipType":"ed","episodeLength":1510}]}"""
        val segments = SkipSegmentsProvider.parseAniSkip(text, 1_440_000)
        assertEquals(70_000L, segments.first { it.kind == SegmentKind.INTRO }.startMs)
        // an ending past the end of this shorter cut is moved by the difference
        val shorter = SkipSegmentsProvider.parseAniSkip(text, 1_420_000).first { it.kind == SegmentKind.CREDITS }
        assertEquals(1_340_000L, shorter.startMs)
        assertEquals(1_410_000L, shorter.endMs)
        // one that fits is left alone
        assertEquals(1_430_000L, SkipSegmentsProvider.parseAniSkip(text, 1_510_000).first { it.kind == SegmentKind.CREDITS }.startMs)
    }

    @Test
    fun neighbouringEpisodesMustAgree() {
        fun intro(a: Long, b: Long) = Segment(SegmentKind.INTRO, a, b, "TheIntroDB")
        val agreed = SkipSegmentsProvider.consensus(listOf(intro(0, 90_000), intro(30_000, 120_000), intro(1_500, 91_000)))!!
        assertEquals(1_500L, agreed.startMs)
        assertEquals(91_000L, agreed.endMs)
        assertEquals("TheIntroDB, по соседним сериям", agreed.source)
        assertNull(SkipSegmentsProvider.consensus(listOf(intro(0, 90_000), intro(30_000, 120_000))))
        assertNull(SkipSegmentsProvider.consensus(listOf(intro(0, 90_000))))
    }

    @Test
    fun firstSeasonFoundByTitleAndYear() {
        val results = listOf(
            AnimeIds.Entry(1, "ona", "2020-06-01", 1, listOf("Jujutsu Kaisen PV")),
            AnimeIds.Entry(40748, "tv", "2020-10-03", 24, listOf("Jujutsu Kaisen", "Магическая битва")),
            AnimeIds.Entry(48561, "movie", "2021-12-24", 1, listOf("Jujutsu Kaisen 0", "Магическая битва 0")),
            AnimeIds.Entry(51009, "tv", "2023-07-06", 23, listOf("Jujutsu Kaisen 2nd Season", "Магическая битва 2")),
        )
        assertEquals(40748, AnimeIds.pick(AnimeTitle("Магическая битва", null, 2020, true), "Магическая битва", results))
        assertEquals(48561, AnimeIds.pick(AnimeTitle("Магическая битва 0", null, 2021, false), "Магическая битва 0", results))
        // a namesake from another year is not taken
        assertNull(AnimeIds.pick(AnimeTitle("Магическая битва", null, 2016, true), "Магическая битва", results))
    }

    @Test
    fun seasonsMappedToMyAnimeListEntriesByEpisodeCounts() {
        // Demon Slayer: the second season of most sources is two MyAnimeList entries (7 + 11)
        val chain = listOf(26, 7, 11, 11, 8).mapIndexed { i, n -> AnimeIds.Entry(100 + i, "tv", null, n) }
        val sizes = listOf(26, 18, 11, 8)
        assertEquals(100 to 26, AnimeIds.map(chain, 0, 26, sizes))
        assertEquals(101 to 7, AnimeIds.map(chain, 1, 7, sizes))
        assertEquals(102 to 3, AnimeIds.map(chain, 1, 10, sizes))
        assertEquals(104 to 8, AnimeIds.map(chain, 3, 8, sizes))
        // counted differently: no guessing
        assertNull(AnimeIds.map(chain, 1, 3, listOf(26, 20, 11, 8)))
        // the last season may still be coming out
        assertEquals(104 to 5, AnimeIds.map(chain, 3, 5, listOf(26, 18, 11, 5)))
        // an entry still airing takes what is left
        val airing = listOf(AnimeIds.Entry(1, "tv", null, 24), AnimeIds.Entry(2, "tv", null, 0))
        assertEquals(2 to 7, AnimeIds.map(airing, 1, 7, listOf(24, 9)))
        // without counts only the first season, across its parts
        assertEquals(101 to 4, AnimeIds.map(chain, 0, 30, emptyList()))
        assertNull(AnimeIds.map(chain, 1, 4, emptyList()))
    }

    /** Canned answers by address; anything else is a 404, like the databases answer for unknown titles. */
    private fun fakeWeb(routes: Map<String, String>, asked: MutableList<String> = mutableListOf()): OkHttpClient =
        OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            val url = URLDecoder.decode(chain.request().url.toString(), "UTF-8")
            asked += url
            val body = routes.entries.firstOrNull { url.contains(it.key) }?.value
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(if (body != null) 200 else 404).message("")
                .body((body ?: "").toResponseBody("application/json".toMediaType()))
                .build()
        }).build()

    @Test
    fun animeWithoutAnIdFoundOnShikimoriAndItsSeasonLocated() = runBlocking {
        val asked = mutableListOf<String>()
        val http = fakeWeb(
            mapOf(
                "/api/animes?limit=20&search=Клинок, рассекающий демонов" to """[{"id":38000,"name":"Kimetsu no Yaiba","russian":"Клинок, рассекающий демонов","kind":"tv","episodes":26,"aired_on":"2019-04-06"}]""",
                "/api/animes/38000/related" to """[{"relation":"Sequel","anime":{"id":40456,"kind":"movie","episodes":1,"aired_on":"2020-10-16"}},
                    {"relation":"Sequel","anime":{"id":49926,"kind":"tv","episodes":7,"aired_on":"2021-10-10"}}]""",
                "/api/animes/49926/related" to """[{"relation":"Sequel","anime":{"id":47778,"kind":"tv","episodes":11,"aired_on":"2021-12-05"}}]""",
                "/api/animes/38000" to """{"id":38000,"kind":"tv","episodes":26,"aired_on":"2019-04-06"}""",
                "skip-times/47778/3?" to """{"found":true,"results":[{"interval":{"startTime":95,"endTime":185},"skipType":"op","episodeLength":1440}]}""",
            ),
            asked,
        )
        val provider = SkipSegmentsProvider(http)
        val request = SkipRequest(
            imdbId = null, malId = null, season = 2, episode = 10, durationMs = 1_440_000,
            anime = AnimeTitle("Клинок, рассекающий демонов", "鬼滅の刃", 2019, true),
            seasonSizes = listOf(26, 18),
        )
        val got = provider.fetch(request)
        assertEquals(Segment(SegmentKind.INTRO, 95_000, 185_000, "AniSkip"), got.single())
        // every submission is asked for, whatever its cut
        assertTrue(asked.any { "skip-times/47778/3?" in it && "episodeLength=0" in it })
        // only as far down the chain as the season needs
        assertTrue(asked.none { "/api/animes/47778/related" in it })
    }

    @Test
    fun caughtEpisodeOfAShowGetsTheIntroItsEpisodesAgreeOn() = runBlocking {
        val http = fakeWeb(
            mapOf(
                "imdb_id=tt1&season=1&episode=1" to """{"intro":[{"start_ms":60000,"end_ms":100000}]}""",
                "imdb_id=tt1&season=1&episode=2" to """{"intro":[{"start_ms":61000,"end_ms":101500}]}""",
                "imdb_id=tt1&season=1&episode=3" to """{"intro":[{"start_ms":200000,"end_ms":240000}]}""",
            ),
        )
        val provider = SkipSegmentsProvider(http)
        val got = provider.fetch(SkipRequest("tt1", null, 1, null, 2_700_000, series = true))
        assertEquals(61_000L, got.single().startMs)
        assertEquals("TheIntroDB, по соседним сериям", got.single().source)
        // a season nobody knows: nothing rather than another season's intro
        assertTrue(provider.fetch(SkipRequest("tt1", null, null, null, 2_700_000, series = true)).isEmpty())
        // an episode without its own data borrows from its neighbours too
        val fifth = provider.fetch(SkipRequest("tt1", null, 1, 5, 2_700_000))
        assertEquals("TheIntroDB, по соседним сериям", fifth.single().source)
    }

    @Test
    fun containsLeavesTheLastSecond() {
        val s = Segment(SegmentKind.INTRO, 10_000, 70_000, "x")
        assertTrue(s.contains(10_000, hour))
        assertTrue(!s.contains(69_500, hour))
    }
}
