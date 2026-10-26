package com.dopomine.film.ui.search

import android.content.Context
import androidx.core.content.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dopomine.film.data.MovieRepository
import com.dopomine.film.ui.common.KpLinks
import com.dopomine.film.ui.common.PagedLoader
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@OptIn(FlowPreview::class)
class SearchViewModel(private val movies: MovieRepository, context: Context) : ViewModel() {
    private val prefs = context.applicationContext.getSharedPreferences("search", Context.MODE_PRIVATE)

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _recent = MutableStateFlow(loadRecent())
    val recent: StateFlow<List<String>> = _recent.asStateFlow()

    /** Kinopoisk id typed or pasted as a link. */
    val directId: StateFlow<Int?> = MutableStateFlow<Int?>(null).also { flow ->
        viewModelScope.launch { _query.collect { flow.value = KpLinks.extractId(it) } }
    }

    private var activeQuery = ""
    val loader = PagedLoader(viewModelScope) { page -> movies.search(activeQuery, page) }

    init {
        viewModelScope.launch {
            _query
                .map { it.trim() }
                .debounce(450)
                .distinctUntilChanged()
                .collect { q ->
                    activeQuery = q
                    if (q.length >= 2 && KpLinks.extractId(q) == null) loader.reset()
                }
        }
    }

    val hasQuery: Boolean get() = activeQuery.length >= 2

    fun setQuery(value: String) {
        _query.value = value
    }

    fun submit() {
        val q = _query.value.trim()
        if (q.length < 2) return
        val list = (listOf(q) + _recent.value.filterNot { it.equals(q, ignoreCase = true) }).take(10)
        _recent.value = list
        prefs.edit { putString("recent", list.joinToString("\n")) }
    }

    fun clearRecent() {
        _recent.value = emptyList()
        prefs.edit { remove("recent") }
    }

    private fun loadRecent(): List<String> =
        prefs.getString("recent", null)?.split("\n")?.filter { it.isNotBlank() } ?: emptyList()
}
