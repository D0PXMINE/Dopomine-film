package com.dopomine.film.player

import android.view.MotionEvent
import android.view.ViewConfiguration
import android.content.Context
import kotlin.math.abs

enum class GestureKind { BRIGHTNESS, VOLUME }

/**
 * Zona-style vertical swipe gestures: left part of the screen changes brightness,
 * right part changes volume, a downward swipe from the top edge reveals the app's menu.
 * Taps and horizontal drags pass through to the player.
 */
class PlayerGestures(
    context: Context,
    private val read: (GestureKind) -> Float,
    private val apply: (GestureKind, Float) -> Unit,
    private val onEnd: () -> Unit,
    private val onReveal: () -> Unit,
) {
    var enabled = true
    var active = false
        private set

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var zone: GestureKind? = null
    private var startValue = 0f
    private var multiTouch = false
    private var fromTop = false
    private var revealed = false

    /** Returns true when the event is consumed by a gesture. */
    fun onTouch(ev: MotionEvent, width: Int, height: Int): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                active = false
                multiTouch = false
                revealed = false
                fromTop = enabled && ev.y < height * 0.15f
                zone = when {
                    !enabled || width == 0 || height == 0 -> null
                    // keep the top bar and the player's seek bar area free
                    ev.y < height * 0.15f || ev.y > height * 0.82f -> null
                    ev.x < width * 0.35f -> GestureKind.BRIGHTNESS
                    ev.x > width * 0.65f -> GestureKind.VOLUME
                    else -> null
                }
                return false
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                multiTouch = true
                if (!active) zone = null
                return active
            }
            MotionEvent.ACTION_MOVE -> {
                if (fromTop && !multiTouch) {
                    if (revealed) return true
                    val dy = ev.y - downY
                    if (dy > slop * 3 && dy > abs(ev.x - downX) * 2) {
                        revealed = true
                        active = true
                        onReveal()
                        return true
                    }
                    return false
                }
                val kind = zone ?: return false
                if (!active) {
                    val dy = ev.y - downY
                    val dx = ev.x - downX
                    if (abs(dy) > slop * 2 && abs(dy) > abs(dx) * 2) {
                        active = true
                        startValue = read(kind)
                        downY = ev.y
                    } else {
                        return false
                    }
                }
                val delta = (downY - ev.y) / (height * 0.6f)
                apply(kind, (startValue + delta).coerceIn(0f, 1f))
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val wasActive = active
                active = false
                zone = null
                fromTop = false
                if (wasActive) {
                    if (!revealed) onEnd()
                    return true
                }
                return false
            }
        }
        return active
    }
}
