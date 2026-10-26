package com.dopomine.film.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.dopomine.film.ui.theme.Radius
import com.dopomine.film.ui.theme.T

/** The white content card of splicer's layout, sitting on the gray canvas. */
@Composable
fun ContentCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(topStart = Radius.card, topEnd = Radius.card)
    Box(
        modifier
            .fillMaxSize()
            .padding(horizontal = 8.dp)
            .clip(shape)
            .background(T.surface)
            .border(1.dp, T.border, shape),
    ) { content() }
}
