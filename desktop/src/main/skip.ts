import { merge } from "../shared/segments";
import type { Segment, SegmentKind, SkipRequest } from "../shared/types";
import { fetchText, Memo } from "./net";

/**
 * Intro, recap and credits timestamps from open community databases (no keys):
 * AniSkip for anime (by MyAnimeList ID), TheIntroDB and IntroDB for shows and films (by IMDb ID).
 */
const parse = (text: string): unknown => {
  try {
    return JSON.parse(text);
  } catch {
    return null;
  }
};

/** `{"found":true,"results":[{"interval":{"startTime":6.5,"endTime":96.5},"skipType":"op"}]}` */
export function parseAniSkip(text: string): Segment[] {
  const root = parse(text) as { results?: { interval?: { startTime?: number; endTime?: number }; skipType?: string }[] } | null;
  return (root?.results ?? []).flatMap(r => {
    const kind: SegmentKind | null = r.skipType == "op" || r.skipType == "mixed-op" ? "INTRO"
      : r.skipType == "ed" || r.skipType == "mixed-ed" ? "CREDITS" : r.skipType == "recap" ? "RECAP" : null;
    const a = r.interval?.startTime;
    const b = r.interval?.endTime;
    return kind && a != null && b != null ? [{ kind, startMs: Math.round(a * 1000), endMs: Math.round(b * 1000), source: "AniSkip" }] : [];
  });
}

/** `{"intro":[{"start_ms":7615,"end_ms":105471}],"recap":[…],"credits":[{"start_ms":3368800,"end_ms":null}]}` */
export function parseTheIntroDb(text: string): Segment[] {
  const root = parse(text) as Record<string, { start_ms?: number | null; end_ms?: number | null }[] | undefined> | null;
  if (!root) return [];
  return ([["intro", "INTRO"], ["recap", "RECAP"], ["credits", "CREDITS"]] as const).flatMap(([key, kind]) =>
    (Array.isArray(root[key]) ? root[key]! : []).flatMap(o => {
      const start = o.start_ms ?? null;
      const end = o.end_ms ?? null;
      // an intro without a start begins with the video; credits without an end run to it
      if (kind == "CREDITS" ? start == null : end == null) return [];
      return [{ kind, startMs: start ?? 0, endMs: end, source: "TheIntroDB" }];
    }));
}

/** `{"intro":{"start_ms":2500,"end_ms":58000},"recap":null,"outro":{…},"no_intro":false}` */
export function parseIntroDb(text: string): Segment[] {
  const root = parse(text) as Record<string, unknown> | null;
  if (!root) return [];
  return ([["intro", "INTRO"], ["recap", "RECAP"], ["outro", "CREDITS"]] as const).flatMap(([key, kind]) => {
    if (root[`no_${key}`] === true) return [];
    const o = root[key] as { start_ms?: number; end_ms?: number; start_sec?: number; end_sec?: number } | null;
    if (!o) return [];
    const start = o.start_ms ?? (o.start_sec != null ? Math.round(o.start_sec * 1000) : null);
    const end = o.end_ms ?? (o.end_sec != null ? Math.round(o.end_sec * 1000) : null);
    return start == null ? [] : [{ kind, startMs: start, endMs: end, source: "IntroDB" }];
  });
}

const memo = new Memo<Segment[]>(3 * 24 * 3600_000, 512);

async function attempt(name: string, log: (line: string) => void, url: string | null, parser: (t: string) => Segment[]): Promise<Segment[]> {
  if (!url) return [];
  try {
    const found = parser(await fetchText(url));
    if (found.length) log(`${name}: ${found.map(s => s.kind.toLowerCase()).join(", ")}`);
    return found;
  } catch {
    return []; // 404 = nothing known; any other failure just means one source less
  }
}

/** All plausible segments, best source first; empty when nobody has this video. */
export async function fetchSegments(r: SkipRequest, log: (line: string) => void = () => {}): Promise<Segment[]> {
  const key = [r.imdbId, r.malId, r.season, r.episode].join(":");
  const hit = memo.get(key);
  if (hit) return merge(r.durationMs, hit);
  const episode = r.season != null && r.episode != null;
  const length = r.durationMs > 0 ? `&episodeLength=${Math.round(r.durationMs / 1000)}` : "";
  const found = (await Promise.all([
    attempt("AniSkip", log, r.malId ? `https://api.aniskip.com/v2/skip-times/${r.malId}/${r.episode ?? 1}?types[]=op&types[]=ed&types[]=mixed-op&types[]=mixed-ed&types[]=recap${length}` : null, parseAniSkip),
    attempt("TheIntroDB", log, r.imdbId ? `https://api.theintrodb.org/v2/media?imdb_id=${r.imdbId}${episode ? `&season=${r.season}&episode=${r.episode}` : ""}` : null, parseTheIntroDb),
    attempt("IntroDB", log, r.imdbId ? `https://api.introdb.app/segments?imdb_id=${r.imdbId}&${episode ? `season=${r.season}&episode=${r.episode}` : "is_movie=true"}` : null, parseIntroDb),
  ])).flat();
  memo.set(key, found);
  return merge(r.durationMs, found);
}
