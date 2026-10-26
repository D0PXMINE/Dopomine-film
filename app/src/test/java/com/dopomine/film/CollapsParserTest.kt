package com.dopomine.film

import com.dopomine.film.player.resolve.CollapsParser
import com.dopomine.film.player.resolve.Playable
import com.dopomine.film.player.resolve.ResolveException
import com.dopomine.film.player.resolve.StreamFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The embed page is JS, not JSON; these samples mirror the shapes the parser has to cope with. */
class CollapsParserTest {
    private val headers = mapOf("Referer" to "https://api.example.ws/")

    @Test
    fun movieWithVoicesAndSubtitles() {
        val html = """
            <script>
            makePlayer({
                poster: "https://img.example/301.jpg",
                source: {
                    hls: "https://cdn.example.ws/hls/abc/index.m3u8?token=xyz&t=1",
                    dash: "https://cdn.example.ws/dash/abc/manifest.mpd?token=xyz",
                    audio: {"names":["Рус. Дублированный","Рус. Проф. многоголосый","Eng.Original"],"order":[0,1,2]},
                    cc: [{"url":"https://cdn.example.ws/sub/rus.vtt","name":"Русские"},{"url":"//cdn.example.ws/sub/eng.vtt","name":"English"}],
                },
                autoplay: true,
            });
            </script>
        """.trimIndent()
        val p = CollapsParser.parse(html, headers) as Playable.Single
        assertEquals("https://cdn.example.ws/hls/abc/index.m3u8?token=xyz&t=1", p.stream.url)
        assertEquals(StreamFormat.HLS, p.stream.format)
        assertEquals(listOf("Рус. Дублированный", "Рус. Проф. многоголосый", "Eng.Original"), p.stream.audioNames)
        assertEquals(2, p.stream.subtitles.size)
        assertEquals("https://cdn.example.ws/sub/eng.vtt", p.stream.subtitles[1].url)
        assertEquals(headers, p.stream.headers)
    }

    @Test
    fun seriesFromJsonSeasons() {
        val html = """
            <script>
            makePlayer({
                seasons:[{"season":1,"episodes":[{"episode":"1","hls":"https:\/\/cdn.example.ws\/s1e1.m3u8","audio":{"names":["LostFilm","NewStudio"]},"cc":[]},{"episode":"2","hls":"https://cdn.example.ws/s1e2.m3u8"}]},{"season":2,"episodes":[{"episode":"1","dash":"https://cdn.example.ws/s2e1.mpd"}]}],
                qualityByWidth: {}
            });
            </script>
        """.trimIndent()
        val p = CollapsParser.parse(html, headers) as Playable.Series
        assertEquals(2, p.seasons.size)
        assertEquals(2, p.seasons[0].episodes.size)
        assertEquals("https://cdn.example.ws/s1e1.m3u8", p.seasons[0].episodes[0].stream.url)
        assertEquals(listOf("LostFilm", "NewStudio"), p.seasons[0].episodes[0].stream.audioNames)
        assertEquals(StreamFormat.DASH, p.seasons[1].episodes[0].stream.format)
    }

    @Test
    fun seriesWithJsStyleKeys() {
        val html = """
            var p = new Player({ seasons: [{season: 3, episodes: [{episode: 5, hls: 'https://cdn.example.ws/a.m3u8', title: 'Пилот'},]},], });
        """.trimIndent()
        val p = CollapsParser.parse(html, headers) as Playable.Series
        assertEquals(3, p.seasons.single().number)
        val e = p.seasons.single().episodes.single()
        assertEquals(5, e.number)
        assertEquals("Пилот", e.title)
        assertEquals("https://cdn.example.ws/a.m3u8", e.stream.url)
    }

    @Test
    fun emptySeasonsFallBackToMovie() {
        val html = """seasons: [], hls: "https://cdn.example.ws/movie.m3u8","""
        val p = CollapsParser.parse(html, headers) as Playable.Single
        assertEquals("https://cdn.example.ws/movie.m3u8", p.stream.url)
    }

    @Test
    fun regionLockIsReported() {
        try {
            CollapsParser.parse("<div>К сожалению, видео недоступно для вашего региона: US</div>", headers)
            fail("expected an error")
        } catch (e: ResolveException) {
            assertTrue(e.message!!.contains("регион"))
        }
    }

    @Test
    fun balancedRespectsStrings() {
        val text = """x = [1, "a]b", {"c": "]"}, [2]] tail"""
        assertEquals("""[1, "a]b", {"c": "]"}, [2]]""", CollapsParser.balanced(text, text.indexOf('[')))
    }
}
