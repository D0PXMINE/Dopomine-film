package com.dopomine.film.data.settings

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.dopomine.film.BuildConfig
import com.dopomine.film.ui.theme.ThemeMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

private val Context.dataStore by preferencesDataStore(name = "settings")

@Immutable
data class AppSettings(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val animations: Boolean = true,
    val playerHost: String = BuildConfig.DEFAULT_PLAYER_HOST,
    val adBlock: Boolean = true,
    val landscapePlayer: Boolean = true,
    val autoResume: Boolean = true,
    val gestures: Boolean = true,
    val autoNext: Boolean = true,
    val catalogCheckedAt: Long = 0L,
)

class SettingsRepository(context: Context, scope: CoroutineScope) {
    private val store = context.applicationContext.dataStore

    private object Keys {
        val theme = stringPreferencesKey("theme")
        val animations = booleanPreferencesKey("animations")
        val playerHost = stringPreferencesKey("player_host")
        val adBlock = booleanPreferencesKey("ad_block")
        val landscape = booleanPreferencesKey("landscape_player")
        val autoResume = booleanPreferencesKey("auto_resume")
        val gestures = booleanPreferencesKey("gestures")
        val autoNext = booleanPreferencesKey("auto_next")
        val catalogCheckedAt = longPreferencesKey("catalog_checked_at")
    }

    private fun Preferences.toSettings(): AppSettings {
        val d = AppSettings()
        return AppSettings(
            theme = this[Keys.theme]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: d.theme,
            animations = this[Keys.animations] ?: d.animations,
            playerHost = this[Keys.playerHost]?.takeIf { it.isNotBlank() } ?: d.playerHost,
            adBlock = this[Keys.adBlock] ?: d.adBlock,
            landscapePlayer = this[Keys.landscape] ?: d.landscapePlayer,
            autoResume = this[Keys.autoResume] ?: d.autoResume,
            gestures = this[Keys.gestures] ?: d.gestures,
            autoNext = this[Keys.autoNext] ?: d.autoNext,
            catalogCheckedAt = this[Keys.catalogCheckedAt] ?: 0L,
        )
    }

    /** `null` until the first read from disk completes. */
    val state: StateFlow<AppSettings?> = store.data
        .map { it.toSettings() }
        .stateIn(scope, SharingStarted.Eagerly, null)

    suspend fun current(): AppSettings = state.filterNotNull().first()

    suspend fun setTheme(mode: ThemeMode) = store.edit { it[Keys.theme] = mode.name }
    suspend fun setAnimations(value: Boolean) = store.edit { it[Keys.animations] = value }
    suspend fun setPlayerHost(host: String) = store.edit { it[Keys.playerHost] = normalizeHost(host) }
    suspend fun resetPlayerHost() = store.edit { it.remove(Keys.playerHost) }
    suspend fun setAdBlock(value: Boolean) = store.edit { it[Keys.adBlock] = value }
    suspend fun setLandscapePlayer(value: Boolean) = store.edit { it[Keys.landscape] = value }
    suspend fun setAutoResume(value: Boolean) = store.edit { it[Keys.autoResume] = value }
    suspend fun setGestures(value: Boolean) = store.edit { it[Keys.gestures] = value }
    suspend fun setAutoNext(value: Boolean) = store.edit { it[Keys.autoNext] = value }
    suspend fun setCatalogCheckedAt(value: Long) = store.edit { it[Keys.catalogCheckedAt] = value }

    companion object {
        /** Accepts "domain.tld", "https://domain.tld/123" etc. and returns "https://domain.tld". */
        fun normalizeHost(input: String): String {
            var value = input.trim()
            if (value.isEmpty()) return BuildConfig.DEFAULT_PLAYER_HOST
            if (!value.startsWith("http://") && !value.startsWith("https://")) value = "https://$value"
            val schemeEnd = value.indexOf("://") + 3
            val pathStart = value.indexOf('/', schemeEnd).takeIf { it > 0 } ?: value.length
            return value.substring(0, pathStart).lowercase()
        }
    }
}
