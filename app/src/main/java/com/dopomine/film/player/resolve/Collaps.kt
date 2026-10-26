package com.dopomine.film.player.resolve

import com.dopomine.film.data.net.HttpStatusException
import com.dopomine.film.data.net.text
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient

/**
 * Collaps is one of the players behind the site. Its embed page carries the stream config in
 * plain sight (HLS/DASH links, voice-over names, subtitles, seasons), which is exactly what the
 * site's actualize.js fetches and writes into its iframe. We read the same page and play the
 * stream natively.
 */
class CollapsResolver(private val http: OkHttpClient) {

    @Volatile private var actual: Pair<String, Long>? = null

    /** Current Collaps API host, published by the script the player site itself loads. */
    suspend fun actualHost(): String? {
        actual?.let { (host, at) -> if (System.currentTimeMillis() - at < 6 * 3600_000L) return host }
        val script = runCatching { http.text(ACTUALIZE_JS) }.getOrNull() ?: return actual?.first
        val host = Regex("""var\s+actual\s*=\s*["'](https?://[^"']+)["']""").find(script)?.groupValues?.get(1)
        if (host != null) actual = host.trimEnd('/') to System.currentTimeMillis()
        return host?.trimEnd('/') ?: actual?.first
    }

    /**
     * @param iframeUrl the Collaps URL from the site's player list, e.g. https://api.ortified.ws/embed/movie/474
     */
    suspend fun resolve(kp: Int, imdb: String?, iframeUrl: String?, referer: String, log: (String) -> Unit): Playable {
        val host = actualHost()
        val candidates = buildList {
            if (iframeUrl != null) {
                val parsed = iframeUrl.toHttpUrlOrNull()
                if (host != null && parsed != null) add(host + parsed.encodedPath + (parsed.encodedQuery?.let { "?$it" } ?: ""))
                add(iframeUrl)
            }
            for (h in listOfNotNull(host, FALLBACK_HOST).distinct()) {
                if (kp > 0) add("$h/embed/kp/$kp")
                if (imdb != null) add("$h/embed/imdb/$imdb")
            }
        }.distinct()
        if (candidates.isEmpty()) throw ResolveException("Collaps: нет адреса")

        var lastError: String? = null
        for (url in candidates) {
            log("Collaps: $url")
            val html = try {
                http.text(url, mapOf("Referer" to referer, "Origin" to referer.trimEnd('/'), "Accept" to "text/html,application/xhtml+xml"))
            } catch (e: HttpStatusException) {
                lastError = "HTTP ${e.code}"
                log("  $lastError")
                continue
            } catch (e: Exception) {
                lastError = e.message ?: e.javaClass.simpleName
                log("  ошибка: $lastError")
                continue
            }
            val origin = url.toHttpUrlOrNull()?.let { "${it.scheme}://${it.host}" } ?: continue
            try {
                // what the player's own requests look like (XHR from the embed's origin)
                return CollapsParser.parse(
                    html,
                    mapOf(
                        "Referer" to "$origin/", "Origin" to origin, "Accept" to "*/*",
                        "Sec-Fetch-Dest" to "empty", "Sec-Fetch-Mode" to "cors", "Sec-Fetch-Site" to "cross-site",
                    ),
                )
            } catch (e: ResolveException) {
                lastError = e.message
                log("  ${e.message}")
            }
        }
        throw ResolveException(lastError ?: "Collaps: видео не найдено")
    }

    companion object {
        const val ACTUALIZE_JS = "https://kodir2.github.io/actualize.js"
        const val FALLBACK_HOST = "https://api.luxembd.ws"
    }
}

/** Pulls the player config out of a Collaps embed page. Tolerant to JS-style (unquoted) keys. */
object CollapsParser {
    private val json = Json { isLenient = true; ignoreUnknownKeys = true }

    fun parse(html: String, headers: Map<String, String>): Playable {
        val seasonsAt = Regex("""["']?seasons["']?\s*:\s*\[""").find(html)
        if (seasonsAt != null) {
            val seasons = balanced(html, seasonsAt.range.last)?.let { parseSeasons(it, headers) }.orEmpty()
            if (seasons.isNotEmpty()) return Playable.Series("Collaps", seasons)
        }
        val config = html.substringAfter("makePlayer({", html)
        val hls = stringValue(config, "hls")
        val dash = stringValue(config, "dash") ?: stringValue(config, "dasha")
        val url = hls ?: dash
        if (url == null) {
            if ("недоступно" in html || "unavailable" in html.lowercase()) {
                throw ResolveException("Collaps: видео недоступно в этом регионе")
            }
            throw ResolveException("Collaps: ссылка на видео не найдена")
        }
        return Playable.Single(
            "Collaps",
            Stream(
                url = absolute(url),
                format = if (hls != null) StreamFormat.HLS else StreamFormat.DASH,
                headers = headers,
                audioNames = audioNames(config),
                subtitles = subtitles(config),
            ),
        )
    }

    private fun parseSeasons(raw: String, headers: Map<String, String>): List<Season> {
        val array = parseLenient(raw) as? JsonArray ?: return emptyList()
        return array.mapIndexedNotNull { si, s ->
            val so = s as? JsonObject ?: return@mapIndexedNotNull null
            val number = so.int("season") ?: (si + 1)
            val episodes = (so["episodes"] as? JsonArray).orEmpty().mapIndexedNotNull { ei, e ->
                val eo = e as? JsonObject ?: return@mapIndexedNotNull null
                val hls = eo.str("hls") ?: eo.str("file")
                val dash = eo.str("dasha") ?: eo.str("dash")
                val link = hls ?: dash ?: return@mapIndexedNotNull null
                val audio = (eo["audio"] as? JsonObject)?.get("names") as? JsonArray
                Episode(
                    season = number,
                    number = eo.int("episode") ?: eo.int("number") ?: (ei + 1),
                    title = eo.str("title") ?: eo.str("name"),
                    stream = Stream(
                        url = absolute(link),
                        format = if (hls != null) StreamFormat.HLS else StreamFormat.DASH,
                        headers = headers,
                        audioNames = audio?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty(),
                        subtitles = (eo["cc"] as? JsonArray)?.let(::subtitleList).orEmpty(),
                    ),
                )
            }.sortedBy { it.number }
            if (episodes.isEmpty()) null else Season(number, episodes)
        }.sortedBy { it.number }
    }

    private fun audioNames(html: String): List<String> {
        val at = Regex("""["']?names["']?\s*:\s*\[""").find(html) ?: return emptyList()
        val raw = balanced(html, at.range.last) ?: return emptyList()
        return (parseLenient(raw) as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
    }

    private fun subtitles(html: String): List<Subtitle> {
        val at = Regex("""["']?cc["']?\s*:\s*\[""").find(html) ?: return emptyList()
        val raw = balanced(html, at.range.last) ?: return emptyList()
        return (parseLenient(raw) as? JsonArray)?.let(::subtitleList).orEmpty()
    }

    private fun subtitleList(array: JsonArray): List<Subtitle> = array.mapNotNull { el ->
        val o = el as? JsonObject ?: return@mapNotNull null
        val url = o.str("url") ?: o.str("file") ?: return@mapNotNull null
        Subtitle(absolute(url), o.str("name") ?: o.str("label") ?: "Субтитры", o.str("lang"))
    }

    /** `key: "value"` or `"key":"value"` — first occurrence. */
    private fun stringValue(html: String, key: String): String? =
        Regex("""["']?\b$key["']?\s*:\s*["']([^"']+)["']""").find(html)?.groupValues?.get(1)
            ?.let(::unescape)
            ?.takeIf { it.startsWith("http") || it.startsWith("//") }

    /** JS string escapes the page uses in links: `\/` and `\u0026` (&). */
    private fun unescape(s: String): String =
        Regex("""\\u([0-9a-fA-F]{4})""").replace(s.replace("\\/", "/")) { it.groupValues[1].toInt(16).toChar().toString() }

    private fun absolute(url: String): String {
        val clean = unescape(url)
        return if (clean.startsWith("//")) "https:$clean" else clean
    }

    /** The bracketed text starting at [openIndex] ('[' or '{'), honoring strings. */
    fun balanced(text: String, openIndex: Int): String? {
        val open = text.getOrNull(openIndex) ?: return null
        val close = when (open) { '[' -> ']'; '{' -> '}'; else -> return null }
        var depth = 0
        var quote: Char? = null
        var i = openIndex
        while (i < text.length) {
            val c = text[i]
            if (quote != null) {
                if (c == '\\') i++ else if (c == quote) quote = null
            } else when (c) {
                '"', '\'' -> quote = c
                open -> depth++
                close -> {
                    depth--
                    if (depth == 0) return text.substring(openIndex, i + 1)
                }
            }
            i++
        }
        return null
    }

    fun parseLenient(raw: String): JsonElement? =
        runCatching { json.parseToJsonElement(raw) }.getOrNull()
            ?: runCatching { json.parseToJsonElement(quoteKeys(raw)) }.getOrNull()

    /** Turns JS object literals (`{a: 1, 'b': "x"}`) into JSON. */
    private fun quoteKeys(raw: String): String {
        val sb = StringBuilder(raw.length + 64)
        var i = 0
        var quote: Char? = null
        while (i < raw.length) {
            val c = raw[i]
            if (quote != null) {
                when {
                    c == '\\' -> { sb.append(c); if (i + 1 < raw.length) sb.append(raw[i + 1]); i += 2; continue }
                    c == quote -> { sb.append('"'); quote = null }
                    c == '"' -> sb.append("\\\"")
                    else -> sb.append(c)
                }
                i++
                continue
            }
            when {
                c == '"' || c == '\'' -> { quote = c; sb.append('"') }
                (c.isLetter() || c == '_') && (sb.trimEnd().lastOrNull() == '{' || sb.trimEnd().lastOrNull() == ',') -> {
                    val start = i
                    while (i < raw.length && (raw[i].isLetterOrDigit() || raw[i] == '_')) i++
                    var j = i
                    while (j < raw.length && raw[j].isWhitespace()) j++
                    if (j < raw.length && raw[j] == ':') sb.append('"').append(raw, start, i).append('"')
                    else sb.append(raw, start, i)
                    continue
                }
                else -> sb.append(c)
            }
            i++
        }
        // trailing commas are not JSON either
        return sb.toString().replace(Regex(""",\s*([}\]])"""), "$1")
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
    private fun JsonObject.int(key: String): Int? =
        (this[key] as? JsonPrimitive)?.let { it.intOrNull ?: it.contentOrNull?.trim()?.toIntOrNull() }
}
