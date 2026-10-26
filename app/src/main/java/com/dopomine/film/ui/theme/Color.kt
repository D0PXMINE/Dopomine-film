package com.dopomine.film.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

object Palette {
    val Background = Color(0xFF09090F)
    val Surface = Color(0xFF13131C)
    val SurfaceHigh = Color(0xFF1B1B27)
    val SurfaceHighest = Color(0xFF252534)
    val Outline = Color(0xFF2C2C3D)
    val TextPrimary = Color(0xFFF3F3F8)
    val TextSecondary = Color(0xFFA0A0B5)
    val TextMuted = Color(0xFF6B6B80)

    val Accent = Color(0xFFFF4D6D)
    val AccentWarm = Color(0xFFFF9B42)
    val AccentViolet = Color(0xFF8B5CF6)

    val RatingGood = Color(0xFF3BB33B)
    val RatingMid = Color(0xFF7D7D8F)
    val RatingBad = Color(0xFFE5484D)
    val Imdb = Color(0xFFF5C518)

    val AccentGradient = Brush.linearGradient(listOf(Accent, AccentWarm))
    val LogoGradient = Brush.horizontalGradient(listOf(AccentViolet, Accent, AccentWarm))

    fun kpRatingColor(rating: Double?): Color = when {
        rating == null -> RatingMid
        rating >= 7.0 -> RatingGood
        rating >= 5.0 -> RatingMid
        else -> RatingBad
    }
}
