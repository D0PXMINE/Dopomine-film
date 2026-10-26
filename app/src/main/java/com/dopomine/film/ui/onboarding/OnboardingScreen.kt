package com.dopomine.film.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dopomine.film.data.api.ApiClient
import com.dopomine.film.ui.common.Launchers
import com.dopomine.film.ui.common.appViewModel
import com.dopomine.film.ui.components.GradientButton
import com.dopomine.film.ui.components.Logo
import com.dopomine.film.ui.components.LogoMark
import com.dopomine.film.ui.settings.Field
import com.dopomine.film.ui.settings.KeyCheck
import com.dopomine.film.ui.settings.KeyStatus
import com.dopomine.film.ui.settings.SettingsViewModel
import com.dopomine.film.ui.theme.Palette

@Composable
fun OnboardingScreen(onDone: () -> Unit) {
    val vm = appViewModel { c, _ -> SettingsViewModel(c) }
    val keyCheck by vm.keyCheck.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var key by rememberSaveable { mutableStateOf("") }

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF1A1030), Palette.Background, Palette.Background))),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(32.dp))
            LogoMark(96.dp)
            Spacer(Modifier.height(20.dp))
            Logo()
            Spacer(Modifier.height(12.dp))
            Text(
                "Фильмы и сериалы с рейтингами Кинопоиска и IMDb. Без рекламы и подписок.",
                style = MaterialTheme.typography.bodyLarge,
                color = Palette.TextSecondary,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(32.dp))
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(24.dp))
                    .background(Palette.Surface)
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Подключите каталог", style = MaterialTheme.typography.titleLarge)
                Step(1, "Зарегистрируйтесь на kinopoiskapiunofficial.tech — это бесплатно")
                Step(2, "Скопируйте API-ключ из профиля")
                Step(3, "Вставьте его ниже")
                TextButton(onClick = { Launchers.openUrl(context, ApiClient.KEY_SITE) }) { Text("Открыть сайт для получения ключа") }
                Field(value = key, onValueChange = { key = it }, label = "API-ключ")
                KeyStatus(keyCheck)
                GradientButton(
                    text = if (keyCheck == KeyCheck.Checking) "Проверяем…" else "Продолжить",
                    onClick = { vm.saveKey(key, onSaved = onDone) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = { vm.skipOnboarding(); onDone() }) {
                Text("Пропустить — ключ можно указать позже в настройках", color = Palette.TextMuted, textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
private fun Step(number: Int, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(26.dp)
                .clip(CircleShape)
                .background(Palette.AccentGradient),
            contentAlignment = Alignment.Center,
        ) { Text(number.toString(), style = MaterialTheme.typography.labelLarge, color = Color.White) }
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}
