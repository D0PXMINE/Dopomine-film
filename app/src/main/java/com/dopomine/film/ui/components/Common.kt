package com.dopomine.film.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dopomine.film.data.AppException
import com.dopomine.film.R
import com.dopomine.film.ui.theme.Radius
import com.dopomine.film.ui.theme.T
import com.dopomine.film.ui.theme.Type

/** Mono uppercase label — splicer's `.caps`. */
@Composable
fun Caps(text: String, modifier: Modifier = Modifier, color: Color = T.text3) {
    Text(text.uppercase(), style = Type.caps, color = color, modifier = modifier, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/** Pill button — splicer's `.btn` (outlined) and `.btn.primary` (filled with the text color). */
@Composable
fun Pill(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    primary: Boolean = false,
    large: Boolean = false,
    trailing: ImageVector? = null,
) {
    val shape = RoundedCornerShape(999.dp)
    Row(
        modifier
            .height(if (large) 46.dp else 36.dp)
            .clip(shape)
            .background(if (primary) T.accent else T.surface)
            .border(1.dp, if (primary) T.accent else T.border, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = if (large) 22.dp else 15.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        val tint = if (primary) T.accentFg else T.text
        if (icon != null) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(if (large) 20.dp else 16.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = if (large) Type.button.copy(fontSize = Type.mono.fontSize * 1.1f) else Type.button, color = tint, maxLines = 1)
        if (trailing != null) {
            Spacer(Modifier.width(4.dp))
            Icon(trailing, null, tint = if (primary) T.accentFg else T.text3, modifier = Modifier.size(16.dp))
        }
    }
}

/** Small toggle chip — splicer's `.chip` / `.chip.on`. */
@Composable
fun Chip(text: String, on: Boolean = false, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(999.dp)
    Text(
        text,
        style = Type.monoSmall,
        color = if (on) T.accentFg else T.text2,
        maxLines = 1,
        modifier = modifier
            .clip(shape)
            .background(if (on) T.accent else T.surface)
            .border(1.dp, if (on) T.accent else T.border, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 7.dp),
    )
}

/** Segmented control — splicer's `.segmented` ("Любой / Ваншоты / Лупы"). */
/** @param fill share the given width equally between the options (for tight spots like the home banner). */
@Composable
fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier, fill: Boolean = false) {
    val shape = RoundedCornerShape(999.dp)
    Row(
        modifier
            .clip(shape)
            .background(T.surface2)
            .border(1.dp, T.border, shape)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEachIndexed { i, label ->
            val active = i == selected
            Box(
                Modifier
                    .then(if (fill) Modifier.weight(1f) else Modifier)
                    .clip(shape)
                    .then(if (active) Modifier.background(T.surface).border(1.dp, T.border, shape) else Modifier)
                    .clickable { onSelect(i) }
                    .padding(horizontal = if (fill) 4.dp else 14.dp, vertical = 7.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = Type.button, color = if (active) T.text else T.text2, maxLines = 1, softWrap = false)
            }
        }
    }
}

@Composable
fun IconBtn(icon: ImageVector, description: String, onClick: () -> Unit, modifier: Modifier = Modifier, active: Boolean = false) {
    Box(
        modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(if (active) T.accent else Color.Transparent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, description, tint = if (active) T.accentFg else T.text2, modifier = Modifier.size(21.dp))
    }
}

/** White card with a hairline border — splicer's `.card`. */
@Composable
fun Card(modifier: Modifier = Modifier, padding: Dp = 16.dp, content: @Composable () -> Unit) {
    Column(
        modifier
            .clip(RoundedCornerShape(Radius.card))
            .background(T.surface)
            .border(1.dp, T.border, RoundedCornerShape(Radius.card))
            .padding(padding),
    ) { content() }
}

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, caption: String? = null, onSeeAll: (() -> Unit)? = null) {
    Row(
        modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            if (caption != null) Caps(caption)
            Text(title, style = Type.title, color = T.text)
        }
        if (onSeeAll != null) {
            Row(
                Modifier.clip(RoundedCornerShape(999.dp)).clickable(onClick = onSeeAll).padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Все", style = Type.button, color = T.text2)
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = T.text3, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
fun TopBar(
    title: String,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 6.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) IconBtn(Icons.AutoMirrored.Rounded.ArrowBack, "Назад", onBack) else Spacer(Modifier.width(10.dp))
        Text(title, style = Type.title, color = T.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = 6.dp))
        actions()
    }
}

@Composable
fun LoadingBox(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = T.text, strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
    }
}

/** Empty / error placeholder with halftone art, like splicer's empty screens. */
@Composable
fun MessageState(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    seed: Int = 5,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Halftone(HalftoneVariant.GLYPH, Modifier.size(110.dp), seed = seed, cell = 5.dp)
        Spacer(Modifier.height(14.dp))
        Text(title, style = Type.subtitle, color = T.text, textAlign = TextAlign.Center)
        if (subtitle != null) {
            Spacer(Modifier.height(4.dp))
            Text(subtitle, style = Type.mono, color = T.text3, textAlign = TextAlign.Center)
        }
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(16.dp))
            Pill(actionLabel, onAction, primary = true)
        }
    }
}

@Composable
fun ErrorState(error: AppException, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    MessageState(error.userMessage, modifier, seed = 13, actionLabel = "Повторить", onAction = onRetry)
}

@Composable
fun EmptyHint(title: String, modifier: Modifier = Modifier, subtitle: String? = null, seed: Int = 21) {
    MessageState(title, modifier.fillMaxWidth(), subtitle = subtitle, seed = seed)
}

/** The brand mark: rays from the center, same geometry as the launcher icon. */
@Composable
fun LogoMark(size: Dp, modifier: Modifier = Modifier, color: Color = T.text) {
    // the eight-ray sparkle from tools/gen_icons.py, same shape as the launcher icon
    Icon(painterResource(R.drawable.ic_logo), contentDescription = null, modifier = modifier.size(size), tint = color)
}

@Composable
fun Logo(modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        LogoMark(26.dp)
        Spacer(Modifier.width(9.dp))
        Text("dopamine", style = Type.title.copy(fontSize = Type.title.fontSize * 1.08f), color = T.text)
    }
}

@Composable
fun Divider(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(T.border))
}
