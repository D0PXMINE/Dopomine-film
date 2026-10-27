package com.dopomine.film.data.catalog

import com.dopomine.film.BuildConfig
import com.dopomine.film.data.net.BROWSER_UA
import com.dopomine.film.data.net.await
import com.dopomine.film.data.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.util.zip.GZIPInputStream

/**
 * Downloads a newer catalog snapshot (rebuilt weekly by GitHub Actions) and stages it;
 * it replaces the current one the next time the app starts.
 */
class CatalogUpdater(
    private val db: CatalogDb,
    private val http: OkHttpClient,
    private val settings: SettingsRepository,
) {
    sealed interface Result {
        data object UpToDate : Result
        data class Staged(val version: String) : Result
        data class Failed(val message: String) : Result
    }

    fun checkInBackground(scope: CoroutineScope) {
        scope.launch {
            val last = settings.current().catalogCheckedAt
            if (System.currentTimeMillis() - last > 3 * 24 * 3600_000L) check()
        }
    }

    suspend fun check(): Result = withContext(Dispatchers.IO) {
        try {
            val current = runCatching { db.version() }.getOrNull()
            // a few bytes first: the archive is downloaded only when it is actually newer
            val published = get(versionUrl()) { it.body!!.string().trim() }
            settings.setCatalogCheckedAt(System.currentTimeMillis())
            if (current != null && published <= current) return@withContext Result.UpToDate

            val tmp = File(db.pendingFile.parentFile, "download.tmp")
            get(BuildConfig.CATALOG_UPDATE_URL) { response ->
                GZIPInputStream(response.body!!.byteStream()).use { input -> tmp.outputStream().use { input.copyTo(it, 64 * 1024) } }
            }
            val newVersion = db.versionOf(tmp)?.takeIf { db.isCompatible(tmp) }
                ?: run { tmp.delete(); return@withContext Result.Failed("Скачанный каталог повреждён или устарел") }
            if (current != null && newVersion <= current) {
                tmp.delete()
                return@withContext Result.UpToDate
            }
            tmp.renameTo(db.pendingFile)
            Result.Staged(newVersion)
        } catch (e: UpdateUnavailable) {
            settings.setCatalogCheckedAt(System.currentTimeMillis())
            Result.Failed("Обновление недоступно (HTTP ${e.code})")
        } catch (e: Exception) {
            Result.Failed(e.message ?: "Нет соединения")
        }
    }

    private suspend fun <T> get(url: String, read: (Response) -> T): T {
        val request = Request.Builder().url(url).header("User-Agent", BROWSER_UA).build()
        return http.newCall(request).await().use { response ->
            if (!response.isSuccessful) throw UpdateUnavailable(response.code)
            read(response)
        }
    }

    private fun versionUrl() = BuildConfig.CATALOG_UPDATE_URL.substringBeforeLast('/') + "/version.txt"

    private class UpdateUnavailable(val code: Int) : Exception()
}
