package com.dopomine.film.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dopomine.film.data.HistoryItem
import com.dopomine.film.data.model.ContentType
import com.dopomine.film.data.model.Movie
import com.dopomine.film.ui.common.Launchers
import com.dopomine.film.ui.common.appViewModel
import com.dopomine.film.ui.components.ContinueCard
import com.dopomine.film.ui.components.Format
import com.dopomine.film.ui.components.GradientButton
import com.dopomine.film.ui.components.ImdbBadge
import com.dopomine.film.ui.components.KpBadge
import com.dopomine.film.ui.components.Logo
import com.dopomine.film.ui.components.MovieShelf
import com.dopomine.film.ui.components.PillSwitch
import com.dopomine.film.ui.components.Poster
import com.dopomine.film.ui.components.SectionHeader
import com.dopomine.film.ui.components.SkeletonBox
import com.dopomine.film.ui.navigation.BottomBarSpace
import com.dopomine.film.ui.navigation.Nav
import com.dopomine.film.ui.theme.Palette
import kotlinx.coroutines.delay
import kotlin.math.absoluteValue

@Composable
fun HomeScreen(nav: Nav) {
    val vm = appViewModel { c, _ -> HomeViewModel(c.movies, c.library) }
    val mode by vm.mode.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val sections = remember(mode) { vm.sections(mode) }
    val heroSection = sections.first()
    val filmsState = rememberLazyListState()
    val seriesState = rememberLazyListState()

    LaunchedEffect(heroSection.key) { vm.load(heroSection) }

    LazyColumn(
        state = if (mode == 0) filmsState else seriesState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = BottomBarSpace),
    ) {
        item("top") {
            Row(
                Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(start = 16.dp, end = 4.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Logo(Modifier.weight(1f))
                IconButton(onClick = nav::search) { Icon(Icons.Rounded.Search, "Поиск") }
                IconButton(onClick = nav::settings) { Icon(Icons.Rounded.Settings, "Настройки") }
            }
        }
        item("switch") {
            PillSwitch(
                options = listOf("Фильмы", "Сериалы"),
                selected = mode,
                onSelect = vm::setMode,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
        item("hero_$mode") {
            val shelf = vm.shelf(heroSection.key)
            HeroCarousel(
                items = shelf.items?.take(8),
                onOpen = { nav.details(it.id) },
                onWatch = { Launchers.openPlayer(context, it) },
            )
        }
        if (history.isNotEmpty()) {
            item("continue") {
                ContinueShelf(history.take(15), onClick = { Launchers.openPlayer(context, it.movie) })
            }
        }
        items(sections, key = { it.key }) { section ->
            LaunchedEffect(section.key) { vm.load(section) }
            val shelf = vm.shelf(section.key)
            MovieShelf(
                title = section.title,
                items = shelf.items,
                error = shelf.error,
                onMovieClick = { nav.details(it.id) },
                onSeeAll = { nav.collection(section.key) },
                onRetry = { vm.load(section, force = true) },
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}

@Composable
private fun ContinueShelf(items: List<HistoryItem>, onClick: (HistoryItem) -> Unit) {
    Column(Modifier.padding(top = 20.dp)) {
        SectionHeader("Продолжить просмотр")
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(items, key = { it.movie.id }) { item -> ContinueCard(item, onClick = { onClick(item) }) }
        }
    }
}

@Composable
private fun HeroCarousel(items: List<Movie>?, onOpen: (Movie) -> Unit, onWatch: (Movie) -> Unit) {
    if (items == null) {
        SkeletonBox(
            Modifier
                .padding(horizontal = 28.dp)
                .fillMaxWidth()
                .aspectRatio(0.72f),
            corner = 24.dp,
        )
        return
    }
    if (items.isEmpty()) return
    val pager = rememberPagerState { items.size }
    AutoAdvance(pager)
    Column {
        HorizontalPager(
            state = pager,
            contentPadding = PaddingValues(horizontal = 28.dp),
            pageSpacing = 14.dp,
            key = { items[it].id },
        ) { page ->
            val movie = items[page]
            val offset = (pager.currentPage - page + pager.currentPageOffsetFraction).absoluteValue.coerceIn(0f, 1f)
            HeroCard(
                movie = movie,
                onOpen = { onOpen(movie) },
                onWatch = { onWatch(movie) },
                modifier = Modifier.graphicsLayer {
                    val scale = lerp(1f, 0.92f, offset)
                    scaleX = scale
                    scaleY = scale
                    alpha = lerp(1f, 0.6f, offset)
                },
            )
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
            horizontalArrangement = Arrangement.Center,
        ) {
            repeat(items.size) { i ->
                val active = i == pager.currentPage
                Box(
                    Modifier
                        .padding(horizontal = 3.dp)
                        .height(6.dp)
                        .width(if (active) 18.dp else 6.dp)
                        .clip(CircleShape)
                        .background(if (active) Palette.Accent else Palette.Outline),
                )
            }
        }
    }
}

@Composable
private fun AutoAdvance(pager: PagerState) {
    LaunchedEffect(pager.currentPage, pager.isScrollInProgress) {
        if (pager.isScrollInProgress || pager.pageCount < 2) return@LaunchedEffect
        delay(7_000)
        pager.animateScrollToPage((pager.currentPage + 1) % pager.pageCount)
    }
}

@Composable
private fun HeroCard(movie: Movie, onOpen: () -> Unit, onWatch: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .aspectRatio(0.72f)
            .clip(RoundedCornerShape(24.dp))
            .clickable(onClick = onOpen),
    ) {
        Poster(movie.posterLargeUrl ?: movie.posterUrl, Modifier.fillMaxSize(), corner = 24.dp, contentDescription = movie.title)
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.35f to Color.Transparent,
                        0.75f to Color(0xB309090F),
                        1f to Color(0xF209090F),
                    )
                ),
        )
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .padding(18.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                KpBadge(movie.ratingKp)
                ImdbBadge(movie.ratingImdb)
                if (movie.type == ContentType.SERIES) {
                    Text("Сериал", style = MaterialTheme.typography.labelSmall, color = Palette.TextSecondary)
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                movie.title,
                style = MaterialTheme.typography.headlineMedium,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val sub = Format.subtitle(movie, genres = 3)
            if (sub.isNotBlank()) {
                Text(sub, style = MaterialTheme.typography.bodyMedium, color = Palette.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                GradientButton(text = "Смотреть", icon = Icons.Rounded.PlayArrow, onClick = onWatch, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(10.dp))
                Box(
                    Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0x33FFFFFF))
                        .clickable(onClick = onOpen),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Outlined.Info, "Подробнее", tint = Color.White)
                }
            }
        }
    }
}
