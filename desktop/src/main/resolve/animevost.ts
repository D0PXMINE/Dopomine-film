import type { Episode, Movie, Playable, Season } from "../../shared/types";
import { isSeries } from "../../shared/types";
import { postForm } from "../net";
import * as titles from "./animeTitles";
import { Log, ResolveError, str, stream } from "./common";

/**
 * AnimeVost — an anime dub team with a public API: search by name, then a playlist of MP4 files
 * (720p and 480p) per release. Each season is its own release ("… (второй сезон) / … 2nd Season"),
 * so a show is assembled from all of its TV releases in release order.
 */
export const NAME = "AnimeVost";
const APIS = ["https://api.animevost.org/v1", "https://api.animetop.info/v1"];
const EPISODE = /^\s*(\d{1,4})\s*серия/i;
const MAX_SEASONS = 12;

interface Release {
  id: number;
  ru: string | null;
  en: string | null;
  year: number | null;
  type: string;
}

const isMovie = (r: Release) => r.type.toLowerCase().includes("фильм");
const isTv = (r: Release) => ["тв", "tv", "ona", "web"].includes(r.type.toLowerCase());

export async function resolveAnimevost(movie: Movie, log: Log): Promise<Playable> {
  let lastError: Error | null = null;
  for (const api of APIS) {
    try {
      return await resolveOn(api, movie, log);
    } catch (e) {
      if (e instanceof ResolveError) throw e;
      lastError = e as Error;
      log(`  ${api}: ${lastError.message}`);
    }
  }
  throw new ResolveError(`AnimeVost недоступен: ${lastError?.message}`);
}

async function search(api: string, q: string): Promise<Release[]> {
  let root: { data?: Record<string, unknown>[] };
  try {
    root = JSON.parse(await postForm(`${api}/search`, { name: q }));
  } catch {
    return [];
  }
  return (root.data ?? []).flatMap(o => {
    const id = Number(str(o, "id"));
    // "Провожающая в последний путь Фрирен / Sousou no Frieren [1-28 из 28]"
    const title = str(o, "title")?.split(" [")[0];
    if (!id || !title) return [];
    const [ru, en] = title.includes(" / ") ? [title.slice(0, title.indexOf(" / ")), title.slice(title.indexOf(" / ") + 3)] : [title, ""];
    return [{ id, ru: ru.trim() || null, en: en.trim() || null, year: Number(str(o, "year")?.slice(0, 4)) || null, type: str(o, "type") ?? "" }];
  });
}

async function resolveOn(api: string, movie: Movie, log: Log): Promise<Playable> {
  const found = new Map<number, Release>();
  for (const q of titles.queries(movie)) {
    log(`AnimeVost: ищем «${q}»`);
    for (const r of await search(api, q)) if (!found.has(r.id)) found.set(r.id, r);
    if ([...found.values()].some(r => titles.match(movie, r.ru, r.en) == titles.EXACT)) break;
  }
  // exact names when there are any, otherwise releases that contain the title
  const levels = [...found.values()].map(r => [r, titles.match(movie, r.ru, r.en)] as const);
  const best = Math.max(titles.NONE, ...levels.map(l => l[1]));
  const mine = best == titles.NONE ? [] : levels.filter(l => l[1] == best).map(l => l[0]);

  if (!isSeries(movie.type)) {
    const fits = mine.filter(r => titles.yearFits(movie, r.year));
    const r = fits.find(isMovie) ?? (fits.length == 1 ? fits[0] : null);
    if (!r) throw new ResolveError("AnimeVost: этого аниме у них нет");
    log(`AnimeVost: «${r.ru ?? r.en}» (${r.year ?? "?"})`);
    const episodes = await playlist(api, r.id, 1);
    if (episodes.length == 0) throw new ResolveError("AnimeVost: нет серий");
    return episodes.length == 1
      ? { kind: "single", sourceName: NAME, stream: episodes[0].stream }
      : { kind: "series", sourceName: NAME, seasons: [{ number: 1, episodes }] };
  }

  // a show: its TV releases are the seasons, oldest first; the first one must fit the catalog year
  const tv = mine.filter(isTv).sort((a, b) => (a.year ?? 9999) - (b.year ?? 9999) || a.id - b.id);
  if (tv.length == 0 || !titles.yearFits(movie, tv[0].year)) throw new ResolveError("AnimeVost: этого аниме у них нет");
  log("AnimeVost: сезоны " + tv.map(r => `«${r.ru ?? r.en}»`).join(", "));
  const seasons: Season[] = [];
  for (const r of tv.slice(0, MAX_SEASONS)) {
    const n = seasons.length + 1;
    const episodes = await playlist(api, r.id, n).catch(() => []);
    if (episodes.length) seasons.push({ number: n, episodes });
  }
  if (seasons.length == 0) throw new ResolveError("AnimeVost: нет серий");
  return { kind: "series", sourceName: NAME, seasons };
}

/** Their files are served over HTTPS too. */
const https = (url: string) => url.replace(/^http:\/\//, "https://");

async function playlist(api: string, id: number, season: number): Promise<Episode[]> {
  const items = JSON.parse(await postForm(`${api}/playlist`, { id: String(id) })) as Record<string, unknown>[];
  if (!Array.isArray(items)) throw new ResolveError("AnimeVost: пустой плейлист");
  const entries = items.flatMap(o => {
    const name = str(o, "name") ?? "";
    const variants = [
      ...(str(o, "hd") ? [{ label: "720p", url: https(str(o, "hd")!) }] : []),
      ...(str(o, "std") ? [{ label: "480p", url: https(str(o, "std")!) }] : []),
    ];
    if (variants.length == 0) return [];
    const m = EPISODE.exec(name);
    return [{ name, number: m ? Number(m[1]) : null, stream: stream(variants[0].url, "PROGRESSIVE", { variants }) }];
  });
  // regular episodes are "12 серия"; OVA and specials would clash with their numbers.
  // A film has a single "Фильм" entry instead, so then everything is taken in order.
  const regular = entries.filter(e => e.number != null);
  if (regular.length) {
    const seen = new Set<number>();
    return regular.sort((a, b) => a.number! - b.number!).filter(e => !seen.has(e.number!) && !!seen.add(e.number!))
      .map(e => ({ season, number: e.number!, title: null, stream: e.stream }));
  }
  return entries.map((e, i) => ({ season, number: i + 1, title: entries.length > 1 ? e.name : null, stream: e.stream }));
}
