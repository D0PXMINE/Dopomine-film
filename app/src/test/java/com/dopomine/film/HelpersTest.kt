package com.dopomine.film

import com.dopomine.film.data.catalog.CatalogQuery
import com.dopomine.film.data.catalog.CatalogRepository
import com.dopomine.film.data.catalog.Genres
import com.dopomine.film.data.catalog.Kind
import com.dopomine.film.data.catalog.Sort
import com.dopomine.film.data.settings.SettingsRepository
import com.dopomine.film.player.resolve.Stream
import com.dopomine.film.player.resolve.StreamFormat
import com.dopomine.film.ui.common.KpLinks
import com.dopomine.film.ui.components.Format
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HelpersTest {
    @Test
    fun extractsKinopoiskIds() {
        assertEquals(301, KpLinks.extractId("https://www.kinopoisk.ru/film/301/"))
        assertEquals(464963, KpLinks.extractId("Смотри: https://www.kinopoisk.ru/series/464963/?utm=1"))
        assertEquals(301, KpLinks.extractId(" 301 "))
        assertNull(KpLinks.extractId("Матрица 1999"))
    }

    @Test
    fun normalizesPlayerHost() {
        assertEquals("https://mirror.example.site", SettingsRepository.normalizeHost("mirror.example.site"))
        assertEquals("https://mirror.example.site", SettingsRepository.normalizeHost("https://Mirror.Example.site/301"))
    }

    @Test
    fun formatsValues() {
        assertEquals("8.5", Format.rating(8.5))
        assertEquals("2 ч 16 мин", Format.minutes(136))
        assertEquals("1:02:03", Format.clock(3723))
        assertEquals("3 сезона", Format.seasons(3))
        assertEquals("21 серия", Format.episodes(21))
    }

    @Test
    fun normalizesSearchText() {
        assertEquals("ежик в тумане", CatalogRepository.normalize("Ёжик в тумане!"))
        assertEquals("1 1", CatalogRepository.normalize("1+1"))
    }

    @Test
    fun genreBitsMatchTheBuilder() {
        assertEquals(1L, Genres.bit("Боевик"))
        assertEquals(1L shl 23, Genres.bit("Супергерои"))
        assertEquals(listOf("Боевик", "Фантастика"), Genres.names(1L or (1L shl 3)))
    }

    @Test
    fun buildsQueries() {
        val (where, args) = CatalogQuery(Kind.FILMS, Sort.RATING, genre = "Комедия", yearFrom = 2000).where()
        assertTrue(where.startsWith("type = 0"))
        assertTrue("rating sort needs votes", where.contains("kp_votes >= ?"))
        assertEquals(listOf<Any?>(Genres.bit("Комедия"), 2000, 20_000, Genres.bit("Документальный")), args)
        assertTrue("no shorts in the top", where.contains("duration >= 40"))
    }

    @Test
    fun detectsStreamFormats() {
        assertEquals(StreamFormat.HLS, Stream.formatOf("https://a/b/index.m3u8?x=1"))
        assertEquals(StreamFormat.DASH, Stream.formatOf("https://a/b/manifest.mpd"))
        assertEquals(StreamFormat.PROGRESSIVE, Stream.formatOf("https://a/b/video.mp4"))
    }
}
