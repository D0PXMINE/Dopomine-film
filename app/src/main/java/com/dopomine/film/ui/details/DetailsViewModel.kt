package com.dopomine.film.ui.details

import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dopomine.film.data.AppException
import com.dopomine.film.data.HistoryItem
import com.dopomine.film.data.LibraryRepository
import com.dopomine.film.data.catalog.CatalogRepository
import com.dopomine.film.data.model.Description
import com.dopomine.film.data.model.Movie
import com.dopomine.film.data.model.People
import com.dopomine.film.data.toAppException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

@Immutable
data class DetailsState(
    val movie: Movie? = null,
    val description: Description? = null,
    /** False while the description is still being looked up. */
    val descriptionDone: Boolean = false,
    val people: People = People(),
    val error: AppException? = null,
    val similar: List<Movie>? = null,
)

class DetailsViewModel(
    private val catalog: CatalogRepository,
    private val library: LibraryRepository,
    handle: SavedStateHandle,
) : ViewModel() {
    val id: Int = checkNotNull(handle.get<Int>("id"))

    private val _state = MutableStateFlow(
        DetailsState(
            description = catalog.cachedDescription(id),
            descriptionDone = catalog.cachedDescription(id) != null,
            people = catalog.cachedPeople(id) ?: People(),
        )
    )
    val state: StateFlow<DetailsState> = _state.asStateFlow()

    val isFavorite: StateFlow<Boolean> = library.isFavorite(id).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val history: StateFlow<HistoryItem?> = library.history
        .map { list -> list.firstOrNull { it.movie.id == id } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private var loading: Job? = null

    init {
        load()
    }

    /**
     * The offline record shows at once; the description, the cast and fresh ratings each fill in
     * as soon as their own source answers, so a slow one never holds the others up.
     */
    fun load() {
        loading?.cancel()
        _state.update { it.copy(error = null) }
        loading = viewModelScope.launch {
            val movie = try {
                catalog.movie(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _state.update { if (it.movie == null) it.copy(error = e.toAppException()) else it }
                return@launch
            }
            _state.update { it.copy(movie = movie) }
            launch { _state.update { it.copy(similar = runCatching { catalog.similar(movie) }.getOrDefault(emptyList())) } }
            launch {
                val found = withTimeoutOrNull(DESCRIPTION_TIMEOUT_MS) {
                    try {
                        catalog.description(movie)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        null
                    }
                }
                _state.update { it.copy(description = found ?: it.description, descriptionDone = true) }
            }
            launch {
                val people = catalog.people(id)
                if (people.directors.isNotEmpty() || people.cast.isNotEmpty()) _state.update { it.copy(people = people) }
            }
            launch {
                val fresh = runCatching { catalog.withFreshRatings(movie) }.getOrNull() ?: return@launch
                _state.update { it.copy(movie = fresh) }
            }
        }
    }

    fun toggleFavorite() {
        val movie = _state.value.movie ?: return
        viewModelScope.launch { library.toggleFavorite(movie) }
    }

    private companion object {
        const val DESCRIPTION_TIMEOUT_MS = 20_000L
    }
}
