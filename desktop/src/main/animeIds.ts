import type { AnimeTitle } from "../shared/types";
import { fetchText, HttpError } from "./net";

/**
 * MyAnimeList IDs — the key AniSkip knows anime by — for titles the catalog has none for. Those
 * are often the most popular ones: Wikidata ties their Kinopoisk ID to the franchise, not to a
 * season. Shikimori's IDs are MyAnimeList's: its search finds the first season (or the film), its
 * "Sequel" links the following ones. (The same as the Android app's AnimeIds.)
 */
export interface AnimeEntry {
  id: number;
  kind: string;
  airedOn: string | null;
  episodes: number;
  names?: string[];
}

export type Fetch = (url: string, headers?: Record<string, string>) => Promise<string>;

const HOSTS = ["shikimori.io", "shikimori.one"];
const MAX_SEASONS = 12;
export const TV_KINDS = new Set(["tv", "ona"]);

const yearOf = (e: AnimeEntry) => (e.airedOn ? Number(e.airedOn.slice(0, 4)) || null : null);
const norm = (s: string) => s.toLowerCase().replace(/ё/g, "е").replace(/[^\p{L}\p{N}]/gu, "");
const covered = (list: AnimeEntry[]) => (list.some(e => e.episodes <= 0) ? Infinity : list.reduce((n, e) => n + e.episodes, 0));

/**
 * Search results of the right kind and year. The first season is the one named exactly as asked,
 * else the earliest; a TV series wins over a web release of the same year (often a promo or a spin-off).
 */
export function pickAnime(title: AnimeTitle, query: string, results: AnimeEntry[]): number | null {
  const year = title.year;
  if (year == null) return null;
  const wanted = norm(query);
  const rank = (e: AnimeEntry): [number, number, string] => [
    (e.names ?? []).some(n => norm(n) == wanted) ? 0 : 1,
    e.kind == "tv" || !title.series ? 0 : 1,
    e.airedOn ?? "9999",
  ];
  const fitting = results.filter(e => (title.series ? TV_KINDS.has(e.kind) : e.kind == "movie") && yearOf(e) != null && Math.abs(yearOf(e)! - year) <= 1);
  fitting.sort((a, b) => {
    const x = rank(a), y = rank(b);
    return x[0] - y[0] || x[1] - y[1] || (x[2] < y[2] ? -1 : x[2] > y[2] ? 1 : 0);
  });
  return fitting[0]?.id ?? null;
}

function walk(chain: AnimeEntry[], from: number, until: number, episode: number): [number, number] | null {
  let left = episode;
  for (let i = from; i < until; i++) {
    const n = chain[i].episodes > 0 ? chain[i].episodes : Infinity;
    if (left <= n) return [chain[i].id, left];
    left -= n;
  }
  return null;
}

/**
 * The entry and episode in it for the [seasonIndex]-th season (0-based) of a show whose first
 * season and TV sequels are [chain]. A season is matched to entries only when the source's
 * episode counts add up (a source may count two "parts" as one season); only the last season may
 * be shorter (its dub is still coming out); an entry with 0 episodes is still airing and takes
 * what is left. Without counts only the first season is mapped.
 */
export function mapSeason(chain: AnimeEntry[], seasonIndex: number, episode: number, seasonSizes: number[]): [number, number] | null {
  if (episode < 1) return null;
  if (seasonSizes.length == 0) return seasonIndex == 0 ? walk(chain, 0, chain.length, episode) : null;
  let at = 0;
  for (let index = 0; index < seasonSizes.length; index++) {
    const size = seasonSizes[index];
    const start = at;
    let total = 0;
    while (at < chain.length && total < size) {
      total += chain[at].episodes > 0 ? chain[at].episodes : size - total;
      at++;
    }
    const partial = index == seasonSizes.length - 1 && total > size;
    if (total != size && !partial) return null;
    if (index == seasonIndex) return walk(chain, start, at, episode);
  }
  return null;
}

/** One at a time: lookups of the same title made side by side would all ask the site. */
class Lock {
  private tail: Promise<unknown> = Promise.resolve();
  run<T>(fn: () => Promise<T>): Promise<T> {
    const result = this.tail.then(fn);
    this.tail = result.catch(() => undefined);
    return result;
  }
}

export class AnimeIds {
  private found = new Map<string, number | null>();
  private chains = new Map<number, { entries: AnimeEntry[]; complete: boolean }>();
  private findLock = new Lock();
  private chainLock = new Lock();

  constructor(private get: Fetch = fetchText) {}

  /** The MyAnimeList ID of a show's first season, or of a film. */
  find(title: AnimeTitle): Promise<number | null> {
    return this.findLock.run(async () => {
      if (title.year == null) return null; // without a year a namesake is too easy to take
      const key = JSON.stringify([title.title, title.original ?? null, title.year, title.series]);
      if (this.found.has(key)) return this.found.get(key)!;
      let failed = false;
      let result: number | null = null;
      const queries = [...new Set([title.title, title.original ?? ""].map(q => q.trim()).filter(q => q.length >= 2))];
      for (const query of queries) {
        let results: AnimeEntry[];
        try {
          results = await this.search(query);
        } catch {
          failed = true;
          continue;
        }
        result = pickAnime(title, query, results);
        if (result != null) break;
      }
      // a failed request may succeed next time; "not found" stays
      if (result != null || !failed) this.found.set(key, result);
      return result;
    });
  }

  /** See [mapSeason]; [base] is the show's first season. */
  async locate(base: number, seasonIndex: number, episode: number, seasonSizes: number[]): Promise<[number, number] | null> {
    if (seasonSizes.length == 0 && seasonIndex != 0) return null;
    const needed = seasonSizes.length == 0 ? episode : seasonSizes.slice(0, seasonIndex + 1).reduce((a, b) => a + b, 0);
    const chain = await this.chain(base, needed);
    return chain.length ? mapSeason(chain, seasonIndex, episode, seasonSizes) : null;
  }

  /** The first season and as many TV sequels as it takes to hold [episodes] episodes. */
  private chain(base: number, episodes: number): Promise<AnimeEntry[]> {
    return this.chainLock.run(async () => {
      let chain = this.chains.get(base);
      if (!chain) {
        const first = await this.entry(base).catch(() => null);
        if (!first) return [];
        chain = { entries: [first], complete: false };
        this.chains.set(base, chain);
      }
      while (!chain.complete && chain.entries.length < MAX_SEASONS && covered(chain.entries) < episodes) {
        let next: AnimeEntry | null;
        try {
          next = await this.nextTv(chain.entries[chain.entries.length - 1].id, new Set(chain.entries.map(e => e.id)));
        } catch {
          break; // asked again next time
        }
        if (next) chain.entries.push(next);
        else chain.complete = true;
      }
      return [...chain.entries];
    });
  }

  private async entry(id: number): Promise<AnimeEntry | null> {
    const o = await this.api(`/api/animes/${id}`);
    return o && typeof o == "object" && !Array.isArray(o) ? toEntry(o as Record<string, unknown>) : null;
  }

  /** The next TV season: a direct TV sequel, or one reached through a film or special between them. */
  private async nextTv(id: number, seen: Set<number>, depth = 0): Promise<AnimeEntry | null> {
    const sequels = (await this.related(id)).filter(e => !seen.has(e.id));
    const byDate = (a: AnimeEntry, b: AnimeEntry) => ((a.airedOn ?? "9999") < (b.airedOn ?? "9999") ? -1 : (a.airedOn ?? "9999") > (b.airedOn ?? "9999") ? 1 : 0);
    const tv = sequels.filter(e => TV_KINDS.has(e.kind)).sort(byDate)[0];
    if (tv) return tv;
    if (depth >= 2) return null;
    for (const other of [...sequels].sort(byDate)) {
      const found = await this.nextTv(other.id, new Set([...seen, other.id]), depth + 1);
      if (found) return found;
    }
    return null;
  }

  private async related(id: number): Promise<AnimeEntry[]> {
    const list = await this.api(`/api/animes/${id}/related`);
    if (!Array.isArray(list)) return [];
    return list.flatMap(r => {
      const o = r as { relation?: string; relation_russian?: string; anime?: Record<string, unknown> | null };
      if (o.relation != "Sequel" && o.relation_russian != "Продолжение") return [];
      const e = o.anime ? toEntry(o.anime) : null;
      return e ? [e] : [];
    });
  }

  private async search(query: string): Promise<AnimeEntry[]> {
    const list = await this.api(`/api/animes?limit=20&search=${encodeURIComponent(query)}`);
    return Array.isArray(list) ? list.flatMap(o => (o && typeof o == "object" ? [toEntry(o as Record<string, unknown>)].filter((e): e is AnimeEntry => e != null) : [])) : [];
  }

  /** One API call, on the second address if the first one fails; null when the site has no such thing. */
  private async api(path: string): Promise<unknown> {
    let error: unknown = null;
    for (const host of HOSTS) {
      let text: string;
      try {
        text = await this.get(`https://${host}${path}`, { Accept: "application/json" });
      } catch (e) {
        if (e instanceof HttpError && e.status == 404) return null;
        error = e;
        continue;
      }
      try {
        return JSON.parse(text);
      } catch {
        return null;
      }
    }
    throw error ?? new Error("Shikimori is not reachable");
  }
}

function toEntry(o: Record<string, unknown>): AnimeEntry | null {
  const id = typeof o.id == "number" ? o.id : Number(o.id);
  if (!Number.isInteger(id) || id <= 0) return null;
  const str = (k: string) => (typeof o[k] == "string" && (o[k] as string).trim() ? (o[k] as string) : null);
  return {
    id,
    kind: str("kind") ?? "",
    airedOn: str("aired_on"),
    episodes: typeof o.episodes == "number" ? o.episodes : 0,
    names: [str("name"), str("russian")].filter((n): n is string => n != null),
  };
}
