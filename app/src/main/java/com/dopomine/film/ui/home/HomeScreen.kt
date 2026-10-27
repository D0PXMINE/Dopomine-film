package com.dopomine.film.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dopomine.film.data.HistoryItem
import com.dopomine.film.data.model.Movie
import com.dopomine.film.ui.common.ContentCard
import com.dopomine.film.ui.common.Launchers
import com.dopomine.film.ui.common.appViewModel
import com.dopomine.film.ui.components.Caps
import com.dopomine.film.ui.components.ContinueCard
import com.dopomine.film.ui.components.Format
import com.dopomine.film.ui.components.Halftone
import com.dopomine.film.ui.components.HalftoneVariant
import com.dopomine.film.ui.components.IconBtn
import com.dopomine.film.ui.components.Logo
import com.dopomine.film.ui.components.MovieShelf
import com.dopomine.film.ui.components.Pill
import com.dopomine.film.ui.components.Poster
import com.dopomine.film.ui.components.RatingTag
import com.dopomine.film.ui.components.SectionHeader
import com.dopomine.film.ui.components.Segmented
import com.dopomine.film.ui.navigation.BottomBarSpace
import com.dopomine.film.ui.navigation.Nav
import com.dopomine.film.ui.theme.Radius
import com.dopomine.film.ui.theme.T
import com.dopomine.film.ui.theme.Type

@Composable
fun HomeScreen(nav: Nav) {
    val vm = appViewModel { c, _ -> HomeViewModel(c.catalog, c.library) }
    val mode by vm.mode.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val sections = remember(mode) { vm.sections(mode) }
    val featured = sections.first()
    val lists = listOf(rememberLazyListState(), rememberLazyListState(), rememberLazyListState())

    LaunchedEffect(featured.key) { vm.load(featured) }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(start = 20.dp, end = 10.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Logo(Modifier.weight(1f))
            IconBtn(Icons.Outlined.Search, "Поиск", nav::search)
            IconBtn(Icons.Outlined.Settings, "Настройки", nav::settings)
        }
        ContentCard {
            LazyColumn(
                state = lists[mode.coerceIn(0, 2)],
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = BottomBarSpace + 8.dp),
            ) {
                item("hero") {
                    val shelf = vm.shelf(featured.key)
                    Hero(
                        mode = mode,
                        count = shelf.items?.size,
                        onMode = vm::setMode,
                        modifier = Modifier.padding(12.dp),
                    )
                }
                item("featured_$mode") {
                    Featured(
                        items = vm.shelf(featured.key).items,
                        title = featured.title,
                        onOpen = { nav.details(it.id) },
                        onWatch = { Launchers.openPlayer(context, it) },
                        onSeeAll = { nav.collection(featured.key) },
                    )
                }
                if (history.isNotEmpty()) {
                    item("continue") { ContinueShelf(history.take(15)) { Launchers.openPlayer(context, it.movie) } }
                }
                items(sections.drop(1), key = { it.key }) { section ->
                    LaunchedEffect(section.key) { vm.load(section) }
                    val shelf = vm.shelf(section.key)
                    MovieShelf(
                        title = section.title,
                        items = shelf.items,
                        error = shelf.error,
                        onMovieClick = { nav.details(it.id) },
                        onSeeAll = { nav.collection(section.key) },
                        onRetry = { vm.load(section, force = true) },
                        modifier = Modifier.padding(top = 14.dp),
                    )
                }
            }
        }
    }
}

private data class HeroText(val caps: String, val title: String, val text: String, val seed: Int)

private val heroes = listOf(
    HeroText("фильмы", "Популярное сейчас", "Каталог Кинопоиска с рейтингами КП и IMDb. Плеер без рекламы.", 7),
    HeroText("сериалы", "Сериалы недели", "Каталог Кинопоиска с рейтингами КП и IMDb. Плеер без рекламы.", 19),
    HeroText("аниме", "Аниме", "Озвучки AniLibria и AnimeVost, все сезоны, до 1080p. Без рекламы.", 31),
)

/** splicer's hero banner: caps line, big title, mono description, dots running off the right edge. */
@Composable
private fun Hero(mode: Int, count: Int?, onMode: (Int) -> Unit, modifier: Modifier = Modifier) {
    val hero = heroes[mode.coerceIn(0, heroes.lastIndex)]
    val shape = RoundedCornerShape(16.dp)
    Box(modifier.fillMaxWidth().heightIn(min = 190.dp).clip(shape).background(T.surface).border(1.dp, T.border, shape)) {
        Box(Modifier.matchParentSize()) {
            Halftone(
                HalftoneVariant.BANNER,
                Modifier.align(Alignment.CenterEnd).fillMaxHeight().fillMaxWidth(0.46f),
                seed = hero.seed,
                cell = 6.dp,
            )
        }
        Column(Modifier.padding(20.dp).fillMaxWidth(0.6f)) {
            Caps(listOfNotNull(hero.caps, count?.let { "в подборке $it" }).joinToString(" · "))
            Spacer(Modifier.height(6.dp))
            Text(hero.title, style = Type.display, color = T.text)
            Spacer(Modifier.height(6.dp))
            Text(hero.text, style = Type.mono, color = T.text3)
            Spacer(Modifier.height(14.dp))
            Segmented(listOf("Фильмы", "Сериалы", "Аниме"), mode, onMode, Modifier.fillMaxWidth(), fill = true)
        }
    }
}

@Composable
private fun Featured(items: List<Movie>?, title: String, onOpen: (Movie) -> Unit, onWatch: (Movie) -> Unit, onSeeAll: () -> Unit) {
    Column {
        SectionHeader(title, onSeeAll = if (!items.isNullOrEmpty()) onSeeAll else null)
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (items == null) {
                items(3) { Box(Modifier.width(168.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(Radius.cover)).background(T.surface2)) }
            } else {
                items(items.take(12), key = { it.id }) { movie ->
                    Column(Modifier.width(168.dp).clip(RoundedCornerShape(Radius.row)).clickable { onOpen(movie) }) {
                        Box {
                            Poster(movie.posterUrl, movie.id, Modifier.fillMaxWidth().aspectRatio(2f / 3f), contentDescription = movie.title)
                            RatingTag(movie.ratingKp, Modifier.padding(8.dp).align(Alignment.TopStart))
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(movie.title, style = Type.subtitle, color = T.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(Format.meta(movie), style = Type.monoSmall, color = T.text3, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(8.dp))
                        Pill("Смотреть", { onWatch(movie) }, icon = Icons.Rounded.PlayArrow, primary = true, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }
}

@Composable
private fun ContinueShelf(items: List<HistoryItem>, onClick: (HistoryItem) -> Unit) {
    Column(Modifier.padding(top = 14.dp)) {
        SectionHeader("Продолжить просмотр")
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(items, key = { it.movie.id }) { item -> ContinueCard(item, onClick = { onClick(item) }) }
        }
    }
}
