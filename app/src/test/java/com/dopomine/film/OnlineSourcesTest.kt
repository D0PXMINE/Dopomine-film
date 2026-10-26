package com.dopomine.film

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParser
import com.dopomine.film.data.net.BROWSER_UA
import com.dopomine.film.player.resolve.Playable
import com.dopomine.film.player.resolve.PlayerSite
import com.dopomine.film.player.resolve.VeoVeoResolver
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
 * Talks to the real player site and VeoVeo. Skipped unless -Ddpm.online=true
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
}
