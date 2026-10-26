package com.dopomine.film.data.catalog

/** Genre bits of the catalog; the order must match GENRES in tools/catalog/build_catalog.py. */
object Genres {
    val all = listOf(
        "Боевик", "Комедия", "Драма", "Фантастика", "Ужасы", "Триллер", "Криминал", "Детектив",
        "Приключения", "Фэнтези", "Мелодрама", "Мультфильм", "Аниме", "Семейный", "Детский", "Военный",
        "История", "Биография", "Документальный", "Вестерн", "Мюзикл", "Музыка", "Спорт", "Супергерои",
    )

    fun bit(name: String): Long = 1L shl all.indexOf(name).also { require(it >= 0) { "unknown genre $name" } }

    fun names(mask: Long): List<String> = all.filterIndexed { i, _ -> mask and (1L shl i) != 0L }

    /** Genres offered in the catalog filter (the rest are too rare to be useful there). */
    val filterable = all
}
