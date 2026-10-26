package com.dopomine.film.ui.catalog

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dopomine.film.data.catalog.CatalogQuery
import com.dopomine.film.data.catalog.CatalogRepository
import com.dopomine.film.data.catalog.Kind
import com.dopomine.film.ui.common.PagedLoader
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class CatalogViewModel(private val catalog: CatalogRepository) : ViewModel() {
    private val _query = MutableStateFlow(CatalogQuery(Kind.FILMS, minVotes = 300))
    val query: StateFlow<CatalogQuery> = _query.asStateFlow()

    val loader = PagedLoader(viewModelScope) { page -> catalog.page(_query.value, page) }

    init {
        loader.reset()
    }

    fun update(transform: (CatalogQuery) -> CatalogQuery) {
        val before = _query.value
        _query.update(transform)
        if (_query.value != before) loader.reset()
    }
}
