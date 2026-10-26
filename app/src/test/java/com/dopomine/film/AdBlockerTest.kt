package com.dopomine.film

import android.net.Uri
import com.dopomine.film.player.AdBlocker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AdBlockerTest {
    private val blocker = AdBlocker(RuntimeEnvironment.getApplication(), CoroutineScope(Dispatchers.IO))

    private fun blocked(url: String) = blocker.shouldBlock(Uri.parse(url))

    @Test
    fun blocksAdNetworksSeenOnThePlayerPage() {
        assertTrue(blocked("https://vak345.com/s.js?v=b391b4a023b1ee948023cbbf13cf81fa"))
        assertTrue(blocked("https://code.moviead55.ru/toronto?rnd=1"))
        assertTrue(blocked("https://graphicslab.io/sdk/v2/rendex-sdk.min.js"))
        assertTrue(blocked("https://alt.graphicslab.io/sdk/v2/rendex-sdk.min.js"))
        assertTrue(blocked("https://counter.yadro.ru/hit;abc"))
        assertTrue(blocked("https://an.yandex.ru/meta/123"))
        assertTrue(blocked("https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js"))
    }

    @Test
    fun blocksVastRequests() {
        assertTrue(blocked("https://cdn.unknown-ads.example/vast.xml?id=5"))
        assertTrue(blocked("https://player.example.com/api/vast?type=preroll"))
        assertTrue(blocked("https://x.example.com/vpaid/loader.js"))
    }

    @Test
    fun keepsPlayersAndVideoWorking() {
        assertFalse(blocked("https://linkpp.ink/?id=301&n=0&exp=1793040197&sig=c1e5c358d5"))
        assertFalse(blocked("https://p.linkpp.ink/api/players?kinopoisk=301"))
        assertFalse(blocked("https://linkpp.ink/kinoboxnew8.js?v=31"))
        assertFalse(blocked("https://theatre.stravers.live/?token_movie=72d249c8bf&token=5009a7a2d0"))
        assertFalse(blocked("https://api.ortified.ws/embed/movie/474"))
        assertFalse(blocked("https://64650e1b.obrut.show/embed/UTN/content/EDOzkjM"))
        assertFalse(blocked("https://player.shortener2.link/balancer-api/iframe?movie_id=38569&token=eyJ"))
        assertFalse(blocked("https://cdn.example.net/hls/301/1080/index.m3u8"))
        assertFalse(blocked("https://cdn.example.net/hls/301/1080/seg-00012-v1-a1.ts"))
        assertFalse(blocked("https://avatars.mds.yandex.net/get-kinopoisk-image/4774061/cf19/360"))
        assertFalse(blocked("https://fonts.googleapis.com/css2?family=Inter"))
        assertFalse(blocked("https://cdnjs.cloudflare.com/ajax/libs/hls.js/1.5.0/hls.min.js"))
    }
}
