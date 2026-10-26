package com.dopomine.film.ui.collection

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dopomine.film.data.MovieRepository
import com.dopomine.film.data.model.Sections
import com.dopomine.film.ui.common.PagedLoader
import com.dopomine.film.ui.common.appViewModel
import com.dopomine.film.ui.components.MovieGrid
import com.dopomine.film.ui.components.TopBar
import com.dopomine.film.ui.navigation.Nav

class CollectionViewModel(movies: MovieRepository, handle: SavedStateHandle) : ViewModel() {
    val section = Sections.byKey(handle.get<String>("key").orEmpty())
    val loader = PagedLoader(viewModelScope) { page ->
        movies.list(requireNotNull(section).source, page)
    }

    init {
        if (section != null) loader.reset()
    }
}

@Composable
fun CollectionScreen(nav: Nav) {
    val vm = appViewModel { c, h -> CollectionViewModel(c.movies, h) }
    Column(Modifier.fillMaxSize()) {
        TopBar(title = vm.section?.title ?: "Подборка", onBack = nav::back)
        MovieGrid(
            loader = vm.loader,
            onMovieClick = { nav.details(it.id) },
            onOpenSettings = nav::settings,
            modifier = Modifier.navigationBarsPadding(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        )
    }
}
