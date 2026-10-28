package com.dopomine.film.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dopomine.film.BuildConfig
import com.dopomine.film.ui.common.ContentCard
import com.dopomine.film.ui.common.Launchers
import com.dopomine.film.ui.common.appViewModel
import com.dopomine.film.ui.components.Caps
import com.dopomine.film.ui.components.Divider
import com.dopomine.film.ui.components.LogoMark
import com.dopomine.film.ui.components.Pill
import com.dopomine.film.ui.components.Segmented
import com.dopomine.film.ui.components.TopBar
import com.dopomine.film.ui.navigation.Nav
import com.dopomine.film.ui.theme.T
import com.dopomine.film.ui.theme.ThemeMode
import com.dopomine.film.ui.theme.Type

@Composable
fun SettingsScreen(nav: Nav) {
    val vm = appViewModel { c, _ -> SettingsViewModel(c) }
    val settings by vm.settings.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val s = settings ?: return

    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            vm.consumeMessage()
        }
    }
    var host by rememberSaveable(s.playerHost) { mutableStateOf(s.playerHost) }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            TopBar("Настройки", onBack = nav::back)
            ContentCard {
                Column(
                    Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 18.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Group("оформление")
                    Text("Тема", style = Type.item, color = T.text)
                    Segmented(listOf("Система", "Светлая", "Тёмная"), s.theme.ordinal, { vm.setTheme(ThemeMode.entries[it]) })
                    Spacer(Modifier.height(4.dp))
                    Toggle("Анимация точек", "Живые узоры на баннерах и пустых экранах", s.animations, vm::setAnimations)

                    Group("плеер")
                    Toggle("Блокировать рекламу", "Фильтр рекламы, всплывающих окон и переходов на чужие сайты", s.adBlock, vm::setAdBlock)
                    Toggle("Горизонтальный режим", "Открывать плеер сразу в альбомной ориентации", s.landscapePlayer, vm::setLandscape)
                    Toggle("Продолжать с места остановки", "Запоминать позицию для каждого фильма и серии", s.autoResume, vm::setAutoResume)
                    Toggle("Следующая серия", "Включать следующую серию автоматически", s.autoNext, vm::setAutoNext)
                    Toggle("Пропускать заставки", "Заставки и пересказы — сразу, на титрах — следующая серия. Кнопка «Пропустить» есть всегда", s.autoSkip, vm::setAutoSkip)
                    Toggle("Жесты", "Свайп слева — яркость, справа — громкость, по горизонтали — перемотка", s.gestures, vm::setGestures)
                    Spacer(Modifier.height(8.dp))
                    Text("Адрес плеера", style = Type.item, color = T.text)
                    Text("Сайт из скрипта: видео ищется на его странице адрес/ID. Если он сменит зеркало — укажите новое.", style = Type.mono, color = T.text3)
                    Field(host, { host = it })
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Pill("Сохранить", { vm.setPlayerHost(host) }, primary = true)
                        Pill("По умолчанию", vm::resetPlayerHost)
                    }

                    Group("каталог")
                    val info = vm.catalogInfo
                    Text(
                        if (info != null) "${info.second} фильмов и сериалов · версия ${info.first?.let(::prettyVersion) ?: "—"}" else "…",
                        style = Type.item, color = T.text,
                    )
                    Text("Названия, годы и жанры — из Wikidata, рейтинги — Кинопоиска и IMDb. Ключи и регистрация не нужны. Каталог обновляется раз в неделю.", style = Type.mono, color = T.text3)
                    Pill(if (vm.updating) "Проверяем…" else "Проверить обновление", vm::updateCatalog)

                    Group("данные")
                    Action("Очистить кэш", "Картинки, ответы сайтов и данные плеера") { vm.clearCache(context) }
                    Action("Очистить историю", "«Продолжить просмотр» и позиции просмотра") { vm.clearHistory() }

                    Spacer(Modifier.height(24.dp))
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        LogoMark(28.dp)
                        Spacer(Modifier.height(8.dp))
                        Text("dopomine ${BuildConfig.VERSION_NAME}", style = Type.mono, color = T.text3)
                        Row {
                            Text("Created by ", style = Type.mono, color = T.text3)
                            Text(
                                "dopamine", style = Type.mono.copy(textDecoration = TextDecoration.Underline), color = T.text,
                                modifier = Modifier.clickable { Launchers.openUrl(context, "https://t.me/d0paminx") },
                            )
                        }
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
    }
}

private fun prettyVersion(v: String) = if (v.length >= 8) "${v.substring(6, 8)}.${v.substring(4, 6)}.${v.substring(0, 4)}" else v

@Composable
private fun Group(title: String) {
    Spacer(Modifier.height(14.dp))
    Caps(title)
    Spacer(Modifier.height(4.dp))
    Divider()
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun Toggle(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onChange(!checked) }.padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = Type.item, color = T.text)
            Text(subtitle, style = Type.monoSmall, color = T.text3)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked)
    }
}

/** splicer's switch: a small pill with a round knob, filled with the text color when on. */
@Composable
private fun Switch(checked: Boolean) {
    Box(
        Modifier.size(40.dp, 23.dp).clip(RoundedCornerShape(999.dp))
            .background(if (checked) T.accent else T.surface3)
            .border(1.dp, if (checked) T.accent else T.border, RoundedCornerShape(999.dp))
            .padding(3.dp),
    ) {
        Box(
            Modifier.offset(x = if (checked) 17.dp else 0.dp).size(17.dp).clip(CircleShape)
                .background(if (checked) T.accentFg else T.surface),
        )
    }
}

@Composable
private fun Action(title: String, subtitle: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(vertical = 8.dp)) {
        Text(title, style = Type.item, color = T.text)
        Text(subtitle, style = Type.monoSmall, color = T.text3)
    }
}

@Composable
private fun Field(value: String, onChange: (String) -> Unit) {
    Box(
        Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(999.dp)).background(T.surface)
            .border(1.dp, T.border, RoundedCornerShape(999.dp)).padding(horizontal = 16.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = Type.mono.copy(color = T.text),
            cursorBrush = SolidColor(T.text),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
