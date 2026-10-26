package com.dopomine.film.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.dopomine.film.AppContainer
import com.dopomine.film.appContainer
import com.dopomine.film.data.AppException
import com.dopomine.film.data.model.Movie
import com.dopomine.film.data.model.PagedMovies
import com.dopomine.film.data.toAppException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Creates a ViewModel with access to the app container and the navigation arguments. */
@Composable
inline fun <reified VM : ViewModel> appViewModel(
    key: String? = null,
    crossinline create: (AppContainer, SavedStateHandle) -> VM,
): VM {
    val container = LocalContext.current.appContainer
    return viewModel(
        key = key,
        factory = viewModelFactory { initializer { create(container, createSavedStateHandle()) } },
    )
}

/**
 * Incremental loader for paged movie lists (infinite scroll).
 * A generation counter makes results of a cancelled/reset load harmless.
 */
class PagedLoader(
    private val scope: CoroutineScope,
    private val fetch: suspend (page: Int) -> PagedMovies,
) {
    var items by mutableStateOf<List<Movie>>(emptyList())
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<AppException?>(null)
        private set
    var endReached by mutableStateOf(false)
        private set
    var loadedOnce by mutableStateOf(false)
        private set

    private var page = 0
    private var generation = 0
    private var job: Job? = null

    fun reset() {
        job?.cancel()
        generation++
        items = emptyList()
        page = 0
        endReached = false
        error = null
        loadedOnce = false
        loading = false
        loadNext()
    }

    fun loadNext() {
        if (loading || endReached) return
        val gen = generation
        loading = true
        error = null
        job = scope.launch {
            try {
                val result = fetch(page + 1)
                if (gen != generation) return@launch
                page = result.page
                val known = items.mapTo(HashSet()) { it.id }
                items = items + result.items.filter { known.add(it.id) }
                endReached = !result.hasMore || result.items.isEmpty()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (gen == generation) error = e.toAppException()
            } finally {
                if (gen == generation) {
                    loading = false
                    loadedOnce = true
                }
            }
        }
    }

    fun retry() {
        error = null
        loadNext()
    }
}
