package com.dopomine.film.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.dopomine.film.data.HistoryItem
import com.dopomine.film.data.LibraryRepository
import com.dopomine.film.data.model.Movie
import com.dopomine.film.ui.common.appViewModel
import com.dopomine.film.ui.components.EmptyHint
import com.dopomine.film.ui.components.Format
import com.dopomine.film.ui.components.MovieCard
import com.dopomine.film.ui.components.MovieListRow
import com.dopomine.film.ui.components.PillSwitch
import com.dopomine.film.ui.navigation.BottomBarSpace
import com.dopomine.film.ui.navigation.Nav
import com.dopomine.film.ui.theme.Palette
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class LibraryViewModel(private val library: LibraryRepository) : ViewModel() {
    val favorites: StateFlow<List<Movie>?> =
        library.favorites.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val history: StateFlow<List<HistoryItem>?> =
        library.history.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun removeFromHistory(id: Int) = viewModelScope.launch { library.removeFromHistory(id) }
    fun clearHistory() = viewModelScope.launch { library.clearHistory() }
}

@Composable
fun LibraryScreen(nav: Nav) {
    val vm = appViewModel { c, _ -> LibraryViewModel(c.library) }
    val favorites by vm.favorites.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var confirmClear by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .statusBarsPadding()
                .padding(start = 16.dp, end = 4.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Моё", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            if (tab == 1 && !history.isNullOrEmpty()) {
                TextButton(onClick = { confirmClear = true }) { Text("Очистить", color = Palette.TextSecondary) }
            }
            IconButton(onClick = nav::settings) { Icon(Icons.Rounded.Settings, "Настройки") }
        }
        PillSwitch(
            options = listOf("Избранное", "История"),
            selected = tab,
            onSelect = { tab = it },
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
        if (tab == 0) {
            val list = favorites
            when {
                list == null -> Unit
                list.isEmpty() -> EmptyHint(
                    Icons.Rounded.BookmarkBorder,
                    "Здесь будут ваши фильмы",
                    subtitle = "Нажмите на закладку на странице фильма или сериала, чтобы сохранить его",
                )
                else -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(108.dp),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = BottomBarSpace + 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    items(list, key = { it.id }) { movie -> MovieCard(movie, onClick = { nav.details(movie.id) }) }
                }
            }
        } else {
            val list = history
            when {
                list == null -> Unit
                list.isEmpty() -> EmptyHint(Icons.Rounded.History, "История пуста", subtitle = "Здесь появятся фильмы и сериалы, которые вы начали смотреть")
                else -> LazyColumn(contentPadding = PaddingValues(bottom = BottomBarSpace + 16.dp)) {
                    items(list, key = { it.movie.id }) { item ->
                        Column {
                            MovieListRow(
                                movie = item.movie,
                                onClick = { nav.details(item.movie.id) },
                                trailing = {
                                    IconButton(onClick = { vm.removeFromHistory(item.movie.id) }) {
                                        Icon(Icons.Rounded.Close, "Удалить из истории", tint = Palette.TextMuted)
                                    }
                                },
                            )
                            if (item.progress > 0f) {
                                Row(
                                    Modifier.padding(start = 94.dp, end = 64.dp, bottom = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    LinearProgressIndicator(
                                        progress = { item.progress },
                                        modifier = Modifier.weight(1f).height(3.dp).clip(RoundedCornerShape(2.dp)),
                                        color = Palette.Accent,
                                        trackColor = Palette.SurfaceHigh,
                                        drawStopIndicator = {},
                                    )
                                    Text(
                                        if (item.progress >= 0.97f) "  просмотрено" else "  ${Format.clock(item.positionSec)}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Palette.TextMuted,
                                        modifier = Modifier.padding(start = 4.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Очистить историю?") },
            text = { Text("Сохранённые позиции просмотра тоже будут удалены.") },
            confirmButton = { TextButton(onClick = { vm.clearHistory(); confirmClear = false }) { Text("Очистить") } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Отмена") } },
            containerColor = Palette.SurfaceHigh,
        )
    }
}
