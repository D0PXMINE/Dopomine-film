package com.dopomine.film

import com.dopomine.film.player.skip.Segment
import com.dopomine.film.player.skip.SegmentKind
import com.dopomine.film.player.skip.SegmentRules
import com.dopomine.film.player.skip.SkipSegmentsProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

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
    fun containsLeavesTheLastSecond() {
        val s = Segment(SegmentKind.INTRO, 10_000, 70_000, "x")
        assertTrue(s.contains(10_000, hour))
        assertTrue(!s.contains(69_500, hour))
    }
}
