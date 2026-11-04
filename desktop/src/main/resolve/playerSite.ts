import type { SourceInfo } from "../../shared/types";
import { fetchJson, fetchText } from "../net";

/**
 * The player site from the userscript (`<mirror>/<Kinopoisk ID>`) embeds a "kinobox" picker
 * (linkpp.ink) which asks `p.<picker host>/api/players` for the list of players. We ask the
 * same API directly to learn which players have the title.
 */
export const DEFAULT_PICKER = "linkpp.ink";
let picker: string | null = null;

let pickerFailedAt = 0;

/**
 * Host of the picker iframe, read from the mirror's page (it can change with the mirror). A slow
 * mirror must not hold the player up: after a few seconds the usual picker is assumed (and the
 * page is asked again a few minutes later).
 */
export async function pickerHost(mirror: string, kp: number): Promise<string> {
  if (picker) return picker;
  if (Date.now() - pickerFailedAt < 5 * 60_000) return DEFAULT_PICKER;
  const page = await fetchText(`${mirror}/${kp}`, { Referer: `${mirror}/` }, 6_000).catch(() => null);
  const host = page && /<iframe[^>]+src="https:\/\/([a-z0-9.-]+)\/\?id=/.exec(page)?.[1];
  if (host) picker = host;
  else pickerFailedAt = Date.now();
  return host || DEFAULT_PICKER;
}

interface PlayersResponse {
  data?: { type?: string; iframeUrl?: string; translations?: { name?: string; quality?: string }[] }[];
}

export async function sources(mirror: string, kp: number, imdb?: string | null): Promise<SourceInfo[]> {
  const host = await pickerHost(mirror, kp);
  const q = kp > 0 ? `kinopoisk=${kp}` : imdb ? `imdb=${encodeURIComponent(imdb)}` : "";
  const r = await fetchJson<PlayersResponse>(`https://p.${host}/api/players?${q}`, { Referer: `https://${host}/`, Origin: `https://${host}` }, 10_000);
  return (r.data ?? []).flatMap(o => {
    const name = o.type;
    const iframe = o.iframeUrl;
    // e.g. Vibix gives an embed snippet instead of a URL; VeoVeo is not offered at all
    if (!name || !iframe || !iframe.startsWith("http") || isExcluded(name)) return [];
    const voices = (o.translations ?? []).flatMap(t => {
      if (!t.name) return [];
      const quality = t.quality && t.quality != "-" ? t.quality : null;
      return [quality ? `${t.name} · ${quality}` : t.name];
    });
    return [{ name, iframeUrl: iframe, voices }];
  });
}

/** Players the app does not offer (VeoVeo: removed at the owner's request). */
export const isExcluded = (name: string) => /veoveo/i.test(name);
export const isCollaps = (s: SourceInfo) => s.name.toLowerCase() == "collaps";
