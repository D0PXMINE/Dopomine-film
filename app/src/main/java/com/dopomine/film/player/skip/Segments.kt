package com.dopomine.film.player.skip

import androidx.compose.runtime.Immutable

enum class SegmentKind(val title: String, val skipLabel: String) {
    INTRO("Заставка", "Пропустить заставку"),
    RECAP("Пересказ", "Пропустить пересказ"),
    CREDITS("Титры", "Пропустить титры"),
}

/** A part of a video worth skipping. [endMs] null means "until the end". */
@Immutable
data class Segment(val kind: SegmentKind, val startMs: Long, val endMs: Long?, val source: String) {
    fun end(durationMs: Long): Long = endMs ?: durationMs

    /** Inside the segment, leaving the last second alone so a skip never lands back in it. */
    fun contains(positionMs: Long, durationMs: Long): Boolean = positionMs >= startMs && positionMs < end(durationMs) - 1_000
}

/** What is playing, in the terms the intro databases use. */
@Immutable
data class SkipRequest(
    val imdbId: String?,
    val malId: Int?,
    val season: Int?,
    val episode: Int?,
    val durationMs: Long,
) {
    val isEpisode: Boolean get() = season != null && episode != null
}

object SegmentRules {
    private const val MIN_MS = 5_000L
    private const val MAX_MS = 5 * 60_000L

    /** Drops implausible segments (wrong episode, other cut) and clamps the rest to the video. */
    fun valid(s: Segment, durationMs: Long): Segment? {
        val start = s.startMs.coerceAtLeast(0)
        val end = s.endMs?.let { if (durationMs > 0) it.coerceAtMost(durationMs) else it }
        if (end != null && end - start < MIN_MS) return null
        if (durationMs <= 0) return s.copy(startMs = start, endMs = end)
        return when (s.kind) {
            SegmentKind.INTRO, SegmentKind.RECAP -> {
                if (end == null || end - start > MAX_MS || start > durationMs / 2) null else s.copy(startMs = start, endMs = end)
            }
            // credits start late; a "credits" mark in the first half belongs to some other cut
            SegmentKind.CREDITS -> if (start < durationMs * 0.6 || start >= durationMs - MIN_MS) null else s.copy(startMs = start, endMs = end)
        }
    }

    /**
     * Keeps one segment per kind, taking the first source that has a plausible one;
     * [sources] are in priority order.
     */
    fun merge(durationMs: Long, vararg sources: List<Segment>): List<Segment> =
        SegmentKind.entries.mapNotNull { kind ->
            sources.asSequence().flatMap { it.asSequence() }.filter { it.kind == kind }.firstNotNullOfOrNull { valid(it, durationMs) }
        }
}
