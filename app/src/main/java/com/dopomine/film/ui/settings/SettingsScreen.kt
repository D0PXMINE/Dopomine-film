package com.dopomine.film.ui.settings

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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dopomine.film.BuildConfig
import com.dopomine.film.data.api.ApiClient
import com.dopomine.film.ui.common.Launchers
import com.dopomine.film.ui.common.appViewModel
import com.dopomine.film.ui.components.GradientButton
import com.dopomine.film.ui.components.LogoMark
import com.dopomine.film.ui.components.TopBar
import com.dopomine.film.ui.navigation.Nav
import com.dopomine.film.ui.theme.Palette

@Composable
fun SettingsScreen(nav: Nav) {
    val vm = appViewModel { c, _ -> SettingsViewModel(c) }
    val settings by vm.settings.collectAsStateWithLifecycle()
    val keyCheck by vm.keyCheck.collectAsStateWithLifecycle()
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
    LaunchedEffect(Unit) { vm.checkCurrentKey() }

    var key by rememberSaveable(s.apiKey) { mutableStateOf(s.apiKey) }
    var host by rememberSaveable(s.playerHost) { mutableStateOf(s.playerHost) }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            TopBar("Настройки", onBack = nav::back)
            Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Card("Кинопоиск API") {
                    Text(
                        "Каталог, рейтинги и описания загружаются через Kinopoisk Unofficial API. Ключ бесплатный — получите его после регистрации на сайте.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Palette.TextSecondary,
                    )
                    Field(value = key, onValueChange = { key = it }, label = "API-ключ")
                    KeyStatus(keyCheck)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        GradientButton("Сохранить", onClick = { vm.saveKey(key) }, height = 44.dp)
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = { Launchers.openUrl(context, ApiClient.KEY_SITE) }) { Text("Получить ключ") }
                    }
                }

                Card("Плеер") {
                    ToggleRow("Блокировать рекламу", "Фильтр рекламы и всплывающих окон в плеере", s.adBlock, vm::setAdBlock)
                    ToggleRow("Горизонтальный режим", "Открывать плеер сразу в альбомной ориентации", s.landscapePlayer, vm::setLandscape)
                    ToggleRow("Продолжать с места остановки", "Запоминать позицию просмотра", s.autoResume, vm::setAutoResume)
                    ToggleRow("Жесты", "Свайп слева — яркость, справа — громкость", s.gestures, vm::setGestures)
                    Spacer(Modifier.height(4.dp))
                    Text("Адрес плеера (зеркало)", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Если сайт плеера сменит адрес, укажите новый. Ссылка открывается как адрес/ID_Кинопоиска.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Palette.TextSecondary,
                    )
                    Field(value = host, onValueChange = { host = it }, label = "https://…", keyboardType = KeyboardType.Uri)
                    Row {
                        TextButton(onClick = { vm.setPlayerHost(host) }) { Text("Сохранить") }
                        TextButton(onClick = vm::resetPlayerHost) { Text("По умолчанию", color = Palette.TextSecondary) }
                    }
                }

                Card("Данные") {
                    ActionRow("Очистить кэш", "Картинки, ответы каталога и данные плеера") { vm.clearCache(context) }
                    ActionRow("Очистить историю", "Список «Продолжить просмотр» и позиции") { vm.clearHistory() }
                }

                Row(
                    Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LogoMark(20.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Dopomine ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall, color = Palette.TextMuted)
                }
                Spacer(Modifier.height(16.dp))
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
    }
}

@Composable
fun KeyStatus(check: KeyCheck) {
    when (check) {
        KeyCheck.Idle -> Unit
        KeyCheck.Checking -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
            Text("Проверяем ключ…", style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary)
        }
        is KeyCheck.Valid -> Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.CheckCircle, null, tint = Palette.RatingGood, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            val quota = if (check.info.dailyLimit > 0) " · сегодня ${check.info.dailyUsed} из ${check.info.dailyLimit} запросов" else ""
            Text("Ключ работает$quota", style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary)
        }
        is KeyCheck.Invalid -> Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.ErrorOutline, null, tint = Palette.RatingBad, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(check.error.userMessage, style = MaterialTheme.typography.bodySmall, color = Palette.RatingBad)
        }
    }
}

@Composable
fun Field(value: String, onValueChange: (String) -> Unit, label: String, keyboardType: KeyboardType = KeyboardType.Ascii) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, autoCorrectEnabled = false),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Palette.Accent,
            unfocusedBorderColor = Palette.Outline,
            focusedLabelColor = Palette.Accent,
            cursorColor = Palette.Accent,
        ),
    )
}

@Composable
private fun Card(title: String, content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Palette.Surface)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = Palette.Accent)
        content()
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { onChange(!checked) }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary)
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = Palette.Accent, checkedThumbColor = Color.White),
        )
    }
}

@Composable
private fun ActionRow(title: String, subtitle: String, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary)
    }
}
