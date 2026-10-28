package com.dopomine.film.ui.settings

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebStorage
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil3.SingletonImageLoader
import com.dopomine.film.AppContainer
import com.dopomine.film.data.catalog.CatalogUpdater
import com.dopomine.film.data.settings.AppSettings
import com.dopomine.film.ui.theme.ThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsViewModel(private val c: AppContainer) : ViewModel() {
    val settings: StateFlow<AppSettings?> = c.settings.state

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    var catalogInfo by mutableStateOf<Pair<String?, Int>?>(null)
        private set
    var updating by mutableStateOf(false)
        private set

    init {
        viewModelScope.launch { catalogInfo = runCatching { c.catalog.catalogInfo() }.getOrNull() }
    }

    fun setTheme(mode: ThemeMode) = viewModelScope.launch { c.settings.setTheme(mode) }
    fun setAnimations(v: Boolean) = viewModelScope.launch { c.settings.setAnimations(v) }
    fun setAdBlock(v: Boolean) = viewModelScope.launch { c.settings.setAdBlock(v) }
    fun setLandscape(v: Boolean) = viewModelScope.launch { c.settings.setLandscapePlayer(v) }
    fun setAutoResume(v: Boolean) = viewModelScope.launch { c.settings.setAutoResume(v) }
    fun setGestures(v: Boolean) = viewModelScope.launch { c.settings.setGestures(v) }
    fun setAutoNext(v: Boolean) = viewModelScope.launch { c.settings.setAutoNext(v) }
    fun setAutoSkip(v: Boolean) = viewModelScope.launch { c.settings.setAutoSkip(v) }

    fun setPlayerHost(host: String) = viewModelScope.launch {
        c.settings.setPlayerHost(host)
        _message.value = "Адрес плеера сохранён"
    }

    fun resetPlayerHost() = viewModelScope.launch {
        c.settings.resetPlayerHost()
        _message.value = "Адрес плеера сброшен"
    }

    fun updateCatalog() {
        if (updating) return
        updating = true
        viewModelScope.launch {
            _message.value = when (val r = c.catalogUpdater.check()) {
                CatalogUpdater.Result.UpToDate -> "Каталог уже свежий"
                is CatalogUpdater.Result.Staged -> "Новый каталог скачан — он подключится при следующем запуске"
                is CatalogUpdater.Result.Failed -> r.message
            }
            updating = false
        }
    }

    fun clearCache(context: Context) {
        val app = context.applicationContext
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                runCatching { c.httpClient.cache?.evictAll() }
                SingletonImageLoader.get(app).let { loader ->
                    loader.memoryCache?.clear()
                    loader.diskCache?.clear()
                }
            }
            WebStorage.getInstance().deleteAllData()
            CookieManager.getInstance().removeAllCookies(null)
            _message.value = "Кэш очищен"
        }
    }

    fun clearHistory() = viewModelScope.launch {
        c.library.clearHistory()
        _message.value = "История очищена"
    }

    fun consumeMessage() {
        _message.value = null
    }
}
