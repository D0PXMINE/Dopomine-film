import { contextBridge, ipcRenderer } from "electron";
import type { DpmApi } from "../shared/types";

const call = (channel: string) => (...args: unknown[]) => ipcRenderer.invoke(channel, ...args);

function subscribe<A extends unknown[]>(channel: string, listener: (...args: A) => void) {
  const wrapped = (_e: Electron.IpcRendererEvent, ...args: unknown[]) => listener(...(args as A));
  ipcRenderer.on(channel, wrapped);
  return () => void ipcRenderer.removeListener(channel, wrapped);
}

const api: DpmApi = {
  catalog: {
    page: call("catalog.page"),
    section: call("catalog.section"),
    sections: call("catalog.sections"),
    search: call("catalog.search"),
    searchOnline: call("catalog.searchOnline"),
    movie: call("catalog.movie"),
    details: call("catalog.details"),
    similar: call("catalog.similar"),
    info: call("catalog.info"),
    checkUpdate: call("catalog.checkUpdate"),
  } as DpmApi["catalog"],
  library: {
    favorites: call("library.favorites"),
    isFavorite: call("library.isFavorite"),
    toggleFavorite: call("library.toggleFavorite"),
    history: call("library.history"),
    removeHistory: call("library.removeHistory"),
    clearHistory: call("library.clearHistory"),
    markOpened: call("library.markOpened"),
    saveProgress: call("library.saveProgress"),
    position: call("library.position"),
    lastEpisode: call("library.lastEpisode"),
    voice: call("library.voice"),
    setVoice: call("library.setVoice"),
    onChange: (l: () => void) => subscribe("library.changed", l),
  } as DpmApi["library"],
  settings: {
    get: call("settings.get"),
    set: call("settings.set"),
    introMark: call("settings.introMark"),
    setIntroMark: call("settings.setIntroMark"),
    onChange: l => subscribe("settings.changed", l),
  } as DpmApi["settings"],
  player: {
    resolve: call("player.resolve"),
    choose: call("player.choose"),
    prepare: call("player.prepare"),
    segments: call("player.segments"),
    log: call("player.log"),
    onLog: l => subscribe("player.logLine", l),
    choices: call("player.choices"),
    showSite: call("player.showSite"),
    onCaught: l => subscribe("player.caught", l),
    hideSite: call("player.hideSite"),
    siteBounds: call("player.siteBounds"),
    siteSeek: call("player.siteSeek"),
    onSiteTime: l => subscribe("player.siteTime", l),
    onSiteEpisode: l => subscribe("player.siteEpisode", l),
  } as DpmApi["player"],
  app: {
    version: call("app.version"),
    openExternal: call("app.openExternal"),
    setFullScreen: call("app.setFullScreen"),
    isFullScreen: call("app.isFullScreen"),
    onFullScreen: l => subscribe("app.fullscreen", l),
    onOpenMovie: l => subscribe("app.openMovie", l),
  } as DpmApi["app"],
};

contextBridge.exposeInMainWorld("dpm", api);
