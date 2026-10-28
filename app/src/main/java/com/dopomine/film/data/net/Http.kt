package com.dopomine.film.data.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Cache
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

const val BROWSER_UA =
    "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

object Http {
    /** @param interceptor lets tests answer requests without the network. */
    fun client(cacheDir: File, interceptor: Interceptor? = null): OkHttpClient = OkHttpClient.Builder()
        .cache(Cache(File(cacheDir, "http"), 30L * 1024 * 1024))
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .apply { if (interceptor != null) addInterceptor(interceptor) }
        .addNetworkInterceptor(CacheHints)
        .build()

    /** Metadata changes slowly; caching it saves traffic and makes screens instant on revisit. */
    private object CacheHints : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val response = chain.proceed(request)
            if (!response.isSuccessful) return response
            val host = request.url.host
            val maxAge = when {
                host == "rating.kinopoisk.ru" -> TimeUnit.HOURS.toSeconds(12)
                host.endsWith("wikipedia.org") -> TimeUnit.DAYS.toSeconds(7)
                host == "query.wikidata.org" -> TimeUnit.DAYS.toSeconds(3)
                // intro timestamps change rarely
                host == "api.introdb.app" || host == "api.theintrodb.org" || host == "api.aniskip.com" -> TimeUnit.DAYS.toSeconds(3)
                else -> return response
            }
            return response.newBuilder()
                .removeHeader("Pragma")
                .header("Cache-Control", "public, max-age=$maxAge")
                .build()
        }
    }
}

suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { runCatching { cancel() } }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (!cont.isCancelled) cont.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            cont.resume(response)
        }
    })
}

/** GET returning the body as text; throws IOException for HTTP errors. */
suspend fun OkHttpClient.text(url: String, headers: Map<String, String> = emptyMap()): String {
    val request = Request.Builder().url(url).apply {
        header("User-Agent", BROWSER_UA)
        headers.forEach { (k, v) -> header(k, v) }
    }.build()
    return newCall(request).await().use { response ->
        if (!response.isSuccessful) throw HttpStatusException(response.code, url)
        withContext(Dispatchers.IO) { response.body?.string().orEmpty() }
    }
}

/** POST of a urlencoded form, returning the body as text. */
suspend fun OkHttpClient.postForm(url: String, fields: Map<String, String>): String {
    val body = FormBody.Builder().apply { fields.forEach { (k, v) -> add(k, v) } }.build()
    val request = Request.Builder().url(url).header("User-Agent", BROWSER_UA).post(body).build()
    return newCall(request).await().use { response ->
        if (!response.isSuccessful) throw HttpStatusException(response.code, url)
        withContext(Dispatchers.IO) { response.body?.string().orEmpty() }
    }
}

class HttpStatusException(val code: Int, url: String) : IOException("HTTP $code for $url")
