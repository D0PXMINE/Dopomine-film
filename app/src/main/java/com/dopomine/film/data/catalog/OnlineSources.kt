package com.dopomine.film.data.catalog

import android.util.LruCache
import com.dopomine.film.data.model.ContentType
import com.dopomine.film.data.model.Movie
import com.dopomine.film.data.model.Ratings
import com.dopomine.film.data.net.text
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.net.URLEncoder

private val json = Json { ignoreUnknownKeys = true; isLenient = true }

/** Fresh Kinopoisk and IMDb ratings; the public XML endpoint needs no key. */
class RatingsClient(private val http: OkHttpClient) {
    private val cache = LruCache<Int, Ratings>(256)
    private val kp = Regex("""<kp_rating num_vote="(\d+)"[^>]*>([\d.]+)<""")
    private val imdb = Regex("""<imdb_rating num_vote="(\d+)"[^>]*>([\d.]+)<""")

    suspend fun get(id: Int): Ratings? {
        cache.get(id)?.let { return it }
        val text = runCatching { http.text("https://rating.kinopoisk.ru/$id.xml") }.getOrNull() ?: return null
        val k = kp.find(text)
        val i = imdb.find(text)
        if (k == null && i == null) return null
        return Ratings(
            kp = k?.groupValues?.get(2)?.toDoubleOrNull()?.takeIf { it > 0 },
            kpVotes = k?.groupValues?.get(1)?.toIntOrNull() ?: 0,
            imdb = i?.groupValues?.get(2)?.toDoubleOrNull()?.takeIf { it > 0 },
            imdbVotes = i?.groupValues?.get(1)?.toIntOrNull() ?: 0,
        ).also { cache.put(id, it) }
    }
}

/** Russian Wikipedia: the lead section of the article is a good description. */
class WikiClient(private val http: OkHttpClient) {
    suspend fun summary(article: String): String? {
        val path = URLEncoder.encode(article.replace(' ', '_'), "UTF-8").replace("+", "%20")
        val text = runCatching { http.text("https://ru.wikipedia.org/api/rest_v1/page/summary/$path") }.getOrNull() ?: return null
        val obj = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
        return obj["extract"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.length > 40 }
    }
}

/** Wikidata lookups for titles that are not in the offline catalog, plus cast. */
class WikidataClient(private val http: OkHttpClient) {

    private suspend fun sparql(query: String): JsonArray? {
        val url = "https://query.wikidata.org/sparql".toHttpUrl().newBuilder()
            .addQueryParameter("query", query)
            .addQueryParameter("format", "json")
            .build().toString()
        val text = runCatching { http.text(url, mapOf("Accept" to "application/sparql-results+json")) }.getOrNull() ?: return null
        return runCatching { json.parseToJsonElement(text).jsonObject["results"]!!.jsonObject["bindings"]!!.jsonArray }.getOrNull()
    }

    private fun JsonObject.v(key: String): String? = this[key]?.jsonObject?.get("value")?.jsonPrimitive?.contentOrNull

    /** Basic facts for any Kinopoisk ID that Wikidata knows. */
    suspend fun byKinopoiskId(id: Int): Movie? {
        val rows = sparql(
            """
            SELECT ?ru ?en ?ot ?date ?start ?imdb ?wiki ?mal (GROUP_CONCAT(DISTINCT ?typeLabel; separator="|") AS ?types) WHERE {
              ?f wdt:P2603 "$id" .
              OPTIONAL { ?f rdfs:label ?ru FILTER(LANG(?ru) = "ru") }
              OPTIONAL { ?f rdfs:label ?en FILTER(LANG(?en) = "en") }
              OPTIONAL { ?f wdt:P1476 ?ot }
              OPTIONAL { ?f wdt:P577 ?date }
              OPTIONAL { ?f wdt:P580 ?start }
              OPTIONAL { ?f wdt:P345 ?imdb }
              OPTIONAL { ?f wdt:P4086 ?mal }
              OPTIONAL { ?a schema:about ?f ; schema:isPartOf <https://ru.wikipedia.org/> ; schema:name ?wiki }
              OPTIONAL { ?f wdt:P31 ?t . ?t rdfs:label ?typeLabel FILTER(LANG(?typeLabel) = "en") }
            } GROUP BY ?ru ?en ?ot ?date ?start ?imdb ?wiki ?mal LIMIT 1
            """.trimIndent()
        ) ?: return null
        val row = rows.firstOrNull()?.jsonObject ?: return null
        val title = row.v("ru") ?: row.v("en") ?: row.v("ot") ?: return null
        val types = row.v("types").orEmpty().lowercase()
        val type = when {
            "miniseries" in types -> ContentType.MINI
            "series" in types -> ContentType.SERIES
            "television program" in types || "show" in types -> ContentType.SHOW
            else -> ContentType.FILM
        }
        return Movie(
            id = id,
            title = title,
            originalTitle = (row.v("ot") ?: row.v("en"))?.takeIf { it != title },
            year = (row.v("date") ?: row.v("start"))?.take(4)?.toIntOrNull(),
            type = type,
            imdbId = row.v("imdb"),
            wiki = row.v("wiki"),
            malId = row.v("mal")?.toIntOrNull(),
        )
    }

    /** Directors and main cast in Russian. */
    suspend fun people(id: Int): Pair<List<String>, List<String>> {
        val rows = sparql(
            """
            SELECT ?role ?name WHERE {
              ?f wdt:P2603 "$id" .
              { ?f wdt:P57 ?p . BIND("d" AS ?role) } UNION { ?f wdt:P161 ?p . BIND("c" AS ?role) }
              ?p rdfs:label ?name FILTER(LANG(?name) = "ru")
            } LIMIT 40
            """.trimIndent()
        ) ?: return emptyList<String>() to emptyList()
        val directors = mutableListOf<String>()
        val cast = mutableListOf<String>()
        rows.forEach { r ->
            val o = r.jsonObject
            val name = o.v("name") ?: return@forEach
            if (o.v("role") == "d") directors += name else cast += name
        }
        return directors.distinct() to cast.distinct().take(16)
    }

    /** Full-text search over Wikidata items that have a Kinopoisk ID (catches titles missing offline). */
    suspend fun search(query: String): List<Pair<Int, String>> {
        val url = "https://www.wikidata.org/w/api.php".toHttpUrl().newBuilder()
            .addQueryParameter("action", "query")
            .addQueryParameter("list", "search")
            .addQueryParameter("srsearch", "haswbstatement:P2603 $query")
            .addQueryParameter("srlimit", "20")
            .addQueryParameter("format", "json")
            .build().toString()
        val text = runCatching { http.text(url) }.getOrNull() ?: return emptyList()
        val qids = runCatching {
            json.parseToJsonElement(text).jsonObject["query"]!!.jsonObject["search"]!!.jsonArray
                .mapNotNull { it.jsonObject["title"]?.jsonPrimitive?.contentOrNull }
        }.getOrNull().orEmpty()
        if (qids.isEmpty()) return emptyList()
        val rows = sparql(
            """
            SELECT ?f ?kp ?ru ?en WHERE {
              VALUES ?f { ${qids.joinToString(" ") { "wd:$it" }} }
              ?f wdt:P2603 ?kp .
              OPTIONAL { ?f rdfs:label ?ru FILTER(LANG(?ru) = "ru") }
              OPTIONAL { ?f rdfs:label ?en FILTER(LANG(?en) = "en") }
            }
            """.trimIndent()
        ) ?: return emptyList()
        val byQid = rows.associate { r ->
            val o = r.jsonObject
            o.v("f").orEmpty().substringAfterLast('/') to ((o.v("kp")?.toIntOrNull() ?: 0) to (o.v("ru") ?: o.v("en") ?: ""))
        }
        return qids.mapNotNull { byQid[it] }.filter { it.first > 0 && it.second.isNotBlank() }.distinctBy { it.first }
    }
}
