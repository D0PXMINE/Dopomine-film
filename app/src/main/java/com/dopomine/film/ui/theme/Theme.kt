package com.dopomine.film.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.dopomine.film.R

@OptIn(ExperimentalTextApi::class)
private fun variable(res: Int, weight: Int) = Font(
    res,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

/** Geist for text, Geist Mono for labels and metadata — the same pair splicer uses. */
val Geist = FontFamily(
    variable(R.font.geist, 300),
    variable(R.font.geist, 400),
    variable(R.font.geist, 500),
    variable(R.font.geist, 600),
    variable(R.font.geist, 700),
)

val GeistMono = FontFamily(
    variable(R.font.geist_mono, 400),
    variable(R.font.geist_mono, 500),
    variable(R.font.geist_mono, 600),
)

object Type {
    val display = TextStyle(fontFamily = Geist, fontWeight = FontWeight.Medium, fontSize = 28.sp, lineHeight = 32.sp, letterSpacing = (-0.02).em)
    val title = TextStyle(fontFamily = Geist, fontWeight = FontWeight.Medium, fontSize = 20.sp, lineHeight = 26.sp, letterSpacing = (-0.01).em)
    val subtitle = TextStyle(fontFamily = Geist, fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 21.sp)
    val body = TextStyle(fontFamily = Geist, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 22.sp)
    val bodySmall = TextStyle(fontFamily = Geist, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 18.sp)
    val item = TextStyle(fontFamily = Geist, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 18.sp)
    val mono = TextStyle(fontFamily = GeistMono, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp)
    val monoSmall = TextStyle(fontFamily = GeistMono, fontWeight = FontWeight.Normal, fontSize = 11.sp, lineHeight = 14.sp)
    val caps = TextStyle(fontFamily = GeistMono, fontWeight = FontWeight.Normal, fontSize = 10.5.sp, lineHeight = 14.sp, letterSpacing = 0.08.em)
    val button = TextStyle(fontFamily = GeistMono, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp)
}

/** Radii from splicer's stylesheet. */
object Radius {
    val card = 18.dp
    val row = 12.dp
    val cover = 8.dp
}

enum class ThemeMode { SYSTEM, LIGHT, DARK }

@Composable
fun DopomineTheme(mode: ThemeMode = ThemeMode.SYSTEM, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val t = if (dark) Tokens.Dark else Tokens.Light
    val scheme = if (dark) {
        darkColorScheme(
            primary = t.accent, onPrimary = t.accentFg, secondary = t.text2, onSecondary = t.surface,
            background = t.bg, onBackground = t.text, surface = t.surface, onSurface = t.text,
            surfaceVariant = t.surface2, onSurfaceVariant = t.text2, surfaceContainer = t.surface,
            surfaceContainerLow = t.surface, surfaceContainerHigh = t.surface2, surfaceContainerHighest = t.surface3,
            surfaceContainerLowest = t.bg, outline = t.border, outlineVariant = t.border, error = t.text,
        )
    } else {
        lightColorScheme(
            primary = t.accent, onPrimary = t.accentFg, secondary = t.text2, onSecondary = t.surface,
            background = t.bg, onBackground = t.text, surface = t.surface, onSurface = t.text,
            surfaceVariant = t.surface2, onSurfaceVariant = t.text2, surfaceContainer = t.surface,
            surfaceContainerLow = t.surface, surfaceContainerHigh = t.surface2, surfaceContainerHighest = t.surface3,
            surfaceContainerLowest = t.bg, outline = t.border, outlineVariant = t.border, error = t.text,
        )
    }
    val typography = Typography(
        headlineMedium = Type.display, titleLarge = Type.title, titleMedium = Type.subtitle, titleSmall = Type.item,
        bodyLarge = Type.body, bodyMedium = Type.bodySmall, bodySmall = Type.mono, labelLarge = Type.button,
        labelMedium = Type.mono, labelSmall = Type.caps,
    )
    val shapes = Shapes(
        extraSmall = RoundedCornerShape(6.dp), small = RoundedCornerShape(10.dp), medium = RoundedCornerShape(Radius.row),
        large = RoundedCornerShape(Radius.card), extraLarge = RoundedCornerShape(24.dp),
    )
    MaterialTheme(colorScheme = scheme, typography = typography, shapes = shapes) {
        CompositionLocalProvider(LocalTokens provides t, LocalContentColor provides t.text, content = content)
    }
}

/** Shortcut: `T.text`, `T.border`, … inside composables. */
val T: Tokens
    @Composable @ReadOnlyComposable get() = LocalTokens.current
