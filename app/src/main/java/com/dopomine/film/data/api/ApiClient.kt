package com.dopomine.film.data.api

import kotlinx.serialization.json.Json
import okhttp3.Cache
import okhttp3.CacheControl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Response
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

object ApiClient {
    const val BASE_URL = "https://kinopoiskapiunofficial.tech/"
    const val KEY_SITE = "https://kinopoiskapiunofficial.tech/signup"

    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
    }

    fun createHttpClient(cacheDir: File, apiKey: () -> String): OkHttpClient =
        OkHttpClient.Builder()
            .cache(Cache(File(cacheDir, "http"), 50L * 1024 * 1024))
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .addInterceptor(ApiKeyInterceptor(apiKey))
            .addInterceptor(OfflineCacheInterceptor())
            .addNetworkInterceptor(CacheRewriteInterceptor())
            .build()

    fun createApi(client: OkHttpClient): KinopoiskApi =
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(KinopoiskApi::class.java)
}

private class ApiKeyInterceptor(private val apiKey: () -> String) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.url.host != "kinopoiskapiunofficial.tech") return chain.proceed(request)
        val builder = request.newBuilder().header("Accept", "application/json")
        // A request may carry its own key (validation of a newly entered key).
        if (request.header("X-API-KEY").isNullOrEmpty()) builder.header("X-API-KEY", apiKey())
        return chain.proceed(builder.build())
    }
}

/**
 * The API answers with no-cache headers, which would burn the daily request quota.
 * Successful responses are cached for a period that depends on how often the data changes.
 */
private class CacheRewriteInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        if (!response.isSuccessful) return response
        val path = chain.request().url.encodedPath
        val maxAge = when {
            path.contains("/api_keys/") -> 0
            path.endsWith("/filters") -> TimeUnit.DAYS.toSeconds(7)
            path.contains("/collections") -> TimeUnit.HOURS.toSeconds(6)
            path.contains("/staff") || path.contains("/similars") || path.contains("/seasons") -> TimeUnit.DAYS.toSeconds(2)
            Regex("/api/v2\\.2/films/\\d+$").containsMatchIn(path) -> TimeUnit.HOURS.toSeconds(24)
            path.contains("search-by-keyword") -> TimeUnit.HOURS.toSeconds(6)
            else -> TimeUnit.HOURS.toSeconds(6)
        }
        if (maxAge <= 0) return response
        return response.newBuilder()
            .removeHeader("Pragma")
            .removeHeader("Expires")
            .header("Cache-Control", "public, max-age=$maxAge")
            .build()
    }
}

/** Serves stale cached data when the network is unavailable. */
private class OfflineCacheInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        return try {
            chain.proceed(request)
        } catch (e: IOException) {
            val cached = chain.proceed(
                request.newBuilder()
                    .cacheControl(CacheControl.Builder().onlyIfCached().maxStale(30, TimeUnit.DAYS).build())
                    .build()
            )
            if (cached.code == 504) {
                cached.close()
                throw e
            }
            cached
        }
    }
}
