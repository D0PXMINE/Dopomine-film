package com.dopomine.film

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParser
import com.dopomine.film.data.net.BROWSER_UA
import com.dopomine.film.data.model.ContentType
import com.dopomine.film.data.model.Movie
import com.dopomine.film.player.resolve.AniLibertyResolver
import com.dopomine.film.player.resolve.AnimevostResolver
import com.dopomine.film.player.resolve.Playable
import com.dopomine.film.player.resolve.PlayerSite
import com.dopomine.film.player.resolve.VeoVeoResolver
import com.dopomine.film.player.skip.SegmentKind
import com.dopomine.film.player.skip.SkipRequest
import com.dopomine.film.player.skip.SkipSegmentsProvider
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Talks to the real player site, VeoVeo and the anime sources. Skipped unless -Ddpm.online=true
 * (network-dependent, so it is not part of the regular build).
 */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OnlineSourcesTest {
    private val http = OkHttpClient()
    private val mirror = BuildConfig.DEFAULT_PLAYER_HOST

    @Test
    fun movieStreamHasVoicesAndQualities() = runBlocking {
        assumeTrue(System.getProperty("dpm.online") == "true")
        val sources = PlayerSite(http).sources(mirror, 301)
        println("sources: " + sources.joinToString { "${it.name} ${it.iframeUrl.take(60)}" })
        val veo = sources.first { it.isVeoVeo }
        val playable = VeoVeoResolver(http).resolve(veo.iframeUrl) { println(it) } as Playable.Single
        val stream = playable.stream
        println("stream: ${stream.url.take(120)}")

        val response = http.newCall(Request.Builder().url(stream.url).header("User-Agent", BROWSER_UA).apply {
            stream.headers.forEach { (k, v) -> header(k, v) }
        }.build()).execute()
        assertEquals(200, response.code)
        val finalUrl = response.request.url.toString()
        val playlist = response.body!!.byteStream().use { HlsPlaylistParser().parse(Uri.parse(finalUrl), it) }
        assertTrue(playlist is HlsMultivariantPlaylist)
        playlist as HlsMultivariantPlaylist
        println("variants: " + playlist.variants.joinToString { "${it.format.height}p" })
        println("audio: " + playlist.audios.joinToString { it.name })
        println("subtitles: " + playlist.subtitles.joinToString { it.name })
        assertTrue(playlist.variants.isNotEmpty())
        assertTrue("several voice-overs", playlist.audios.size >= 2)
        // relative URIs must resolve against the redirected CDN host, not the API host
        assertTrue(playlist.variants.first().url.host != Uri.parse(stream.url).host || finalUrl == stream.url)
    }

    @Test
    fun seriesHasSeasons() = runBlocking {
        assumeTrue(System.getProperty("dpm.online") == "true")
        val veo = PlayerSite(http).sources(mirror, 464963).first { it.isVeoVeo }
        val playable = VeoVeoResolver(http).resolve(veo.iframeUrl) { println(it) } as Playable.Series
        println("seasons: " + playable.seasons.joinToString { "${it.number}:${it.episodes.size}" })
        assertTrue(playable.seasons.size >= 8)
        assertTrue(playable.seasons.first().episodes.first().stream.url.contains(".m3u8"))
    }

    // ---- anime ----------------------------------------------------------------------------------

    private val frieren = Movie(5401195, "Фрирен", "Frieren", 2023, type = ContentType.SERIES, genres = listOf(Movie.ANIME))
    private val titan = Movie(749374, "Атака титанов", "Attack on Titan", 2013, type = ContentType.SERIES, malId = 16498)
    private val reze = Movie(5430477, "Человек-бензопила. Фильм: История Резе", "Chainsaw Man – The Movie: Reze Arc", 2025, malId = 57555)

    private fun status(url: String): Int =
        http.newCall(Request.Builder().url(url).header("User-Agent", BROWSER_UA).header("Range", "bytes=0-1023").build()).execute().use { it.code }

    @Test
    fun aniLibertyFindsSeriesByNameAndByMal() = runBlocking {
        assumeTrue(System.getProperty("dpm.online") == "true")
        val resolver = AniLibertyResolver(http)
        for (movie in listOf(frieren, frieren.copy(malId = 52991))) {
            val playable = resolver.resolve(movie) { println(it) } as Playable.Series
            println("seasons: " + playable.seasons.joinToString { "${it.number}:${it.episodes.size}" } + " start ${playable.startSeason}")
            val first = playable.seasons.first { it.number == (playable.startSeason ?: 1) }
            assertTrue(first.episodes.size >= 28)
            val stream = first.episodes.first().stream
            println("variants: " + stream.variants.joinToString { it.label })
            assertEquals(listOf("1080p", "720p", "480p"), stream.variants.map { it.label })
            assertTrue("no ad switches left in the link", '?' !in stream.url)
            val response = http.newCall(Request.Builder().url(stream.url).header("User-Agent", BROWSER_UA).build()).execute()
            val text = response.body!!.string()
            assertEquals(200, response.code)
            assertTrue(text.startsWith("#EXTM3U"))
            assertTrue("no inserted ad breaks", "DISCONTINUITY" !in text)
        }
    }

    @Test
    fun animevostAssemblesSeasonsAndFindsFilms() = runBlocking {
        assumeTrue(System.getProperty("dpm.online") == "true")
        val resolver = AnimevostResolver(http)
        val show = resolver.resolve(titan) { println(it) } as Playable.Series
        println("seasons: " + show.seasons.joinToString { "${it.number}:${it.episodes.size}" })
        assertTrue(show.seasons.size >= 4)
        assertEquals(25, show.seasons.first().episodes.size)
        val file = show.seasons.first().episodes.first().stream
        println("file: ${file.url} variants ${file.variants.map { it.label }}")
        assertTrue(file.url.startsWith("https://"))
        assertTrue(status(file.url) in listOf(200, 206))

        val film = resolver.resolve(reze) { println(it) }
        println("film: $film".take(200))
        assertTrue(film is Playable.Single)

        val frierenShow = resolver.resolve(frieren) { println(it) } as Playable.Series
        println("frieren: " + frierenShow.seasons.joinToString { "${it.number}:${it.episodes.size}" })
        assertEquals(28, frierenShow.seasons.first().episodes.size)
    }

    // ---- intro skipping -------------------------------------------------------------------------

    @Test
    fun introDatabasesAnswer() = runBlocking {
        assumeTrue(System.getProperty("dpm.online") == "true")
        val provider = SkipSegmentsProvider(http)
        val got = provider.fetch(SkipRequest("tt0944947", null, 1, 3, 3_440_000)) { println(it) }
        println("GoT s1e3: $got")
        assertTrue(got.any { it.kind == SegmentKind.INTRO && it.endMs!! in 60_000..140_000 })
        val frieren = provider.fetch(SkipRequest(null, 52991, 1, 2, 1_560_000)) { println(it) }
        println("Frieren e2: $frieren")
        assertEquals("AniSkip", frieren.first { it.kind == SegmentKind.INTRO }.source)
        val matrix = provider.fetch(SkipRequest("tt0133093", null, null, null, 8_160_000)) { println(it) }
        println("Matrix: $matrix")
        assertTrue(matrix.isNotEmpty())
    }
}
