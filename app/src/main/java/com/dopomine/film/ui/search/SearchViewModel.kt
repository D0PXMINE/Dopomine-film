package com.dopomine.film.ui.search

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dopomine.film.data.catalog.CatalogRepository
import com.dopomine.film.data.catalog.SearchKind
import com.dopomine.film.data.model.Movie
import com.dopomine.film.ui.common.KpLinks
import com.dopomine.film.ui.common.PagedLoader
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@OptIn(FlowPreview::class)
class SearchViewModel(private val catalog: CatalogRepository, context: Context) : ViewModel() {
    private val prefs = context.applicationContext.getSharedPreferences("search", Context.MODE_PRIVATE)

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _recent = MutableStateFlow(loadRecent())
    val recent: StateFlow<List<String>> = _recent.asStateFlow()

    var directId by mutableStateOf<Int?>(null)
        private set

    /** Titles found on Wikidata that the offline catalog does not have. */
    var online by mutableStateOf<List<Movie>?>(null)
        private set
    var onlineLoading by mutableStateOf(false)
        private set
    private var onlineJob: Job? = null

    /** Films, shows or anime only (or everything). */
    var kind by mutableStateOf(SearchKind.ALL)
        private set

    private var activeQuery = ""
    val loader = PagedLoader(viewModelScope) { page -> catalog.search(activeQuery, page, kind) }

    init {
        viewModelScope.launch {
            _query.map { it.trim() }.debounce(250).distinctUntilChanged().collect { q ->
                activeQuery = q
                directId = KpLinks.extractId(q)
                online = null
                onlineJob?.cancel()
                if (q.length >= 2 && directId == null) loader.reset()
            }
        }
    }

    fun setQuery(value: String) {
        _query.value = value
    }

    fun selectKind(value: SearchKind) {
        if (value == kind) return
        kind = value
        online = null
        onlineJob?.cancel()
        onlineLoading = false
        if (activeQuery.length >= 2 && directId == null) loader.reset()
    }

    fun searchOnline() {
        val q = activeQuery
        if (q.length < 2) return
        onlineJob?.cancel()
        onlineLoading = true
        onlineJob = viewModelScope.launch {
            online = runCatching { catalog.searchOnline(q, loader.items.map { it.id }.toSet(), kind) }.getOrDefault(emptyList())
            onlineLoading = false
        }
    }

    fun submit() {
        val q = _query.value.trim()
        if (q.length < 2) return
        val list = (listOf(q) + _recent.value.filterNot { it.equals(q, ignoreCase = true) }).take(12)
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
