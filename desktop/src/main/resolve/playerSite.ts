import type { SourceInfo } from "../../shared/types";
import { fetchJson, fetchText } from "../net";

/**
 * The player site from the userscript (`<mirror>/<Kinopoisk ID>`) embeds a "kinobox" picker
 * (linkpp.ink) which asks `p.<picker host>/api/players` for the list of players. We ask the
 * same API directly to learn which players have the title.
 */
export const DEFAULT_PICKER = "linkpp.ink";
let picker: string | null = null;

/** Host of the picker iframe, read from the mirror's page (it can change with the mirror). */
export async function pickerHost(mirror: string, kp: number): Promise<string> {
  if (picker) return picker;
  const page = await fetchText(`${mirror}/${kp}`, { Referer: `${mirror}/` }).catch(() => null);
  picker = (page && /<iframe[^>]+src="https:\/\/([a-z0-9.-]+)\/\?id=/.exec(page)?.[1]) || DEFAULT_PICKER;
  return picker;
}

interface PlayersResponse {
  data?: { type?: string; iframeUrl?: string; translations?: { name?: string; quality?: string }[] }[];
}

export async function sources(mirror: string, kp: number, imdb?: string | null): Promise<SourceInfo[]> {
  const host = await pickerHost(mirror, kp);
  const q = kp > 0 ? `kinopoisk=${kp}` : imdb ? `imdb=${encodeURIComponent(imdb)}` : "";
  const r = await fetchJson<PlayersResponse>(`https://p.${host}/api/players?${q}`, { Referer: `https://${host}/`, Origin: `https://${host}` });
  return (r.data ?? []).flatMap(o => {
    const name = o.type;
    const iframe = o.iframeUrl;
    // e.g. Vibix gives an embed snippet instead of a URL
    if (!name || !iframe || !iframe.startsWith("http")) return [];
    const voices = (o.translations ?? []).flatMap(t => {
      if (!t.name) return [];
      const quality = t.quality && t.quality != "-" ? t.quality : null;
      return [quality ? `${t.name} · ${quality}` : t.name];
    });
    return [{ name, iframeUrl: iframe, voices }];
  });
}

export const isVeoVeo = (s: SourceInfo) => s.name.toLowerCase() == "veoveo";
export const isCollaps = (s: SourceInfo) => s.name.toLowerCase() == "collaps";
