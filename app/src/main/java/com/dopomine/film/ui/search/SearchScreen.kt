package com.dopomine.film.ui.search

import androidx.compose.foundation.background
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dopomine.film.data.model.Movie
import com.dopomine.film.ui.common.Launchers
import com.dopomine.film.ui.common.appViewModel
import com.dopomine.film.ui.components.EmptyHint
import com.dopomine.film.ui.components.ErrorState
import com.dopomine.film.ui.components.MovieListRow
import com.dopomine.film.ui.navigation.BottomBarSpace
import com.dopomine.film.ui.navigation.Nav
import com.dopomine.film.ui.theme.Palette

@Composable
fun SearchScreen(nav: Nav) {
    val context = LocalContext.current
    val vm = appViewModel { c, _ -> SearchViewModel(c.movies, context) }
    val query by vm.query.collectAsStateWithLifecycle()
    val recent by vm.recent.collectAsStateWithLifecycle()
    val directId by vm.directId.collectAsStateWithLifecycle()
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val loader = vm.loader
    val listState = rememberLazyListState()

    LaunchedEffect(Unit) { if (query.isEmpty()) focus.requestFocus() }

    val shouldLoadMore by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= listState.layoutInfo.totalItemsCount - 5
        }
    }
    LaunchedEffect(Unit) {
        snapshotFlow { shouldLoadMore }.collect { if (it && loader.items.isNotEmpty()) loader.loadNext() }
    }

    Column(Modifier.fillMaxSize()) {
        TextField(
            value = query,
            onValueChange = vm::setQuery,
            modifier = Modifier
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .fillMaxWidth()
                .focusRequester(focus),
            placeholder = { Text("Фильм, сериал или ссылка Кинопоиска") },
            leadingIcon = { Icon(Icons.Rounded.Search, null) },
            trailingIcon = {
                if (query.isNotEmpty()) IconButton(onClick = { vm.setQuery("") }) { Icon(Icons.Rounded.Close, "Очистить") }
            },
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { vm.submit(); focusManager.clearFocus() }),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Palette.SurfaceHigh,
                unfocusedContainerColor = Palette.Surface,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                cursorColor = Palette.Accent,
            ),
        )

        val id = directId
        when {
            id != null -> DirectIdCard(
                id = id,
                onOpen = { nav.details(id) },
                onWatch = { Launchers.openPlayer(context, Movie(id = id, title = "Кинопоиск #$id")) },
            )
            query.trim().length < 2 -> RecentSearches(recent, onPick = { vm.setQuery(it); vm.submit() }, onClear = vm::clearRecent)
            loader.items.isEmpty() && loader.error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                ErrorState(loader.error!!, onRetry = loader::retry, onOpenSettings = nav::settings)
            }
            loader.items.isEmpty() && loader.loadedOnce && !loader.loading ->
                EmptyHint(Icons.Rounded.SearchOff, "Ничего не нашлось", subtitle = "Проверьте написание или попробуйте оригинальное название")
            loader.items.isEmpty() -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(strokeWidth = 3.dp)
            }
            else -> LazyColumn(state = listState, contentPadding = PaddingValues(bottom = BottomBarSpace)) {
                items(loader.items, key = { it.id }) { movie ->
                    MovieListRow(movie, onClick = { vm.submit(); nav.details(movie.id) })
                }
                if (loader.loading) {
                    item("loading") {
                        Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(strokeWidth = 3.dp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DirectIdCard(id: Int, onOpen: () -> Unit, onWatch: () -> Unit) {
    Row(
        Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Palette.Surface)
            .clickable(onClick = onOpen)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Link, null, tint = Palette.Accent)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("Кинопоиск ID $id", style = MaterialTheme.typography.titleMedium)
            Text("Открыть страницу фильма", style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary)
        }
        IconButton(onClick = onWatch) {
            Box(
                Modifier.size(40.dp).clip(RoundedCornerShape(50)).background(Palette.AccentGradient),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.PlayArrow, "Смотреть", tint = Color.White) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RecentSearches(recent: List<String>, onPick: (String) -> Unit, onClear: () -> Unit) {
    if (recent.isEmpty()) {
        EmptyHint(
            Icons.Rounded.Search,
            "Найдите любой фильм или сериал",
            subtitle = "Введите название или вставьте ссылку с Кинопоиска",
        )
        return
    }
    Column(Modifier.padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Недавние запросы", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = onClear) { Text("Очистить", color = Palette.TextSecondary) }
        }
        Spacer(Modifier.height(4.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            recent.forEach { q ->
                Row(
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .background(Palette.Surface)
                        .clickable { onPick(q) }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.History, null, tint = Palette.TextMuted, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(q, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}
