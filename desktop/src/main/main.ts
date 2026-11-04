import { app, BrowserWindow, ipcMain, Menu, nativeTheme, net, protocol, shell } from "electron";
import path from "node:path";
import { pathToFileURL } from "node:url";
import type { CatalogQuery, Movie, Settings, SitePage, SkipRequest, Stream } from "../shared/types";
import * as catalog from "./catalog";
import * as media from "./media";
import * as player from "./player";
import { pickerHost } from "./resolve/playerSite";
import * as site from "./site";
import { fetchSegments } from "./skip";
import * as store from "./store";

/**
 * Dopomine for desktop. The window (React, src/renderer) shows the catalog and plays videos;
 * this process reads the catalog, talks to the player sites and keeps the library.
 */
protocol.registerSchemesAsPrivileged([
  { scheme: "dpm", privileges: { standard: true, secure: true, supportFetchAPI: true, corsEnabled: true, stream: true } },
]);

const DEV_URL = process.env.VITE_DEV_SERVER_URL;
// tests run with a throwaway profile
if (process.env.DPM_USER_DATA) app.setPath("userData", process.env.DPM_USER_DATA);
const rendererDir = path.join(__dirname, "..", "renderer");
let win: BrowserWindow | null = null;

/** Kinopoisk links (or bare IDs) given on the command line or opened with the app. */
function kpIdFrom(args: string[]): number | null {
  for (const a of args) {
    const m = /kinopoisk\.ru\/(?:film|series)\/(\d+)/.exec(a) ?? /^dopomine:\/\/(?:film\/)?(\d+)/.exec(a);
    if (m) return Number(m[1]);
  }
  return null;
}

function themeColors() {
  const dark = store.settings().theme == "dark" || (store.settings().theme == "system" && nativeTheme.shouldUseDarkColors);
  return dark ? { bg: "#050505", fg: "#f1f1f1" } : { bg: "#ececec", fg: "#0a0a0a" };
}

function createWindow() {
  const colors = themeColors();
  win = new BrowserWindow({
    width: 1360,
    height: 860,
    minWidth: 960,
    minHeight: 620,
    show: false,
    backgroundColor: colors.bg,
    title: "Dopomine",
    icon: path.join(__dirname, "assets", "icon.png"),
    autoHideMenuBar: true,
    webPreferences: {
      preload: path.join(__dirname, "preload.js"),
      contextIsolation: true,
      sandbox: false,
      autoplayPolicy: "no-user-gesture-required",
      spellcheck: false,
    },
  });
  media.setPlayerContents(win.webContents.id);
  win.once("ready-to-show", () => win?.show());
  win.on("enter-full-screen", () => win?.webContents.send("app.fullscreen", true));
  win.on("leave-full-screen", () => win?.webContents.send("app.fullscreen", false));
  // links in descriptions etc. open in the browser, never inside the app
  win.webContents.setWindowOpenHandler(({ url }) => {
    if (/^https?:/.test(url)) void shell.openExternal(url);
    return { action: "deny" };
  });
  win.webContents.on("will-navigate", (e, url) => {
    if (!url.startsWith("dpm://") && !(DEV_URL && url.startsWith(DEV_URL))) e.preventDefault();
  });
  void win.loadURL(DEV_URL ?? "dpm://app/index.html");
  const id = kpIdFrom(process.argv);
  if (id) win.webContents.once("did-finish-load", () => win?.webContents.send("app.openMovie", id));
}

function handle<A extends unknown[], R>(channel: string, fn: (...args: A) => R | Promise<R>) {
  ipcMain.handle(channel, (_e, ...args) => fn(...(args as A)));
}

function registerIpc() {
  handle("catalog.page", (q: CatalogQuery, page: number, size?: number) => catalog.page(q, page, size));
  handle("catalog.section", async (key: string, size?: number) => {
    const s = catalog.sectionByKey(key);
    return s ? (await catalog.page(s.query, 1, size ?? 24)).items : [];
  });
  handle("catalog.sections", (mode: "films" | "series" | "anime") => catalog.sections(mode));
  handle("catalog.search", (text: string, page: number) => catalog.search(text, page));
  handle("catalog.searchOnline", (text: string, exclude: number[]) => catalog.searchOnline(text, exclude));
  handle("catalog.movie", (id: number) => catalog.movie(id));
  handle("catalog.details", (id: number) => catalog.details(id));
  handle("catalog.similar", (m: Movie) => catalog.similar(m));
  handle("catalog.info", () => catalog.info());
  handle("catalog.checkUpdate", async () => {
    const r = await catalog.checkUpdate();
    store.setSettings({ catalogCheckedAt: Date.now() });
    return r;
  });

  handle("library.favorites", () => store.favorites());
  handle("library.isFavorite", (id: number) => store.isFavorite(id));
  handle("library.toggleFavorite", (m: Movie) => store.toggleFavorite(m));
  handle("library.history", () => store.history());
  handle("library.removeHistory", (id: number) => store.removeHistory(id));
  handle("library.clearHistory", () => store.clearHistory());
  handle("library.markOpened", (m: Movie) => store.markOpened(m));
  handle("library.saveProgress", (id: number, item: string, pos: number, dur: number, s?: number | null, e?: number | null) => store.saveProgress(id, item, pos, dur, s, e));
  handle("library.position", (id: number, item: string) => store.position(id, item));
  handle("library.lastEpisode", (id: number) => store.lastEpisode(id));
  handle("library.voice", (id: number) => store.voice(id));
  handle("library.setVoice", (id: number, name: string) => store.setVoice(id, name));

  handle("settings.get", () => store.settings());
  handle("settings.set", (patch: Partial<Settings>) => store.setSettings(patch));
  handle("settings.introMark", (id: number) => store.introMark(id));
  handle("settings.setIntroMark", (id: number, mark: [number, number] | null) => store.setIntroMark(id, mark));

  handle("player.resolve", (m: Movie) => player.resolve(m));
  handle("player.choose", (m: Movie, key: string) => player.choose(m, key));
  handle("player.prepare", (s: Stream) => media.prepare(s));
  handle("player.choices", (m: Movie) => player.choicesOf(m));
  handle("player.segments", (r: SkipRequest) => fetchSegments(r, player.log));
  handle("player.log", () => player.logLines());
  handle("player.showSite", async (m: Movie, bounds: Electron.Rectangle, page?: SitePage | null) => {
    if (!win) return;
    const s = store.settings();
    const mirror = s.playerHost.replace(/\/+$/, "");
    const picker = await pickerHost(mirror, m.id);
    site.showSite(win, mirror, picker, m.id, s.adBlock, bounds, (t, d) => win?.webContents.send("player.siteTime", t, d), {
      embedUrl: page?.embedUrl,
      onStream: page?.catching
        ? stream => {
          player.log(`${page.name}: поток пойман — играет плеер приложения`);
          win?.webContents.send("player.caught", stream, page.name, site.pageHint());
        }
        : undefined,
      onEpisode: hint => win?.webContents.send("player.siteEpisode", hint),
    });
  });
  handle("player.hideSite", () => win && site.hideSite(win));
  handle("player.siteBounds", (b: Electron.Rectangle) => site.siteBounds(b));
  handle("player.siteSeek", (seconds: number) => site.siteSeek(seconds));

  handle("app.version", () => app.getVersion());
  handle("app.openExternal", (url: string) => (/^https?:/.test(url) ? shell.openExternal(url) : undefined));
  handle("app.setFullScreen", (on: boolean) => win?.setFullScreen(on));
  handle("app.isFullScreen", () => win?.isFullScreen() ?? false);

  player.onLog(line => win?.webContents.send("player.logLine", line));
  store.onChange(() => {
    win?.webContents.send("library.changed");
    win?.webContents.send("settings.changed", store.settings());
    if (win) win.setBackgroundColor(themeColors().bg);
  });
}

// Electron answers an uncaught error in this process with a modal dialog that freezes the app;
// a failed lookup or a closed page is never worth that: log it and carry on.
process.on("uncaughtException", e => console.error("uncaught:", e));
process.on("unhandledRejection", e => console.error("unhandled rejection:", e));

if (!app.requestSingleInstanceLock()) {
  app.quit();
} else {
  app.on("second-instance", (_e, argv) => {
    if (!win) return;
    if (win.isMinimized()) win.restore();
    win.focus();
    const id = kpIdFrom(argv);
    if (id) win.webContents.send("app.openMovie", id);
  });

  // macOS hands links over with an event rather than the command line
  app.on("open-url", (e, url) => {
    e.preventDefault();
    const id = kpIdFrom([url]);
    if (id) win?.webContents.send("app.openMovie", id);
  });

  app.whenReady().then(() => {
    // macOS needs the standard menus for Cmd+C/V in text fields and Cmd+Q; elsewhere no menu bar
    Menu.setApplicationMenu(process.platform == "darwin" ? Menu.buildFromTemplate([{ role: "appMenu" }, { role: "editMenu" }, { role: "windowMenu" }]) : null);
    // dopomine://<Kinopoisk ID> links (the installer registers them too; the portable build cannot)
    if (app.isPackaged) app.setAsDefaultProtocolClient("dopomine");
    protocol.handle("dpm", req => {
      const { pathname } = new URL(req.url);
      const file = path.join(rendererDir, decodeURIComponent(pathname));
      // only files of the app itself
      if (!file.startsWith(rendererDir)) return new Response("not found", { status: 404 });
      return net.fetch(pathToFileURL(file).toString());
    });
    media.installMediaHooks();
    registerIpc();
    createWindow();
    // a quiet look for a newer catalog every few days
    const s = store.settings();
    if (Date.now() - s.catalogCheckedAt > 3 * 24 * 3600_000) {
      setTimeout(() => void catalog.checkUpdate().then(() => store.setSettings({ catalogCheckedAt: Date.now() })), 15_000);
    }
  });

  app.on("window-all-closed", () => {
    store.flush();
    app.quit();
  });
}
