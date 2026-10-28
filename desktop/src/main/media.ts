import { session } from "electron";
import type { Stream } from "../shared/types";
import { PASS_PREFIX, UA } from "./net";

/**
 * Video sites expect their own Referer / Origin / cookies on every playlist and segment request,
 * and their CDNs do not answer cross-origin requests from an app page. The player window's
 * media requests get both here: the stream's headers going out, CORS permission coming back.
 */
let playerContentsId: number | null = null;
let streamHeaders: Record<string, string> = {};

const MEDIA = new Set(["xhr", "media", "fetch", "other"]);
const canonical = (name: string) => name.split("-").map(p => p.charAt(0).toUpperCase() + p.slice(1)).join("-");

export function setPlayerContents(id: number) {
  playerContentsId = id;
}

export function prepare(stream: Stream): Stream {
  streamHeaders = { ...stream.headers };
  return stream;
}

export function installMediaHooks() {
  const ses = session.defaultSession;
  ses.webRequest.onBeforeSendHeaders((d, cb) => {
    const h = d.requestHeaders;
    // headers the main process asked for (see net.ts)
    for (const k of Object.keys(h)) {
      if (k.toLowerCase().startsWith(PASS_PREFIX)) {
        h[canonical(k.slice(PASS_PREFIX.length))] = h[k];
        delete h[k];
      }
    }
    if (d.webContentsId != null && d.webContentsId == playerContentsId && /^https?:/.test(d.url)) {
      if (d.resourceType == "image") {
        // Kinopoisk's poster CDN is happiest without an app referer
        delete h.Referer;
      } else if (MEDIA.has(d.resourceType)) {
        for (const [k, v] of Object.entries(streamHeaders)) h[canonical(k.toLowerCase())] = v;
        h["User-Agent"] = streamHeaders["User-Agent"] ?? UA;
      }
    }
    cb({ requestHeaders: h });
  });

  ses.webRequest.onHeadersReceived((d, cb) => {
    if (d.webContentsId == null || d.webContentsId != playerContentsId || !MEDIA.has(d.resourceType) || !/^https?:/.test(d.url)) {
      cb({});
      return;
    }
    const headers: Record<string, string[]> = {};
    for (const [k, v] of Object.entries(d.responseHeaders ?? {})) if (!k.toLowerCase().startsWith("access-control-")) headers[k] = v;
    headers["Access-Control-Allow-Origin"] = ["*"];
    headers["Access-Control-Allow-Headers"] = ["*"];
    headers["Access-Control-Allow-Methods"] = ["GET, HEAD, OPTIONS"];
    headers["Access-Control-Expose-Headers"] = ["*"];
    // CDNs often refuse the preflight a range request triggers; it only needs a 200
    cb({ responseHeaders: headers, statusLine: d.method == "OPTIONS" ? "HTTP/1.1 200 OK" : undefined });
  });
}
