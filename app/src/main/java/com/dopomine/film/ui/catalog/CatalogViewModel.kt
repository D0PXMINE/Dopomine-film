package com.dopomine.film.ui.catalog

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dopomine.film.data.MovieRepository
import com.dopomine.film.data.model.ListSource
import com.dopomine.film.data.model.SortOrder
import com.dopomine.film.data.model.YearRange
import com.dopomine.film.ui.common.PagedLoader
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class CatalogKind(val api: String, val label: String, val series: Boolean) {
    FILM("FILM", "Фильмы", false),
    SERIES("TV_SERIES", "Сериалы", true),
    MINI("MINI_SERIES", "Мини-сериалы", true),
    SHOW("TV_SHOW", "ТВ-шоу", true),
}

@Immutable
data class CatalogFilters(
    val kind: CatalogKind = CatalogKind.FILM,
    val sort: SortOrder = SortOrder.POPULAR,
    val genre: Pair<Int, String>? = null,
    val years: YearRange = YearRange.options.first(),
    val minRating: Int? = null,
) {
    val isSeries: Boolean get() = kind.series

    fun toSource() = ListSource.Filter(
        type = kind.api,
        order = sort.api,
        genre = genre?.first,
        yearFrom = years.from,
        yearTo = years.to,
        ratingFrom = minRating,
    )
}

class CatalogViewModel(private val movies: MovieRepository) : ViewModel() {
    private val _filters = MutableStateFlow(CatalogFilters())
    val filters: StateFlow<CatalogFilters> = _filters.asStateFlow()

    val loader = PagedLoader(viewModelScope) { page -> movies.list(_filters.value.toSource(), page) }

    init {
        loader.reset()
    }

    fun update(transform: (CatalogFilters) -> CatalogFilters) {
        val before = _filters.value
        _filters.update(transform)
        if (_filters.value != before) loader.reset()
    }

    fun setSeries(series: Boolean) = update {
        if (it.isSeries == series) it else it.copy(kind = if (series) CatalogKind.SERIES else CatalogKind.FILM)
    }
}
