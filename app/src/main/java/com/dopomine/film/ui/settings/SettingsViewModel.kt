package com.dopomine.film.ui.settings

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebStorage
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil3.SingletonImageLoader
import com.dopomine.film.AppContainer
import com.dopomine.film.data.AppException
import com.dopomine.film.data.model.ApiKeyInfo
import com.dopomine.film.data.settings.AppSettings
import com.dopomine.film.data.toAppException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Immutable
sealed interface KeyCheck {
    data object Idle : KeyCheck
    data object Checking : KeyCheck
    data class Valid(val info: ApiKeyInfo) : KeyCheck
    data class Invalid(val error: AppException) : KeyCheck
}

class SettingsViewModel(private val container: AppContainer) : ViewModel() {
    val settings: StateFlow<AppSettings?> = container.settings.state

    private val _keyCheck = MutableStateFlow<KeyCheck>(KeyCheck.Idle)
    val keyCheck: StateFlow<KeyCheck> = _keyCheck.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /** Validates the key against the API and saves it when it works. */
    fun saveKey(key: String, onSaved: () -> Unit = {}) {
        val trimmed = key.trim()
        if (trimmed.isEmpty()) {
            viewModelScope.launch { container.settings.setApiKey("") }
            _keyCheck.value = KeyCheck.Idle
            return
        }
        _keyCheck.value = KeyCheck.Checking
        viewModelScope.launch {
            try {
                val info = container.movies.keyInfo(trimmed)
                container.settings.setApiKey(trimmed)
                container.settings.setOnboardingDone()
                container.movies.clearMemoryCache()
                _keyCheck.value = KeyCheck.Valid(info)
                onSaved()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _keyCheck.value = KeyCheck.Invalid(e.toAppException())
            }
        }
    }

    fun checkCurrentKey() {
        val key = settings.value?.apiKey.orEmpty()
        if (key.isBlank()) return
        _keyCheck.value = KeyCheck.Checking
        viewModelScope.launch {
            _keyCheck.value = try {
                KeyCheck.Valid(container.movies.keyInfo(key))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                KeyCheck.Invalid(e.toAppException())
            }
        }
    }

    fun skipOnboarding() = viewModelScope.launch { container.settings.setOnboardingDone() }

    fun setPlayerHost(host: String) = viewModelScope.launch {
        container.settings.setPlayerHost(host)
        _message.value = "Адрес плеера сохранён"
    }

    fun resetPlayerHost() = viewModelScope.launch {
        container.settings.resetPlayerHost()
        _message.value = "Адрес плеера сброшен"
    }

    fun setAdBlock(v: Boolean) = viewModelScope.launch { container.settings.setAdBlock(v) }
    fun setLandscape(v: Boolean) = viewModelScope.launch { container.settings.setLandscapePlayer(v) }
    fun setAutoResume(v: Boolean) = viewModelScope.launch { container.settings.setAutoResume(v) }
    fun setGestures(v: Boolean) = viewModelScope.launch { container.settings.setGestures(v) }

    fun clearCache(context: Context) {
        val app = context.applicationContext
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                runCatching { container.httpClient.cache?.evictAll() }
                SingletonImageLoader.get(app).let { loader ->
                    loader.memoryCache?.clear()
                    loader.diskCache?.clear()
                }
            }
            container.movies.clearMemoryCache()
            WebStorage.getInstance().deleteAllData()
            CookieManager.getInstance().removeAllCookies(null)
            _message.value = "Кэш очищен"
        }
    }

    fun clearHistory() = viewModelScope.launch {
        container.library.clearHistory()
        _message.value = "История очищена"
    }

    fun consumeMessage() {
        _message.value = null
    }
}
