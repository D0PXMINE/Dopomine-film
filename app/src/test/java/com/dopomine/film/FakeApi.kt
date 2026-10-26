package com.dopomine.film

import com.dopomine.film.data.api.ApiKeyDto
import com.dopomine.film.data.api.CountryDto
import com.dopomine.film.data.api.EpisodeDto
import com.dopomine.film.data.api.FilmDto
import com.dopomine.film.data.api.FilmItemDto
import com.dopomine.film.data.api.FilterGenreDto
import com.dopomine.film.data.api.FiltersDto
import com.dopomine.film.data.api.GenreDto
import com.dopomine.film.data.api.KeywordSearchDto
import com.dopomine.film.data.api.KinopoiskApi
import com.dopomine.film.data.api.PagedFilmsDto
import com.dopomine.film.data.api.QuotaDto
import com.dopomine.film.data.api.SeasonDto
import com.dopomine.film.data.api.SeasonsDto
import com.dopomine.film.data.api.SimilarsDto
import com.dopomine.film.data.api.StaffDto
import kotlinx.serialization.json.JsonPrimitive

/** In-memory API with a handful of real titles, used by screenshot tests. */
class FakeApi : KinopoiskApi {

    private data class T(
        val id: Int, val ru: String, val orig: String, val year: Int, val kp: Double, val imdb: Double,
        val series: Boolean, val genres: List<String>, val countries: List<String>,
    )

    private val titles = listOf(
        T(301, "Матрица", "The Matrix", 1999, 8.5, 8.7, false, listOf("фантастика", "боевик"), listOf("США")),
        T(258687, "Интерстеллар", "Interstellar", 2014, 8.6, 8.7, false, listOf("фантастика", "драма", "приключения"), listOf("США", "Великобритания")),
        T(326, "Побег из Шоушенка", "The Shawshank Redemption", 1994, 9.1, 9.3, false, listOf("драма"), listOf("США")),
        T(448, "Форрест Гамп", "Forrest Gump", 1994, 8.9, 8.8, false, listOf("драма", "комедия"), listOf("США")),
        T(435, "Зелёная миля", "The Green Mile", 1999, 9.1, 8.6, false, listOf("драма", "фэнтези"), listOf("США")),
        T(535341, "1+1", "Intouchables", 2011, 8.8, 8.5, false, listOf("драма", "комедия"), listOf("Франция")),
        T(447301, "Начало", "Inception", 2010, 8.7, 8.8, false, listOf("фантастика", "боевик"), listOf("США")),
        T(111543, "Тёмный рыцарь", "The Dark Knight", 2008, 8.5, 9.0, false, listOf("боевик", "криминал"), listOf("США")),
        T(41519, "Брат", "", 1997, 8.3, 7.8, false, listOf("драма", "криминал"), listOf("Россия")),
        T(1143242, "Джентльмены", "The Gentlemen", 2019, 8.6, 7.8, false, listOf("криминал", "комедия"), listOf("США")),
        T(464963, "Игра престолов", "Game of Thrones", 2011, 9.0, 9.2, true, listOf("фэнтези", "драма"), listOf("США")),
        T(404900, "Во все тяжкие", "Breaking Bad", 2008, 8.9, 9.5, true, listOf("драма", "криминал"), listOf("США")),
        T(915196, "Очень странные дела", "Stranger Things", 2016, 8.4, 8.7, true, listOf("фантастика", "ужасы"), listOf("США")),
        T(77044, "Друзья", "Friends", 1994, 9.2, 8.9, true, listOf("комедия", "мелодрама"), listOf("США")),
        T(178707, "Шерлок", "Sherlock", 2010, 8.8, 9.1, true, listOf("детектив", "драма"), listOf("Великобритания")),
    )

    private fun T.item() = FilmItemDto(
        kinopoiskId = id,
        nameRu = ru,
        nameOriginal = orig.ifBlank { null },
        year = JsonPrimitive(year),
        ratingKinopoisk = JsonPrimitive(kp),
        ratingImdb = JsonPrimitive(imdb),
        type = if (series) "TV_SERIES" else "FILM",
        genres = genres.map { GenreDto(it) },
        countries = countries.map { CountryDto(it) },
        posterUrl = "https://posters.test/$id.jpg",
        posterUrlPreview = "https://posters.test/$id.jpg",
    )

    private fun page(series: Boolean?, seed: Int = 0): PagedFilmsDto {
        val list = titles.filter { series == null || it.series == series }
        val rotated = if (list.isEmpty()) list else list.drop(seed % list.size) + list.take(seed % list.size)
        return PagedFilmsDto(total = rotated.size, totalPages = 1, items = rotated.map { it.item() })
    }

    override suspend fun collection(type: String, page: Int): PagedFilmsDto =
        page(series = type.contains("SERIES") || type.contains("TV_SHOWS"), seed = type.length)

    override suspend fun films(
        type: String?, order: String, genre: Int?, country: Int?, yearFrom: Int?, yearTo: Int?,
        ratingFrom: Int?, keyword: String?, page: Int,
    ): PagedFilmsDto {
        if (keyword != null) {
            val found = titles.filter { it.ru.contains(keyword, true) || it.orig.contains(keyword, true) }
            return PagedFilmsDto(found.size, 1, found.map { it.item() })
        }
        return page(series = type != "FILM", seed = (genre ?: 0) + (yearFrom ?: 0))
    }

    override suspend fun searchByKeyword(keyword: String, page: Int) = KeywordSearchDto(pagesCount = 0)

    override suspend fun film(id: Int): FilmDto {
        val t = titles.first { it.id == id }
        return FilmDto(
            kinopoiskId = t.id,
            nameRu = t.ru,
            nameOriginal = t.orig.ifBlank { null },
            posterUrl = "https://posters.test/${t.id}.jpg",
            posterUrlPreview = "https://posters.test/${t.id}.jpg",
            ratingKinopoisk = JsonPrimitive(t.kp),
            ratingKinopoiskVoteCount = JsonPrimitive(2_150_000),
            ratingImdb = JsonPrimitive(t.imdb),
            ratingImdbVoteCount = JsonPrimitive(2_100_000),
            year = JsonPrimitive(t.year),
            filmLength = JsonPrimitive(if (t.series) 60 else 136),
            slogan = "Добро пожаловать в реальный мир",
            description = "Жизнь Томаса Андерсона разделена на две части: днём он — самый обычный офисный работник, " +
                "получающий нагоняи от начальства, а ночью превращается в хакера по имени Нео, и нет места в сети, " +
                "куда он бы не смог проникнуть. Но однажды всё меняется. Томас узнаёт ужасающую правду о реальности.",
            type = if (t.series) "TV_SERIES" else "FILM",
            ratingAgeLimits = "age16",
            countries = t.countries.map { CountryDto(it) },
            genres = t.genres.map { GenreDto(it) },
            startYear = if (t.series) JsonPrimitive(t.year) else null,
            endYear = if (t.series) JsonPrimitive(t.year + 8) else null,
            serial = t.series,
            completed = t.series,
        )
    }

    override suspend fun seasons(id: Int) = SeasonsDto(
        total = 3,
        items = (1..3).map { s ->
            SeasonDto(s, (1..8).map { e -> EpisodeDto(s, e, nameRu = "Серия $e сезона $s", releaseDate = "201${s}-04-0$e") })
        },
    )

    override suspend fun similars(id: Int) = SimilarsDto(
        total = 6,
        items = titles.filter { it.id != id }.take(6).map {
            FilmItemDto(filmId = it.id, nameRu = it.ru, posterUrlPreview = "https://posters.test/${it.id}.jpg")
        },
    )

    override suspend fun staff(id: Int): List<StaffDto> = listOf(
        StaffDto(1, nameRu = "Лана Вачовски", professionKey = "DIRECTOR"),
        StaffDto(2, nameRu = "Киану Ривз", description = "Neo", professionKey = "ACTOR"),
        StaffDto(3, nameRu = "Лоренс Фишбёрн", description = "Morpheus", professionKey = "ACTOR"),
        StaffDto(4, nameRu = "Кэрри-Энн Мосс", description = "Trinity", professionKey = "ACTOR"),
        StaffDto(5, nameRu = "Хьюго Уивинг", description = "Agent Smith", professionKey = "ACTOR"),
    )

    override suspend fun filters() = FiltersDto(genres = listOf(FilterGenreDto(1, "триллер")))

    override suspend fun keyInfo(key: String, header: String) =
        ApiKeyDto(totalQuota = QuotaDto(0, 0), dailyQuota = QuotaDto(500, 42), accountType = "FREE")
}
