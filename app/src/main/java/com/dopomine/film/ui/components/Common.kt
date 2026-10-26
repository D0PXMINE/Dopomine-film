package com.dopomine.film.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dopomine.film.data.AppException
import com.dopomine.film.data.ErrorKind
import com.dopomine.film.ui.theme.Palette
import kotlin.math.cos
import kotlin.math.sin

/** Animated placeholder brush for skeleton loading. */
@Composable
fun shimmerBrush(): Brush {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val x by transition.animateFloat(
        initialValue = -400f,
        targetValue = 1200f,
        animationSpec = infiniteRepeatable(tween(1300, easing = LinearEasing), RepeatMode.Restart),
        label = "shimmerX",
    )
    return Brush.linearGradient(
        colors = listOf(Palette.SurfaceHigh, Palette.SurfaceHighest, Palette.SurfaceHigh),
        start = Offset(x, 0f),
        end = Offset(x + 400f, 400f),
    )
}

@Composable
fun SkeletonBox(modifier: Modifier = Modifier, corner: Dp = 12.dp) {
    Box(modifier.clip(RoundedCornerShape(corner)).background(shimmerBrush()))
}

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, onSeeAll: (() -> Unit)? = null) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        if (onSeeAll != null) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .clickable(onClick = onSeeAll)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Все", style = MaterialTheme.typography.labelLarge, color = Palette.TextSecondary)
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = Palette.TextSecondary, modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
fun LoadingBox(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary, strokeWidth = 3.dp)
    }
}

@Composable
fun MessageState(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(Palette.SurfaceHigh),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = Palette.TextSecondary, modifier = Modifier.size(34.dp))
        }
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        if (subtitle != null) {
            Spacer(Modifier.height(6.dp))
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = Palette.TextSecondary, textAlign = TextAlign.Center)
        }
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(16.dp))
            GradientButton(text = actionLabel, onClick = onAction)
        }
        if (secondaryLabel != null && onSecondary != null) {
            TextButton(onClick = onSecondary) { Text(secondaryLabel) }
        }
    }
}

/** Error placeholder that offers the right fix: settings for key problems, retry otherwise. */
@Composable
fun ErrorState(
    error: AppException,
    onRetry: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val icon = when (error.kind) {
        ErrorKind.NO_KEY, ErrorKind.BAD_KEY, ErrorKind.QUOTA -> Icons.Rounded.Key
        ErrorKind.NOT_FOUND -> Icons.Rounded.SearchOff
        ErrorKind.NETWORK -> Icons.Rounded.CloudOff
        else -> Icons.Rounded.Refresh
    }
    if (error.needsKey || error.kind == ErrorKind.QUOTA) {
        MessageState(
            icon = icon,
            title = error.userMessage,
            modifier = modifier,
            actionLabel = "Открыть настройки",
            onAction = onOpenSettings,
            secondaryLabel = "Повторить",
            onSecondary = onRetry,
        )
    } else {
        MessageState(icon = icon, title = error.userMessage, modifier = modifier, actionLabel = "Повторить", onAction = onRetry)
    }
}

@Composable
fun GradientButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    height: Dp = 48.dp,
) {
    Row(
        modifier = modifier
            .height(height)
            .clip(RoundedCornerShape(14.dp))
            .background(Palette.AccentGradient)
            .clickable(onClick = onClick)
            .padding(horizontal = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = Color.White, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
    }
}

/** Two-option pill switch, e.g. "Фильмы / Сериалы". */
@Composable
fun PillSwitch(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(Palette.Surface)
            .padding(4.dp),
    ) {
        options.forEachIndexed { index, label ->
            val active = index == selected
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .then(if (active) Modifier.background(Palette.AccentGradient) else Modifier)
                    .clickable { onSelect(index) }
                    .padding(horizontal = 18.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (active) Color.White else Palette.TextSecondary,
                )
            }
        }
    }
}

/** The brand mark: a film reel with a play-shaped cut-out, same geometry as the launcher icon. */
@Composable
fun LogoMark(size: Dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val s = this.size.minDimension / 60f // geometry is defined on a 60x60 grid
        val c = Offset(this.size.width / 2, this.size.height / 2)
        val path = Path().apply {
            fillType = PathFillType.EvenOdd
            addOval(androidx.compose.ui.geometry.Rect(c, 30f * s))
            for (k in 0 until 5) {
                val a = Math.toRadians(-90.0 + k * 72.0)
                val hc = Offset(c.x + (17f * s * cos(a)).toFloat(), c.y + (17f * s * sin(a)).toFloat())
                addOval(androidx.compose.ui.geometry.Rect(hc, 6.5f * s))
            }
            val r = 7.5f * s
            moveTo(c.x + r, c.y)
            lineTo(c.x - r / 2, c.y + r * 0.866f)
            lineTo(c.x - r / 2, c.y - r * 0.866f)
            close()
        }
        drawPath(path, Palette.LogoGradient)
    }
}

@Composable
fun Logo(modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        LogoMark(28.dp)
        Spacer(Modifier.width(8.dp))
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(fontWeight = FontWeight.Black)) { append("dopo") }
                withStyle(SpanStyle(fontWeight = FontWeight.Light)) { append("mine") }
            },
            fontSize = 22.sp,
            letterSpacing = (-0.5).sp,
            color = Palette.TextPrimary,
        )
    }
}

@Composable
fun EmptyHint(icon: ImageVector, title: String, modifier: Modifier = Modifier, subtitle: String? = null) {
    MessageState(icon = icon, title = title, subtitle = subtitle, modifier = modifier.fillMaxWidth())
}

@Composable
fun TopBar(
    title: String,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {},
) {
    Row(
        modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            androidx.compose.material3.IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Назад")
            }
        } else {
            Spacer(Modifier.width(12.dp))
        }
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(start = 4.dp),
        )
        actions()
    }
}
