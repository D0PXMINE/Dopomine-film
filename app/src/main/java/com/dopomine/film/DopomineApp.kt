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
import com.dopomine.film.data.catalog.CatalogDb
import com.dopomine.film.data.catalog.CatalogRepository
import com.dopomine.film.data.catalog.CatalogUpdater
import com.dopomine.film.data.catalog.RatingsClient
import com.dopomine.film.data.catalog.WikiClient
import com.dopomine.film.data.catalog.WikidataClient
import com.dopomine.film.data.db.AppDatabase
import com.dopomine.film.data.net.Http
import com.dopomine.film.data.settings.SettingsRepository
import com.dopomine.film.player.AdBlocker
import com.dopomine.film.player.resolve.CollapsResolver
import com.dopomine.film.player.resolve.PlayerSite
import com.dopomine.film.player.resolve.AniLibertyResolver
import com.dopomine.film.player.resolve.AnimevostResolver
import com.dopomine.film.player.resolve.VeoVeoResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.Dispatcher
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Manual dependency container: the app is small enough not to need a DI framework.
 * [testInterceptor] lets tests answer web requests with canned data.
 */
class AppContainer(context: Context, testInterceptor: Interceptor? = null) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val settings = SettingsRepository(context, appScope)

    /** Metadata requests (cached). */
    val httpClient: OkHttpClient = Http.client(context.cacheDir, testInterceptor)

    /** Video playlists and segments: same connection pool, no disk cache to pollute. */
    val mediaClient: OkHttpClient = httpClient.newBuilder()
        .cache(null)
        .apply { networkInterceptors().clear() }
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    val catalogDb = CatalogDb(context)
    val catalog = CatalogRepository(catalogDb, RatingsClient(httpClient), WikiClient(httpClient), WikidataClient(httpClient))
    val catalogUpdater = CatalogUpdater(catalogDb, mediaClient, settings)
    val database = AppDatabase.create(context)
    val library = LibraryRepository(database)
    val adBlocker = AdBlocker(context, appScope)
    val playerSite = PlayerSite(httpClient)
    val collaps = CollapsResolver(httpClient)
    val veoveo = VeoVeoResolver(httpClient)
    val aniliberty = AniLibertyResolver(httpClient)
    val animevost = AnimevostResolver(httpClient)
}

class DopomineApp : Application(), SingletonImageLoader.Factory {
    lateinit var container: AppContainer
        internal set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.catalogUpdater.checkInBackground(container.appScope)
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
            .crossfade(160)
            .build()
    }
}

val Context.appContainer: AppContainer
    get() = (applicationContext as DopomineApp).container
