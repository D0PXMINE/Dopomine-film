package com.dopomine.film.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * The monochrome palette of splicer: white cards on a soft gray canvas in light mode,
 * the inverse in dark mode. There are no accent colors; "accent" is the text color.
 */
@Immutable
data class Tokens(
    val dark: Boolean,
    val bg: Color,
    val surface: Color,
    val surface2: Color,
    val surface3: Color,
    val border: Color,
    val borderStrong: Color,
    val text: Color,
    val text2: Color,
    val text3: Color,
    val accent: Color,
    val accentFg: Color,
    val overlay: Color,
) {
    companion object {
        val Light = Tokens(
            dark = false,
            bg = Color(0xFFECECEC),
            surface = Color(0xFFFFFFFF),
            surface2 = Color(0xFFF5F5F5),
            surface3 = Color(0xFFEBEBEB),
            border = Color(0xFFE3E3E3),
            borderStrong = Color(0xFFCFCFCF),
            text = Color(0xFF0A0A0A),
            text2 = Color(0xFF4D4D4D),
            text3 = Color(0xFF8C8C8C),
            accent = Color(0xFF0A0A0A),
            accentFg = Color(0xFFFFFFFF),
            overlay = Color(0x52121212),
        )

        val Dark = Tokens(
            dark = true,
            bg = Color(0xFF050505),
            surface = Color(0xFF121212),
            surface2 = Color(0xFF1B1B1B),
            surface3 = Color(0xFF252525),
            border = Color(0xFF242424),
            borderStrong = Color(0xFF363636),
            text = Color(0xFFF1F1F1),
            text2 = Color(0xFFB0B0B0),
            text3 = Color(0xFF6F6F6F),
            accent = Color(0xFFF1F1F1),
            accentFg = Color(0xFF0A0A0A),
            overlay = Color(0x99000000),
        )
    }
}

val LocalTokens = staticCompositionLocalOf { Tokens.Light }
