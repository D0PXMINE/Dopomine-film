package com.dopomine.film

import com.dopomine.film.data.api.ApiClient
import com.dopomine.film.data.api.FilmDto
import com.dopomine.film.data.api.KeywordSearchDto
import com.dopomine.film.data.api.PagedFilmsDto
import com.dopomine.film.data.model.ContentType
import com.dopomine.film.data.model.toDetails
import com.dopomine.film.data.model.toMovie
import com.dopomine.film.data.settings.SettingsRepository
import com.dopomine.film.ui.common.KpLinks
import com.dopomine.film.ui.components.Format
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ParsingTest {
    private val json = ApiClient.json

    @Test
    fun collectionItemsParseBothRatings() {
        val body = """
            {"total":250,"totalPages":13,"items":[
              {"kinopoiskId":301,"imdbId":"tt0133093","nameRu":"Матрица","nameEn":null,"nameOriginal":"The Matrix",
               "countries":[{"country":"США"}],"genres":[{"genre":"фантастика"},{"genre":"боевик"}],
               "ratingKinopoisk":8.5,"ratingImdb":8.7,"year":1999,"type":"FILM",
               "posterUrl":"https://kinopoiskapiunofficial.tech/images/posters/kp/301.jpg",
               "posterUrlPreview":"https://kinopoiskapiunofficial.tech/images/posters/kp_small/301.jpg",
               "coverUrl":null,"logoUrl":null,"description":"…","ratingAgeLimits":"age16"},
              {"kinopoiskId":464963,"nameRu":"Игра престолов","ratingKinopoisk":null,"ratingImdb":"9.2",
               "year":"2011","type":"TV_SERIES","posterUrl":"p","posterUrlPreview":"pp"}
            ]}
        """.trimIndent()
        val page = json.decodeFromString(PagedFilmsDto.serializer(), body)
        val movies = page.items.mapNotNull { it.toMovie() }
        assertEquals(2, movies.size)
        assertEquals("Матрица", movies[0].title)
        assertEquals("The Matrix", movies[0].originalTitle)
        assertEquals(8.5, movies[0].ratingKp!!, 0.001)
        assertEquals(8.7, movies[0].ratingImdb!!, 0.001)
        assertEquals(1999, movies[0].year)
        assertEquals(ContentType.FILM, movies[0].type)
        assertEquals(listOf("фантастика", "боевик"), movies[0].genres)
        assertNull(movies[1].ratingKp)
        assertEquals(9.2, movies[1].ratingImdb!!, 0.001)
        assertEquals(2011, movies[1].year)
        assertEquals(ContentType.SERIES, movies[1].type)
    }

    @Test
    fun keywordSearchUsesStringRatings() {
        val body = """
            {"keyword":"мат","pagesCount":7,"searchFilmsCountResult":140,"films":[
              {"filmId":301,"nameRu":"Матрица","nameEn":"The Matrix","type":"FILM","year":"1999",
               "description":"США","filmLength":"2:16","countries":[],"genres":[],"rating":"8.5",
               "ratingVoteCount":600000,"posterUrl":"p","posterUrlPreview":"pp"},
              {"filmId":1,"nameRu":"Ожидаемый","type":"FILM","year":"2027","rating":"99%","posterUrl":"p","posterUrlPreview":"pp"}
            ]}
        """.trimIndent()
        val dto = json.decodeFromString(KeywordSearchDto.serializer(), body)
        val movies = dto.films.mapNotNull { it.toMovie() }
        assertEquals(301, movies[0].id)
        assertEquals(8.5, movies[0].ratingKp!!, 0.001)
        assertNull("awaiting percentage is not a rating", movies[1].ratingKp)
    }

    @Test
    fun filmDetailsParse() {
        val body = """
            {"kinopoiskId":464963,"nameRu":"Игра престолов","nameOriginal":"Game of Thrones",
             "posterUrl":"p","posterUrlPreview":"pp","ratingKinopoisk":9.0,"ratingKinopoiskVoteCount":800000,
             "ratingImdb":9.2,"ratingImdbVoteCount":2300000,"year":2011,"filmLength":55,
             "slogan":"Winter is coming","description":"…","type":"TV_SERIES","ratingAgeLimits":"age18",
             "countries":[{"country":"США"}],"genres":[{"genre":"фэнтези"}],"startYear":2011,"endYear":2019,
             "serial":true,"completed":true,"unknownField":{"a":1}}
        """.trimIndent()
        val details = json.decodeFromString(FilmDto.serializer(), body).toDetails()
        assertEquals(ContentType.SERIES, details.movie.type)
        assertEquals("18+", details.ageLimit)
        assertEquals(55, details.lengthMinutes)
        assertEquals(2019, details.endYear)
        assertEquals(2_300_000, details.ratingImdbVotes)
    }
}

class HelpersTest {
    @Test
    fun extractsKinopoiskIds() {
        assertEquals(301, KpLinks.extractId("https://www.kinopoisk.ru/film/301/"))
        assertEquals(464963, KpLinks.extractId("Смотри: https://www.kinopoisk.ru/series/464963/?utm=1"))
        assertEquals(301, KpLinks.extractId("kinopoisk.ws/film/301"))
        assertEquals(301, KpLinks.extractId(" 301 "))
        assertNull(KpLinks.extractId("Матрица 1999"))
        assertNull(KpLinks.extractId("12"))
    }

    @Test
    fun normalizesPlayerHost() {
        assertEquals("https://mirror.example.site", SettingsRepository.normalizeHost("mirror.example.site"))
        assertEquals("https://mirror.example.site", SettingsRepository.normalizeHost("https://Mirror.Example.site/301"))
        assertEquals("http://a.b", SettingsRepository.normalizeHost("http://a.b/"))
    }

    @Test
    fun formatsValues() {
        assertEquals("8.5", Format.rating(8.5))
        assertEquals("—", Format.rating(null))
        assertEquals("2 ч 16 мин", Format.minutes(136))
        assertEquals("1:02:03", Format.clock(3723))
        assertEquals("4:05", Format.clock(245))
        assertEquals("1 сезон", Format.seasons(1))
        assertEquals("3 сезона", Format.seasons(3))
        assertEquals("11 сезонов", Format.seasons(11))
        assertEquals("21 серия", Format.episodes(21))
    }
}
