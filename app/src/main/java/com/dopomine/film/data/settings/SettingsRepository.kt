package com.dopomine.film.data.settings

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.dopomine.film.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.runBlocking

private val Context.dataStore by preferencesDataStore(name = "settings")

@Immutable
data class AppSettings(
    val apiKey: String = BuildConfig.KP_API_KEY,
    val playerHost: String = BuildConfig.DEFAULT_PLAYER_HOST,
    val adBlock: Boolean = true,
    val landscapePlayer: Boolean = true,
    val autoResume: Boolean = true,
    val gestures: Boolean = true,
    val onboardingDone: Boolean = false,
)

class SettingsRepository(context: Context, scope: CoroutineScope) {
    private val store = context.applicationContext.dataStore

    private object Keys {
        val apiKey = stringPreferencesKey("api_key")
        val playerHost = stringPreferencesKey("player_host")
        val adBlock = booleanPreferencesKey("ad_block")
        val landscape = booleanPreferencesKey("landscape_player")
        val autoResume = booleanPreferencesKey("auto_resume")
        val gestures = booleanPreferencesKey("gestures")
        val onboardingDone = booleanPreferencesKey("onboarding_done")
    }

    private fun Preferences.toSettings(): AppSettings {
        val defaults = AppSettings()
        return AppSettings(
            apiKey = this[Keys.apiKey]?.takeIf { it.isNotBlank() } ?: defaults.apiKey,
            playerHost = this[Keys.playerHost]?.takeIf { it.isNotBlank() } ?: defaults.playerHost,
            adBlock = this[Keys.adBlock] ?: defaults.adBlock,
            landscapePlayer = this[Keys.landscape] ?: defaults.landscapePlayer,
            autoResume = this[Keys.autoResume] ?: defaults.autoResume,
            gestures = this[Keys.gestures] ?: defaults.gestures,
            onboardingDone = this[Keys.onboardingDone] ?: false,
        )
    }

    /** `null` until the first read from disk completes. */
    val state: StateFlow<AppSettings?> = store.data
        .map { it.toSettings() }
        .stateIn(scope, SharingStarted.Eagerly, null)

    suspend fun current(): AppSettings = state.filterNotNull().first()

    /** Blocking read for OkHttp threads; never called on the main thread. */
    fun apiKeyBlocking(): String = state.value?.apiKey ?: runBlocking { current().apiKey }

    suspend fun apiKey(): String = current().apiKey

    suspend fun setApiKey(key: String) = store.edit { it[Keys.apiKey] = key.trim() }

    suspend fun setPlayerHost(host: String) = store.edit { it[Keys.playerHost] = normalizeHost(host) }

    suspend fun resetPlayerHost() = store.edit { it.remove(Keys.playerHost) }

    suspend fun setAdBlock(value: Boolean) = store.edit { it[Keys.adBlock] = value }

    suspend fun setLandscapePlayer(value: Boolean) = store.edit { it[Keys.landscape] = value }

    suspend fun setAutoResume(value: Boolean) = store.edit { it[Keys.autoResume] = value }

    suspend fun setGestures(value: Boolean) = store.edit { it[Keys.gestures] = value }

    suspend fun setOnboardingDone() = store.edit { it[Keys.onboardingDone] = true }

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
