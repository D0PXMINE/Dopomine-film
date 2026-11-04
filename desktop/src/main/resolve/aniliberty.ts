import type { Episode, Movie, Playable, Segment, Variant } from "../../shared/types";
import { isSeries } from "../../shared/types";
import { fetchJson } from "../net";
import * as titles from "./animeTitles";
import { int, Log, ResolveError, str, stream } from "./common";

/**
 * AniLiberty (AniLibria) — an anime dub team with an open API: every episode has plain HLS files
 * in 480p, 720p and 1080p. Releases carry MyAnimeList IDs, so a catalog title with one is matched
 * exactly; a release's franchise lists the other seasons in order.
 */
export const NAME = "AniLiberty";
const HOSTS = ["https://api.anilibria.app", "https://anilibria.top", "https://aniliberty.top"];
const SEASON_TYPES = new Set(["TV", "ONA", "WEB"]);
const MAX_SEASONS = 12;

interface Release {
  id: number;
  type: string;
  year: number | null;
  names: string[];
  mal: number | null;
}

export async function resolveAniLiberty(movie: Movie, log: Log): Promise<Playable> {
  let lastError: Error | null = null;
  for (const host of HOSTS) {
    try {
      return await resolveOn(host, movie, log);
    } catch (e) {
      if (e instanceof ResolveError) throw e;
      lastError = e as Error;
      log(`  ${host}: ${lastError.message}`);
    }
  }
  throw new ResolveError(`AniLiberty недоступен: ${lastError?.message}`);
}

function release(o: unknown): Release | null {
  const id = int(o, "id");
  if (id == null) return null;
  const n = (o as { name?: unknown }).name;
  const names = [str(n, "main"), str(n, "english"), str(n, "alternative")].filter((x): x is string => !!x);
  if (names.length == 0) return null;
  return { id, type: str((o as { type?: unknown }).type, "value") ?? "", year: int(o, "year"), names, mal: int((o as { mal?: unknown }).mal, "id") };
}

/** Exact MyAnimeList match first, then the same name and year, of a fitting kind. */
function pick(movie: Movie, releases: Release[]): Release | null {
  if (movie.malId != null) {
    const exact = releases.find(r => r.mal == movie.malId);
    if (exact) return exact;
  }
  const fitting = releases.filter(r => (isSeries(movie.type) ? SEASON_TYPES.has(r.type) : r.type == "MOVIE") && titles.yearFits(movie, r.year));
  let best: Release | null = null;
  let level = titles.NONE;
  for (const r of fitting) {
    const l = titles.match(movie, ...r.names);
    if (l > level) { best = r; level = l; }
  }
  return best;
}

async function resolveOn(host: string, movie: Movie, log: Log): Promise<Playable> {
  const found = new Map<number, Release>();
  let match: Release | null = null;
  for (const q of titles.queries(movie)) {
    log(`AniLiberty: ищем «${q}»`);
    const list = await fetchJson<unknown[]>(`${host}/api/v1/app/search/releases?query=${encodeURIComponent(q)}`);
    for (const r of (Array.isArray(list) ? list : []).map(release)) if (r && !found.has(r.id)) found.set(r.id, r);
    match = pick(movie, [...found.values()]);
    if (match && movie.malId != null && match.mal == movie.malId) break;
  }
  if (!match) throw new ResolveError("AniLiberty: этого аниме у них нет");
  log(`AniLiberty: «${match.names[0]}» (${match.year ?? "?"}, ${match.type}${match.mal ? `, MAL ${match.mal}` : ""})`);

  if (!isSeries(movie.type) || match.type == "MOVIE") {
    const episodes = await episodesOf(host, match.id, 1);
    if (episodes.length == 0) throw new ResolveError("AniLiberty: нет серий");
    return episodes.length == 1
      ? { kind: "single", sourceName: NAME, stream: episodes[0].stream }
      : { kind: "series", sourceName: NAME, seasons: [{ number: 1, episodes, malId: match.mal }] };
  }

  // a show: every TV release of the franchise is a season, in the franchise's order
  let chain = (await franchise(host, match.id)).filter(r => SEASON_TYPES.has(r.type));
  if (!chain.some(r => r.id == match!.id)) chain = [match];
  chain = chain.slice(0, MAX_SEASONS);
  const seasons = (await Promise.all(chain.map(async (r, i) => {
    try {
      return { number: i + 1, episodes: await episodesOf(host, r.id, i + 1), malId: r.mal };
    } catch {
      return null;
    }
  }))).filter((s): s is NonNullable<typeof s> => !!s && s.episodes.length > 0);
  if (seasons.length == 0) throw new ResolveError("AniLiberty: нет серий");
  log(`AniLiberty: сезонов ${seasons.length}`);
  return { kind: "series", sourceName: NAME, seasons, startSeason: chain.findIndex(r => r.id == match!.id) + 1 };
}

async function franchise(host: string, releaseId: number): Promise<Release[]> {
  try {
    const list = await fetchJson<{ franchise_releases?: { sort_order?: number; release?: unknown }[] }[]>(`${host}/api/v1/anime/franchises/release/${releaseId}`);
    return (list?.[0]?.franchise_releases ?? [])
      .sort((a, b) => (a.sort_order ?? 0) - (b.sort_order ?? 0))
      .map(x => release(x.release))
      .filter((r): r is Release => !!r);
  } catch {
    return [];
  }
}

/** Their own opening / ending marks: `"opening":{"start":1,"stop":89}`, in seconds. */
function marks(e: Record<string, unknown>): Segment[] {
  const out: Segment[] = [];
  for (const [key, kind] of [["opening", "INTRO"], ["ending", "CREDITS"]] as const) {
    const start = int(e[key], "start");
    const stop = int(e[key], "stop");
    if (start == null || (stop == null && kind == "INTRO")) continue;
    out.push({ kind, startMs: start * 1000, endMs: stop == null ? null : stop * 1000, source: NAME });
  }
  return out;
}

async function episodesOf(host: string, releaseId: number, season: number): Promise<Episode[]> {
  const root = await fetchJson<{ episodes?: Record<string, unknown>[]; is_blocked_by_copyrights?: boolean }>(`${host}/api/v1/anime/releases/${releaseId}`);
  const items = root?.episodes ?? [];
  if (items.length == 0 && root?.is_blocked_by_copyrights) throw new ResolveError("AniLiberty: убрано по требованию правообладателя");
  const sorted = [...items].sort((a, b) => Number(a.ordinal ?? 0) - Number(b.ordinal ?? 0));
  const ordinals = sorted.map(e => Number(e.ordinal));
  const whole = ordinals.every(o => Number.isInteger(o)) && new Set(ordinals).size == ordinals.length;
  return sorted.flatMap((e, i): Episode[] => {
    // the playlist itself has no ads; the query only switches ads on in their web player
    const variants: Variant[] = ([["1080p", "hls_1080"], ["720p", "hls_720"], ["480p", "hls_480"]] as const)
      .flatMap(([label, key]) => (str(e, key) ? [{ label, url: str(e, key)!.split("?")[0] }] : []));
    if (variants.length == 0) return [];
    return [{ season, number: whole ? Number(e.ordinal) : i + 1, title: str(e, "name"), stream: stream(variants[0].url, "HLS", { variants, segments: marks(e) }) }];
  });
}
