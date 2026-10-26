package com.dopomine.film.ui.home

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.mutableStateMapOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dopomine.film.data.AppException
import com.dopomine.film.data.HistoryItem
import com.dopomine.film.data.LibraryRepository
import com.dopomine.film.data.MovieRepository
import com.dopomine.film.data.model.Movie
import com.dopomine.film.data.model.Section
import com.dopomine.film.data.model.Sections
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

class HomeViewModel(
    private val movies: MovieRepository,
    library: LibraryRepository,
) : ViewModel() {

    /** 0 = films, 1 = series. */
    private val _mode = MutableStateFlow(0)
    val mode: StateFlow<Int> = _mode.asStateFlow()

    val history: StateFlow<List<HistoryItem>> =
        library.history.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val shelves = mutableStateMapOf<String, ShelfState>()
    private val inFlight = HashSet<String>()

    fun sections(mode: Int): List<Section> = if (mode == 0) Sections.movies else Sections.series

    fun setMode(value: Int) {
        _mode.value = value
    }

    fun shelf(key: String): ShelfState = shelves[key] ?: ShelfState()

    /** Loads a shelf the first time it becomes visible (sections are lazy to save API quota). */
    fun load(section: Section, force: Boolean = false) {
        val key = section.key
        if (key in inFlight) return
        if (!force && shelves[key]?.items != null) return
        inFlight += key
        shelves[key] = ShelfState(items = shelves[key]?.items)
        viewModelScope.launch {
            try {
                val page = movies.list(section.source)
                shelves[key] = ShelfState(items = page.items)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                shelves[key] = ShelfState(items = shelves[key]?.items, error = e.toAppException())
            } finally {
                inFlight -= key
            }
        }
    }

    fun refresh() {
        movies.clearMemoryCache()
        val keys = shelves.keys.toList()
        keys.forEach { key -> Sections.byKey(key)?.let { load(it, force = true) } }
    }
}
