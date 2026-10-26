package com.dopomine.film.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.AlertDialog
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
import com.dopomine.film.ui.common.ContentCard
import com.dopomine.film.ui.common.appViewModel
import com.dopomine.film.ui.components.Caps
import com.dopomine.film.ui.components.EmptyHint
import com.dopomine.film.ui.components.Format
import com.dopomine.film.ui.components.IconBtn
import com.dopomine.film.ui.components.MovieCard
import com.dopomine.film.ui.components.MovieRow
import com.dopomine.film.ui.components.Segmented
import com.dopomine.film.ui.navigation.BottomBarSpace
import com.dopomine.film.ui.navigation.Nav
import com.dopomine.film.ui.theme.T
import com.dopomine.film.ui.theme.Type
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class LibraryViewModel(private val library: LibraryRepository) : ViewModel() {
    val favorites: StateFlow<List<Movie>?> = library.favorites.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val history: StateFlow<List<HistoryItem>?> = library.history.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

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
        Row(Modifier.statusBarsPadding().padding(start = 20.dp, end = 10.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Моё", style = Type.display, color = T.text, modifier = Modifier.weight(1f))
            IconBtn(Icons.Outlined.Settings, "Настройки", nav::settings)
        }
        ContentCard {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Segmented(
                        listOf("Избранное · ${favorites?.size ?: 0}", "История · ${history?.size ?: 0}"),
                        tab, { tab = it },
                    )
                    Box(Modifier.weight(1f))
                    if (tab == 1 && !history.isNullOrEmpty()) {
                        Text("очистить", style = Type.button, color = T.text3,
                            modifier = Modifier.clip(RoundedCornerShape(999.dp)).clickable { confirmClear = true }.padding(8.dp))
                    }
                }
                if (tab == 0) {
                    val list = favorites
                    when {
                        list == null -> Unit
                        list.isEmpty() -> EmptyHint("Здесь будут ваши фильмы", subtitle = "Нажмите «В избранное» на странице фильма или сериала", seed = 31)
                        else -> LazyVerticalGrid(
                            columns = GridCells.Adaptive(108.dp),
                            contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 4.dp, bottom = BottomBarSpace + 16.dp),
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
                        list.isEmpty() -> EmptyHint("История пуста", subtitle = "Здесь появится всё, что вы начали смотреть", seed = 37)
                        else -> LazyColumn(contentPadding = PaddingValues(bottom = BottomBarSpace + 16.dp)) {
                            item { Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) { Caps("название", Modifier.weight(1f)); Caps("прогресс") } }
                            items(list, key = { it.movie.id }) { item ->
                                Column {
                                    MovieRow(item.movie, onClick = { nav.details(item.movie.id) }, trailing = {
                                        IconBtn(Icons.Rounded.Close, "Удалить из истории", { vm.removeFromHistory(item.movie.id) })
                                    })
                                    if (item.progress > 0f) {
                                        Row(Modifier.padding(start = 84.dp, end = 64.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                            Box(Modifier.weight(1f).height(3.dp).clip(RoundedCornerShape(2.dp)).background(T.surface3)) {
                                                Box(Modifier.fillMaxWidth(item.progress).height(3.dp).background(T.text))
                                            }
                                            val label = when {
                                                item.progress >= 0.97f -> "просмотрено"
                                                item.lastSeason != null -> "${item.lastSeason}×${item.lastEpisode}"
                                                else -> Format.clock(item.positionSec)
                                            }
                                            Text("  $label", style = Type.monoSmall, color = T.text3)
                                        }
                                    }
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
            title = { Text("Очистить историю?", style = Type.subtitle) },
            text = { Text("Сохранённые позиции просмотра тоже будут удалены.", style = Type.mono, color = T.text2) },
            confirmButton = { TextButton(onClick = { vm.clearHistory(); confirmClear = false }) { Text("Очистить", style = Type.button, color = T.text) } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Отмена", style = Type.button, color = T.text3) } },
            containerColor = T.surface,
        )
    }
}
