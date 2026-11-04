package com.dopomine.film.player.resolve

import androidx.compose.runtime.Immutable
import com.dopomine.film.player.skip.Segment

enum class StreamFormat { HLS, DASH, PROGRESSIVE }

/** One quality of a source that publishes a separate file per quality (AniLiberty, AnimeVost). */
@Immutable
data class Variant(val label: String, val url: String)

@Immutable
data class Subtitle(val url: String, val label: String, val language: String? = null)

/** A direct video stream that ExoPlayer can play, plus what it needs to be fetched. */
@Immutable
data class Stream(
    val url: String,
    val format: StreamFormat,
    val headers: Map<String, String> = emptyMap(),
    /** Voice-over names for the audio renditions, in playlist order (Collaps puts them in the page). */
    val audioNames: List<String> = emptyList(),
    val subtitles: List<Subtitle> = emptyList(),
    /** Separate files per quality, best first; [url] is one of them. Empty for adaptive playlists. */
    val variants: List<Variant> = emptyList(),
    /** Intro / credits marks the source itself publishes for this file (AniLiberty does). */
    val segments: List<Segment> = emptyList(),
    /** The same video in another form, played if this one fails (Collaps: HLS up to 720p behind DASH up to 1080p). */
    val fallback: Stream? = null,
) {
    fun withVariant(v: Variant) = copy(url = v.url, format = formatOf(v.url))

    companion object {
        fun formatOf(url: String): StreamFormat {
            val path = url.substringBefore('?').lowercase()
            return when {
                path.endsWith(".m3u8") || ".m3u8" in path -> StreamFormat.HLS
                path.endsWith(".mpd") -> StreamFormat.DASH
                else -> StreamFormat.PROGRESSIVE
            }
        }
    }
}

@Immutable
data class Episode(val season: Int, val number: Int, val title: String?, val stream: Stream)

@Immutable
data class Season(val number: Int, val episodes: List<Episode>, val malId: Int? = null)

/** What a source returned for a title. */
@Immutable
sealed interface Playable {
    val sourceName: String

    data class Single(override val sourceName: String, val stream: Stream) : Playable
    /** [startSeason]: the season the matched release belongs to, when the source lists a whole franchise. */
    data class Series(override val sourceName: String, val seasons: List<Season>, val startSeason: Int? = null) : Playable
}

/** A player (balancer) offered by the player site for a title. */
@Immutable
data class SourceInfo(
    val name: String,
    val iframeUrl: String,
    val voices: List<String>,
) {
    val isCollaps: Boolean get() = name.equals("Collaps", ignoreCase = true)

    /** Players the app can read directly (no hidden page needed). */
    val isDirect: Boolean get() = isCollaps

    companion object {
        /** Players the app does not offer at all (VeoVeo: removed at the owner's request). */
        fun isExcluded(name: String): Boolean = name.contains("veoveo", ignoreCase = true)
    }
}

class ResolveException(message: String) : Exception(message)
