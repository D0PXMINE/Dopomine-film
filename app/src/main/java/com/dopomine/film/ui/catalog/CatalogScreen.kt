package com.dopomine.film.ui.catalog

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dopomine.film.data.model.Genres
import com.dopomine.film.data.model.SortOrder
import com.dopomine.film.data.model.YearRange
import com.dopomine.film.ui.common.appViewModel
import com.dopomine.film.ui.components.MovieGrid
import com.dopomine.film.ui.components.PillSwitch
import com.dopomine.film.ui.navigation.BottomBarSpace
import com.dopomine.film.ui.navigation.Nav
import com.dopomine.film.ui.theme.Palette

private enum class Sheet { SORT, GENRE, YEARS, RATING, KIND }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CatalogScreen(nav: Nav) {
    val vm = appViewModel { c, _ -> CatalogViewModel(c.movies) }
    val filters by vm.filters.collectAsStateWithLifecycle()
    var sheet by rememberSaveable { mutableStateOf<Sheet?>(null) }
    val gridState = rememberLazyGridState()

    LaunchedEffect(filters) { gridState.scrollToItem(0) }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.statusBarsPadding().padding(top = 8.dp)) {
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Каталог", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                PillSwitch(
                    options = listOf("Фильмы", "Сериалы"),
                    selected = if (filters.isSeries) 1 else 0,
                    onSelect = { vm.setSeries(it == 1) },
                )
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (filters.isSeries) {
                    FilterChipButton(filters.kind.label, active = filters.kind != CatalogKind.SERIES) { sheet = Sheet.KIND }
                }
                FilterChipButton(filters.sort.label, active = filters.sort != SortOrder.POPULAR) { sheet = Sheet.SORT }
                FilterChipButton(filters.genre?.second ?: "Жанр", active = filters.genre != null) { sheet = Sheet.GENRE }
                FilterChipButton(filters.years.label, active = filters.years.from != null) { sheet = Sheet.YEARS }
                FilterChipButton(filters.minRating?.let { "Рейтинг $it+" } ?: "Рейтинг", active = filters.minRating != null) { sheet = Sheet.RATING }
            }
        }
        MovieGrid(
            loader = vm.loader,
            onMovieClick = { nav.details(it.id) },
            onOpenSettings = nav::settings,
            state = gridState,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = BottomBarSpace + 16.dp),
        )
    }

    val current = sheet
    if (current != null) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { sheet = null },
            sheetState = sheetState,
            containerColor = Palette.Surface,
        ) {
            val close = { sheet = null }
            when (current) {
                Sheet.KIND -> OptionList(
                    title = "Тип",
                    options = CatalogKind.entries.filter { it.series }.map { it.label },
                    selected = filters.kind.label,
                ) { label -> vm.update { f -> f.copy(kind = CatalogKind.entries.first { it.label == label }) }; close() }
                Sheet.SORT -> OptionList(
                    title = "Сортировка",
                    options = SortOrder.entries.map { it.label },
                    selected = filters.sort.label,
                ) { label -> vm.update { f -> f.copy(sort = SortOrder.entries.first { it.label == label }) }; close() }
                Sheet.YEARS -> OptionList(
                    title = "Годы выхода",
                    options = YearRange.options.map { it.label },
                    selected = filters.years.label,
                ) { label -> vm.update { f -> f.copy(years = YearRange.options.first { it.label == label }) }; close() }
                Sheet.RATING -> OptionList(
                    title = "Рейтинг Кинопоиска",
                    options = listOf("Любой", "6+", "7+", "8+"),
                    selected = filters.minRating?.let { "$it+" } ?: "Любой",
                ) { label -> vm.update { f -> f.copy(minRating = label.removeSuffix("+").toIntOrNull()) }; close() }
                Sheet.GENRE -> GenreGrid(selected = filters.genre?.first) { genre -> vm.update { f -> f.copy(genre = genre) }; close() }
            }
        }
    }
}

@Composable
private fun FilterChipButton(label: String, active: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(if (active) Palette.Accent.copy(alpha = 0.18f) else Palette.Surface)
            .clickable(onClick = onClick)
            .padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = if (active) Palette.Accent else Palette.TextPrimary)
        Icon(Icons.Rounded.KeyboardArrowDown, null, tint = if (active) Palette.Accent else Palette.TextSecondary, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun OptionList(title: String, options: List<String>, selected: String, onSelect: (String) -> Unit) {
    Column(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
        options.forEach { option ->
            val active = option == selected
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(option) }
                    .padding(horizontal = 24.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(option, style = MaterialTheme.typography.bodyLarge, color = if (active) Palette.Accent else Palette.TextPrimary, modifier = Modifier.weight(1f))
                if (active) Box(Modifier.size(8.dp).clip(RoundedCornerShape(50)).background(Palette.Accent))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GenreGrid(selected: Int?, onSelect: (Pair<Int, String>?) -> Unit) {
    Column(
        Modifier
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 20.dp),
    ) {
        Text("Жанр", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 4.dp, bottom = 12.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            GenreChip("Все жанры", selected == null) { onSelect(null) }
            Genres.all.forEach { genre -> GenreChip(genre.second, selected == genre.first) { onSelect(genre) } }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun GenreChip(label: String, active: Boolean, onClick: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        color = if (active) Color.White else Palette.TextPrimary,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .then(if (active) Modifier.background(Palette.AccentGradient) else Modifier.background(Palette.SurfaceHigh))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
    )
}

