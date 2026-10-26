package com.dopomine.film.ui.home

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.mutableStateMapOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dopomine.film.data.AppException
import com.dopomine.film.data.HistoryItem
import com.dopomine.film.data.LibraryRepository
import com.dopomine.film.data.catalog.CatalogRepository
import com.dopomine.film.data.catalog.Section
import com.dopomine.film.data.catalog.Sections
import com.dopomine.film.data.model.Movie
import com.dopomine.film.data.toAppException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@Immutable
data class ShelfState(val items: List<Movie>? = null, val error: AppException? = null)

class HomeViewModel(private val catalog: CatalogRepository, library: LibraryRepository) : ViewModel() {
    /** 0 = films, 1 = series. */
    private val _mode = MutableStateFlow(0)
    val mode: StateFlow<Int> = _mode.asStateFlow()

    val history: StateFlow<List<HistoryItem>> =
        library.history.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val shelves = mutableStateMapOf<String, ShelfState>()
    private val inFlight = HashSet<String>()

    fun sections(mode: Int): List<Section> = if (mode == 0) Sections.films else Sections.series

    fun setMode(value: Int) {
        _mode.value = value
    }

    fun shelf(key: String): ShelfState = shelves[key] ?: ShelfState()

    /** Shelves load when they scroll into view; queries are local, so this is cheap. */
    fun load(section: Section, force: Boolean = false) {
        val key = section.key
        if (key in inFlight || (!force && shelves[key]?.items != null)) return
        inFlight += key
        viewModelScope.launch {
            try {
                shelves[key] = ShelfState(items = catalog.page(section.query, size = 24).items)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                shelves[key] = ShelfState(error = e.toAppException())
            } finally {
                inFlight -= key
            }
        }
    }
}
