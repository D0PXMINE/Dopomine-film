package com.dopomine.film.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dopomine.film.ui.theme.Palette

/** Kinopoisk rating pill, colored the way Kinopoisk does it (green / gray / red). */
@Composable
fun KpBadge(rating: Double?, modifier: Modifier = Modifier) {
    if (rating == null) return
    Text(
        text = Format.rating(rating),
        color = Color.White,
        fontSize = 12.sp,
        fontWeight = FontWeight.ExtraBold,
        modifier = modifier
            .background(Palette.kpRatingColor(rating), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
fun ImdbBadge(rating: Double?, modifier: Modifier = Modifier) {
    if (rating == null) return
    Text(
        text = buildAnnotatedString {
            withStyle(SpanStyle(fontWeight = FontWeight.Black)) { append("IMDb ") }
            append(Format.rating(rating))
        },
        color = Color.Black,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        modifier = modifier
            .background(Palette.Imdb, RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/** "КП 8.5  IMDb 8.7" in one line for list rows. */
@Composable
fun RatingsRow(kp: Double?, imdb: Double?, modifier: Modifier = Modifier) {
    if (kp == null && imdb == null) return
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (kp != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("КП", style = MaterialTheme.typography.labelSmall, color = Palette.TextMuted)
                KpBadge(kp)
            }
        }
        ImdbBadge(imdb)
    }
}
