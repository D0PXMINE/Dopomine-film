package com.dopomine.film.ui.details

import android.os.Build
import androidx.compose.animation.animateContentSize
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.dopomine.film.data.HistoryItem
import com.dopomine.film.data.model.ContentType
import com.dopomine.film.data.model.MovieDetails
import com.dopomine.film.data.model.Person
import com.dopomine.film.data.model.Season
import com.dopomine.film.ui.common.Launchers
import com.dopomine.film.ui.common.appViewModel
import com.dopomine.film.ui.components.ErrorState
import com.dopomine.film.ui.components.Format
import com.dopomine.film.ui.components.GradientButton
import com.dopomine.film.ui.components.LoadingBox
import com.dopomine.film.ui.components.MovieShelf
import com.dopomine.film.ui.components.Poster
import com.dopomine.film.ui.components.SectionHeader
import com.dopomine.film.ui.navigation.Nav
import com.dopomine.film.ui.theme.Palette

@Composable
fun DetailsScreen(nav: Nav) {
    val vm = appViewModel { c, h -> DetailsViewModel(c.movies, c.library, h) }
    val state by vm.state.collectAsStateWithLifecycle()
    val isFavorite by vm.isFavorite.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val details = state.details

    Box(Modifier.fillMaxSize()) {
        when {
            details != null -> DetailsContent(
                details = details,
                state = state,
                isFavorite = isFavorite,
                history = history,
                onWatch = { Launchers.openPlayer(context, details.movie) },
                onToggleFavorite = vm::toggleFavorite,
                onMovieClick = { nav.details(it.id) },
                onRetrySeasons = vm::loadSeasons,
            )
            state.error != null -> ErrorState(
                error = state.error!!,
                onRetry = vm::load,
                onOpenSettings = nav::settings,
                modifier = Modifier.align(Alignment.Center),
            )
            else -> LoadingBox()
        }
        DetailsTopBar(
            onBack = nav::back,
            onShare = details?.let { d -> { Launchers.share(context, d.movie) } },
            onCopyId = { Launchers.copyId(context, vm.id) },
        )
    }
}

@Composable
private fun DetailsTopBar(onBack: () -> Unit, onShare: (() -> Unit)?, onCopyId: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RoundIcon(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Назад", tint = Color.White) }
        Spacer(Modifier.weight(1f))
        if (onShare != null) {
            RoundIcon(onClick = onShare) { Icon(Icons.Rounded.Share, "Поделиться", tint = Color.White) }
            Spacer(Modifier.width(8.dp))
        }
        Box {
            RoundIcon(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "Ещё", tint = Color.White) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = Palette.SurfaceHigh) {
                DropdownMenuItem(
                    text = { Text("Скопировать ID Кинопоиска") },
                    leadingIcon = { Icon(Icons.Rounded.ContentCopy, null) },
                    onClick = { menu = false; onCopyId() },
                )
            }
        }
    }
}

@Composable
private fun RoundIcon(onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(Color(0x66000000))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun DetailsContent(
    details: MovieDetails,
    state: DetailsState,
    isFavorite: Boolean,
    history: HistoryItem?,
    onWatch: () -> Unit,
    onToggleFavorite: () -> Unit,
    onMovieClick: (com.dopomine.film.data.model.Movie) -> Unit,
    onRetrySeasons: () -> Unit,
) {
    val movie = details.movie
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
        item("header") { Header(details) }
        item("ratings") { RatingsCard(details, Modifier.padding(horizontal = 16.dp, vertical = 16.dp)) }
        item("actions") {
            Actions(
                history = history,
                isFavorite = isFavorite,
                onWatch = onWatch,
                onToggleFavorite = onToggleFavorite,
            )
        }
        val description = movie.description
        if (!description.isNullOrBlank() || details.slogan != null) {
            item("about") { About(details.slogan, description) }
        }
        item("info") { InfoTable(details) }
        if (movie.type == ContentType.SERIES) {
            item("seasons") {
                SeasonsBlock(
                    seasons = state.seasons,
                    loading = state.seasonsLoading,
                    failed = state.seasonsError != null,
                    onRetry = onRetrySeasons,
                )
            }
        }
        val cast = state.cast
        if (!cast.isNullOrEmpty()) {
            item("cast") { CastBlock(cast) }
        }
        val similars = state.similars
        if (similars == null || similars.isNotEmpty()) {
            item("similar") {
                MovieShelf(
                    title = "Похожие",
                    items = similars,
                    error = null,
                    onMovieClick = onMovieClick,
                    onSeeAll = null,
                    onRetry = {},
                    modifier = Modifier.padding(top = 16.dp),
                )
            }
        }
        item("bottom") { Spacer(Modifier.navigationBarsPadding()) }
    }
}

@Composable
private fun Header(details: MovieDetails) {
    val movie = details.movie
    Box(Modifier.fillMaxWidth()) {
        val backdrop = movie.coverUrl ?: details.posterFullUrl
        val blurPoster = movie.coverUrl == null
        AsyncImage(
            model = backdrop,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .height(320.dp)
                .then(if (blurPoster && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Modifier.blur(28.dp) else Modifier),
            alpha = if (blurPoster) 0.55f else 0.9f,
        )
        Box(
            Modifier
                .fillMaxWidth()
                .height(320.dp)
                .background(Brush.verticalGradient(listOf(Color(0x6609090F), Color(0x0009090F), Palette.Background))),
        )
        Row(
            Modifier
                .padding(top = 170.dp, start = 16.dp, end = 16.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.Bottom,
        ) {
            Poster(
                details.posterFullUrl ?: movie.posterUrl,
                Modifier
                    .width(118.dp)
                    .height(177.dp),
                corner = 14.dp,
                contentDescription = movie.title,
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(movie.title, style = MaterialTheme.typography.headlineSmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                movie.originalTitle?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = Palette.TextSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height(6.dp))
                val years = when {
                    movie.type == ContentType.SERIES && details.startYear != null ->
                        if (details.endYear != null && details.endYear != details.startYear) "${details.startYear}–${details.endYear}"
                        else if (details.completed == false) "${details.startYear}–…" else "${details.startYear}"
                    else -> movie.year?.toString()
                }
                val meta = listOfNotNull(years, Format.minutes(details.lengthMinutes), details.ageLimit).joinToString(" · ")
                if (meta.isNotBlank()) Text(meta, style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary)
                if (movie.genres.isNotEmpty()) {
                    Text(
                        movie.genres.take(3).joinToString(", ").replaceFirstChar { it.uppercase() },
                        style = MaterialTheme.typography.bodySmall,
                        color = Palette.TextSecondary,
                        maxLines = 2,
                    )
                }
            }
        }
    }
}

@Composable
private fun RatingsCard(details: MovieDetails, modifier: Modifier = Modifier) {
    val movie = details.movie
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Palette.Surface)
            .padding(vertical = 14.dp),
    ) {
        RatingBlock(
            value = movie.ratingKp,
            label = "Кинопоиск",
            votes = Format.votes(details.ratingKpVotes),
            color = Palette.kpRatingColor(movie.ratingKp),
            modifier = Modifier.weight(1f),
        )
        Box(Modifier.width(1.dp).height(56.dp).background(Palette.Outline).align(Alignment.CenterVertically))
        RatingBlock(
            value = movie.ratingImdb,
            label = "IMDb",
            votes = Format.votes(details.ratingImdbVotes),
            color = Palette.Imdb,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun RatingBlock(value: Double?, label: String, votes: String?, color: Color, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            Format.rating(value),
            fontSize = 30.sp,
            fontWeight = FontWeight.Black,
            color = if (value == null) Palette.TextMuted else color,
        )
        Text(label, style = MaterialTheme.typography.labelLarge, color = Palette.TextPrimary)
        Text(
            votes?.let { "$it оценок" } ?: if (value == null) "нет оценок" else " ",
            style = MaterialTheme.typography.bodySmall,
            color = Palette.TextMuted,
        )
    }
}

@Composable
private fun Actions(history: HistoryItem?, isFavorite: Boolean, onWatch: () -> Unit, onToggleFavorite: () -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val resume = history?.takeIf { it.positionSec > 60 && it.progress < 0.97f }
            GradientButton(
                text = if (resume != null) "Продолжить с ${Format.clock(resume.positionSec)}" else "Смотреть",
                icon = Icons.Rounded.PlayArrow,
                onClick = onWatch,
                modifier = Modifier.weight(1f),
                height = 54.dp,
            )
            Spacer(Modifier.width(10.dp))
            Box(
                Modifier
                    .size(54.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (isFavorite) Palette.Accent.copy(alpha = 0.18f) else Palette.Surface)
                    .clickable(onClick = onToggleFavorite),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (isFavorite) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder,
                    contentDescription = if (isFavorite) "Убрать из избранного" else "В избранное",
                    tint = if (isFavorite) Palette.Accent else Palette.TextPrimary,
                )
            }
        }
        if (history != null && history.progress > 0f && history.progress < 0.97f) {
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(
                progress = { history.progress },
                modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                color = Palette.Accent,
                trackColor = Palette.SurfaceHigh,
                drawStopIndicator = {},
            )
        }
    }
}

@Composable
private fun About(slogan: String?, description: String?) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column(
        Modifier
            .padding(horizontal = 16.dp)
            .padding(top = 20.dp)
            .animateContentSize(),
    ) {
        if (slogan != null) {
            Text("«${slogan.trim('«', '»', '"')}»", style = MaterialTheme.typography.bodyMedium, fontStyle = FontStyle.Italic, color = Palette.TextSecondary)
            Spacer(Modifier.height(8.dp))
        }
        if (!description.isNullOrBlank()) {
            Text(
                description,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = if (expanded) Int.MAX_VALUE else 5,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clickable { expanded = !expanded },
            )
            if (!expanded && description.length > 220) {
                Text(
                    "Читать полностью",
                    style = MaterialTheme.typography.labelLarge,
                    color = Palette.Accent,
                    modifier = Modifier.padding(top = 6.dp).clickable { expanded = true },
                )
            }
        }
    }
}

@Composable
private fun InfoTable(details: MovieDetails) {
    val movie = details.movie
    val rows = listOfNotNull(
        ("Тип" to Format.typeLabel(movie.type)),
        movie.countries.takeIf { it.isNotEmpty() }?.let { "Страна" to it.joinToString(", ") },
        movie.genres.takeIf { it.isNotEmpty() }?.let { "Жанр" to it.joinToString(", ").replaceFirstChar(Char::uppercase) },
        movie.year?.let { "Год" to it.toString() },
        Format.minutes(details.lengthMinutes)?.let { (if (movie.type == ContentType.SERIES) "Серия" else "Время") to it },
        details.ageLimit?.let { "Возраст" to it },
    )
    Column(
        Modifier
            .padding(horizontal = 16.dp)
            .padding(top = 20.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Palette.Surface)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        rows.forEach { (label, value) ->
            Row {
                Text(label, style = MaterialTheme.typography.bodyMedium, color = Palette.TextMuted, modifier = Modifier.width(88.dp))
                Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SeasonsBlock(seasons: List<Season>?, loading: Boolean, failed: Boolean, onRetry: () -> Unit) {
    var selected by rememberSaveable { mutableIntStateOf(0) }
    Column(Modifier.padding(top = 16.dp)) {
        val title = if (!seasons.isNullOrEmpty()) "Сезоны · ${Format.seasons(seasons.size)}" else "Сезоны"
        SectionHeader(title)
        when {
            loading -> Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(strokeWidth = 3.dp)
            }
            failed -> Text(
                "Не удалось загрузить список серий. Нажмите, чтобы повторить",
                style = MaterialTheme.typography.bodyMedium,
                color = Palette.TextSecondary,
                modifier = Modifier.padding(horizontal = 16.dp).clickable(onClick = onRetry),
            )
            seasons.isNullOrEmpty() -> Text(
                "Информации о сериях пока нет",
                style = MaterialTheme.typography.bodyMedium,
                color = Palette.TextMuted,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            else -> {
                val index = selected.coerceIn(0, seasons.lastIndex)
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    itemsIndexed(seasons, key = { _, s -> s.number }) { i, season ->
                        val active = i == index
                        Text(
                            "${season.number} сезон",
                            style = MaterialTheme.typography.labelLarge,
                            color = if (active) Color.White else Palette.TextPrimary,
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .then(if (active) Modifier.background(Palette.AccentGradient) else Modifier.background(Palette.Surface))
                                .clickable { selected = i }
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                        )
                    }
                }
                val season = seasons[index]
                Column(
                    Modifier
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(Palette.Surface),
                ) {
                    Text(
                        Format.episodes(season.episodes.size) + " · выбрать серию можно в плеере",
                        style = MaterialTheme.typography.bodySmall,
                        color = Palette.TextMuted,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
                    )
                    season.episodes.forEach { ep ->
                        Row(Modifier.padding(horizontal = 16.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                ep.number.toString(),
                                style = MaterialTheme.typography.titleSmall,
                                color = Palette.Accent,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.width(32.dp),
                            )
                            Column(Modifier.weight(1f)) {
                                Text(ep.title ?: "Серия ${ep.number}", style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                ep.releaseDate?.let {
                                    Text(it.take(10), style = MaterialTheme.typography.bodySmall, color = Palette.TextMuted)
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                }
            }
        }
    }
}

@Composable
private fun CastBlock(cast: List<Person>) {
    val directors = cast.filter { it.profession == "DIRECTOR" }.take(3)
    val actors = cast.filter { it.profession == "ACTOR" }.take(20)
    Column(Modifier.padding(top = 16.dp)) {
        if (directors.isNotEmpty()) {
            Text(
                (if (directors.size > 1) "Режиссёры: " else "Режиссёр: ") + directors.joinToString(", ") { it.name },
                style = MaterialTheme.typography.bodyMedium,
                color = Palette.TextSecondary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        if (actors.isNotEmpty()) {
            SectionHeader("В ролях")
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                items(actors) { person ->
                    Column(Modifier.width(84.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            Modifier
                                .size(72.dp)
                                .clip(CircleShape)
                                .background(Palette.SurfaceHigh),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Rounded.Person, null, tint = Palette.TextMuted)
                            AsyncImage(
                                model = person.photoUrl,
                                contentDescription = person.name,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(person.name, style = MaterialTheme.typography.labelMedium, maxLines = 2, textAlign = TextAlign.Center, overflow = TextOverflow.Ellipsis)
                        person.role?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = Palette.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                        }
                    }
                }
            }
        }
    }
}
