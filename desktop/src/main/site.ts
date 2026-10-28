import { app, BrowserWindow, session, WebContents, WebContentsView, WebFrameMain } from "electron";
import fs from "node:fs";
import path from "node:path";
import type { Stream } from "../shared/types";
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

function looksLikeStream(url: string): boolean {
  const p = url.split("?")[0].toLowerCase();
  return p.endsWith(".m3u8") || p.endsWith(".mpd") || p.includes(".m3u8/") ||
    (!manifestsOnly && p.endsWith(".mp4") && !p.includes("preview") && !p.includes("trailer"));
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
    if (!onCatch || !/^https?:/.test(d.url) || !looksLikeStream(d.url)) return;
    const key = d.url.split("?")[0];
    if (reported.has(key)) return;
    reported.add(key);
    const headers: Record<string, string> = {};
    for (const [k, v] of Object.entries(d.requestHeaders)) {
      const lower = k.toLowerCase();
      if (["range", "accept-encoding", "upgrade-insecure-requests", "host", "connection"].includes(lower) || lower.startsWith("sec-")) continue;
      headers[k] = v;
    }
    onCatch({ url: d.url, format: formatOf(d.url), headers, audioNames: [], subtitles: [], variants: [], segments: [] });
  });
  return ses;
}

/** Runs a script in every frame of a page (players live in cross-origin iframes). */
async function inAllFrames(wc: WebContents, code: string): Promise<unknown[]> {
  if (wc.isDestroyed()) return [];
  const frames: WebFrameMain[] = wc.mainFrame ? wc.mainFrame.framesInSubtree : [];
  return Promise.all(frames.map(f => f.executeJavaScript(code).catch(() => null)));
}

const MUTE_AND_PLAY = `(function(){
  window.__dpmSniff = true;
  document.querySelectorAll('video').forEach(function(v){ v.muted = true; try { v.play(); } catch (e) {} });
  var el = document.elementFromPoint(innerWidth / 2, innerHeight / 2);
  var b = el && el.closest && el.closest('button,[class*=play],[id*=play]');
  if (b) try { b.click(); } catch (e) {}
})()`;

function configure(mirror: string, picker: string, block: boolean) {
  mirrorHost = new URL(mirror).host;
  pickerDomain = registrable(picker);
  adBlock = block;
}

/** Opens the site hidden and waits for its player to request a stream. */
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
  const frames = readAsset("frames.js");
  win.webContents.on("did-frame-finish-load", () => void inAllFrames(win.webContents, "window.__dpmSniff = true;" + frames));
  let timer: NodeJS.Timeout | undefined;
  let nudge: NodeJS.Timeout | undefined;
  try {
    return await new Promise<Stream | null>(resolve => {
      timer = setTimeout(() => resolve(null), opts.timeoutMs ?? 30_000);
      nudge = setInterval(() => void inAllFrames(win.webContents, MUTE_AND_PLAY), 1500);
      onCatch = s => {
        log(`Найден поток: ${s.url.slice(0, 120)}`);
        if (manifestsOnly && s.format == "PROGRESSIVE") return;
        resolve(s);
      };
      const url = opts.embedUrl ?? `${mirror}/${kp}`;
      win.loadURL(url, { httpReferrer: opts.embedUrl ? `https://${picker}/` : `${mirror}/` }).catch(e => log(`Страница не открылась: ${(e as Error).message}`));
    });
  } finally {
    // the page must not be touched once it is gone (an exception here would stop the whole app)
    clearTimeout(timer);
    clearInterval(nudge);
    onCatch = null;
    if (!win.isDestroyed()) win.destroy();
  }
}

// ---- the site's own player, shown over the player area -----------------------------------------

let view: WebContentsView | null = null;
let timeTimer: NodeJS.Timeout | null = null;

export function showSite(win: BrowserWindow, mirror: string, picker: string, kp: number, block: boolean, bounds: Electron.Rectangle, onTime: (t: number, d: number) => void) {
  hideSite(win);
  configure(mirror, picker, block);
  const ses = siteSession();
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
  const frames = readAsset("frames.js");
  wc.on("dom-ready", () => void wc.executeJavaScript(top).catch(() => null));
  wc.on("did-frame-finish-load", () => void inAllFrames(wc, frames));
  win.contentView.addChildView(view);
  view.setBounds(bounds);
  void wc.loadURL(`${mirror}/${kp}`, { httpReferrer: `${mirror}/` });
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
  if (view) {
    if (!win.isDestroyed()) win.contentView.removeChildView(view);
    if (!view.webContents.isDestroyed()) view.webContents.close();
    view = null;
  }
}

app.on("before-quit", () => {
  if (timeTimer) clearInterval(timeTimer);
});
