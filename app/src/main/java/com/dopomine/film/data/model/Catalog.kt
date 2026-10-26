package com.dopomine.film.data.model

import androidx.compose.runtime.Immutable
import java.util.Calendar

/** Kinopoisk genre ids as returned by /api/v2.2/films/filters. */
object Genres {
    val all: List<Pair<Int, String>> = listOf(
        11 to "Боевик",
        13 to "Комедия",
        2 to "Драма",
        6 to "Фантастика",
        17 to "Ужасы",
        1 to "Триллер",
        3 to "Криминал",
        5 to "Детектив",
        7 to "Приключения",
        12 to "Фэнтези",
        4 to "Мелодрама",
        18 to "Мультфильм",
        24 to "Аниме",
        19 to "Семейный",
        33 to "Детский",
        14 to "Военный",
        15 to "История",
        8 to "Биография",
        22 to "Документальный",
        10 to "Вестерн",
        20 to "Мюзикл",
        16 to "Музыка",
        21 to "Спорт",
        9 to "Фильм-нуар",
        30 to "Реальное ТВ",
    )
}

enum class SortOrder(val api: String, val label: String) {
    POPULAR("NUM_VOTE", "Популярные"),
    RATING("RATING", "По рейтингу"),
    YEAR("YEAR", "Новые"),
}

@Immutable
data class YearRange(val label: String, val from: Int?, val to: Int?) {
    companion object {
        val currentYear: Int get() = Calendar.getInstance().get(Calendar.YEAR)

        val options: List<YearRange>
            get() {
                val y = currentYear
                return listOf(
                    YearRange("Все годы", null, null),
                    YearRange("${y - 1}–$y", y - 1, y),
                    YearRange("2020–${y - 2}", 2020, y - 2),
                    YearRange("2010-е", 2010, 2019),
                    YearRange("2000-е", 2000, 2009),
                    YearRange("1990-е", 1990, 1999),
                    YearRange("1980-е", 1980, 1989),
                    YearRange("До 1980", 1900, 1979),
                )
            }
    }
}

/** Where a list of movies comes from. */
@Immutable
sealed interface ListSource {
    /** A Kinopoisk top or themed collection. */
    data class Collection(val type: String) : ListSource

    /** A query to /api/v2.2/films. */
    data class Filter(
        val type: String = "ALL",
        val order: String = SortOrder.POPULAR.api,
        val genre: Int? = null,
        val country: Int? = null,
        val yearFrom: Int? = null,
        val yearTo: Int? = null,
        val ratingFrom: Int? = null,
    ) : ListSource
}

@Immutable
data class Section(val key: String, val title: String, val source: ListSource)

/** Home screen shelves. Movies and series have their own sets. */
object Sections {
    private val y get() = YearRange.currentYear

    val movies: List<Section>
        get() = listOf(
            Section("popular_movies", "Популярные фильмы", ListSource.Collection("TOP_POPULAR_MOVIES")),
            Section("new_movies", "Новинки", ListSource.Filter(type = "FILM", yearFrom = y - 1, yearTo = y)),
            Section("top250_movies", "Топ-250 Кинопоиска", ListSource.Collection("TOP_250_MOVIES")),
            Section("action_movies", "Боевики", ListSource.Filter(type = "FILM", genre = 11)),
            Section("comedy_movies", "Комедии", ListSource.Filter(type = "FILM", genre = 13)),
            Section("scifi_movies", "Фантастика", ListSource.Filter(type = "FILM", genre = 6)),
            Section("horror_movies", "Ужасы", ListSource.Filter(type = "FILM", genre = 17)),
            Section("thriller_movies", "Триллеры", ListSource.Filter(type = "FILM", genre = 1)),
            Section("animation", "Мультфильмы", ListSource.Collection("KIDS_ANIMATION_THEME")),
            Section("family", "Для всей семьи", ListSource.Collection("FAMILY")),
            Section("greatest_xxi", "100 великих фильмов XXI века", ListSource.Collection("TOP_100_GREATEST_MOVIES_XXI")),
            Section("comics", "По комиксам", ListSource.Collection("COMICS_THEME")),
            Section("love", "Про любовь", ListSource.Collection("LOVE_THEME")),
            Section("catastrophe", "Катастрофы", ListSource.Collection("CATASTROPHE_THEME")),
            Section("zombie", "Зомби", ListSource.Collection("ZOMBIE_THEME")),
            Section("vampire", "Вампиры", ListSource.Collection("VAMPIRE_THEME")),
            Section("soon", "Скоро в кино", ListSource.Collection("CLOSES_RELEASES")),
        )

    val series: List<Section>
        get() = listOf(
            Section("popular_series", "Популярные сериалы", ListSource.Collection("POPULAR_SERIES")),
            Section("new_series", "Новые сериалы", ListSource.Filter(type = "TV_SERIES", yearFrom = y - 1, yearTo = y)),
            Section("top250_series", "Топ-250 сериалов", ListSource.Collection("TOP_250_TV_SHOWS")),
            Section("mini_series", "Мини-сериалы", ListSource.Filter(type = "MINI_SERIES")),
            Section("drama_series", "Драмы", ListSource.Filter(type = "TV_SERIES", genre = 2)),
            Section("comedy_series", "Комедийные", ListSource.Filter(type = "TV_SERIES", genre = 13)),
            Section("crime_series", "Криминал", ListSource.Filter(type = "TV_SERIES", genre = 3)),
            Section("detective_series", "Детективы", ListSource.Filter(type = "TV_SERIES", genre = 5)),
            Section("scifi_series", "Фантастика", ListSource.Filter(type = "TV_SERIES", genre = 6)),
            Section("fantasy_series", "Фэнтези", ListSource.Filter(type = "TV_SERIES", genre = 12)),
            Section("anime_series", "Аниме", ListSource.Filter(type = "TV_SERIES", genre = 24)),
            Section("cartoon_series", "Мультсериалы", ListSource.Filter(type = "TV_SERIES", genre = 18)),
            Section("doc_series", "Документальные", ListSource.Filter(type = "TV_SERIES", genre = 22)),
        )

    fun byKey(key: String): Section? = (movies + series).firstOrNull { it.key == key }
}
