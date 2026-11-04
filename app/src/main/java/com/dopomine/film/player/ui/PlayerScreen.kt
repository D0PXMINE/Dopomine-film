package com.dopomine.film.player.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.BrightnessMedium
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ClosedCaption
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.HighQuality
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.MoreTime
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PictureInPictureAlt
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.ScreenRotation
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Subject
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dopomine.film.player.GestureKind
import com.dopomine.film.player.Mode
import com.dopomine.film.player.NativeState
import com.dopomine.film.player.PlayerSession
import com.dopomine.film.player.Sheet
import com.dopomine.film.player.TrackOption
import com.dopomine.film.player.resolve.Playable
import com.dopomine.film.player.skip.SegmentKind
import com.dopomine.film.ui.components.Caps
import com.dopomine.film.ui.components.Format
import com.dopomine.film.ui.components.Halftone
import com.dopomine.film.ui.components.HalftoneVariant
import com.dopomine.film.ui.theme.T
import com.dopomine.film.ui.theme.Type
import kotlinx.coroutines.delay
import kotlin.math.abs

interface PlayerControls {
    fun close()
    fun togglePlay()
    fun seekBy(ms: Long)
    fun seekTo(ms: Long)
    fun selectAudio(key: String)
    fun selectQuality(key: String)
    fun selectText(key: String)
    fun setSpeed(speed: Float)
    fun chooseSource(key: String)
    fun playEpisode(season: Int, episode: Int)
    fun hasNextEpisode(): Boolean
    fun nextEpisode()
    fun openSheet(sheet: Sheet?)
    fun restartFromBeginning()
    fun cycleResize()
    fun rotate()
    fun enterPip()
    fun showSiteOverlay()
    fun hideSiteOverlay()
    fun canUseSiteOverlay(): Boolean
    fun retry()
    fun readLevel(kind: GestureKind): Float
    fun setLevel(kind: GestureKind, value: Float)
    fun gestureEnd()

    // intro skipping
    fun skipSegment()
    fun undoSkip()
    fun cancelNextEpisode()
    fun toggleAutoSkip()
    fun markIntro()
    fun clearIntroMark()
}

private val Scrim = Color(0xB3000000)
private val White = Color(0xFFF1F1F1)
private val White60 = Color(0x99F1F1F1)
private val White20 = Color(0x33F1F1F1)

@Composable
fun PlayerScreen(session: PlayerSession, state: NativeState, controls: PlayerControls, gesturesEnabled: Boolean) {
    if (session.inPip) return
    Box(Modifier.fillMaxSize()) {
        when (session.mode) {
            Mode.RESOLVING -> ResolvingLayer(session, controls)
            Mode.ERROR -> ErrorLayer(session, controls)
            Mode.NATIVE -> NativeLayer(session, state, controls, gesturesEnabled)
            Mode.WEB -> WebChrome(session, controls)
        }
        if (session.webOverlay) SiteOverlayBar(controls)
        session.gesture?.let { LevelHint(it.kind, it.value, Modifier.align(Alignment.Center)) }
    }
    session.sheet?.let { PlayerSheet(it, session, state, controls) }
}

// ---- Resolving / error -----------------------------------------------------------------------------

@Composable
private fun ResolvingLayer(session: PlayerSession, controls: PlayerControls) {
    Box(Modifier.fillMaxSize().background(Color.Black).swallowTouches()) {
        Column(
            Modifier.align(Alignment.Center).padding(32.dp).widthIn(max = 460.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Halftone(HalftoneVariant.GLYPH, Modifier.size(132.dp), seed = 11, cell = 6.dp, color = White)
            Spacer(Modifier.height(18.dp))
            Text(session.title, style = Type.title, color = White, textAlign = TextAlign.Center, maxLines = 2)
            Spacer(Modifier.height(6.dp))
            Text(session.status, style = Type.mono, color = White60, textAlign = TextAlign.Center)
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DarkPill("Журнал", Icons.Rounded.Subject) { controls.openSheet(Sheet.LOG) }
                DarkPill("Плеер сайта", Icons.Rounded.VideoLibrary) { controls.chooseSource("site_player") }
            }
        }
        RoundButton(Icons.AutoMirrored.Rounded.ArrowBack, "Закрыть", Modifier.align(Alignment.TopStart).padding(12.dp), onClick = controls::close)
    }
}

@Composable
private fun ErrorLayer(session: PlayerSession, controls: PlayerControls) {
    Box(Modifier.fillMaxSize().background(Color.Black).swallowTouches()) {
        Column(Modifier.align(Alignment.Center).padding(32.dp).widthIn(max = 460.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Halftone(HalftoneVariant.GLYPH, Modifier.size(96.dp), seed = 3, cell = 5.dp, animated = false, color = White60)
            Spacer(Modifier.height(16.dp))
            Text(session.error ?: "Не удалось запустить видео", style = Type.subtitle, color = White, textAlign = TextAlign.Center)
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DarkPill("Повторить", primary = true) { controls.retry() }
                DarkPill("Источник", Icons.Rounded.Tune) { controls.openSheet(Sheet.SOURCES) }
                DarkPill("Журнал", Icons.Rounded.Subject) { controls.openSheet(Sheet.LOG) }
            }
        }
        RoundButton(Icons.AutoMirrored.Rounded.ArrowBack, "Закрыть", Modifier.align(Alignment.TopStart).padding(12.dp), onClick = controls::close)
    }
}

// ---- Native player ------------------------------------------------------------------------------

@Composable
private fun NativeLayer(session: PlayerSession, state: NativeState, controls: PlayerControls, gesturesEnabled: Boolean) {
    val visible = session.controlsVisible
    LaunchedEffect(visible, state.isPlaying, session.sheet) {
        if (visible && state.isPlaying && session.sheet == null) {
            delay(3_500)
            session.controlsVisible = false
        }
    }
    Box(Modifier.fillMaxSize()) {
        GestureSurface(session, state, controls, gesturesEnabled)

        if (state.buffering && !state.isPlaying) {
            CircularProgressIndicator(Modifier.align(Alignment.Center).size(44.dp), color = White, strokeWidth = 2.dp)
        }
        session.seekHint?.let { hint ->
            Text(
                hint, style = Type.subtitle, color = White,
                modifier = Modifier.align(Alignment.Center).clip(RoundedCornerShape(14.dp)).background(Scrim).padding(horizontal = 18.dp, vertical = 10.dp),
            )
        }

        AnimatedVisibility(visible, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxWidth().height(120.dp).align(Alignment.TopCenter).background(Brush.verticalGradient(listOf(Color(0xCC000000), Color.Transparent))))
                Box(Modifier.fillMaxWidth().height(170.dp).align(Alignment.BottomCenter).background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xE6000000)))))
                TopBar(session, controls, Modifier.align(Alignment.TopCenter))
                CenterButtons(state, controls, Modifier.align(Alignment.Center))
                BottomBar(session, state, controls, Modifier.align(Alignment.BottomCenter))
            }
        }

        session.resumedAt?.let { pos ->
            LaunchedEffect(pos) { delay(7_000); session.resumedAt = null }
            Row(
                Modifier.align(Alignment.BottomStart).padding(start = 16.dp, bottom = 96.dp)
                    .clip(RoundedCornerShape(999.dp)).background(Color(0xE6121212))
                    .border(1.dp, White20, RoundedCornerShape(999.dp))
                    .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Продолжаем с ${Format.clock(pos)}", style = Type.mono, color = White)
                Spacer(Modifier.width(8.dp))
                DarkPill("С начала", small = true) { controls.restartFromBeginning() }
            }
        }

        SkipOverlay(session, controls, Modifier.align(Alignment.BottomEnd).padding(end = 24.dp, bottom = if (visible) 104.dp else 28.dp))

        session.skipped?.let { segment ->
            LaunchedEffect(segment) { delay(6_000); if (session.skipped == segment) session.skipped = null }
            Row(
                Modifier.align(Alignment.BottomStart).padding(start = 16.dp, bottom = if (visible) 104.dp else 28.dp)
                    .clip(RoundedCornerShape(999.dp)).background(Color(0xE6121212))
                    .border(1.dp, White20, RoundedCornerShape(999.dp))
                    .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("${segment.kind.title} пропущен${if (segment.kind == SegmentKind.INTRO) "а" else ""}", style = Type.mono, color = White)
                Spacer(Modifier.width(8.dp))
                DarkPill("Вернуть", small = true) { controls.undoSkip() }
            }
        }

        if (state.ended && controls.hasNextEpisode()) {
            DarkPill("Следующая серия", Icons.Rounded.SkipNext, primary = true, modifier = Modifier.align(Alignment.BottomEnd).padding(24.dp)) {
                controls.nextEpisode()
            }
        }
    }
}

/** Taps, double taps (±10 s), vertical swipes (brightness / volume) and horizontal scrubbing. */
@Composable
private fun GestureSurface(session: PlayerSession, state: NativeState, controls: PlayerControls, enabled: Boolean) {
    val slop = LocalViewConfiguration.current.touchSlop
    val currentState by rememberUpdatedState(state)
    var scrubTarget by remember { mutableStateOf<Long?>(null) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { session.controlsVisible = !session.controlsVisible },
                        onDoubleTap = { p ->
                            when {
                                p.x < w / 3 -> { controls.seekBy(-10_000); flash(session, "−10 с") }
                                p.x > w * 2 / 3 -> { controls.seekBy(10_000); flash(session, "+10 с") }
                                else -> controls.togglePlay()
                            }
                        },
                    )
                }
                .pointerInput(enabled) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val start = down.position
                        var mode = 0 // 0 undecided, 1 vertical level, 2 horizontal scrub, -1 ignore
                        var kind = GestureKind.BRIGHTNESS
                        var startLevel = 0f
                        val startPos = currentState.positionMs
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            if (event.changes.size > 1) { mode = -1 }
                            val d: Offset = change.position - start
                            if (mode == 0) {
                                if (abs(d.y) > slop * 2 && abs(d.y) > abs(d.x) * 2 && enabled &&
                                    start.y > h * 0.12f && start.y < h * 0.88f && (start.x < w * 0.4f || start.x > w * 0.6f)
                                ) {
                                    mode = 1
                                    kind = if (start.x < w / 2) GestureKind.BRIGHTNESS else GestureKind.VOLUME
                                    startLevel = controls.readLevel(kind)
                                } else if (abs(d.x) > slop * 2 && abs(d.x) > abs(d.y) * 2 && currentState.durationMs > 0) {
                                    mode = 2
                                } else if (abs(d.x) > slop * 3 || abs(d.y) > slop * 3) {
                                    mode = -1
                                }
                            }
                            when (mode) {
                                1 -> {
                                    change.consume()
                                    controls.setLevel(kind, (startLevel - (d.y - slop) / (h * 0.6f)).coerceIn(0f, 1f))
                                }
                                2 -> {
                                    change.consume()
                                    val delta = (d.x / w * 150_000).toLong() // a full swipe moves ±2.5 minutes
                                    val target = (startPos + delta).coerceIn(0, currentState.durationMs)
                                    scrubTarget = target
                                    val sign = if (delta >= 0) "+" else "−"
                                    session.seekHint = "$sign${Format.clock((abs(delta) / 1000).toInt())} · ${Format.clock((target / 1000).toInt())}"
                                }
                            }
                            if (change.positionChange() != Offset.Zero && mode > 0) change.consume()
                        }
                        when (mode) {
                            1 -> controls.gestureEnd()
                            2 -> {
                                scrubTarget?.let { controls.seekTo(it) }
                                scrubTarget = null
                                session.seekHint = null
                            }
                        }
                    }
                },
        )
    }
}

private fun flash(session: PlayerSession, text: String) {
    session.seekHint = text
    // cleared by the caller's next frame; a short-lived hint is enough
    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ if (session.seekHint == text) session.seekHint = null }, 650)
}

@Composable
private fun TopBar(session: PlayerSession, controls: PlayerControls, modifier: Modifier) {
    Row(modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        RoundButton(Icons.AutoMirrored.Rounded.ArrowBack, "Закрыть", onClick = controls::close)
        Column(Modifier.weight(1f).padding(start = 6.dp)) {
            Text(session.title, style = Type.subtitle, color = White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            session.subtitle?.let { Text(it, style = Type.monoSmall, color = White60, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        if (session.isSeries) {
            DarkPill("Серии", Icons.Rounded.VideoLibrary, small = true) { controls.openSheet(Sheet.EPISODES) }
            Spacer(Modifier.width(6.dp))
        } else if (controls.canUseSiteOverlay()) {
            DarkPill("Серии и озвучка", Icons.Rounded.VideoLibrary, small = true) { controls.showSiteOverlay() }
            Spacer(Modifier.width(6.dp))
        }
        DarkPill("Источник", Icons.Rounded.Tune, small = true) { controls.openSheet(Sheet.SOURCES) }
        if (session.canPip) RoundButton(Icons.Rounded.PictureInPictureAlt, "Картинка в картинке", onClick = controls::enterPip)
        RoundButton(Icons.Rounded.ScreenRotation, "Повернуть", onClick = controls::rotate)
    }
}

@Composable
private fun CenterButtons(state: NativeState, controls: PlayerControls, modifier: Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(36.dp)) {
        RoundButton(Icons.Rounded.Replay10, "Назад 10 секунд", size = 52.dp) { controls.seekBy(-10_000) }
        // the round play button from splicer's player bar
        Box(
            Modifier.size(72.dp).clip(CircleShape).background(White).clickable(onClick = controls::togglePlay),
            contentAlignment = Alignment.Center,
        ) {
            Icon(if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (state.isPlaying) "Пауза" else "Играть", tint = Color(0xFF0A0A0A), modifier = Modifier.size(38.dp))
        }
        RoundButton(Icons.Rounded.Forward10, "Вперёд 10 секунд", size = 52.dp) { controls.seekBy(10_000) }
    }
}

@Composable
private fun BottomBar(session: PlayerSession, state: NativeState, controls: PlayerControls, modifier: Modifier) {
    Column(modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(Format.clock((state.positionMs / 1000).toInt()), style = Type.mono, color = White)
            SeekBar(state, controls, Modifier.weight(1f).padding(horizontal = 12.dp))
            Text(Format.clock((state.durationMs / 1000).toInt()), style = Type.mono, color = White60)
        }
        Spacer(Modifier.height(8.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (state.audio.size > 1) item {
                DarkPill(state.audio.firstOrNull { it.selected }?.label?.take(22) ?: "Озвучка", Icons.Rounded.RecordVoiceOver, small = true) { controls.openSheet(Sheet.AUDIO) }
            }
            if (state.qualities.size > 1) item {
                DarkPill(state.qualities.firstOrNull { it.selected }?.label ?: "Качество", Icons.Rounded.HighQuality, small = true) { controls.openSheet(Sheet.QUALITY) }
            }
            if (state.texts.size > 1) item {
                DarkPill(if (state.texts.firstOrNull()?.selected == true) "Субтитры" else "Субтитры вкл.", Icons.Rounded.ClosedCaption, small = true) { controls.openSheet(Sheet.SUBTITLES) }
            }
            item { DarkPill("Автопропуск", Icons.Rounded.FastForward, small = true, primary = session.autoSkip) { controls.toggleAutoSkip() } }
            item { DarkPill(if (session.segments.any { it.kind == SegmentKind.INTRO }) "Заставка" else "Заставка ?", Icons.Rounded.MoreTime, small = true) { controls.openSheet(Sheet.SKIP) } }
            item { DarkPill("${formatSpeed(state.speed)}×", Icons.Rounded.Speed, small = true) { controls.openSheet(Sheet.SPEED) } }
            item {
                DarkPill(listOf("Вписать", "Заполнить", "Растянуть")[session.resize], Icons.Rounded.AspectRatio, small = true) { controls.cycleResize() }
            }
            if (controls.hasNextEpisode()) item { DarkPill("Следующая", Icons.Rounded.SkipNext, small = true) { controls.nextEpisode() } }
        }
    }
}

@Composable
private fun SeekBar(state: NativeState, controls: PlayerControls, modifier: Modifier) {
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    val duration = state.durationMs.coerceAtLeast(1)
    val fraction = dragFraction ?: (state.positionMs.toFloat() / duration).coerceIn(0f, 1f)
    val buffered = (state.bufferedMs.toFloat() / duration).coerceIn(0f, 1f)
    BoxWithConstraints(
        modifier
            .height(28.dp)
            .pointerInput(duration) {
                detectTapGestures { p -> controls.seekTo((p.x / size.width * duration).toLong()) }
            }
            .pointerInput(duration) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    dragFraction = (down.position.x / size.width).coerceIn(0f, 1f)
                    while (true) {
                        val ev = awaitPointerEvent()
                        val c = ev.changes.firstOrNull() ?: break
                        if (!c.pressed) break
                        dragFraction = (c.position.x / size.width).coerceIn(0f, 1f)
                        c.consume()
                    }
                    dragFraction?.let { controls.seekTo((it * duration).toLong()) }
                    dragFraction = null
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(Modifier.fillMaxWidth().height(3.dp).clip(CircleShape).background(White20))
        Box(Modifier.fillMaxWidth(buffered).height(3.dp).clip(CircleShape).background(Color(0x66F1F1F1)))
        Box(Modifier.fillMaxWidth(fraction).height(3.dp).clip(CircleShape).background(White))
        Box(Modifier.padding(start = (maxWidth - 14.dp) * fraction).size(14.dp).clip(CircleShape).background(White))
    }
}

private fun formatSpeed(speed: Float) = if (speed % 1f == 0f) speed.toInt().toString() else speed.toString()

// ---- Web mode ---------------------------------------------------------------------------------

@Composable
private fun WebChrome(session: PlayerSession, controls: PlayerControls) {
    LaunchedEffect(session.controlsVisible) {
        if (session.controlsVisible) { delay(4_000); session.controlsVisible = false }
    }
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(session.controlsVisible && !session.webFullscreen, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopCenter)) {
            Row(
                Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color(0xCC000000), Color.Transparent))).padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RoundButton(Icons.AutoMirrored.Rounded.ArrowBack, "Закрыть", onClick = controls::close)
                Column(Modifier.weight(1f).padding(start = 6.dp)) {
                    Text(session.title, style = Type.subtitle, color = White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val sub = listOfNotNull(session.subtitle, if (session.blocked > 0) "заблокировано рекламы: ${session.blocked}" else null).joinToString(" · ")
                    Text(sub, style = Type.monoSmall, color = White60, maxLines = 1)
                }
                DarkPill("Автопропуск", Icons.Rounded.FastForward, small = true, primary = session.autoSkip) { controls.toggleAutoSkip() }
                Spacer(Modifier.width(6.dp))
                DarkPill("Заставка", Icons.Rounded.MoreTime, small = true) { controls.openSheet(Sheet.SKIP) }
                Spacer(Modifier.width(6.dp))
                DarkPill("Источник", Icons.Rounded.Tune, small = true) { controls.openSheet(Sheet.SOURCES) }
                RoundButton(Icons.Rounded.ScreenRotation, "Повернуть", onClick = controls::rotate)
            }
        }
        SkipOverlay(session, controls, Modifier.align(Alignment.BottomEnd).padding(end = 24.dp, bottom = 72.dp))
        session.sniffHint?.let { hint ->
            // at the top: the page's own controls are at the bottom
            if (!session.webFullscreen) SniffHint(hint, controls, Modifier.align(Alignment.TopCenter).padding(top = 56.dp))
        }
        AnimatedVisibility(!session.controlsVisible && !session.webFullscreen, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopCenter)) {
            Box(Modifier.clip(RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp)).clickable { session.controlsVisible = true }.padding(horizontal = 18.dp, vertical = 6.dp)) {
                Icon(Icons.Rounded.KeyboardArrowDown, "Показать меню", tint = Color(0x80FFFFFF), modifier = Modifier.size(22.dp))
            }
        }
    }
}

/** While a player page is on screen and the app still waits for its stream; folds to a chip after a while. */
@Composable
private fun SniffHint(text: String, controls: PlayerControls, modifier: Modifier) {
    var expanded by remember(text) { mutableStateOf(true) }
    LaunchedEffect(text) { delay(8_000); expanded = false }
    Row(
        modifier.padding(horizontal = 16.dp).widthIn(max = 560.dp)
            .clip(RoundedCornerShape(16.dp)).background(Color(0xE6121212))
            .border(1.dp, White20, RoundedCornerShape(16.dp))
            .clickable { expanded = !expanded }
            .padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(Modifier.size(16.dp), color = White60, strokeWidth = 2.dp)
        Spacer(Modifier.width(10.dp))
        Text(if (expanded) text else "Ждём поток…", style = Type.monoSmall, color = White, modifier = Modifier.weight(1f, fill = false))
        Spacer(Modifier.width(8.dp))
        DarkPill("Источник", Icons.Rounded.Tune, small = true) { controls.openSheet(Sheet.SOURCES) }
    }
}

@Composable
private fun SiteOverlayBar(controls: PlayerControls) {
    Row(
        Modifier.fillMaxWidth().background(Color(0xE6000000)).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Выберите серию или озвучку в плеере ниже — видео продолжится в приложении",
            style = Type.monoSmall, color = White60, modifier = Modifier.weight(1f),
        )
        DarkPill("Готово", primary = true, small = true) { controls.hideSiteOverlay() }
    }
}

// ---- Hints ------------------------------------------------------------------------------------

@Composable
private fun LevelHint(kind: GestureKind, value: Float, modifier: Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(18.dp)).background(Scrim).padding(horizontal = 18.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val icon = when {
            kind == GestureKind.BRIGHTNESS -> Icons.Rounded.BrightnessMedium
            value <= 0.001f -> Icons.AutoMirrored.Rounded.VolumeOff
            else -> Icons.AutoMirrored.Rounded.VolumeUp
        }
        Icon(icon, null, tint = White, modifier = Modifier.size(26.dp))
        Spacer(Modifier.height(10.dp))
        Box(Modifier.width(140.dp).height(4.dp).clip(CircleShape).background(White20)) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(value).background(White))
        }
        Spacer(Modifier.height(6.dp))
        Text("${(value * 100).toInt()}%", style = Type.mono, color = White)
    }
}

// ---- Sheets -----------------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayerSheet(sheet: Sheet, session: PlayerSession, state: NativeState, controls: PlayerControls) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = { controls.openSheet(null) },
        sheetState = sheetState,
        containerColor = T.surface,
        contentColor = T.text,
        dragHandle = { Box(Modifier.padding(top = 10.dp, bottom = 6.dp).size(36.dp, 4.dp).clip(CircleShape).background(T.borderStrong)) },
    ) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 12.dp)) {
            when (sheet) {
                Sheet.SOURCES -> {
                    SheetTitle("Источник", "Откуда брать видео. AniLiberty, AnimeVost и Collaps играют прямо в приложении; остальные — поток, пойманный со страницы плеера.")
                    LazyColumn(Modifier.heightIn(max = 520.dp)) {
                        items(session.choices) { c ->
                            OptionRow(c.title, c.subtitle, c.key == session.currentChoice) { controls.chooseSource(c.key) }
                        }
                    }
                }
                Sheet.EPISODES -> EpisodesSheet(session, controls)
                Sheet.AUDIO -> TrackSheet("Озвучка", state.audio, controls::selectAudio)
                Sheet.QUALITY -> TrackSheet("Качество", state.qualities, controls::selectQuality)
                Sheet.SUBTITLES -> TrackSheet("Субтитры", state.texts, controls::selectText)
                Sheet.SPEED -> {
                    SheetTitle("Скорость", null)
                    listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f).forEach { s ->
                        OptionRow("${formatSpeed(s)}×", if (s == 1f) "Обычная" else null, state.speed == s) { controls.setSpeed(s) }
                    }
                }
                Sheet.SKIP -> SkipSheet(session, if (session.mode == Mode.WEB) session.webPositionMs else state.positionMs, controls)
                Sheet.LOG -> {
                    SheetTitle("Журнал", "Что делал плеер, пока искал видео. Пригодится, если что-то не работает.")
                    val list = rememberLazyListState()
                    LaunchedEffect(session.log.size) { if (session.log.isNotEmpty()) list.scrollToItem(session.log.lastIndex) }
                    LazyColumn(Modifier.heightIn(max = 420.dp).padding(horizontal = 20.dp), state = list) {
                        items(session.log) { line -> Text(line, style = Type.monoSmall, color = T.text2, modifier = Modifier.padding(vertical = 3.dp)) }
                    }
                }
            }
        }
    }
}

/** "Skip intro" / "Next episode" button, with the countdown when auto-skip runs into the credits. */
@Composable
private fun SkipOverlay(session: PlayerSession, controls: PlayerControls, modifier: Modifier) {
    val segment = session.activeSegment ?: return
    if (session.sheet != null || session.inPip) return
    val countdown = session.nextCountdown
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (countdown != null) DarkPill("Отмена") { controls.cancelNextEpisode() }
        val label = when {
            segment.kind == SegmentKind.CREDITS && countdown != null -> "Следующая серия через $countdown"
            segment.kind == SegmentKind.CREDITS -> "Следующая серия"
            else -> segment.kind.skipLabel
        }
        DarkPill(label, Icons.Rounded.SkipNext, primary = true) { controls.skipSegment() }
    }
}

@Composable
private fun SkipSheet(session: PlayerSession, positionMs: Long, controls: PlayerControls) {
    val position = (positionMs / 1000).toInt()
    SheetTitle(
        "Пропуск заставок",
        "Разметка берётся у источника и из открытых баз TheIntroDB, IntroDB и AniSkip. Если её нет — отметьте заставку сами: она будет пропускаться во всех сериях.",
    )
    OptionRow("Пропускать автоматически", "Заставки и пересказы — сразу, на титрах — следующая серия", session.autoSkip) { controls.toggleAutoSkip() }
    Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
        Caps("Эта серия")
        Spacer(Modifier.height(6.dp))
        if (session.segments.isEmpty()) {
            Text("Разметки пока нет", style = Type.body, color = T.text2)
        }
        session.segments.forEach { s ->
            val range = "${Format.clock((s.startMs / 1000).toInt())} – ${s.endMs?.let { Format.clock((it / 1000).toInt()) } ?: "конец"}"
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(s.kind.title, style = Type.item, color = T.text, modifier = Modifier.width(96.dp))
                Text(range, style = Type.mono, color = T.text, modifier = Modifier.weight(1f))
                Text(s.source, style = Type.monoSmall, color = T.text3)
            }
        }
    }
    val start = session.markStart
    OptionRow(
        if (start == null) "Здесь начинается заставка" else "Здесь заканчивается заставка",
        if (start == null) "Отметить сейчас — ${Format.clock(position)}" else "Начало ${Format.clock(start)}, конец — ${Format.clock(position)}",
        false,
    ) { controls.markIntro() }
    session.introMark?.let { (a, b) ->
        OptionRow("Удалить мою разметку", "${Format.clock(a)} – ${Format.clock(b)}, для всех серий", false) { controls.clearIntroMark() }
    }
}

@Composable
private fun EpisodesSheet(session: PlayerSession, controls: PlayerControls) {
    val series = session.playable as? Playable.Series ?: return
    var selected by remember { mutableIntStateOf(series.seasons.indexOfFirst { it.number == session.season }.coerceAtLeast(0)) }
    SheetTitle("Серии", "${series.seasons.size} ${if (series.seasons.size == 1) "сезон" else "сезонов"} · ${series.sourceName}")
    LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        itemsIndexed(series.seasons) { i, s ->
            com.dopomine.film.ui.components.Chip("${s.number} сезон", on = i == selected) { selected = i }
        }
    }
    Spacer(Modifier.height(8.dp))
    val season = series.seasons.getOrNull(selected) ?: return
    LazyColumn(Modifier.heightIn(max = 460.dp)) {
        items(season.episodes) { e ->
            val current = season.number == session.season && e.number == session.episode
            OptionRow("${e.number} серия", e.title?.takeIf { it.isNotBlank() && it != "${e.number}" }, current) {
                controls.playEpisode(season.number, e.number)
            }
        }
    }
}

@Composable
private fun TrackSheet(title: String, options: List<TrackOption>, onPick: (String) -> Unit) {
    SheetTitle(title, null)
    LazyColumn(Modifier.heightIn(max = 480.dp)) {
        items(options) { o -> OptionRow(o.label, o.detail, o.selected) { onPick(o.key) } }
    }
}

@Composable
private fun SheetTitle(title: String, subtitle: String?) {
    Column(Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) {
        Text(title.uppercase(), style = Type.caps, color = T.text3)
        if (subtitle != null) {
            Spacer(Modifier.height(4.dp))
            Text(subtitle, style = Type.mono, color = T.text2)
        }
        Spacer(Modifier.height(6.dp))
    }
}

@Composable
private fun OptionRow(title: String, subtitle: String?, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp).clip(RoundedCornerShape(12.dp))
            .background(if (selected) T.surface2 else Color.Transparent)
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = Type.item, color = T.text)
            if (subtitle != null) Text(subtitle, style = Type.monoSmall, color = T.text3, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (selected) Icon(Icons.Rounded.Check, null, tint = T.text, modifier = Modifier.size(20.dp))
    }
}

// ---- Small pieces -------------------------------------------------------------------------------

@Composable
private fun RoundButton(icon: ImageVector, description: String, modifier: Modifier = Modifier, size: androidx.compose.ui.unit.Dp = 40.dp, onClick: () -> Unit) {
    Box(modifier.size(size).clip(CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, description, tint = White, modifier = Modifier.size(size * 0.58f))
    }
}

@Composable
private fun DarkPill(
    text: String,
    icon: ImageVector? = null,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    small: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        modifier
            .height(if (small) 32.dp else 38.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(if (primary) White else Color(0x33000000))
            .border(1.dp, if (primary) White else White20, RoundedCornerShape(999.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = if (small) 12.dp else 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = if (primary) Color(0xFF0A0A0A) else White, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, style = Type.button, color = if (primary) Color(0xFF0A0A0A) else White, maxLines = 1)
    }
}

private fun Modifier.swallowTouches(): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        while (true) {
            val ev = awaitPointerEvent()
            ev.changes.forEach { it.consume() }
            if (ev.changes.all { !it.pressed }) break
        }
    }
}

