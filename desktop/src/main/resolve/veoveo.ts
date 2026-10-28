import type { Episode, Playable, Season, Stream } from "../../shared/types";
import { fetchJson, fetchText } from "../net";
import { formatOf, int, Log, ResolveError, str, stream } from "./common";

/**
 * VeoVeo: its own JSON API lists every episode with a plain HLS link (voice-overs and
 * subtitles are renditions inside the playlist), so it plays natively with all of them.
 */
const FALLBACK_HOST = "https://api.rstprgapipt.com";

export async function resolveVeoVeo(iframeUrl: string, log: Log): Promise<Playable> {
  const url = new URL(iframeUrl);
  const id = url.searchParams.get("movie_id") ?? url.searchParams.get("content-id");
  if (!id) throw new ResolveError("VeoVeo: нет ID фильма");
  const hosts = [...new Set([`${url.protocol}//${url.host}`, FALLBACK_HOST])];
  let last: string | null = null;
  for (const host of hosts) {
    const api = `${host}/balancer-api/proxy/playlists/catalog-api/episodes?content-id=${id}`;
    log(`VeoVeo: ${api}`);
    let items: unknown[];
    try {
      items = await fetchJson<unknown[]>(api, { Referer: `${host}/` });
    } catch (e) {
      last = (e as Error).message;
      log(`  ошибка: ${last}`);
      continue;
    }
    if (!Array.isArray(items) || items.length == 0) {
      last = "VeoVeo: пустой ответ";
      continue;
    }
    return build(items, host);
  }
  throw new ResolveError(last ?? "VeoVeo: видео не найдено");
}

function build(items: unknown[], host: string): Playable {
  const parsed = items.flatMap(el => {
    const o = el as Record<string, unknown>;
    const season = int(o.season, "order") ?? 0;
    const variants = (Array.isArray(o.episodeVariants) ? o.episodeVariants : []) as Record<string, unknown>[];
    // the HLS variant carries all voice-overs; the MP4 one is a single-voice fallback
    const files = variants.map(v => str(v, "filepath")).filter((f): f is string => !!f).sort((a, b) => Number(b.includes(".m3u8")) - Number(a.includes(".m3u8")));
    if (!files[0]) return [];
    return [{ season, order: int(o, "order") ?? 0, title: str(o, "title"), file: files[0] }];
  });
  if (parsed.length == 0) throw new ResolveError("VeoVeo: нет файлов");
  const headers = { Referer: `${host}/` };
  const make = (file: string): Stream => {
    const clean = file.startsWith("//") ? "https:" + file : file;
    const progressive = clean.split("?")[0].includes(".mp4") && !clean.includes(".m3u8");
    return stream(clean, progressive ? "PROGRESSIVE" : "HLS", { headers });
  };
  if (parsed.every(p => p.season == 0)) return { kind: "single", sourceName: "VeoVeo", stream: make(parsed[0].file) };
  const bySeason = new Map<number, typeof parsed>();
  for (const p of parsed.filter(p => p.season > 0)) bySeason.set(p.season, [...(bySeason.get(p.season) ?? []), p]);
  const seasons: Season[] = [...bySeason.entries()].sort((a, b) => a[0] - b[0]).map(([number, list]) => {
    const seen = new Set<number>();
    const episodes: Episode[] = list.sort((a, b) => a.order - b.order).filter(p => !seen.has(p.order) && !!seen.add(p.order)).map(p => ({
      season: number,
      number: p.order,
      title: p.title && p.title.toLowerCase() != `${p.order} серия` ? p.title : null,
      stream: make(p.file),
    }));
    return { number, episodes };
  });
  return { kind: "series", sourceName: "VeoVeo", seasons };
}

/** Some entries point to a small JSON with the real link; resolved when that episode plays. */
export async function finalizeVeoVeo(s: Stream): Promise<Stream> {
  if (!s.url.split("?")[0].endsWith(".json")) return s;
  const o = JSON.parse(await fetchText(s.url, s.headers)) as { sources?: { link?: string }[] };
  const link = o.sources?.[0]?.link;
  if (!link) throw new ResolveError("VeoVeo: пустой источник");
  return { ...s, url: link, format: formatOf(link) };
}
