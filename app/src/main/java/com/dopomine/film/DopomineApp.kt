package com.dopomine.film

import android.app.Application
import android.content.Context
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import com.dopomine.film.data.LibraryRepository
import com.dopomine.film.data.MovieRepository
import com.dopomine.film.data.api.ApiClient
import com.dopomine.film.data.api.KinopoiskApi
import com.dopomine.film.data.db.AppDatabase
import com.dopomine.film.data.settings.SettingsRepository
import com.dopomine.film.player.AdBlocker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Manual dependency container: the app is small enough not to need a DI framework.
 * [api] can be replaced in tests.
 */
class AppContainer(context: Context, api: KinopoiskApi? = null) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val settings = SettingsRepository(context, appScope)
    val httpClient: OkHttpClient = ApiClient.createHttpClient(context.cacheDir) { settings.apiKeyBlocking() }
    val movies = MovieRepository(api ?: ApiClient.createApi(httpClient), settings)
    val database = AppDatabase.create(context)
    val library = LibraryRepository(database)
    val adBlocker = AdBlocker(context, appScope)
}

class DopomineApp : Application(), SingletonImageLoader.Factory {
    lateinit var container: AppContainer
        internal set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader {
        val imageClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .dispatcher(Dispatcher().apply { maxRequestsPerHost = 12 })
            .build()
        return ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { imageClient })) }
            .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.20).build() }
            .diskCache {
                DiskCache.Builder()
                    .directory(context.cacheDir.resolve("images"))
                    .maxSizeBytes(250L * 1024 * 1024)
                    .build()
            }
            .crossfade(180)
            .build()
    }
}

val Context.appContainer: AppContainer
    get() = (applicationContext as DopomineApp).container
