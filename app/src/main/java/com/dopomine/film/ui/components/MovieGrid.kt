package com.dopomine.film.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dopomine.film.data.model.Movie
import com.dopomine.film.ui.common.PagedLoader
import com.dopomine.film.ui.theme.Palette

/** Poster grid with infinite scroll on top of a [PagedLoader]. */
@Composable
fun MovieGrid(
    loader: PagedLoader,
    onMovieClick: (Movie) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    state: LazyGridState = rememberLazyGridState(),
    contentPadding: PaddingValues = PaddingValues(16.dp),
    header: (@Composable () -> Unit)? = null,
) {
    val items = loader.items
    val error = loader.error

    if (items.isEmpty()) {
        Box(modifier.fillMaxSize()) {
            when {
                error != null -> ErrorState(error, onRetry = loader::retry, onOpenSettings = onOpenSettings, modifier = Modifier.align(Alignment.Center))
                loader.loadedOnce && !loader.loading -> EmptyHint(Icons.Rounded.SearchOff, "Ничего не нашлось", Modifier.align(Alignment.Center), subtitle = "Попробуйте изменить фильтры")
                else -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(108.dp),
                    contentPadding = contentPadding,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    userScrollEnabled = false,
                ) {
                    if (header != null) item(span = { GridItemSpan(maxLineSpan) }) { header() }
                    items(12) { MovieCardSkeleton() }
                }
            }
        }
        return
    }

    val shouldLoadMore by remember(state) {
        derivedStateOf {
            val last = state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= state.layoutInfo.totalItemsCount - 9
        }
    }
    LaunchedEffect(state, loader) {
        snapshotFlow { shouldLoadMore }.collect { if (it) loader.loadNext() }
    }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(108.dp),
        state = state,
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (header != null) item(span = { GridItemSpan(maxLineSpan) }, key = "header") { header() }
        items(items, key = { it.id }) { movie -> MovieCard(movie, onClick = { onMovieClick(movie) }) }
        if (loader.loading || error != null) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "footer") {
                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    if (error != null) {
                        TextButton(onClick = loader::retry) {
                            Text("${error.userMessage}. Повторить", color = Palette.Accent, style = MaterialTheme.typography.labelLarge)
                        }
                    } else {
                        CircularProgressIndicator(strokeWidth = 3.dp)
                    }
                }
            }
        }
    }
}
