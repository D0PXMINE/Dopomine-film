package com.dopomine.film.player.resolve

import com.dopomine.film.data.catalog.CatalogRepository
import com.dopomine.film.data.model.Movie

/**
 * Title matching for the anime sources. They name releases in Russian and in romaji or English,
 * one release per season ("Вторжение гигантов (второй сезон) / Attack on Titan 2nd Season"),
 * while the catalog has one entry for the whole show.
 */
internal object AnimeTitles {
    private val ORDINALS = listOf("перв", "втор", "трет", "четв", "пят", "шест", "седьм", "восьм", "девят", "десят")
    private val SEASON_MARKERS = listOf(
        Regex("""\(?\s*(${ORDINALS.joinToString("|")})\S*\s+сезон\S*\s*\)?""", RegexOption.IGNORE_CASE),
        Regex("""\(?\s*\d{1,2}(?:-?й)?\s+сезон\S*\s*\)?""", RegexOption.IGNORE_CASE),
        Regex("""\b\d{1,2}(?:st|nd|rd|th)\s+season\b""", RegexOption.IGNORE_CASE),
        Regex("""\bseason\s*\d{1,2}\b""", RegexOption.IGNORE_CASE),
        Regex(""":?\s*the final season\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(?:part|часть)\s*\d{1,2}\b""", RegexOption.IGNORE_CASE),
    )

    fun norm(text: String?): String? = text?.let(CatalogRepository::normalize)?.takeIf { it.isNotEmpty() }

    /** Normalized names of a catalog title: Russian and original. */
    fun names(movie: Movie): Set<String> = setOfNotNull(norm(movie.title), norm(movie.originalTitle))

    private val SUBTITLE = Regex("""\s*(?::|\.\s|\s[–—-]\s).*$""")

    /** A name without its subtitle: «Человек-бензопила. Фильм: История Резе» -> «Человек-бензопила». */
    private fun head(name: String?): String? = name?.replace(SUBTITLE, "")?.trim()?.takeIf { it.isNotEmpty() && it != name }

    /** What to type into a source's search: full names first, then without a subtitle. */
    fun queries(movie: Movie): List<String> = listOfNotNull(
        movie.title, movie.originalTitle, head(movie.title), head(movie.originalTitle),
    ).map { it.trim() }.filter { it.length >= 2 }.distinct().take(4)

    /** A release name without its season marker, normalized: the name of the show itself. */
    fun base(name: String?): String? {
        if (name == null) return null
        var text: String = name
        SEASON_MARKERS.forEach { text = it.replace(text, " ") }
        return norm(text)
    }

    /**
     * How well release names fit a title: [EXACT] when one of them is the title (season marker
     * aside), [PARTIAL] when the title appears in one as whole words — the catalog often has the
     * short name («Фрирен») where the source has the full one («Провожающая в последний путь
     * Фрирен»), [NONE] otherwise.
     */
    fun match(movie: Movie, vararg name: String?): Int {
        val wanted = names(movie)
        if (name.any { n -> norm(n) in wanted || base(n) in wanted }) return EXACT
        val words = (wanted + listOfNotNull(norm(head(movie.title)), norm(head(movie.originalTitle)))).filter { it.length >= 5 }
        val partial = name.any { n ->
            val text = " ${norm(n) ?: return@any false} "
            words.any { " $it " in text }
        }
        return if (partial) PARTIAL else NONE
    }

    const val NONE = 0
    const val PARTIAL = 1
    const val EXACT = 2

    fun yearFits(movie: Movie, year: Int?): Boolean = movie.year == null || year == null || kotlin.math.abs(movie.year - year) <= 1
}
