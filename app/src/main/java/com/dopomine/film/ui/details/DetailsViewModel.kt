package com.dopomine.film.ui.details

import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dopomine.film.data.AppException
import com.dopomine.film.data.HistoryItem
import com.dopomine.film.data.LibraryRepository
import com.dopomine.film.data.catalog.CatalogRepository
import com.dopomine.film.data.model.Movie
import com.dopomine.film.data.model.MovieDetails
import com.dopomine.film.data.toAppException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@Immutable
data class DetailsState(
    val movie: Movie? = null,
    val details: MovieDetails? = null,
    val error: AppException? = null,
    val similar: List<Movie>? = null,
)

class DetailsViewModel(
    private val catalog: CatalogRepository,
    private val library: LibraryRepository,
    handle: SavedStateHandle,
) : ViewModel() {
    val id: Int = checkNotNull(handle.get<Int>("id"))

    private val _state = MutableStateFlow(DetailsState(details = catalog.cachedDetails(id), movie = catalog.cachedDetails(id)?.movie))
    val state: StateFlow<DetailsState> = _state.asStateFlow()

    val isFavorite: StateFlow<Boolean> = library.isFavorite(id).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val history: StateFlow<HistoryItem?> = library.history
        .map { list -> list.firstOrNull { it.movie.id == id } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        load()
    }

    fun load() {
        _state.update { it.copy(error = null) }
        viewModelScope.launch {
            try {
                // the offline record shows instantly; online extras fill in after
                val movie = catalog.movie(id)
                _state.update { it.copy(movie = movie) }
                launch { _state.update { it.copy(similar = runCatching { catalog.similar(movie) }.getOrDefault(emptyList())) } }
                val details = catalog.details(id)
                _state.update { it.copy(details = details, movie = details.movie) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _state.update { if (it.movie == null) it.copy(error = e.toAppException()) else it }
            }
        }
    }

    fun toggleFavorite() {
        val movie = _state.value.movie ?: return
        viewModelScope.launch { library.toggleFavorite(movie) }
    }
}
