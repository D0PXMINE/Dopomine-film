package com.dopomine.film.player.resolve

import androidx.compose.runtime.Immutable

enum class StreamFormat { HLS, DASH, PROGRESSIVE }

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
) {
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
data class Season(val number: Int, val episodes: List<Episode>)

/** What a source returned for a title. */
@Immutable
sealed interface Playable {
    val sourceName: String

    data class Single(override val sourceName: String, val stream: Stream) : Playable
    data class Series(override val sourceName: String, val seasons: List<Season>) : Playable
}

/** A player (balancer) offered by the player site for a title. */
@Immutable
data class SourceInfo(
    val name: String,
    val iframeUrl: String,
    val voices: List<String>,
) {
    val isCollaps: Boolean get() = name.equals("Collaps", ignoreCase = true)
    val isVeoVeo: Boolean get() = name.equals("VeoVeo", ignoreCase = true)

    /** Players the app can read directly (no hidden page needed). */
    val isDirect: Boolean get() = isCollaps || isVeoVeo
}

class ResolveException(message: String) : Exception(message)
