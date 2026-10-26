package com.dopomine.film.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.BrightnessMedium
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.PictureInPictureAlt
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.ScreenRotation
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dopomine.film.ui.components.Format
import com.dopomine.film.ui.components.GradientButton
import com.dopomine.film.ui.components.LogoMark
import com.dopomine.film.ui.theme.Palette
import kotlinx.coroutines.delay

interface PlayerActions {
    fun close()
    fun reload()
    fun enterPip()
    fun rotate()
    fun selectSource(index: Int)
    fun restartFromBeginning()
    fun openSources()
}

/** Native chrome over the WebView. Areas without buttons let touches through to the player. */
@Composable
fun PlayerOverlay(ui: PlayerUiState, actions: PlayerActions) {
    if (ui.inPip) return
    Box(Modifier.fillMaxSize()) {
        LoadingLayer(ui, actions)

        ui.error?.let { message -> ErrorLayer(message, actions) }

        AnimatedVisibility(
            visible = ui.controlsVisible && !ui.loading && ui.error == null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            TopControls(ui, actions)
        }

        // Small handle to bring the menu back without covering the player's own buttons.
        AnimatedVisibility(
            visible = !ui.controlsVisible && !ui.loading && ui.error == null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            Box(
                Modifier
                    .padding(top = 2.dp)
                    .clip(RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp))
                    .clickable { ui.controlsVisible = true }
                    .padding(horizontal = 18.dp, vertical = 6.dp),
            ) {
                Icon(Icons.Rounded.KeyboardArrowDown, "Показать меню", tint = Color(0x80FFFFFF), modifier = Modifier.size(22.dp))
            }
        }

        ui.gesture?.let { GestureLayer(it, Modifier.align(Alignment.Center)) }

        ui.resumedAt?.let { position ->
            ResumeBanner(position, onRestart = actions::restartFromBeginning, onDismiss = { ui.resumedAt = null }, modifier = Modifier.align(Alignment.BottomCenter))
        }
    }

    if (ui.sourcesSheet) SourcesSheet(ui, actions)

    LaunchedEffect(ui.controlsVisible, ui.loading) {
        if (ui.controlsVisible && !ui.loading) {
            delay(4_000)
            ui.controlsVisible = false
        }
    }
}

private fun Modifier.blockTouches(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) awaitPointerEvent().changes.forEach { it.consume() }
    }
}

@Composable
private fun TopControls(ui: PlayerUiState, actions: PlayerActions) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color(0xCC000000), Color.Transparent)))
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = actions::close) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Закрыть", tint = Color.White) }
        Column(Modifier.weight(1f).padding(start = 4.dp)) {
            Text(ui.title, style = MaterialTheme.typography.titleMedium, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val sub = listOfNotNull(
                ui.subtitle,
                ui.sources.getOrNull(ui.selectedSource)?.title,
                if (ui.blockedCount > 0) "заблокировано рекламы: ${ui.blockedCount}" else null,
            ).joinToString(" · ")
            if (sub.isNotBlank()) {
                Text(sub, style = MaterialTheme.typography.bodySmall, color = Color(0xCCFFFFFF), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (ui.sources.size > 1) {
            Row(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(Color(0x33FFFFFF))
                    .clickable(onClick = actions::openSources)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Tune, null, tint = Color.White, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Источник", style = MaterialTheme.typography.labelLarge, color = Color.White)
            }
            Spacer(Modifier.width(4.dp))
        }
        if (ui.canPip) IconButton(onClick = actions::enterPip) { Icon(Icons.Rounded.PictureInPictureAlt, "Картинка в картинке", tint = Color.White) }
        IconButton(onClick = actions::rotate) { Icon(Icons.Rounded.ScreenRotation, "Повернуть", tint = Color.White) }
        IconButton(onClick = actions::reload) { Icon(Icons.Rounded.Refresh, "Обновить", tint = Color.White) }
    }
}

@Composable
private fun LoadingLayer(ui: PlayerUiState, actions: PlayerActions) {
    AnimatedVisibility(visible = ui.loading && ui.error == null, enter = fadeIn(), exit = fadeOut()) {
        Box(Modifier.fillMaxSize().background(Palette.Background).blockTouches(), contentAlignment = Alignment.Center) {
            val pulse = rememberInfiniteTransition(label = "pulse")
            val scale by pulse.animateFloat(0.88f, 1.06f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "scale")
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
                LogoMark(72.dp, Modifier.graphicsLayer { scaleX = scale; scaleY = scale })
                Spacer(Modifier.height(20.dp))
                Text(ui.title, style = MaterialTheme.typography.titleLarge, color = Color.White, textAlign = TextAlign.Center, maxLines = 2)
                Spacer(Modifier.height(6.dp))
                Text("Загружаем плеер без рекламы…", style = MaterialTheme.typography.bodyMedium, color = Palette.TextSecondary)
                Spacer(Modifier.height(16.dp))
                LinearProgressIndicator(
                    progress = { ui.progress / 100f },
                    modifier = Modifier.width(180.dp).height(3.dp).clip(RoundedCornerShape(2.dp)),
                    color = Palette.Accent,
                    trackColor = Palette.SurfaceHigh,
                    drawStopIndicator = {},
                )
                if (ui.slowLoading) {
                    Spacer(Modifier.height(20.dp))
                    Text("Долго загружается? Попробуйте обновить", style = MaterialTheme.typography.bodySmall, color = Palette.TextMuted)
                    TextButton(onClick = actions::reload) { Text("Обновить") }
                }
            }
            IconButton(onClick = actions::close, modifier = Modifier.align(Alignment.TopStart).padding(8.dp)) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Закрыть", tint = Color.White)
            }
        }
    }
}

@Composable
private fun ErrorLayer(message: String, actions: PlayerActions) {
    Box(Modifier.fillMaxSize().background(Palette.Background).blockTouches(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
            Icon(Icons.Rounded.Shield, null, tint = Palette.TextMuted, modifier = Modifier.size(48.dp))
            Spacer(Modifier.height(16.dp))
            Text(message, style = MaterialTheme.typography.titleMedium, color = Color.White, textAlign = TextAlign.Center)
            Spacer(Modifier.height(20.dp))
            GradientButton("Повторить", onClick = actions::reload)
        }
        IconButton(onClick = actions::close, modifier = Modifier.align(Alignment.TopStart).padding(8.dp)) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Закрыть", tint = Color.White)
        }
    }
}

@Composable
private fun GestureLayer(indicator: GestureIndicator, modifier: Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xB3000000))
            .padding(horizontal = 18.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val icon = when {
            indicator.kind == GestureKind.BRIGHTNESS -> Icons.Rounded.BrightnessMedium
            indicator.value <= 0.001f -> Icons.AutoMirrored.Rounded.VolumeOff
            else -> Icons.AutoMirrored.Rounded.VolumeUp
        }
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(28.dp))
        Spacer(Modifier.height(10.dp))
        Box(
            Modifier
                .width(140.dp)
                .height(6.dp)
                .clip(CircleShape)
                .background(Color(0x40FFFFFF)),
        ) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(indicator.value)
                    .background(Palette.AccentGradient),
            )
        }
        Spacer(Modifier.height(6.dp))
        Text("${(indicator.value * 100).toInt()}%", style = MaterialTheme.typography.labelMedium, color = Color.White)
    }
}

@Composable
private fun ResumeBanner(position: Int, onRestart: () -> Unit, onDismiss: () -> Unit, modifier: Modifier) {
    LaunchedEffect(position) {
        delay(7_000)
        onDismiss()
    }
    Row(
        modifier
            .navigationBarsPadding()
            .padding(bottom = 72.dp)
            .clip(RoundedCornerShape(50))
            .background(Color(0xE613131C))
            .padding(start = 18.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Продолжаем с ${Format.clock(position)}", style = MaterialTheme.typography.bodyMedium, color = Color.White)
        TextButton(onClick = { onRestart(); onDismiss() }) { Text("С начала", color = Palette.Accent) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SourcesSheet(ui: PlayerUiState, actions: PlayerActions) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = { ui.sourcesSheet = false },
        sheetState = state,
        containerColor = Palette.Surface,
    ) {
        Text(
            "Источник и озвучка",
            style = MaterialTheme.typography.titleLarge,
            color = Color.White,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
        )
        Text(
            "Если видео не запускается, выберите другой источник. Озвучку и серию можно сменить и внутри плеера.",
            style = MaterialTheme.typography.bodySmall,
            color = Palette.TextSecondary,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
        )
        LazyColumn(Modifier.navigationBarsPadding().padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            itemsIndexed(ui.sources) { index, source ->
                val active = index == ui.selectedSource
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            actions.selectSource(index)
                            ui.sourcesSheet = false
                        }
                        .padding(horizontal = 24.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (active) Palette.AccentGradient else Brush.linearGradient(listOf(Palette.SurfaceHigh, Palette.SurfaceHigh))),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text((index + 1).toString(), style = MaterialTheme.typography.labelLarge, color = Color.White)
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(source.title, style = MaterialTheme.typography.titleMedium, color = if (active) Palette.Accent else Color.White)
                        source.subtitle?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    if (active) Icon(Icons.Rounded.CheckCircle, null, tint = Palette.Accent)
                }
            }
        }
    }
}
