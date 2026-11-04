import { app, net } from "electron";

/** A desktop Chrome: the player sites serve it the same pages as a regular browser. */
export const UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36";

/**
 * How the app names itself to open APIs that ask clients to. Wikimedia rate-limits clients that
 * hide who they are (a browser User-Agent outside a browser counts as "unidentified": 10 requests
 * a minute per address); named ones get 200. Shikimori asks the same.
 */
const appUa = () => `Dopomine-desktop/${app.getVersion()} (https://github.com/D0PXMINE/Dopomine-film)`;
const wantsAppName = (url: string) => /^https:\/\/([a-z0-9-]+\.)*(wikipedia\.org|wikidata\.org|wikimedia\.org|shikimori\.(io|one)|aniskip\.com)\//.test(url);

/**
 * Chromium's fetch will not set Referer, Origin or Cookie by itself; requests pass them as
 * `X-Dpm-<name>` and the session's request hook (see media.ts) turns them into the real headers.
 */
export const PASS_PREFIX = "x-dpm-";

export class HttpError extends Error {
  constructor(readonly status: number, url: string) {
    super(`HTTP ${status} for ${url}`);
  }
}

function headersFor(extra: Record<string, string>, url = ""): Record<string, string> {
  const out: Record<string, string> = { "User-Agent": wantsAppName(url) ? appUa() : UA };
  for (const [k, v] of Object.entries(extra)) {
    const lower = k.toLowerCase();
    if (lower == "referer" || lower == "origin" || lower == "cookie" || lower.startsWith("sec-fetch")) out[PASS_PREFIX + lower] = v;
    else out[k] = v;
  }
  return out;
}

export async function fetchText(url: string, headers: Record<string, string> = {}, timeoutMs = 25_000): Promise<string> {
  const ctrl = new AbortController();
  const timer = setTimeout(() => ctrl.abort(), timeoutMs);
  try {
    const res = await net.fetch(url, { headers: headersFor(headers, url), signal: ctrl.signal, bypassCustomProtocolHandlers: true });
    if (!res.ok) throw new HttpError(res.status, url);
    return await res.text();
  } finally {
    clearTimeout(timer);
  }
}

export async function fetchJson<T = unknown>(url: string, headers: Record<string, string> = {}, timeoutMs?: number): Promise<T> {
  return JSON.parse(await fetchText(url, { Accept: "application/json", ...headers }, timeoutMs)) as T;
}

export async function postForm(url: string, fields: Record<string, string>): Promise<string> {
  const res = await net.fetch(url, {
    method: "POST",
    headers: headersFor({ "Content-Type": "application/x-www-form-urlencoded" }),
    body: new URLSearchParams(fields).toString(),
  });
  if (!res.ok) throw new HttpError(res.status, url);
  return await res.text();
}

export async function fetchBuffer(url: string): Promise<Buffer> {
  const res = await net.fetch(url, { headers: headersFor({}) });
  if (!res.ok) throw new HttpError(res.status, url);
  return Buffer.from(await res.arrayBuffer());
}

/** Small time-limited memo for lookups that do not change during a session. */
export class Memo<V> {
  private map = new Map<string, { value: V; at: number }>();
  constructor(private ttlMs: number, private max = 256) {}

  get(key: string): V | undefined {
    const hit = this.map.get(key);
    if (!hit) return undefined;
    if (Date.now() - hit.at > this.ttlMs) {
      this.map.delete(key);
      return undefined;
    }
    return hit.value;
  }

  set(key: string, value: V) {
    if (this.map.size >= this.max) this.map.delete(this.map.keys().next().value as string);
    this.map.set(key, { value, at: Date.now() });
  }
}
