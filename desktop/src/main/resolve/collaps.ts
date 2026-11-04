import type { Episode, Playable, Season, Stream, Subtitle } from "../../shared/types";
import { fetchText, HttpError } from "../net";
import { int, Log, ResolveError, str, stream } from "./common";

/**
 * Collaps: its embed page carries the stream config in plain sight (HLS/DASH links, voice-over
 * names, subtitles, seasons), which is what the site's actualize.js writes into its iframe.
 * Its HLS stops at 720p; the DASH manifest of the same video goes up to 1080p, so DASH is
 * played first and HLS is kept as the fallback.
 */
const ACTUALIZE_JS = "https://kodir2.github.io/actualize.js";
const FALLBACK_HOST = "https://api.luxembd.ws";
let actual: { host: string; at: number } | null = null;

/** Current Collaps API host, published by the script the player site itself loads. */
async function actualHost(): Promise<string | null> {
  if (actual && Date.now() - actual.at < 6 * 3600_000) return actual.host;
  const script = await fetchText(ACTUALIZE_JS).catch(() => null);
  const host = script && /var\s+actual\s*=\s*["'](https?:\/\/[^"']+)["']/.exec(script)?.[1];
  if (host) actual = { host: host.replace(/\/+$/, ""), at: Date.now() };
  return actual?.host ?? null;
}

export async function resolveCollaps(kp: number, imdb: string | null | undefined, iframeUrl: string | null, referer: string, log: Log): Promise<Playable> {
  const host = await actualHost();
  const candidates: string[] = [];
  if (iframeUrl) {
    try {
      const u = new URL(iframeUrl);
      if (host) candidates.push(host + u.pathname + u.search);
    } catch { /* not a URL: try it as is */ }
    candidates.push(iframeUrl);
  }
  for (const h of [...new Set([host, FALLBACK_HOST].filter((x): x is string => !!x))]) {
    if (kp > 0) candidates.push(`${h}/embed/kp/${kp}`);
    if (imdb) candidates.push(`${h}/embed/imdb/${imdb}`);
  }
  if (candidates.length == 0) throw new ResolveError("Collaps: нет адреса");

  let lastError: string | null = null;
  for (const url of [...new Set(candidates)]) {
    log(`Collaps: ${url}`);
    let html: string;
    try {
      html = await fetchText(url, { Referer: referer, Origin: referer.replace(/\/+$/, ""), Accept: "text/html,application/xhtml+xml" });
    } catch (e) {
      lastError = e instanceof HttpError ? `HTTP ${e.status}` : (e as Error).message;
      log(`  ${lastError}`);
      continue;
    }
    const origin = new URL(url).origin;
    try {
      // what the player's own requests look like (XHR from the embed's origin)
      return parseCollaps(html, { Referer: `${origin}/`, Origin: origin });
    } catch (e) {
      lastError = (e as Error).message;
      log(`  ${lastError}`);
    }
  }
  throw new ResolveError(lastError ?? "Collaps: видео не найдено");
}

/** Pulls the player config out of a Collaps embed page. Tolerant to JS-style (unquoted) keys. */
export function parseCollaps(html: string, headers: Record<string, string>): Playable {
  const seasonsAt = /["']?seasons["']?\s*:\s*\[/.exec(html);
  if (seasonsAt) {
    const raw = balanced(html, seasonsAt.index + seasonsAt[0].length - 1);
    const seasons = raw ? parseSeasons(raw, headers) : [];
    if (seasons.length > 0) return { kind: "series", sourceName: "Collaps", seasons };
  }
  const at = html.indexOf("makePlayer({");
  const config = at >= 0 ? html.slice(at + "makePlayer({".length) : html;
  const s = withFallback(stringValue(config, "hls"), stringValue(config, "dasha") ?? stringValue(config, "dash"), { headers, audioNames: audioNames(config), subtitles: subtitles(config) });
  if (!s) {
    if (html.includes("недоступно") || html.toLowerCase().includes("unavailable")) throw new ResolveError("Collaps: видео недоступно в этом регионе");
    throw new ResolveError("Collaps: ссылка на видео не найдена");
  }
  return { kind: "single", sourceName: "Collaps", stream: s };
}

/** DASH (up to 1080p) with HLS (up to 720p) behind it; either alone if only one is given. */
function withFallback(hls: string | null, dash: string | null, extra: Partial<Stream>): Stream | null {
  const viaHls = hls ? stream(absolute(hls), "HLS", extra) : null;
  if (!dash) return viaHls;
  return stream(absolute(dash), "DASH", { ...extra, ...(viaHls ? { fallback: viaHls } : {}) });
}

function parseSeasons(raw: string, headers: Record<string, string>): Season[] {
  const array = parseLenient(raw);
  if (!Array.isArray(array)) return [];
  return array.flatMap((so, si): Season[] => {
    if (!so || typeof so != "object") return [];
    const number = int(so, "season") ?? si + 1;
    const list = (so as { episodes?: unknown[] }).episodes ?? [];
    const episodes = list.flatMap((eo, ei): Episode[] => {
      if (!eo || typeof eo != "object") return [];
      const names = ((eo as { audio?: { names?: unknown[] } }).audio?.names ?? []).filter((x): x is string => typeof x == "string");
      const cc = (eo as { cc?: unknown[] }).cc;
      const s = withFallback(str(eo, "hls") ?? str(eo, "file"), str(eo, "dasha") ?? str(eo, "dash"), { headers, audioNames: names, subtitles: Array.isArray(cc) ? subtitleList(cc) : [] });
      if (!s) return [];
      return [{
        season: number,
        number: int(eo, "episode") ?? int(eo, "number") ?? ei + 1,
        title: str(eo, "title") ?? str(eo, "name"),
        stream: s,
      }];
    }).sort((a, b) => a.number - b.number);
    return episodes.length ? [{ number, episodes }] : [];
  }).sort((a, b) => a.number - b.number);
}

function audioNames(html: string): string[] {
  const at = /["']?names["']?\s*:\s*\[/.exec(html);
  const raw = at && balanced(html, at.index + at[0].length - 1);
  const arr = raw ? parseLenient(raw) : null;
  return Array.isArray(arr) ? arr.filter((x): x is string => typeof x == "string") : [];
}

function subtitles(html: string): Subtitle[] {
  const at = /["']?cc["']?\s*:\s*\[/.exec(html);
  const raw = at && balanced(html, at.index + at[0].length - 1);
  const arr = raw ? parseLenient(raw) : null;
  return Array.isArray(arr) ? subtitleList(arr) : [];
}

function subtitleList(array: unknown[]): Subtitle[] {
  return array.flatMap(o => {
    const url = str(o, "url") ?? str(o, "file");
    return url ? [{ url: absolute(url), label: str(o, "name") ?? str(o, "label") ?? "Субтитры", language: str(o, "lang") }] : [];
  });
}

/** `key: "value"` or `"key":"value"` — first occurrence. */
function stringValue(html: string, key: string): string | null {
  const m = new RegExp(`["']?\\b${key}["']?\\s*:\\s*["']([^"']+)["']`).exec(html);
  const v = m ? unescape(m[1]) : null;
  return v && (v.startsWith("http") || v.startsWith("//")) ? v : null;
}

/** JS string escapes the page uses in links: `\/` and `&` (&). */
const unescape = (s: string) => s.replace(/\\\//g, "/").replace(/\\u([0-9a-fA-F]{4})/g, (_, h) => String.fromCharCode(parseInt(h, 16)));

function absolute(url: string) {
  const clean = unescape(url);
  return clean.startsWith("//") ? "https:" + clean : clean;
}

/** The bracketed text starting at `openIndex` ('[' or '{'), honoring strings. */
export function balanced(text: string, openIndex: number): string | null {
  const open = text[openIndex];
  const close = open == "[" ? "]" : open == "{" ? "}" : null;
  if (!close) return null;
  let depth = 0;
  let quote: string | null = null;
  for (let i = openIndex; i < text.length; i++) {
    const c = text[i];
    if (quote) {
      if (c == "\\") i++;
      else if (c == quote) quote = null;
    } else if (c == '"' || c == "'") quote = c;
    else if (c == open) depth++;
    else if (c == close) {
      depth--;
      if (depth == 0) return text.slice(openIndex, i + 1);
    }
  }
  return null;
}

export function parseLenient(raw: string): unknown {
  try {
    return JSON.parse(raw);
  } catch {
    try {
      return JSON.parse(quoteKeys(raw));
    } catch {
      return null;
    }
  }
}

/** Turns JS object literals (`{a: 1, 'b': "x"}`) into JSON. */
function quoteKeys(raw: string): string {
  let out = "";
  let quote: string | null = null;
  let i = 0;
  // the last non-blank character written outside strings: a key can only follow '{' or ','
  let last = " ";
  while (i < raw.length) {
    const c = raw[i];
    if (quote) {
      if (c == "\\") { out += c + (raw[i + 1] ?? ""); i += 2; continue; }
      if (c == quote) { out += '"'; quote = null; last = '"'; }
      else if (c == '"') out += '\\"';
      else out += c;
      i++;
      continue;
    }
    if (c == '"' || c == "'") { quote = c; out += '"'; i++; continue; }
    if (/[A-Za-z_]/.test(c) && (last == "{" || last == ",")) {
      const start = i;
      while (i < raw.length && /[A-Za-z0-9_]/.test(raw[i])) i++;
      let j = i;
      while (j < raw.length && /\s/.test(raw[j])) j++;
      out += raw[j] == ":" ? `"${raw.slice(start, i)}"` : raw.slice(start, i);
      last = raw[i - 1];
      continue;
    }
    out += c;
    if (!/\s/.test(c)) last = c;
    i++;
  }
  // trailing commas are not JSON either
  return out.replace(/,\s*([}\]])/g, "$1");
}
