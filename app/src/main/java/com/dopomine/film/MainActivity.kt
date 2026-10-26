package com.dopomine.film

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dopomine.film.ui.common.KpLinks
import com.dopomine.film.ui.navigation.AppNavHost
import com.dopomine.film.ui.theme.DopomineTheme
import com.dopomine.film.ui.theme.ThemeMode
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow

class MainActivity : ComponentActivity() {
    private val deepLinks = Channel<Int>(Channel.CONFLATED)

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        val settings = appContainer.settings
        splash.setKeepOnScreenCondition { settings.state.value == null }
        if (savedInstanceState == null) handleIntent(intent)

        val links = deepLinks.receiveAsFlow()
        setContent {
            val state by settings.state.collectAsStateWithLifecycle()
            val loaded = state ?: return@setContent
            val dark = when (loaded.theme) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            DisposableEffect(dark) {
                val bars = if (dark) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
                onDispose {}
            }
            DopomineTheme(loaded.theme) {
                AppNavHost(deepLinks = links, animations = loaded.animations)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** Kinopoisk links opened with or shared to the app go straight to the title page. */
    private fun handleIntent(intent: Intent?) {
        val text = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.dataString
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            else -> null
        }
        KpLinks.extractId(text)?.let { deepLinks.trySend(it) }
    }
}
