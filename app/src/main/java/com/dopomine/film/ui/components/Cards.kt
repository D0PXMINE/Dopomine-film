package com.dopomine.film.ui.components

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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.SubcomposeAsyncImage
import com.dopomine.film.data.AppException
import com.dopomine.film.data.HistoryItem
import com.dopomine.film.data.model.Movie
import com.dopomine.film.ui.theme.Radius
import com.dopomine.film.ui.theme.T
import com.dopomine.film.ui.theme.Type

/** Poster with a halftone placeholder while loading or when Kinopoisk has no image. */
@Composable
fun Poster(url: String?, seed: Int, modifier: Modifier = Modifier, corner: Dp = Radius.cover, contentDescription: String? = null) {
    val shape = RoundedCornerShape(corner)
    Box(modifier.clip(shape).background(T.surface2).border(1.dp, T.border, shape)) {
        SubcomposeAsyncImage(
            model = url,
            contentDescription = contentDescription,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
            loading = { Halftone(HalftoneVariant.GLYPH, Modifier.fillMaxSize(), seed = seed, cell = 6.dp, animated = false, color = T.borderStrong) },
            error = { Halftone(HalftoneVariant.CLOUD, Modifier.fillMaxSize(), seed = seed, cell = 6.dp, animated = false, color = T.text3) },
        )
    }
}

/** Kinopoisk rating in the corner of a poster, in the style of splicer's cover labels. */
@Composable
fun RatingTag(rating: Double?, modifier: Modifier = Modifier) {
    if (rating == null) return
    Text(
        Format.rating(rating),
        style = Type.monoSmall,
        color = Color.White,
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xD90A0A0A))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
fun MovieCard(movie: Movie, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.clip(RoundedCornerShape(Radius.row)).clickable(onClick = onClick)) {
        Box {
            Poster(movie.posterUrl, movie.id, Modifier.fillMaxWidth().aspectRatio(2f / 3f), contentDescription = movie.title)
            RatingTag(movie.ratingKp, Modifier.padding(6.dp).align(Alignment.TopStart))
            if (movie.type.isSeries) {
                Text(
                    "сериал",
                    style = Type.monoSmall,
                    color = T.text,
                    modifier = Modifier.align(Alignment.BottomStart).padding(6.dp)
                        .clip(RoundedCornerShape(6.dp)).background(T.surface).padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
        Spacer(Modifier.height(7.dp))
        Text(movie.title, style = Type.item, color = T.text, maxLines = 2, minLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 2.dp))
        Text(
            listOfNotNull(movie.year?.toString(), movie.ratingImdb?.let { "IMDb ${Format.rating(it)}" }).joinToString(" · "),
            style = Type.monoSmall, color = T.text3, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 2.dp, vertical = 2.dp),
        )
    }
}

@Composable
fun MovieCardSkeleton(modifier: Modifier = Modifier) {
    Column(modifier) {
        Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(Radius.cover)).background(T.surface2))
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth(0.85f).height(11.dp).clip(RoundedCornerShape(4.dp)).background(T.surface2))
        Spacer(Modifier.height(6.dp))
        Box(Modifier.fillMaxWidth(0.5f).height(10.dp).clip(RoundedCornerShape(4.dp)).background(T.surface2))
    }
}

/** List row in the style of splicer's sample rows: cover, title, mono tag line, ratings on the right. */
@Composable
fun MovieRow(movie: Movie, onClick: () -> Unit, modifier: Modifier = Modifier, trailing: @Composable (() -> Unit)? = null) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = 8.dp).clip(RoundedCornerShape(Radius.row))
            .clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Poster(movie.posterUrl, movie.id, Modifier.width(48.dp).aspectRatio(2f / 3f), corner = 7.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(movie.title, style = Type.item, color = T.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Spacer(Modifier.width(6.dp))
                Text(Format.typeLabel(movie.type), style = Type.monoSmall, color = T.text3, maxLines = 1)
            }
            val sub = listOfNotNull(movie.originalTitle, Format.meta(movie).ifBlank { null }).joinToString(" · ")
            if (sub.isNotBlank()) Text(sub, style = Type.monoSmall, color = T.text3, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(10.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(movie.ratingKp?.let { Format.rating(it) } ?: "—", style = Type.mono, color = T.text)
            Text(movie.ratingImdb?.let { "IMDb ${Format.rating(it)}" } ?: " ", style = Type.monoSmall, color = T.text3)
        }
        trailing?.invoke()
    }
}

@Composable
fun ContinueCard(item: HistoryItem, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.width(280.dp).clip(RoundedCornerShape(Radius.card)).background(T.surface)
            .border(1.dp, T.border, RoundedCornerShape(Radius.card)).clickable(onClick = onClick).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Poster(item.movie.posterUrl, item.movie.id, Modifier.width(54.dp).aspectRatio(2f / 3f), corner = 7.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(item.movie.title, style = Type.item, color = T.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val line = when {
                item.lastSeason != null && item.lastEpisode != null -> "${item.lastSeason} сезон · ${item.lastEpisode} серия"
                item.durationSec > 0 && item.positionSec in 1 until item.durationSec -> "осталось ${Format.clock(item.durationSec - item.positionSec)}"
                else -> Format.meta(item.movie, 1)
            }
            Text(line, style = Type.monoSmall, color = T.text3, maxLines = 1)
            Spacer(Modifier.height(8.dp))
            Box(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(T.surface3)) {
                Box(Modifier.fillMaxWidth(item.progress).height(3.dp).background(T.text))
            }
        }
        Spacer(Modifier.width(10.dp))
        Box(Modifier.size(36.dp).clip(RoundedCornerShape(999.dp)).background(T.accent), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.PlayArrow, "Продолжить", tint = T.accentFg, modifier = Modifier.size(22.dp))
        }
    }
}

/** A horizontal shelf of posters. */
@Composable
fun MovieShelf(
    title: String,
    items: List<Movie>?,
    error: AppException?,
    onMovieClick: (Movie) -> Unit,
    onSeeAll: (() -> Unit)?,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    caption: String? = null,
) {
    if (items != null && items.isEmpty() && error == null) return
    Column(modifier.fillMaxWidth()) {
        SectionHeader(title, caption = caption, onSeeAll = if (!items.isNullOrEmpty()) onSeeAll else null)
        when {
            error != null && items.isNullOrEmpty() -> Row(
                Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp))
                    .border(1.dp, T.border, RoundedCornerShape(14.dp)).clickable(onClick = onRetry).padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(error.userMessage, style = Type.mono, color = T.text2, modifier = Modifier.weight(1f))
                Text("повторить", style = Type.button, color = T.text)
            }
            items == null -> LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), userScrollEnabled = false) {
                items(5) { MovieCardSkeleton(Modifier.width(118.dp)) }
            }
            else -> LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(items, key = { it.id }) { movie -> MovieCard(movie, onClick = { onMovieClick(movie) }, modifier = Modifier.width(118.dp)) }
            }
        }
    }
}
