package com.dopomine.film.player.resolve

import com.dopomine.film.data.net.text
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

/**
 * The player site from the userscript (`<mirror>/<Kinopoisk ID>`) embeds a "kinobox" picker
 * (linkpp.ink) which asks `p.<picker host>/api/players` for the list of players. We ask the
 * same API directly to learn which players have the title.
 */
class PlayerSite(private val http: OkHttpClient) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Volatile private var pickerHost: String? = null

    /** Host of the picker iframe, read from the mirror's page (it can change with the mirror). */
    suspend fun pickerHost(mirror: String, kp: Int): String {
        pickerHost?.let { return it }
        val page = runCatching { http.text("$mirror/$kp", mapOf("Referer" to "$mirror/")) }.getOrNull()
        val host = page?.let { Regex("""<iframe[^>]+src="https://([a-z0-9.-]+)/\?id=""").find(it)?.groupValues?.get(1) }
            ?: DEFAULT_PICKER
        pickerHost = host
        return host
    }

    suspend fun sources(mirror: String, kp: Int, imdb: String? = null): List<SourceInfo> {
        val host = pickerHost(mirror, kp)
        val url = "https://p.$host/api/players".toHttpUrl().newBuilder().apply {
            if (kp > 0) addQueryParameter("kinopoisk", kp.toString()) else if (imdb != null) addQueryParameter("imdb", imdb)
        }.build().toString()
        val text = http.text(url, mapOf("Referer" to "https://$host/", "Origin" to "https://$host"))
        val data = json.parseToJsonElement(text).jsonObject["data"]?.jsonArray ?: return emptyList()
        return data.mapNotNull { el ->
            val o = el.jsonObject
            val name = o["type"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val iframe = o["iframeUrl"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            if (!iframe.startsWith("http")) return@mapNotNull null // e.g. Vibix gives an embed snippet
            val voices = o["translations"]?.jsonArray?.mapNotNull { t ->
                val to = t.jsonObject
                val n = to["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val q = to["quality"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() && it != "-" }
                if (q != null) "$n · $q" else n
            }.orEmpty()
            SourceInfo(name, iframe, voices)
        }
    }

    companion object {
        const val DEFAULT_PICKER = "linkpp.ink"
    }
}
