import { app, BrowserWindow, session, WebContents, WebContentsView, WebFrameMain } from "electron";
import fs from "node:fs";
import path from "node:path";
import type { Stream, StreamFormat } from "../shared/types";
import { UA } from "./net";
import { formatOf } from "./resolve/common";

/**
 * The player site itself, in its own browser session: run hidden to catch the stream its player
 * requests, or shown over the player area as the last resort — with ads, pop-ups and redirects
 * to other sites blocked.
 */
const PARTITION = "persist:site";
const asset = (name: string) => path.join(__dirname, "assets", name);
const readAsset = (name: string) => fs.readFileSync(asset(name), "utf8");

// ---- ad blocking ------------------------------------------------------------------------------

let hosts: Set<string> | null = null;
const adHosts = () => hosts ??= new Set(readAsset("hosts.txt").split("\n").map(l => l.trim()).filter(l => l && !l.startsWith("#")));

/** Typical VAST/VPAID/ad-server request patterns used by embedded video players. */
const AD_URL = /(^|[/._?&=-])(vast|vpaid|vmap|preroll|midroll|adfox|prebid|adserver|adsystem)([/._?&=-]|$)|\/advert|\/rtb\/|[?&](adunit|ad_?type)=/;

function isAdHost(host: string): boolean {
  const set = adHosts();
  let h = host.toLowerCase().replace(/\.$/, "");
  for (;;) {
    if (set.has(h)) return true;
    const dot = h.indexOf(".");
    if (dot < 0 || h.indexOf(".", dot + 1) < 0) return false;
    h = h.slice(dot + 1);
  }
}

const registrable = (host: string) => host.split(".").slice(-2).join(".");

let mirrorHost = "";
let pickerDomain = "linkpp.ink";
let adBlock = true;
export let blockedCount = 0;

const isPlayerSite = (host: string) =>
  !!host && (registrable(host) == registrable(mirrorHost) || registrable(host) == pickerDomain);

// ---- stream sniffing ----------------------------------------------------------------------------

type Catch = (s: Stream) => void;
let onCatch: Catch | null = null;
let manifestsOnly = false;
const reported = new Set<string>();
/** Headers of recent requests, by request id and by address: what the player sent for its stream. */
const sentById = new Map<number, Record<string, string>>();
const sentByUrl = new Map<string, Record<string, string>>();
const MANIFEST_TYPE = /mpegurl|dash\+xml/i;
/** frames.js reports from inside the page arrive as console messages starting with this. */
const BRIDGE_MARK = "\u0001dpm:";

function looksLikeStream(url: string): boolean {
  const p = url.split("?")[0].toLowerCase();
  return p.endsWith(".m3u8") || p.endsWith(".mpd") || p.includes(".m3u8/") ||
    (!manifestsOnly && p.endsWith(".mp4") && !p.includes("preview") && !p.includes("trailer"));
}

function remember<K, V>(map: Map<K, V>, key: K, value: V) {
  map.set(key, value);
  if (map.size > 400) map.delete(map.keys().next().value as K);
}

/** A stream address the page's player asked for: offered to whoever waits for one. */
function caught(url: string, headers: Record<string, string>, format?: StreamFormat) {
  if (!onCatch || !/^https?:/.test(url)) return;
  const key = url.split("?")[0];
  if (reported.has(key)) return;
  try {
    const host = new URL(url).host;
    if (isAdHost(host) || AD_URL.test(url.toLowerCase())) return;
  } catch {
    return;
  }
  const kind = format ?? formatOf(url);
  if (manifestsOnly && kind == "PROGRESSIVE") return;
  reported.add(key);
  onCatch({ url, format: kind, headers, audioNames: [], subtitles: [], variants: [], segments: [] });
}

/** What frames.js posts from inside the page (manifests recognised by their content). */
function onFrameMessage(text: string) {
  if (!text.startsWith(BRIDGE_MARK)) return;
  try {
    const m = JSON.parse(text.slice(BRIDGE_MARK.length)) as { type?: string; url?: string; referer?: string; kind?: string };
    if (m.type != "media" || !m.url) return;
    const headers = sentByUrl.get(m.url) ?? (m.referer?.startsWith("http") ? { Referer: m.referer } : {});
    caught(m.url, headers, m.kind == "hls" ? "HLS" : m.kind == "dash" ? "DASH" : undefined);
  } catch { /* not ours */ }
}

/** frames.js with a bridge to the app (console messages) and the catching mode set. */
function framesScript(visible: boolean): string {
  return `window.__dpmSniff = true; window.__dpmVisible = ${visible};` +
    `if (!window.dpmBridge) window.dpmBridge = { postMessage: function (m) { console.debug(${JSON.stringify(BRIDGE_MARK)} + m); } };` +
    readAsset("frames.js");
}

let installed = false;

function siteSession() {
  const ses = session.fromPartition(PARTITION);
  if (installed) return ses;
  installed = true;
  ses.setUserAgent(UA);
  ses.webRequest.onBeforeRequest((d, cb) => {
    try {
      const host = new URL(d.url).host;
      if (adBlock && d.resourceType != "mainFrame" && !isPlayerSite(host) && (isAdHost(host) || AD_URL.test(d.url.toLowerCase()))) {
        blockedCount++;
        cb({ cancel: true });
        return;
      }
    } catch { /* data:, blob: */ }
    cb({});
  });
  // the final headers of the stream request: what the player itself sent
  ses.webRequest.onSendHeaders(d => {
    if (!onCatch || !/^https?:/.test(d.url) || d.resourceType == "image" || d.resourceType == "stylesheet" || d.resourceType == "font") return;
    const headers: Record<string, string> = {};
    for (const [k, v] of Object.entries(d.requestHeaders)) {
      const lower = k.toLowerCase();
      if (["range", "accept-encoding", "upgrade-insecure-requests", "host", "connection"].includes(lower) || lower.startsWith("sec-")) continue;
      headers[k] = v;
    }
    remember(sentById, d.id, headers);
    remember(sentByUrl, d.url, headers);
    if (looksLikeStream(d.url)) caught(d.url, headers);
  });
  // manifests behind addresses with no telling extension are known by what they turn out to be
  ses.webRequest.onHeadersReceived((d, cb) => {
    cb({});
    if (!onCatch || !/^https?:/.test(d.url)) return;
    const type = Object.entries(d.responseHeaders ?? {}).find(([k]) => k.toLowerCase() == "content-type")?.[1]?.[0] ?? "";
    if (MANIFEST_TYPE.test(type)) caught(d.url, sentById.get(d.id) ?? sentByUrl.get(d.url) ?? {}, /dash/i.test(type) ? "DASH" : "HLS");
  });
  return ses;
}

/** Runs a script in every frame of a page (players live in cross-origin iframes). */
async function inAllFrames(wc: WebContents, code: string): Promise<unknown[]> {
  if (wc.isDestroyed()) return [];
  const frames: WebFrameMain[] = wc.mainFrame ? wc.mainFrame.framesInSubtree : [];
  return Promise.all(frames.map(f => f.executeJavaScript(code).catch(() => null)));
}

function configure(mirror: string, picker: string, block: boolean) {
  mirrorHost = new URL(mirror).host;
  pickerDomain = registrable(picker);
  adBlock = block;
}

/** The page's scripts run in every frame as soon as it has a document (players live in iframes). */
function injectFrames(wc: WebContents, visible: boolean) {
  const code = framesScript(visible);
  wc.on("dom-ready", () => void inAllFrames(wc, code));
  wc.on("did-frame-finish-load", () => void inAllFrames(wc, code));
  wc.on("console-message", details => onFrameMessage(details.message));
}

/**
 * Opens the page hidden and waits for its player to request a stream. A page that gives none
 * within the time is then shown to the viewer (see [showSite]), where catching goes on.
 */
export async function sniff(mirror: string, picker: string, kp: number, opts: { adBlock: boolean; manifestsOnly: boolean; timeoutMs?: number; embedUrl?: string }, log: (l: string) => void): Promise<Stream | null> {
  configure(mirror, picker, opts.adBlock);
  manifestsOnly = opts.manifestsOnly;
  reported.clear();
  const ses = siteSession();
  const win = new BrowserWindow({
    show: false,
    width: 1280,
    height: 720,
    webPreferences: { session: ses, backgroundThrottling: false, autoplayPolicy: "no-user-gesture-required", sandbox: true },
  });
  win.webContents.setAudioMuted(true);
  win.webContents.setWindowOpenHandler(() => ({ action: "deny" }));
  injectFrames(win.webContents, false);
  let timer: NodeJS.Timeout | undefined;
  let mine: Catch | null = null;
  try {
    return await new Promise<Stream | null>(resolve => {
      timer = setTimeout(() => resolve(null), opts.timeoutMs ?? 12_000);
      mine = s => {
        log(`Найден поток: ${s.url.slice(0, 120)}`);
        resolve(s);
      };
      onCatch = mine;
      const url = opts.embedUrl ?? `${mirror}/${kp}`;
      win.loadURL(url, { httpReferrer: opts.embedUrl ? `https://${picker}/` : `${mirror}/` }).catch(e => log(`Страница не открылась: ${(e as Error).message}`));
    });
  } finally {
    // the page must not be touched once it is gone (an exception here would stop the whole app)
    clearTimeout(timer);
    if (onCatch === mine) onCatch = null;
    if (!win.isDestroyed()) win.destroy();
  }
}

// ---- the site's own player, shown over the player area -----------------------------------------

let view: WebContentsView | null = null;
let timeTimer: NodeJS.Timeout | null = null;
/** The catcher of the shown page (only it may be cleared when the page goes). */
let viewCatch: Catch | null = null;

/**
 * The page over the player area: the site (or one player's page, [opts.embedUrl]). With
 * [opts.onStream] the app keeps catching: the stream its player loads once the viewer presses
 * play is handed over, and the app's own player takes it from there.
 */
export function showSite(win: BrowserWindow, mirror: string, picker: string, kp: number, block: boolean, bounds: Electron.Rectangle, onTime: (t: number, d: number) => void, opts: { embedUrl?: string | null; onStream?: Catch } = {}) {
  hideSite(win);
  configure(mirror, picker, block);
  const ses = siteSession();
  manifestsOnly = !block;
  reported.clear();
  if (opts.onStream) {
    const handOver = opts.onStream;
    viewCatch = s => {
      if (onCatch === viewCatch) onCatch = null;
      handOver(s);
    };
    onCatch = viewCatch;
  }
  view = new WebContentsView({ webPreferences: { session: ses, autoplayPolicy: "no-user-gesture-required", sandbox: true } });
  const wc = view.webContents;
  wc.setWindowOpenHandler(() => ({ action: "deny" }));
  // ads try to take the page elsewhere; only the player site itself may navigate
  const guard = (e: Electron.Event, url: string) => {
    try {
      if (!isPlayerSite(new URL(url).host)) e.preventDefault();
    } catch {
      e.preventDefault();
    }
  };
  wc.on("will-navigate", guard);
  wc.on("will-redirect", guard);
  const top = readAsset("top.js");
  if (!opts.embedUrl) wc.on("dom-ready", () => void wc.executeJavaScript(top).catch(() => null));
  if (opts.onStream) {
    injectFrames(wc, true);
  } else {
    const frames = readAsset("frames.js");
    wc.on("did-frame-finish-load", () => void inAllFrames(wc, frames));
  }
  win.contentView.addChildView(view);
  view.setBounds(bounds);
  if (opts.embedUrl) void wc.loadURL(opts.embedUrl, { httpReferrer: `https://${picker}/` });
  else void wc.loadURL(`${mirror}/${kp}`, { httpReferrer: `${mirror}/` });
  // the page's video position, for intro skipping
  timeTimer = setInterval(async () => {
    if (!view || view.webContents.isDestroyed()) return;
    const found = await inAllFrames(wc, `(function(){var best=null;document.querySelectorAll('video').forEach(function(v){if(isFinite(v.duration)&&v.duration>60&&(!best||v.duration>best.duration))best=v});return best?[best.currentTime,best.duration]:null})()`);
    const hit = found.find((x): x is [number, number] => Array.isArray(x));
    if (hit) onTime(hit[0], hit[1]);
  }, 1000);
}

export function siteBounds(bounds: Electron.Rectangle) {
  view?.setBounds(bounds);
}

export async function siteSeek(seconds: number) {
  if (!view || view.webContents.isDestroyed()) return;
  await inAllFrames(view.webContents, `(function(){document.querySelectorAll('video').forEach(function(v){if(isFinite(v.duration)&&v.duration>60)v.currentTime=${Number(seconds)}})})()`);
}

export function hideSite(win: BrowserWindow) {
  if (timeTimer) clearInterval(timeTimer);
  timeTimer = null;
  if (viewCatch && onCatch === viewCatch) onCatch = null;
  viewCatch = null;
  if (view) {
    if (!win.isDestroyed()) win.contentView.removeChildView(view);
    if (!view.webContents.isDestroyed()) view.webContents.close();
    view = null;
  }
}

app.on("before-quit", () => {
  if (timeTimer) clearInterval(timeTimer);
});
