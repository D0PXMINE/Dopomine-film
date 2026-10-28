import { app } from "electron";
import fs from "node:fs";
import path from "node:path";
import type { HistoryItem, Movie, Settings } from "../shared/types";

/** The userscript's player site; can be changed in settings when it moves to another mirror. */
export const DEFAULT_PLAYER_HOST = "https://fz7-16gs81j.troutcdn.site";

export const DEFAULT_SETTINGS: Settings = {
  theme: "system",
  animations: true,
  playerHost: DEFAULT_PLAYER_HOST,
  adBlock: true,
  autoResume: true,
  autoNext: true,
  autoSkip: false,
  volume: 1,
  muted: false,
  catalogCheckedAt: 0,
};

interface Progress {
  positionSec: number;
  durationSec: number;
  updatedAt: number;
}

interface Data {
  settings: Settings;
  favorites: { movie: Movie; addedAt: number }[];
  history: HistoryItem[];
  /** movieId -> item ("movie", "s1e3", "d5400") -> position */
  progress: Record<string, Record<string, Progress>>;
  /** movieId -> intro the viewer marked by hand (seconds) */
  introMarks: Record<string, [number, number]>;
  /** movieId -> voice-over picked last time */
  voices: Record<string, string>;
}

const file = () => path.join(app.getPath("userData"), "library.json");

let data: Data | null = null;
let saveTimer: NodeJS.Timeout | null = null;
const listeners = new Set<() => void>();

function load(): Data {
  if (data) return data;
  let raw: Partial<Data> = {};
  try {
    raw = JSON.parse(fs.readFileSync(file(), "utf8"));
  } catch {
    // first start, or a damaged file: start clean rather than refuse to open
  }
  data = {
    settings: { ...DEFAULT_SETTINGS, ...(raw.settings ?? {}) },
    favorites: raw.favorites ?? [],
    history: raw.history ?? [],
    progress: raw.progress ?? {},
    introMarks: raw.introMarks ?? {},
    voices: raw.voices ?? {},
  };
  return data;
}

/** Writes are batched; the file is replaced atomically so a crash never leaves half a file. */
function save() {
  if (saveTimer) clearTimeout(saveTimer);
  saveTimer = setTimeout(flush, 400);
}

export function flush() {
  if (saveTimer) clearTimeout(saveTimer);
  saveTimer = null;
  if (!data) return;
  fs.mkdirSync(path.dirname(file()), { recursive: true });
  const tmp = file() + ".tmp";
  fs.writeFileSync(tmp, JSON.stringify(data));
  fs.renameSync(tmp, file());
}

function changed() {
  save();
  listeners.forEach(l => l());
}

export function onChange(listener: () => void) {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

// ---- settings ----------------------------------------------------------------------------------

export const settings = () => load().settings;

export function setSettings(patch: Partial<Settings>): Settings {
  const d = load();
  d.settings = { ...d.settings, ...patch };
  if (patch.playerHost != null) d.settings.playerHost = normalizeHost(patch.playerHost);
  changed();
  return d.settings;
}

/** Accepts "domain.tld", "https://domain.tld/123" etc. and returns "https://domain.tld". */
export function normalizeHost(input: string): string {
  let value = input.trim();
  if (!value) return DEFAULT_PLAYER_HOST;
  if (!/^https?:\/\//.test(value)) value = "https://" + value;
  try {
    const u = new URL(value);
    return `${u.protocol}//${u.host}`.toLowerCase();
  } catch {
    return DEFAULT_PLAYER_HOST;
  }
}

export const introMark = (movieId: number) => load().introMarks[movieId] ?? null;

export function setIntroMark(movieId: number, mark: [number, number] | null) {
  const d = load();
  if (mark) d.introMarks[movieId] = mark;
  else delete d.introMarks[movieId];
  save();
}

export const voice = (movieId: number) => load().voices[movieId] ?? null;

export function setVoice(movieId: number, name: string) {
  load().voices[movieId] = name;
  save();
}

// ---- favorites & history -------------------------------------------------------------------------

export const favorites = () => [...load().favorites].sort((a, b) => b.addedAt - a.addedAt).map(f => f.movie);
export const isFavorite = (id: number) => load().favorites.some(f => f.movie.id == id);

export function toggleFavorite(movie: Movie): boolean {
  const d = load();
  const was = isFavorite(movie.id);
  d.favorites = was ? d.favorites.filter(f => f.movie.id != movie.id) : [...d.favorites, { movie, addedAt: Date.now() }];
  changed();
  return !was;
}

export function history(): HistoryItem[] {
  return [...load().history].sort((a, b) => b.watchedAt - a.watchedAt);
}

export function markOpened(movie: Movie) {
  const d = load();
  const old = d.history.find(h => h.movie.id == movie.id);
  d.history = [
    { movie, watchedAt: Date.now(), positionSec: old?.positionSec ?? 0, durationSec: old?.durationSec ?? 0, lastSeason: old?.lastSeason, lastEpisode: old?.lastEpisode },
    ...d.history.filter(h => h.movie.id != movie.id),
  ].slice(0, 300);
  changed();
}

export function removeHistory(id: number) {
  const d = load();
  d.history = d.history.filter(h => h.movie.id != id);
  delete d.progress[id];
  changed();
}

export function clearHistory() {
  const d = load();
  d.history = [];
  d.progress = {};
  changed();
}

export function saveProgress(movieId: number, item: string, positionMs: number, durationMs: number, season?: number | null, episode?: number | null) {
  const d = load();
  const positionSec = Math.round(positionMs / 1000);
  const durationSec = Math.round(durationMs / 1000);
  (d.progress[movieId] ??= {})[item] = { positionSec, durationSec, updatedAt: Date.now() };
  const h = d.history.find(x => x.movie.id == movieId);
  if (h) {
    h.positionSec = positionSec;
    h.durationSec = durationSec;
    h.watchedAt = Date.now();
    if (season != null) h.lastSeason = season;
    if (episode != null) h.lastEpisode = episode;
  }
  changed();
}

/** Saved position in seconds; a video watched to the very end starts over. */
export function position(movieId: number, item: string): number | null {
  const p = load().progress[movieId]?.[item];
  if (!p) return null;
  if (p.durationSec > 0 && p.positionSec > p.durationSec - 60) return null;
  return p.positionSec;
}

export function lastEpisode(movieId: number): [number, number] | null {
  const h = load().history.find(x => x.movie.id == movieId);
  return h?.lastSeason != null && h.lastEpisode != null ? [h.lastSeason, h.lastEpisode] : null;
}
