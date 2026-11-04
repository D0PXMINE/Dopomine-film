package com.dopomine.film.ui.details

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dopomine.film.data.HistoryItem
import com.dopomine.film.data.model.Description
import com.dopomine.film.data.model.Movie
import com.dopomine.film.data.model.People
import com.dopomine.film.ui.common.ContentCard
import com.dopomine.film.ui.common.Launchers
import com.dopomine.film.ui.common.appViewModel
import com.dopomine.film.ui.components.Caps
import com.dopomine.film.ui.components.Chip
import com.dopomine.film.ui.components.Divider
import com.dopomine.film.ui.components.ErrorState
import com.dopomine.film.ui.components.Format
import com.dopomine.film.ui.components.Halftone
import com.dopomine.film.ui.components.HalftoneVariant
import com.dopomine.film.ui.components.IconBtn
import com.dopomine.film.ui.components.LoadingBox
import com.dopomine.film.ui.components.MovieShelf
import com.dopomine.film.ui.components.Pill
import com.dopomine.film.ui.components.Poster
import com.dopomine.film.ui.components.TopBar
import com.dopomine.film.ui.navigation.Nav
import com.dopomine.film.ui.theme.T
import com.dopomine.film.ui.theme.Type

@Composable
fun DetailsScreen(nav: Nav) {
    val vm = appViewModel { c, h -> DetailsViewModel(c.catalog, c.library, h) }
    val state by vm.state.collectAsStateWithLifecycle()
    val isFavorite by vm.isFavorite.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val movie = state.movie

    Column(Modifier.fillMaxSize()) {
        TopBar(title = "", onBack = nav::back) {
            if (movie != null) {
                IconBtn(Icons.Outlined.Share, "Поделиться", { Launchers.share(context, movie) })
                IconBtn(Icons.Outlined.ContentCopy, "Скопировать ID", { Launchers.copyId(context, vm.id) })
            }
        }
        ContentCard {
            when {
                movie != null -> Content(
                    movie = movie,
                    description = state.description,
                    descriptionDone = state.descriptionDone,
                    people = state.people,
                    similar = state.similar,
                    isFavorite = isFavorite,
                    history = history,
                    onWatch = { Launchers.openPlayer(context, movie) },
                    onFavorite = vm::toggleFavorite,
                    onMovie = { nav.details(it.id) },
                )
                state.error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { ErrorState(state.error!!, onRetry = vm::load) }
                else -> LoadingBox()
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Content(
    movie: Movie,
    description: Description?,
    descriptionDone: Boolean,
    people: People,
    similar: List<Movie>?,
    isFavorite: Boolean,
    history: HistoryItem?,
    onWatch: () -> Unit,
    onFavorite: () -> Unit,
    onMovie: (Movie) -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item("header") {
            Box {
                // dotted art behind the header, fading to the right like splicer's banner
                Halftone(HalftoneVariant.BANNER, Modifier.fillMaxWidth().height(250.dp), seed = movie.id % 1000, cell = 7.dp, color = T.surface3)
                Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 18.dp), verticalAlignment = Alignment.Bottom) {
                    Poster(movie.posterLargeUrl, movie.id, Modifier.width(132.dp).aspectRatio(2f / 3f), contentDescription = movie.title)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f).padding(bottom = 4.dp)) {
                        Caps(listOfNotNull(Format.typeLabel(movie.type), years(movie)).joinToString(" · "))
                        Spacer(Modifier.height(6.dp))
                        Text(movie.title, style = Type.display, color = T.text, maxLines = 4, overflow = TextOverflow.Ellipsis)
                        movie.originalTitle?.let { Text(it, style = Type.mono, color = T.text3, maxLines = 2) }
                        Spacer(Modifier.height(6.dp))
                        val meta = listOfNotNull(
                            Format.minutes(movie.duration),
                            movie.seasons?.let { Format.seasons(it) },
                            movie.countries.firstOrNull(),
                        ).joinToString(" · ")
                        if (meta.isNotBlank()) Text(meta, style = Type.mono, color = T.text2)
                    }
                }
            }
        }
        item("ratings") { Ratings(movie) }
        item("actions") {
            Column(Modifier.padding(horizontal = 16.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val resume = history?.takeIf { it.positionSec > 60 && it.progress < 0.97f }
                    val label = when {
                        history?.lastSeason != null && history.lastEpisode != null -> "Продолжить · ${history.lastSeason}×${history.lastEpisode}"
                        resume != null -> "Продолжить · ${Format.clock(resume.positionSec)}"
                        else -> "Смотреть"
                    }
                    Pill(label, onWatch, icon = Icons.Rounded.PlayArrow, primary = true, large = true, modifier = Modifier.weight(1f))
                    Pill(if (isFavorite) "В избранном" else "В избранное", onFavorite,
                        icon = if (isFavorite) Icons.Outlined.Bookmark else Icons.Outlined.BookmarkBorder, large = true)
                }
                if (history != null && history.progress in 0.01f..0.97f) {
                    Spacer(Modifier.height(10.dp))
                    Box(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(T.surface3)) {
                        Box(Modifier.fillMaxWidth(history.progress).height(3.dp).background(T.text))
                    }
                }
            }
        }
        if (movie.genres.isNotEmpty()) {
            item("genres") {
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    movie.genres.forEach { Chip(it.lowercase()) {} }
                }
            }
        }
        item("about") { About(description, descriptionDone) }
        item("facts") { Facts(movie) }
        val cast = people.cast
        val directors = people.directors
        if (cast.isNotEmpty() || directors.isNotEmpty()) {
            item("people") {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    if (directors.isNotEmpty()) {
                        Caps(if (directors.size > 1) "режиссёры" else "режиссёр")
                        Text(directors.joinToString(", "), style = Type.item, color = T.text, modifier = Modifier.padding(top = 4.dp, bottom = 12.dp))
                    }
                    if (cast.isNotEmpty()) {
                        Caps("в ролях")
                        Spacer(Modifier.height(8.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            cast.forEach { Chip(it) {} }
                        }
                    }
                }
            }
        }
        if (similar == null || similar.isNotEmpty()) {
            item("similar") {
                MovieShelf("Похожие", similar, null, onMovie, null, {}, Modifier.padding(top = 12.dp))
            }
        }
        item("bottom") { Spacer(Modifier.navigationBarsPadding()) }
    }
}

private fun years(movie: Movie): String? = when {
    movie.year == null -> null
    movie.type.isSeries && movie.endYear != null && movie.endYear != movie.year -> "${movie.year}–${movie.endYear}"
    else -> movie.year.toString()
}

/** Two stat blocks, mono like splicer's metadata. */
@Composable
private fun Ratings(movie: Movie) {
    Row(
        Modifier.padding(16.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).border(1.dp, T.border, RoundedCornerShape(16.dp)),
    ) {
        Stat(Format.rating(movie.ratingKp), "Кинопоиск", Format.votes(movie.votesKp), Modifier.weight(1f))
        Box(Modifier.width(1.dp).height(72.dp).background(T.border))
        Stat(Format.rating(movie.ratingImdb), "IMDb", Format.votes(movie.votesImdb), Modifier.weight(1f))
    }
}

@Composable
private fun Stat(value: String, label: String, votes: String?, modifier: Modifier) {
    Column(modifier.padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = Type.display, color = if (value == "—") T.text3 else T.text)
        Caps(label)
        Text(votes?.let { "$it оценок" } ?: " ", style = Type.monoSmall, color = T.text3)
    }
}

@Composable
private fun About(description: Description?, done: Boolean) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val text = description?.text
    Column(Modifier.padding(horizontal = 16.dp).animateContentSize()) {
        when {
            text == null && !done -> Text("Загружаем описание…", style = Type.mono, color = T.text3)
            text == null -> Unit
            else -> {
                Text(
                    text, style = Type.body, color = T.text,
                    maxLines = if (expanded) Int.MAX_VALUE else 6, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clickable { expanded = !expanded },
                )
                if (!expanded && text.length > 300) {
                    Text("читать полностью", style = Type.button, color = T.text2, modifier = Modifier.padding(top = 6.dp).clickable { expanded = true })
                }
                Text("по материалам ${description?.source ?: "Википедии"}", style = Type.monoSmall, color = T.text3, modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
}

@Composable
private fun Facts(movie: Movie) {
    val rows = listOfNotNull(
        movie.countries.takeIf { it.isNotEmpty() }?.let { "страна" to it.joinToString(", ") },
        movie.year?.let { "год" to (years(movie) ?: it.toString()) },
        Format.minutes(movie.duration)?.let { (if (movie.type.isSeries) "серия" else "время") to it },
        movie.seasons?.let { "сезоны" to Format.seasons(it) + (movie.episodes?.let { e -> " · " + Format.episodes(e) } ?: "") },
        "кинопоиск" to "ID ${movie.id}",
        movie.imdbId?.let { "imdb" to it },
    )
    Column(Modifier.padding(16.dp)) {
        Divider()
        rows.forEach { (label, value) ->
            Row(Modifier.padding(vertical = 9.dp)) {
                Caps(label, Modifier.width(100.dp).padding(top = 2.dp))
                Text(value, style = Type.item, color = T.text, modifier = Modifier.weight(1f))
            }
            Divider()
        }
    }
}
