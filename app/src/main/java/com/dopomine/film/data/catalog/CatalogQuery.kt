package com.dopomine.film.data.catalog

import androidx.compose.runtime.Immutable
import com.dopomine.film.data.model.Movie
import java.util.Calendar

enum class Kind(val label: String, val sql: String) {
    FILMS("Фильмы", "type = 0"),
    SERIES("Сериалы", "type IN (1, 2)"),
    MINI("Мини-сериалы", "type = 2"),
    SHOWS("ТВ-шоу", "type = 3"),
    ANIME("Аниме", "(genres & ${Genres.bit("Аниме")}) != 0"),
    ALL("Всё", "1 = 1"),
}

/**
 * What search keeps: films, shows (TV shows too) or anime. An anime film is both a film and
 * anime, as in the catalog's tabs.
 */
enum class SearchKind(val label: String, val sql: String?) {
    ALL("Все", null),
    FILMS("Фильмы", "type = 0"),
    SERIES("Сериалы", "type != 0"),
    ANIME("Аниме", Kind.ANIME.sql),
    ;

    fun matches(movie: Movie): Boolean = when (this) {
        ALL -> true
        FILMS -> !movie.type.isSeries
        SERIES -> movie.type.isSeries
        ANIME -> movie.isAnime
    }
}

enum class Sort(val label: String, val sql: String) {
    TREND("Популярные сейчас", "trend DESC"),
    POPULAR("Самые известные", "popularity DESC"),
    RATING("По рейтингу", "kp_rating DESC, kp_votes DESC"),
    NEW("Новые", "year DESC, popularity DESC"),
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

/** A filtered, sorted slice of the catalog. Everything runs locally against SQLite. */
@Immutable
data class CatalogQuery(
    val kind: Kind = Kind.FILMS,
    val sort: Sort = Sort.TREND,
    val genre: String? = null,
    val animated: Boolean? = null,
    val country: String? = null,
    val yearFrom: Int? = null,
    val yearTo: Int? = null,
    val minRating: Double? = null,
    val minVotes: Int = 0,
) {
    fun where(): Pair<String, List<Any?>> {
        val parts = mutableListOf(kind.sql)
        val args = mutableListOf<Any?>()
        genre?.let { parts += "(genres & ?) != 0"; args += Genres.bit(it) }
        animated?.let { parts += "animated = ?"; args += if (it) 1 else 0 }
        country?.let { parts += "countries LIKE ?"; args += "%$it%" }
        yearFrom?.let { parts += "year >= ?"; args += it }
        yearTo?.let { parts += "year <= ?"; args += it }
        minRating?.let { parts += "kp_rating >= ?"; args += it }
        // rating sorts need enough votes to mean something
        val votes = if (sort == Sort.RATING) maxOf(minVotes, if (kind == Kind.FILMS) 20_000 else 5_000) else minVotes
        if (votes > 0) { parts += "kp_votes >= ?"; args += votes }
        // like Kinopoisk's own top: no documentaries or shorts unless that is what was asked for
        if (sort == Sort.RATING && kind == Kind.FILMS) {
            if (genre != DOCUMENTARY) { parts += "(genres & ?) = 0"; args += Genres.bit(DOCUMENTARY) }
            if (genre != ANIMATION) parts += "(duration IS NULL OR duration >= 40)"
        }
        if (sort == Sort.NEW) parts += "year IS NOT NULL"
        return parts.joinToString(" AND ") to args
    }

    private companion object {
        const val DOCUMENTARY = "Документальный"
        const val ANIMATION = "Мультфильм"
    }
}

@Immutable
data class Section(val key: String, val title: String, val query: CatalogQuery)

/** Home screen shelves; films and series have their own sets. */
object Sections {
    private val y get() = YearRange.currentYear

    val films: List<Section>
        get() = listOf(
            Section("f_trend", "Популярные фильмы", CatalogQuery(Kind.FILMS, Sort.TREND, minVotes = 1000)),
            Section("f_new", "Новинки", CatalogQuery(Kind.FILMS, Sort.POPULAR, yearFrom = y - 1)),
            Section("f_top", "Топ Кинопоиска", CatalogQuery(Kind.FILMS, Sort.RATING, minVotes = 100_000)),
            Section("f_action", "Боевики", CatalogQuery(Kind.FILMS, Sort.TREND, genre = "Боевик", minVotes = 2000)),
            Section("f_comedy", "Комедии", CatalogQuery(Kind.FILMS, Sort.TREND, genre = "Комедия", minVotes = 2000)),
            Section("f_scifi", "Фантастика", CatalogQuery(Kind.FILMS, Sort.TREND, genre = "Фантастика", minVotes = 2000)),
            Section("f_thriller", "Триллеры", CatalogQuery(Kind.FILMS, Sort.TREND, genre = "Триллер", minVotes = 2000)),
            Section("f_horror", "Ужасы", CatalogQuery(Kind.FILMS, Sort.TREND, genre = "Ужасы", minVotes = 2000)),
            Section("f_drama", "Драмы", CatalogQuery(Kind.FILMS, Sort.TREND, genre = "Драма", minVotes = 2000)),
            Section("f_crime", "Криминал", CatalogQuery(Kind.FILMS, Sort.TREND, genre = "Криминал", minVotes = 2000)),
            Section("f_anim", "Мультфильмы", CatalogQuery(Kind.FILMS, Sort.TREND, animated = true, minVotes = 1000)),
            Section("f_family", "Для всей семьи", CatalogQuery(Kind.FILMS, Sort.TREND, genre = "Семейный", minVotes = 1000)),
            Section("f_hero", "Супергерои", CatalogQuery(Kind.FILMS, Sort.POPULAR, genre = "Супергерои")),
            Section("f_adventure", "Приключения", CatalogQuery(Kind.FILMS, Sort.TREND, genre = "Приключения", minVotes = 2000)),
            Section("f_fantasy", "Фэнтези", CatalogQuery(Kind.FILMS, Sort.TREND, genre = "Фэнтези", minVotes = 2000)),
            Section("f_romance", "Мелодрамы", CatalogQuery(Kind.FILMS, Sort.TREND, genre = "Мелодрама", minVotes = 2000)),
            Section("f_ru", "Российское кино", CatalogQuery(Kind.FILMS, Sort.TREND, country = "Россия", yearFrom = 2000, minVotes = 1000)),
            Section("f_ussr", "Советская классика", CatalogQuery(Kind.FILMS, Sort.POPULAR, country = "СССР")),
            Section("f_war", "Военные", CatalogQuery(Kind.FILMS, Sort.POPULAR, genre = "Военный")),
            Section("f_doc", "Документальные", CatalogQuery(Kind.FILMS, Sort.POPULAR, genre = "Документальный")),
        )

    val series: List<Section>
        get() = listOf(
            Section("s_trend", "Популярные сериалы", CatalogQuery(Kind.SERIES, Sort.TREND, minVotes = 1000)),
            Section("s_new", "Новые сериалы", CatalogQuery(Kind.SERIES, Sort.POPULAR, yearFrom = y - 1)),
            Section("s_top", "Лучшие сериалы", CatalogQuery(Kind.SERIES, Sort.RATING, minVotes = 30_000)),
            Section("s_mini", "Мини-сериалы", CatalogQuery(Kind.MINI, Sort.TREND)),
            Section("s_drama", "Драмы", CatalogQuery(Kind.SERIES, Sort.TREND, genre = "Драма", minVotes = 1000)),
            Section("s_comedy", "Комедийные", CatalogQuery(Kind.SERIES, Sort.TREND, genre = "Комедия", minVotes = 1000)),
            Section("s_crime", "Криминал", CatalogQuery(Kind.SERIES, Sort.TREND, genre = "Криминал", minVotes = 1000)),
            Section("s_detective", "Детективы", CatalogQuery(Kind.SERIES, Sort.TREND, genre = "Детектив", minVotes = 1000)),
            Section("s_scifi", "Фантастика", CatalogQuery(Kind.SERIES, Sort.TREND, genre = "Фантастика", minVotes = 1000)),
            Section("s_fantasy", "Фэнтези", CatalogQuery(Kind.SERIES, Sort.TREND, genre = "Фэнтези", minVotes = 1000)),
            Section("s_thriller", "Триллеры", CatalogQuery(Kind.SERIES, Sort.TREND, genre = "Триллер", minVotes = 1000)),
            Section("s_anime", "Аниме", CatalogQuery(Kind.SERIES, Sort.TREND, genre = "Аниме")),
            Section("s_anim", "Мультсериалы", CatalogQuery(Kind.SERIES, Sort.TREND, animated = true)),
            Section("s_ru", "Российские сериалы", CatalogQuery(Kind.SERIES, Sort.TREND, country = "Россия", minVotes = 1000)),
            Section("s_kr", "Корейские дорамы", CatalogQuery(Kind.SERIES, Sort.TREND, country = "Корея")),
            Section("s_doc", "Документальные", CatalogQuery(Kind.SERIES, Sort.POPULAR, genre = "Документальный")),
            Section("s_show", "ТВ-шоу", CatalogQuery(Kind.SHOWS, Sort.TREND)),
        )

    /** Anime has its own tab: films and shows together, with genre shelves of its own. */
    val anime: List<Section>
        get() = listOf(
            Section("a_trend", "Популярное аниме", CatalogQuery(Kind.ANIME, Sort.TREND, minVotes = 300)),
            Section("a_series", "Аниме-сериалы", CatalogQuery(Kind.SERIES, Sort.TREND, genre = "Аниме", minVotes = 300)),
            Section("a_films", "Полнометражное аниме", CatalogQuery(Kind.FILMS, Sort.TREND, genre = "Аниме", minVotes = 300)),
            Section("a_new", "Новинки аниме", CatalogQuery(Kind.ANIME, Sort.POPULAR, yearFrom = y - 1)),
            Section("a_top", "Лучшее аниме", CatalogQuery(Kind.ANIME, Sort.RATING, minVotes = 10_000)),
            Section("a_action", "Экшен", CatalogQuery(Kind.ANIME, Sort.TREND, genre = "Боевик")),
            Section("a_fantasy", "Фэнтези и исекай", CatalogQuery(Kind.ANIME, Sort.TREND, genre = "Фэнтези")),
            Section("a_scifi", "Фантастика и меха", CatalogQuery(Kind.ANIME, Sort.TREND, genre = "Фантастика")),
            Section("a_comedy", "Комедии", CatalogQuery(Kind.ANIME, Sort.TREND, genre = "Комедия")),
            Section("a_romance", "Романтика", CatalogQuery(Kind.ANIME, Sort.TREND, genre = "Мелодрама")),
            Section("a_drama", "Драмы", CatalogQuery(Kind.ANIME, Sort.TREND, genre = "Драма")),
            Section("a_mystery", "Детективы и мистика", CatalogQuery(Kind.ANIME, Sort.TREND, genre = "Детектив")),
            Section("a_classic", "Классика аниме", CatalogQuery(Kind.ANIME, Sort.POPULAR, yearTo = 2005)),
        )

    fun byKey(key: String): Section? = (films + series + anime).firstOrNull { it.key == key }
}
