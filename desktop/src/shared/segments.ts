import type { EpisodeHint, Movie, Playable, Segment, SegmentKind, SkipRequest } from "./types";
import { isAnime, isSeries } from "./types";

const MIN_MS = 5_000;
const MAX_MS = 5 * 60_000;
const KINDS: SegmentKind[] = ["INTRO", "RECAP", "CREDITS"];

export const SEGMENT_TITLE: Record<SegmentKind, string> = { INTRO: "Заставка", RECAP: "Пересказ", CREDITS: "Титры" };
export const SKIP_LABEL: Record<SegmentKind, string> = { INTRO: "Пропустить заставку", RECAP: "Пропустить пересказ", CREDITS: "Пропустить титры" };

export const segmentEnd = (s: Segment, durationMs: number) => s.endMs ?? durationMs;

/** Inside the segment, leaving the last second alone so a skip never lands back in it. */
export const inSegment = (s: Segment, positionMs: number, durationMs: number) => positionMs >= s.startMs && positionMs < segmentEnd(s, durationMs) - 1000;

/** Drops implausible segments (wrong episode, other cut) and clamps the rest to the video. */
export function valid(s: Segment, durationMs: number): Segment | null {
  const start = Math.max(0, s.startMs);
  const end = s.endMs == null ? null : durationMs > 0 ? Math.min(s.endMs, durationMs) : s.endMs;
  if (end != null && end - start < MIN_MS) return null;
  const out = { ...s, startMs: start, endMs: end };
  if (durationMs <= 0) return out;
  // credits start late; a "credits" mark in the first half belongs to some other cut
  if (s.kind == "CREDITS") return start < durationMs * 0.6 || start >= durationMs - MIN_MS ? null : out;
  return end == null || end - start > MAX_MS || start > durationMs / 2 ? null : out;
}

/** One segment per kind: the first source (in priority order) that has a plausible one. */
export function merge(durationMs: number, ...sources: Segment[][]): Segment[] {
  return KINDS.flatMap(kind => {
    for (const s of sources.flat()) {
      if (s.kind != kind) continue;
      const ok = valid(s, durationMs);
      if (ok) return [ok];
    }
    return [];
  });
}

/**
 * What is playing, for the intro databases. A stream caught from a page (or the page's own
 * player) has no numbers: the page may show them ([page]); a show with a single season needs only
 * the episode, and without one the intro its first episodes agree on is used.
 */
export function skipRequest(m: Movie, playable: Playable | null | undefined, durationMs: number, itemSeason: number | null, itemEpisode: number | null, page: EpisodeHint | null): SkipRequest {
  const series = isSeries(m.type);
  const fromPage = series && itemSeason == null && itemEpisode == null;
  const onlySeason = (m.seasons ?? (m.type == "MINI" ? 1 : 0)) == 1;
  const season = fromPage ? page?.season ?? (onlySeason ? 1 : null) : itemSeason;
  const episode = fromPage ? page?.episode ?? null : itemEpisode;
  const seasons = playable?.kind == "series" ? playable.seasons : [];
  // episode counts line seasons up with MyAnimeList's entries only when they are numbered 1, 2, 3…
  const numbered = seasons.length > 0 && seasons.every((s, i) => s.number == i + 1);
  return {
    imdbId: m.imdbId ?? null,
    malId: seasons.find(s => s.number == season)?.malId ?? null,
    season,
    episode,
    durationMs,
    series,
    baseMalId: m.malId ?? null,
    anime: isAnime(m) ? { title: m.title, original: m.originalTitle ?? null, year: m.year ?? null, series } : null,
    seasonIndex: (season ?? 1) - 1,
    seasonSizes: numbered ? seasons.map(s => s.episodes.reduce((n, e) => Math.max(n, e.number), 0)) : [],
  };
}
