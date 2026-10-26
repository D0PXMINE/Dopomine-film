package com.dopomine.film.ui.components

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.SubcomposeAsyncImage
import com.dopomine.film.data.AppException
import com.dopomine.film.data.HistoryItem
import com.dopomine.film.data.model.ContentType
import com.dopomine.film.data.model.Movie
import com.dopomine.film.ui.theme.Palette

@Composable
fun Poster(url: String?, modifier: Modifier = Modifier, corner: Dp = 12.dp, contentDescription: String? = null) {
    Box(
        modifier
            .clip(RoundedCornerShape(corner))
            .background(Palette.SurfaceHigh),
    ) {
        SubcomposeAsyncImage(
            model = url,
            contentDescription = contentDescription,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
            loading = { Box(Modifier.fillMaxSize().background(shimmerBrush())) },
            error = {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Movie, null, tint = Palette.TextMuted, modifier = Modifier.size(32.dp))
                }
            },
        )
    }
}

/** Poster card used in shelves and grids: KP badge on the poster, title and IMDb below. */
@Composable
fun MovieCard(movie: Movie, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
    ) {
        Box {
            Poster(
                url = movie.posterUrl,
                contentDescription = movie.title,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(2f / 3f),
            )
            KpBadge(movie.ratingKp, Modifier.padding(6.dp).align(Alignment.TopStart))
            if (movie.type == ContentType.SERIES) {
                Text(
                    "Сериал",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(6.dp)
                        .background(Color(0xCC09090F), RoundedCornerShape(6.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            movie.title,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 2,
            minLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 2.dp),
        )
        Row(
            Modifier.padding(horizontal = 2.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            movie.year?.let { Text(it.toString(), style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary) }
            ImdbBadge(movie.ratingImdb)
        }
    }
}

@Composable
fun MovieCardSkeleton(modifier: Modifier = Modifier) {
    Column(modifier) {
        SkeletonBox(Modifier.fillMaxWidth().aspectRatio(2f / 3f))
        Spacer(Modifier.height(8.dp))
        SkeletonBox(Modifier.fillMaxWidth(0.9f).height(12.dp), corner = 4.dp)
        Spacer(Modifier.height(6.dp))
        SkeletonBox(Modifier.fillMaxWidth(0.5f).height(12.dp), corner = 4.dp)
    }
}

/** Wide row for search results and lists. */
@Composable
fun MovieListRow(movie: Movie, onClick: () -> Unit, modifier: Modifier = Modifier, trailing: @Composable (() -> Unit)? = null) {
    Row(
        modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Poster(movie.posterUrl, Modifier.width(64.dp).aspectRatio(2f / 3f), corner = 8.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(movie.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val secondary = listOfNotNull(movie.originalTitle, Format.typeLabel(movie.type)).joinToString(" · ")
            Text(secondary, style = MaterialTheme.typography.bodySmall, color = Palette.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val sub = Format.subtitle(movie)
            if (sub.isNotBlank()) {
                Text(sub, style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(6.dp))
            RatingsRow(movie.ratingKp, movie.ratingImdb)
        }
        trailing?.invoke()
    }
}

/** "Continue watching" card: wide artwork with progress. */
@Composable
fun ContinueCard(item: HistoryItem, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier
            .width(240.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
            Poster(item.movie.coverUrl ?: item.movie.posterUrl, Modifier.fillMaxSize(), corner = 14.dp)
            Box(
                Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000)))),
            )
            Box(
                Modifier
                    .align(Alignment.Center)
                    .size(44.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Palette.AccentGradient),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.PlayArrow, null, tint = Color.White, modifier = Modifier.size(28.dp))
            }
            if (item.progress > 0f) {
                LinearProgressIndicator(
                    progress = { item.progress },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp)),
                    color = Palette.Accent,
                    trackColor = Color(0x55FFFFFF),
                    drawStopIndicator = {},
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(item.movie.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 2.dp))
        val remaining = if (item.durationSec > 0 && item.positionSec in 1 until item.durationSec) {
            "Осталось ${Format.clock(item.durationSec - item.positionSec)}"
        } else {
            Format.subtitle(item.movie, genres = 1)
        }
        Text(remaining, style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary, maxLines = 1, modifier = Modifier.padding(horizontal = 2.dp))
    }
}

/** A horizontal shelf of posters with header, skeleton and error states. */
@Composable
fun MovieShelf(
    title: String,
    items: List<Movie>?,
    error: AppException?,
    onMovieClick: (Movie) -> Unit,
    onSeeAll: (() -> Unit)?,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        SectionHeader(title, onSeeAll = if (!items.isNullOrEmpty()) onSeeAll else null)
        when {
            error != null && items.isNullOrEmpty() -> Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Palette.Surface)
                    .clickable(onClick = onRetry)
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(error.userMessage, style = MaterialTheme.typography.bodyMedium, color = Palette.TextSecondary, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                Text("Повторить", style = MaterialTheme.typography.labelLarge, color = Palette.Accent)
            }
            items == null -> LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                userScrollEnabled = false,
            ) {
                items(5) { MovieCardSkeleton(Modifier.width(124.dp)) }
            }
            items.isEmpty() -> Text(
                "Пока пусто",
                style = MaterialTheme.typography.bodyMedium,
                color = Palette.TextMuted,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            else -> LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(items, key = { it.id }) { movie ->
                    MovieCard(movie, onClick = { onMovieClick(movie) }, modifier = Modifier.width(124.dp))
                }
            }
        }
    }
}
