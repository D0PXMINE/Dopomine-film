package com.dopomine.film.ui.catalog

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dopomine.film.data.catalog.Genres
import com.dopomine.film.data.catalog.Kind
import com.dopomine.film.data.catalog.Sort
import com.dopomine.film.data.catalog.YearRange
import com.dopomine.film.ui.common.ContentCard
import com.dopomine.film.ui.common.appViewModel
import com.dopomine.film.ui.components.Caps
import com.dopomine.film.ui.components.Chip
import com.dopomine.film.ui.components.Divider
import com.dopomine.film.ui.components.MovieGrid
import com.dopomine.film.ui.components.Pill
import com.dopomine.film.ui.components.Segmented
import com.dopomine.film.ui.navigation.BottomBarSpace
import com.dopomine.film.ui.navigation.Nav
import com.dopomine.film.ui.theme.T
import com.dopomine.film.ui.theme.Type

private enum class Sheet { SORT, YEARS, RATING }

private val kinds = listOf(Kind.FILMS, Kind.SERIES, Kind.ANIME, Kind.MINI, Kind.SHOWS)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CatalogScreen(nav: Nav) {
    val vm = appViewModel { c, _ -> CatalogViewModel(c.catalog) }
    val q by vm.query.collectAsStateWithLifecycle()
    var sheet by rememberSaveable { mutableStateOf<Sheet?>(null) }
    val grid = rememberLazyGridState()
    LaunchedEffect(q) { grid.scrollToItem(0) }

    Column(Modifier.fillMaxSize()) {
        Text("Каталог", style = Type.display, color = T.text, modifier = Modifier.statusBarsPadding().padding(start = 20.dp, top = 10.dp, bottom = 10.dp))
        ContentCard {
            Column(Modifier.fillMaxSize()) {
                Column(Modifier.padding(top = 14.dp)) {
                    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp)) {
                        Segmented(kinds.map { it.label }, kinds.indexOf(q.kind).coerceAtLeast(0), { i -> vm.update { it.copy(kind = kinds[i]) } })
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Pill(q.sort.label, { sheet = Sheet.SORT }, icon = Icons.Rounded.SwapVert, trailing = Icons.Rounded.KeyboardArrowDown)
                        val years = YearRange.options.firstOrNull { it.from == q.yearFrom && it.to == q.yearTo } ?: YearRange.options.first()
                        Pill(years.label, { sheet = Sheet.YEARS }, icon = Icons.Outlined.CalendarMonth, trailing = Icons.Rounded.KeyboardArrowDown, primary = q.yearFrom != null)
                        Pill(q.minRating?.let { "КП ${it.toInt()}+" } ?: "Рейтинг", { sheet = Sheet.RATING }, icon = Icons.Outlined.StarOutline,
                            trailing = Icons.Rounded.KeyboardArrowDown, primary = q.minRating != null)
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Chip("все жанры", on = q.genre == null && q.animated == null) { vm.update { it.copy(genre = null, animated = null) } }
                        Genres.filterable.forEach { g ->
                            Chip(g.lowercase(), on = q.genre == g) { vm.update { it.copy(genre = if (it.genre == g) null else g, animated = null) } }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Divider()
                }
                MovieGrid(
                    loader = vm.loader,
                    onMovieClick = { nav.details(it.id) },
                    state = grid,
                    contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 12.dp, bottom = BottomBarSpace + 16.dp),
                )
            }
        }
    }

    val current = sheet
    if (current != null) {
        ModalBottomSheet(
            onDismissRequest = { sheet = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = T.surface,
        ) {
            Column(Modifier.navigationBarsPadding().padding(bottom = 12.dp)) {
                when (current) {
                    Sheet.SORT -> Options("Сортировка", Sort.entries.map { it.label }, q.sort.label) { l ->
                        vm.update { it.copy(sort = Sort.entries.first { s -> s.label == l }) }
                        sheet = null
                    }
                    Sheet.YEARS -> {
                        val selected = YearRange.options.firstOrNull { it.from == q.yearFrom && it.to == q.yearTo }?.label ?: YearRange.options.first().label
                        Options("Годы выхода", YearRange.options.map { it.label }, selected) { l ->
                            val r = YearRange.options.first { it.label == l }
                            vm.update { it.copy(yearFrom = r.from, yearTo = r.to) }
                            sheet = null
                        }
                    }
                    Sheet.RATING -> Options("Рейтинг Кинопоиска", listOf("Любой", "6+", "7+", "8+"), q.minRating?.let { "${it.toInt()}+" } ?: "Любой") { l ->
                        vm.update { it.copy(minRating = l.removeSuffix("+").toDoubleOrNull()) }
                        sheet = null
                    }
                }
            }
        }
    }
}

@Composable
private fun Options(title: String, options: List<String>, selected: String, onPick: (String) -> Unit) {
    Caps(title, Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
    options.forEach { o ->
        val active = o == selected
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp).clip(RoundedCornerShape(12.dp))
                .clickable { onPick(o) }
                .padding(horizontal = 12.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(o, style = Type.item, color = T.text, modifier = Modifier.weight(1f))
            if (active) Icon(Icons.Rounded.Check, null, tint = T.text, modifier = Modifier.size(20.dp))
        }
    }
}

