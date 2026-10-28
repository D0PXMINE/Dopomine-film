import type { Segment, SegmentKind } from "./types";

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
