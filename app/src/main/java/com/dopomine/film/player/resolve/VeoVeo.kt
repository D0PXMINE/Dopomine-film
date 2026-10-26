package com.dopomine.film.player.resolve

import com.dopomine.film.data.net.text
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient

/**
 * VeoVeo is another player behind the site. Its own JSON API lists every episode with a
 * plain HLS link (voice-overs and subtitles are renditions inside the playlist), so it plays
 * natively with all of them selectable.
 */
class VeoVeoResolver(private val http: OkHttpClient) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** @param iframeUrl the VeoVeo URL from the site's player list (carries `movie_id`). */
    suspend fun resolve(iframeUrl: String, log: (String) -> Unit): Playable {
        val url = iframeUrl.toHttpUrlOrNull() ?: throw ResolveException("VeoVeo: неверный адрес")
        val id = url.queryParameter("movie_id") ?: url.queryParameter("content-id")
            ?: throw ResolveException("VeoVeo: нет ID фильма")
        val hosts = listOf("${url.scheme}://${url.host}", FALLBACK_HOST).distinct()
        var last: String? = null
        for (host in hosts) {
            val api = "$host/balancer-api/proxy/playlists/catalog-api/episodes?content-id=$id"
            log("VeoVeo: $api")
            val text = try {
                http.text(api, mapOf("Accept" to "application/json", "Referer" to "$host/"))
            } catch (e: Exception) {
                last = e.message
                log("  ошибка: $last")
                continue
            }
            val items = runCatching { json.parseToJsonElement(text).jsonArray }.getOrNull()
            if (items.isNullOrEmpty()) {
                last = "VeoVeo: пустой ответ"
                continue
            }
            return build(items, host)
        }
        throw ResolveException(last ?: "VeoVeo: видео не найдено")
    }

    private suspend fun build(items: JsonArray, host: String): Playable {
        data class Item(val season: Int, val order: Int, val title: String?, val file: String)

        val parsed = items.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val season = (o["season"] as? JsonObject)?.int("order") ?: 0
            val variants = (o["episodeVariants"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            // the HLS variant carries all voice-overs; the MP4 one is a single-voice fallback
            val file = variants.mapNotNull { it.str("filepath") }.sortedByDescending { ".m3u8" in it }.firstOrNull()
                ?: return@mapNotNull null
            Item(season, o.int("order") ?: 0, o.str("title"), file)
        }
        if (parsed.isEmpty()) throw ResolveException("VeoVeo: нет файлов")
        val headers = mapOf("Referer" to "$host/")

        if (parsed.all { it.season == 0 }) {
            return Playable.Single("VeoVeo", stream(parsed.first().file, headers))
        }
        val seasons = parsed.filter { it.season > 0 }.groupBy { it.season }.toSortedMap().map { (number, list) ->
            Season(number, list.sortedBy { it.order }.distinctBy { it.order }.map { item ->
                Episode(
                    season = number,
                    number = item.order,
                    title = item.title?.takeIf { it.isNotBlank() && !it.equals("${item.order} серия", true) },
                    stream = stream(item.file, headers),
                )
            })
        }
        return Playable.Series("VeoVeo", seasons)
    }

    /** Some entries point to a small JSON with the real link; it is resolved when that episode plays. */
    private fun stream(file: String, headers: Map<String, String>): Stream {
        val clean = if (file.startsWith("//")) "https:$file" else file
        return Stream(clean, if (".mp4" in clean.substringBefore('?') && ".m3u8" !in clean) StreamFormat.PROGRESSIVE else StreamFormat.HLS, headers)
    }

    /** Resolves `.json` indirections into the playable link (VeoVeo's "parsed" sources). */
    suspend fun finalize(stream: Stream): Stream {
        if (!stream.url.substringBefore('?').endsWith(".json")) return stream
        val text = http.text(stream.url, stream.headers)
        val link = runCatching {
            json.parseToJsonElement(text).jsonObject["sources"]!!.jsonArray.first().jsonObject.str("link")
        }.getOrNull() ?: throw ResolveException("VeoVeo: пустой источник")
        return stream.copy(url = link, format = Stream.formatOf(link))
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.let { it.intOrNull ?: it.contentOrNull?.toIntOrNull() }

    companion object {
        const val FALLBACK_HOST = "https://api.rstprgapipt.com"
    }
}
