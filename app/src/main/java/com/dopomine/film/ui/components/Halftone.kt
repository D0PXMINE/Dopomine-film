package com.dopomine.film.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dopomine.film.ui.theme.T
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Halftone art ported from splicer (src/ui/halftone/field.ts): diamond dots on a 45° lattice,
 * sized by a smoky noise field that slowly drifts.
 */
enum class HalftoneVariant { CLOUD, BANNER, GLYPH }

/** Whether the halftone art animates (a setting, like splicer's «Анимация точек»). */
val LocalAnimations = staticCompositionLocalOf { true }

private object Field {
    private fun fract(x: Float) = x - floor(x)

    fun hash12(x: Float, y: Float): Float {
        var ax = fract(x * 0.1031f)
        var ay = fract(y * 0.1031f)
        var az = ax
        val d = ax * (ay + 33.33f) + ay * (az + 33.33f) + az * (ax + 33.33f)
        ax += d; ay += d; az += d
        return fract((ax + ay) * az)
    }

    private fun vnoise(x: Float, y: Float, sx: Float, sy: Float): Float {
        val ix = floor(x)
        val iy = floor(y)
        val fx = x - ix
        val fy = y - iy
        val ux = fx * fx * (3 - 2 * fx)
        val uy = fy * fy * (3 - 2 * fy)
        val a = hash12(ix + sx, iy + sy)
        val b = hash12(ix + 1 + sx, iy + sy)
        val c = hash12(ix + sx, iy + 1 + sy)
        val d = hash12(ix + 1 + sx, iy + 1 + sy)
        return a + (b - a) * ux + (c - a) * uy + (a - b - c + d) * ux * uy
    }

    fun fbm(x0: Float, y0: Float, sx: Float, sy: Float, octaves: Int): Float {
        var x = x0
        var y = y0
        var sum = 0f
        var amp = 0.5f
        var norm = 0f
        repeat(octaves) {
            sum += amp * vnoise(x, y, sx, sy)
            norm += amp
            x = x * 2.03f + 17.1f
            y = y * 2.03f + 9.2f
            amp *= 0.5f
        }
        return sum / norm
    }

    fun smoothstep(e0: Float, e1: Float, x: Float): Float {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3 - 2 * t)
    }

    fun scene(variant: HalftoneVariant, sx: Float, sy: Float, u: Float, v: Float, aspect: Float, t: Float): Float {
        val px = u * aspect
        val wx = fbm(px * 1.5f + t * 0.07f, v * 1.5f - t * 0.045f, sx, sy, 2)
        val wy = fbm(px * 1.5f + 5.2f - t * 0.05f, v * 1.5f + 1.3f + t * 0.06f, sx, sy, 2)
        val n = fbm(px * 2.4f + wx * 1.3f + t * 0.03f, v * 2.4f + wy * 1.3f, sx, sy, 4)
        return when (variant) {
            HalftoneVariant.GLYPH -> {
                val dx = (u - 0.5f) * aspect
                val dy = v - 0.5f
                val d = sqrt(dx * dx + dy * dy) + (n - 0.5f) * 0.14f
                (1 - smoothstep(0.17f, 0.44f, d)) * (0.42f + n * 0.95f)
            }
            HalftoneVariant.CLOUD -> {
                val dx = (u - 0.5f - 0.05f * sin(t * 0.13f)) * 2
                val dy = (v - 0.5f - 0.04f * kotlin.math.cos(t * 0.11f)) * 2
                val envelope = 1 - smoothstep(0.32f, 0.78f, sqrt(dx * dx + dy * dy) + (wx - 0.5f) * 0.5f)
                envelope * (0.2f + smoothstep(0.2f, 0.78f, n) * 0.98f)
            }
            HalftoneVariant.BANNER -> {
                val front = 0.26f + (fbm(v * 2.2f + t * 0.05f, 7.3f + t * 0.02f, sx, sy, 2) - 0.5f) * 0.8f
                val envelope = smoothstep(front - 0.22f, front + 0.3f, u + (wx - 0.5f) * 0.5f + (n - 0.5f) * 0.4f) *
                    smoothstep(0f, 0.2f, u)
                envelope * (0.03f + smoothstep(0.24f, 0.82f, n) * 1.25f)
            }
        }
    }

    const val THRESHOLD = 0.14f
}

/**
 * @param seed picks the shape; the same seed always gives the same art.
 * @param animated drift and "breathing" dots (about 20 fps, only while on screen).
 */
@Composable
fun Halftone(
    variant: HalftoneVariant,
    modifier: Modifier = Modifier,
    seed: Int = 7,
    cell: Dp = 7.dp,
    animated: Boolean = LocalAnimations.current,
    color: Color = T.text,
) {
    var time by remember { mutableFloatStateOf(20f + (seed % 50)) }
    if (animated) {
        LaunchedEffect(seed) {
            val start = withFrameNanos { it }
            var last = 0L
            while (true) {
                withFrameNanos { now ->
                    if (now - last > 50_000_000L) { // ~20 fps is plenty for slow smoke
                        last = now
                        time = 20f + (seed % 50) + (now - start) / 1_000_000_000f
                    }
                }
            }
        }
    }
    val cellPx = with(LocalDensity.current) { cell.toPx() }
    val path = remember { Path() }
    val sx = (seed % 97) * 1.618f
    val sy = (seed % 89) * 2.414f
    val bleed = variant == HalftoneVariant.BANNER

    Canvas(modifier) {
        val t = time
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas
        val aspect = w / h
        val half = cellPx / 2
        val minEdge = if (bleed) -half else half
        path.reset()
        var row = 0
        while (true) {
            val y = row * half
            if (y > h - minEdge) break
            if (y >= minEdge) {
                var x = if (row % 2 == 0) 0f else half
                while (x <= w - minEdge) {
                    if (x >= minEdge) {
                        val cu = ((x + y) / cellPx).roundToInt().toFloat()
                        val cv = ((x - y) / cellPx).roundToInt().toFloat()
                        val base = Field.scene(variant, sx, sy, x / w, y / h, aspect, t)
                        var value = base
                        if (value > 0.02f) {
                            val h1 = Field.hash12(cu + 3.7f, cv + 11.3f)
                            value = if (animated) {
                                val h2 = Field.hash12(cu + 19.19f, cv + 7.77f)
                                val wave = 0.86f + 0.14f * sin(((x + y) / cellPx) * 0.32f - t * 1.1f)
                                val shimmer = (h1 - 0.5f) * 0.16f * (0.6f + 0.4f * sin(t * 1.7f + h2 * 6.2832f))
                                value * wave + shimmer
                            } else {
                                value + (h1 - 0.5f) * 0.16f
                            }
                            if (!bleed) {
                                val e = min(min(x, w - x), min(y, h - y))
                                value *= Field.smoothstep(cellPx * 0.6f, cellPx * 2.6f, e)
                            } else {
                                val h3 = Field.hash12(cu + 41.3f, cv + 2.9f)
                                value = if (h3 >= Field.smoothstep(0.02f, 0.3f, base)) -1f else maxOf(value, 0.17f)
                            }
                            if (value >= Field.THRESHOLD) {
                                val r = 0.5f * min(1f, sqrt(value)) * cellPx
                                path.moveTo(x, y - r)
                                path.lineTo(x + r, y)
                                path.lineTo(x, y + r)
                                path.lineTo(x - r, y)
                                path.close()
                            }
                        }
                    }
                    x += cellPx
                }
            }
            row++
        }
        drawPath(path, color)
    }
}
