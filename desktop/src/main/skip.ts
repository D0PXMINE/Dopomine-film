import { merge, valid } from "../shared/segments";
import type { Segment, SegmentKind, SkipRequest } from "../shared/types";
import { AnimeIds, type Fetch } from "./animeIds";
import { fetchText, Memo } from "./net";

/**
 * Intro, recap and credits timestamps from open community databases (no keys):
 * AniSkip for anime (by MyAnimeList ID, looked up on Shikimori when the catalog has none),
 * TheIntroDB and IntroDB for shows and films (by IMDb ID). When this very episode is not marked,
 * or which episode plays is unknown, the intro of neighbouring episodes is used if they agree.
 */
const parse = (text: string): unknown => {
  try {
    return JSON.parse(text);
  } catch {
    return null;
  }
};

/**
 * `{"found":true,"results":[{"interval":{"startTime":6.5,"endTime":96.5},"skipType":"op","episodeLength":1566.5}]}`
 *
 * One segment per kind: the submission made on the cut closest to [durationMs]. An ending that
 * falls past the end of a shorter cut is moved by the difference (the part after it, the preview,
 * is what such cuts drop).
 */
export function parseAniSkip(text: string, durationMs = 0): Segment[] {
  const root = parse(text) as { results?: { interval?: { startTime?: number; endTime?: number }; skipType?: string; episodeLength?: number }[] } | null;
  const hits = (root?.results ?? []).flatMap(r => {
    const kind: SegmentKind | null = r.skipType == "op" || r.skipType == "mixed-op" ? "INTRO"
      : r.skipType == "ed" || r.skipType == "mixed-ed" ? "CREDITS" : r.skipType == "recap" ? "RECAP" : null;
    const a = r.interval?.startTime;
    const b = r.interval?.endTime;
    const length = typeof r.episodeLength == "number" && r.episodeLength > 0 ? Math.round(r.episodeLength * 1000) : null;
    return kind && a != null && b != null ? [{ kind, start: Math.round(a * 1000), end: Math.round(b * 1000), length }] : [];
  });
  const kinds = [...new Set(hits.map(h => h.kind))];
  return kinds.map(kind => {
    const list = hits.filter(h => h.kind == kind);
    const best = durationMs > 0
      ? list.reduce((x, y) => (Math.abs((y.length ?? Infinity) - durationMs) < Math.abs((x.length ?? Infinity) - durationMs) ? y : x))
      : list[0];
    let { start, end } = best;
    const shift = durationMs > 0 && best.length != null ? durationMs - best.length : 0;
    if (kind == "CREDITS" && shift < 0 && start >= durationMs - 5_000) {
      start += shift;
      end += shift;
    }
    return { kind, startMs: start, endMs: end, source: "AniSkip" };
  });
}

/**
 * The intro most episodes agree on: at least two whose start and end differ by no more than a
 * few seconds; their middle values.
 */
export function consensus(intros: Segment[], toleranceMs = 4_000): Segment | null {
  let group: Segment[] = [];
  for (const a of intros) {
    const near = intros.filter(b => Math.abs(a.startMs - b.startMs) <= toleranceMs && Math.abs((a.endMs ?? 0) - (b.endMs ?? 0)) <= toleranceMs);
    if (near.length > group.length) group = near;
  }
  if (group.length < 2) return null;
  const starts = group.map(s => s.startMs).sort((x, y) => x - y);
  const ends = group.flatMap(s => (s.endMs == null ? [] : [s.endMs])).sort((x, y) => x - y);
  return { kind: "INTRO", startMs: starts[Math.floor(starts.length / 2)], endMs: ends[Math.floor(ends.length / 2)] ?? null, source: `${group[0].source}, по соседним сериям` };
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

const AniSkipTypes = "types[]=op&types[]=ed&types[]=mixed-op&types[]=mixed-ed&types[]=recap";

export class SkipLookup {
  private memo = new Memo<Segment[]>(3 * 24 * 3600_000, 512);
  private anime: AnimeIds;

  constructor(private get: Fetch = fetchText) {
    this.anime = new AnimeIds(get);
  }

  /** All plausible segments, best source first; empty when nobody has this video. */
  async fetch(r: SkipRequest, log: (line: string) => void = () => {}): Promise<Segment[]> {
    const key = JSON.stringify([r.imdbId, r.malId, r.season, r.episode, r.durationMs, r.series, r.baseMalId, r.anime, r.seasonIndex, r.seasonSizes]);
    const hit = this.memo.get(key);
    if (hit) return merge(r.durationMs, hit);
    const series = r.series ?? r.season != null;
    const season = r.season ?? null;
    const episode = r.episode ?? null;
    let found: Segment[];
    if (series && season == null) {
      // a show playing some season: one season's intro says nothing about another's
      found = [];
    } else if (series && episode == null) {
      // a caught stream of a show: which episode is unknown, but many shows have the same intro in every one
      found = await this.agreedIntro(r, season!, [1, 2, 3], log);
    } else {
      found = await this.exact(r, episode, log);
      if (series && episode != null && !found.some(s => s.kind == "INTRO" && valid(s, r.durationMs))) {
        // the next-door episodes are the most alike; the first ones are the most often marked
        const near = [...new Set([episode - 1, episode + 1, 1, 2, 3].filter(e => e >= 1 && e != episode))].slice(0, 4);
        found = [...found, ...(await this.agreedIntro(r, season!, near, log))];
      }
    }
    this.memo.set(key, found);
    return merge(r.durationMs, found);
  }

  /** What the databases have for one episode (or the film), best source first. */
  private async exact(r: SkipRequest, episode: number | null, log: (line: string) => void): Promise<Segment[]> {
    const series = r.series ?? r.season != null;
    const numbers = series ? (r.season != null && episode != null ? `season=${r.season}&episode=${episode}` : null) : "";
    const [aniSkip, theIntroDb, introDb] = await Promise.all([
      this.attempt("AniSkip", log, async () => {
        const target = await this.aniSkipTarget(r, episode);
        // every submission, whatever the length of the cut it was made on: asking for one length
        // returns nothing unless some submitter had exactly that cut
        return target ? parseAniSkip(await this.get(`https://api.aniskip.com/v2/skip-times/${target[0]}/${target[1]}?${AniSkipTypes}&episodeLength=0`), r.durationMs) : [];
      }),
      this.attempt("TheIntroDB", log, async () =>
        r.imdbId && numbers != null ? parseTheIntroDb(await this.get(`https://api.theintrodb.org/v2/media?imdb_id=${r.imdbId}${numbers ? "&" + numbers : ""}`)) : []),
      this.attempt("IntroDB", log, async () =>
        r.imdbId && numbers != null ? parseIntroDb(await this.get(`https://api.introdb.app/segments?imdb_id=${r.imdbId}&${numbers || "is_movie=true"}`)) : []),
    ]);
    return [...aniSkip, ...theIntroDb, ...introDb];
  }

  /**
   * The intro of other episodes of the season, when at least two of them agree to within a few
   * seconds (shows with a cold open of varying length do not, and get nothing).
   */
  private async agreedIntro(r: SkipRequest, season: number, episodes: number[], log: (line: string) => void): Promise<Segment[]> {
    const intros = (await Promise.all(episodes.map(async e => {
      const found = await this.exact({ ...r, season, episode: e }, e, () => {});
      for (const s of found) {
        if (s.kind != "INTRO") continue;
        const ok = valid(s, r.durationMs);
        if (ok) return ok;
      }
      return null;
    }))).filter((s): s is Segment => s != null);
    const agreed = consensus(intros);
    if (!agreed) return [];
    log(`Заставка по соседним сериям: ${Math.round(agreed.startMs / 1000)}–${agreed.endMs == null ? "?" : Math.round(agreed.endMs / 1000)} с`);
    return [agreed];
  }

  /** The MyAnimeList entry and episode in it: exact season ID, else the first season's and its sequels. */
  private async aniSkipTarget(r: SkipRequest, episode: number | null): Promise<[number, number] | null> {
    if (r.malId) return [r.malId, episode ?? 1];
    const base = r.baseMalId ?? (r.anime ? await this.anime.find(r.anime) : null);
    if (!base) return null;
    if (!(r.series ?? r.season != null)) return [base, 1];
    if (episode == null) return null;
    return this.anime.locate(base, r.seasonIndex ?? (r.season ?? 1) - 1, episode, r.seasonSizes ?? []);
  }

  private async attempt(name: string, log: (line: string) => void, run: () => Promise<Segment[]>): Promise<Segment[]> {
    try {
      const found = await run();
      if (found.length) log(`${name}: ${found.map(s => s.kind.toLowerCase()).join(", ")}`);
      return found;
    } catch {
      return []; // 404 = nothing known; any other failure just means one source less
    }
  }
}

const lookup = new SkipLookup();

/** All plausible segments, best source first; empty when nobody has this video. */
export const fetchSegments = (r: SkipRequest, log: (line: string) => void = () => {}) => lookup.fetch(r, log);
