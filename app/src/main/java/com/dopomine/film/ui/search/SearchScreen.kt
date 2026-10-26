package com.dopomine.film.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dopomine.film.data.model.Movie
import com.dopomine.film.ui.common.ContentCard
import com.dopomine.film.ui.common.Launchers
import com.dopomine.film.ui.common.appViewModel
import com.dopomine.film.ui.components.Caps
import com.dopomine.film.ui.components.Chip
import com.dopomine.film.ui.components.EmptyHint
import com.dopomine.film.ui.components.MovieRow
import com.dopomine.film.ui.components.Pill
import com.dopomine.film.ui.navigation.BottomBarSpace
import com.dopomine.film.ui.navigation.Nav
import com.dopomine.film.ui.theme.T
import com.dopomine.film.ui.theme.Type

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SearchScreen(nav: Nav) {
    val context = LocalContext.current
    val vm = appViewModel { c, _ -> SearchViewModel(c.catalog, context) }
    val query by vm.query.collectAsStateWithLifecycle()
    val recent by vm.recent.collectAsStateWithLifecycle()
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val loader = vm.loader
    val list = rememberLazyListState()

    LaunchedEffect(Unit) { if (query.isEmpty()) focus.requestFocus() }
    val nearEnd by remember { derivedStateOf { (list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= list.layoutInfo.totalItemsCount - 6 } }
    LaunchedEffect(Unit) { snapshotFlow { nearEnd }.collect { if (it && loader.items.isNotEmpty()) loader.loadNext() } }

    Column(Modifier.fillMaxSize()) {
        // splicer's search field: a big rounded pill with a magnifier and a mono placeholder
        Row(
            Modifier.statusBarsPadding().padding(horizontal = 12.dp, vertical = 10.dp).fillMaxWidth().height(50.dp)
                .clip(RoundedCornerShape(999.dp)).background(T.surface).border(1.dp, T.border, RoundedCornerShape(999.dp))
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Search, null, tint = T.text3, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) Text("Фильм, сериал или ссылка Кинопоиска…", style = Type.mono, color = T.text3)
                BasicTextField(
                    value = query,
                    onValueChange = vm::setQuery,
                    singleLine = true,
                    textStyle = Type.item.copy(color = T.text),
                    cursorBrush = SolidColor(T.text),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { vm.submit(); focusManager.clearFocus() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
            }
            if (query.isNotEmpty()) {
                Icon(Icons.Rounded.Close, "Очистить", tint = T.text3, modifier = Modifier.size(20.dp).clip(RoundedCornerShape(999.dp)).clickable { vm.setQuery("") })
            }
        }
        ContentCard {
            val id = vm.directId
            when {
                id != null -> DirectId(id, onOpen = { nav.details(id) }, onWatch = { Launchers.openPlayer(context, Movie(id = id, title = "Кинопоиск #$id")) })
                query.trim().length < 2 -> Column(Modifier.padding(16.dp)) {
                    if (recent.isEmpty()) {
                        EmptyHint("Найдите любой фильм или сериал", subtitle = "Название по-русски или в оригинале, ссылка с Кинопоиска или просто ID")
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Caps("недавние запросы", Modifier.weight(1f))
                            Text("очистить", style = Type.button, color = T.text3, modifier = Modifier.clickable(onClick = vm::clearRecent).padding(6.dp))
                        }
                        Spacer(Modifier.height(10.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            recent.forEach { q -> Chip(q) { vm.setQuery(q); vm.submit() } }
                        }
                    }
                }
                else -> LazyColumn(state = list, contentPadding = PaddingValues(top = 8.dp, bottom = BottomBarSpace + 16.dp)) {
                    item("head") {
                        Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                            Caps("название", Modifier.weight(1f))
                            Caps("кп / imdb")
                        }
                    }
                    items(loader.items, key = { it.id }) { movie -> MovieRow(movie, onClick = { vm.submit(); nav.details(movie.id) }) }
                    if (loader.loading) item("loading") { Spinner() }
                    if (!loader.loading && loader.loadedOnce) {
                        item("online") {
                            OnlineBlock(
                                empty = loader.items.isEmpty(),
                                results = vm.online,
                                loading = vm.onlineLoading,
                                onSearch = vm::searchOnline,
                                onOpen = { nav.details(it.id) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Spinner() {
    Box(Modifier.fillMaxWidth().padding(18.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(strokeWidth = 2.dp, color = T.text, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun OnlineBlock(empty: Boolean, results: List<Movie>?, loading: Boolean, onSearch: () -> Unit, onOpen: (Movie) -> Unit) {
    Column(Modifier.padding(top = 10.dp)) {
        when {
            loading -> Spinner()
            results == null -> Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                if (empty) Text("В каталоге ничего не нашлось", style = Type.subtitle, color = T.text)
                Text("Новые и редкие фильмы можно поискать в Wikidata", style = Type.mono, color = T.text3)
                Spacer(Modifier.height(10.dp))
                Pill("Искать в интернете", onSearch, icon = Icons.Outlined.Search)
            }
            results.isEmpty() -> Text("И в интернете ничего", style = Type.mono, color = T.text3, modifier = Modifier.padding(16.dp))
            else -> {
                Caps("найдено в интернете", Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
                results.forEach { MovieRow(it, onClick = { onOpen(it) }) }
            }
        }
    }
}

@Composable
private fun DirectId(id: Int, onOpen: () -> Unit, onWatch: () -> Unit) {
    Row(
        Modifier.padding(16.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).border(1.dp, T.border, RoundedCornerShape(16.dp))
            .clickable(onClick = onOpen).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Link, null, tint = T.text)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("Кинопоиск · $id", style = Type.subtitle, color = T.text)
            Text("Открыть страницу фильма", style = Type.mono, color = T.text3)
        }
        Pill("Смотреть", onWatch, icon = Icons.Rounded.PlayArrow, primary = true)
    }
}
