package com.dopomine.film.ui.details

import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dopomine.film.data.AppException
import com.dopomine.film.data.HistoryItem
import com.dopomine.film.data.LibraryRepository
import com.dopomine.film.data.MovieRepository
import com.dopomine.film.data.model.ContentType
import com.dopomine.film.data.model.Movie
import com.dopomine.film.data.model.MovieDetails
import com.dopomine.film.data.model.Person
import com.dopomine.film.data.model.Season
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
    val details: MovieDetails? = null,
    val error: AppException? = null,
    val similars: List<Movie>? = null,
    val cast: List<Person>? = null,
    val seasons: List<Season>? = null,
    val seasonsLoading: Boolean = false,
    val seasonsError: AppException? = null,
)

class DetailsViewModel(
    private val movies: MovieRepository,
    private val library: LibraryRepository,
    handle: SavedStateHandle,
) : ViewModel() {
    val id: Int = checkNotNull(handle.get<Int>("id"))

    private val _state = MutableStateFlow(DetailsState(details = movies.cachedDetails(id)))
    val state: StateFlow<DetailsState> = _state.asStateFlow()

    val isFavorite: StateFlow<Boolean> =
        library.isFavorite(id).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

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
                val details = movies.details(id)
                _state.update { it.copy(details = details, error = null) }
                launch { loadSimilars() }
                launch { loadCast() }
                if (details.movie.type == ContentType.SERIES) launch { loadSeasons() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _state.update { it.copy(error = e.toAppException()) }
            }
        }
    }

    private suspend fun loadSimilars() {
        val list = runCatching { movies.similars(id) }.getOrElse { if (it is CancellationException) throw it; emptyList() }
        _state.update { it.copy(similars = list) }
    }

    private suspend fun loadCast() {
        val list = runCatching { movies.staff(id) }.getOrElse { if (it is CancellationException) throw it; emptyList() }
        _state.update { it.copy(cast = list) }
    }

    fun loadSeasons() {
        if (_state.value.seasonsLoading || _state.value.seasons != null) return
        _state.update { it.copy(seasonsLoading = true, seasonsError = null) }
        viewModelScope.launch {
            try {
                val seasons = movies.seasons(id)
                _state.update { it.copy(seasons = seasons, seasonsLoading = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _state.update { it.copy(seasonsLoading = false, seasonsError = e.toAppException()) }
            }
        }
    }

    fun toggleFavorite() {
        val movie = _state.value.details?.movie ?: return
        viewModelScope.launch { library.toggleFavorite(movie) }
    }
}
