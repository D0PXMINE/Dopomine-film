import type { Movie, Playable, ResolveResult, SourceChoice, SourceInfo } from "../shared/types";
import { isAnime, SOURCE } from "../shared/types";
import * as catalog from "./catalog";
import { resolveAniLiberty } from "./resolve/aniliberty";
import { resolveAnimevost } from "./resolve/animevost";
import { resolveCollaps } from "./resolve/collaps";
import { isCollaps, pickerHost, sources } from "./resolve/playerSite";
import * as site from "./site";
import * as store from "./store";

// ---- the log the player shows under "Журнал" ------------------------------------------------------

const lines: string[] = [];
const logListeners = new Set<(line: string) => void>();

export function log(line: string) {
  lines.push(line);
  if (lines.length > 300) lines.shift();
  logListeners.forEach(l => l(line));
}

export const logLines = () => [...lines];
export const onLog = (l: (line: string) => void) => {
  logListeners.add(l);
  return () => logListeners.delete(l);
};

// ---- resolving ------------------------------------------------------------------------------

const mirror = () => store.settings().playerHost.replace(/\/+$/, "");

const SOURCES_TIMEOUT_MS = 10_000;
/** Collaps is found by its own ID too: it waits for the site's list only this long. */
const COLLAPS_LIST_WAIT_MS = 3_000;
const DIRECT_TIMEOUT_MS = 30_000;
/** How long a page runs hidden before it is shown to the viewer (catching goes on there). */
const SNIFF_HIDDEN_MS = 12_000;
const TIMED_OUT = Symbol("timed out");

function within<T>(p: Promise<T>, ms: number): Promise<T | typeof TIMED_OUT> {
  let timer: NodeJS.Timeout | undefined;
  return Promise.race([p, new Promise<typeof TIMED_OUT>(r => { timer = setTimeout(() => r(TIMED_OUT), ms); })]).finally(() => clearTimeout(timer));
}

/** Sources played natively, in the order they are tried; anime has its own dub teams first. */
const directOrder = (m: Movie) => [...(isAnime(m) ? [SOURCE.ANILIBERTY, SOURCE.ANIMEVOST] : []), SOURCE.COLLAPS];

function choicesFor(m: Movie, list: SourceInfo[]): SourceChoice[] {
  const out: SourceChoice[] = [];
  if (isAnime(m)) {
    out.push({ key: SOURCE.ANILIBERTY, title: "AniLiberty", subtitle: "Аниме · озвучка AniLibria, до 1080p, все сезоны" });
    out.push({ key: SOURCE.ANIMEVOST, title: "AnimeVost", subtitle: "Аниме · озвучка AnimeVost, 720p" });
  }
  out.push({ key: SOURCE.COLLAPS, title: "Collaps", subtitle: "Прямой поток до 1080p · озвучки, субтитры и серии" });
  for (const s of list.filter(s => !isCollaps(s))) {
    out.push({ key: `src:${s.name}`, title: s.name, subtitle: "Поток со страницы плеера" + (s.voices[0] ? ` · ${s.voices[0]}` : "") });
  }
  out.push({ key: SOURCE.SITE, title: "Авто", subtitle: "Поток плеера, который выберет сайт" });
  out.push({ key: SOURCE.SITE_PLAYER, title: "Плеер сайта", subtitle: "Страница сайта без рекламы" });
  return out;
}

/** Favourites and history keep only a few fields: take the IDs the players match by from the catalog. */
async function hydrate(m: Movie): Promise<Movie> {
  if (m.malId != null && m.imdbId != null) return m;
  const full = await catalog.byId(m.id).catch(() => null);
  return full ? { ...m, imdbId: m.imdbId ?? full.imdbId, malId: m.malId ?? full.malId, genres: [...new Set([...m.genres, ...full.genres])] } : m;
}

let listFor: { id: number; list: Promise<SourceInfo[]> } | null = null;

/** The site's list of players, fetched alongside the direct sources (a slow site never holds playback up). */
function list(m: Movie): Promise<SourceInfo[]> {
  if (listFor?.id == m.id) return listFor.list;
  const pending = (async () => {
    const found = await within(sources(mirror(), m.id, m.imdbId).catch(e => {
      log(`Список плееров не получен: ${(e as Error).message}`);
      return [] as SourceInfo[];
    }), SOURCES_TIMEOUT_MS);
    if (found === TIMED_OUT) {
      log(`Список плееров: сайт не ответил за ${SOURCES_TIMEOUT_MS / 1000} с`);
      return [];
    }
    if (found.length) log(`Плееры на сайте: ${found.map(s => s.name).join(", ")}`);
    return found;
  })();
  listFor = { id: m.id, list: pending };
  // an empty answer is asked again next time
  void pending.then(found => { if (!found.length && listFor?.list === pending) listFor = null; });
  return pending;
}

const listWithin = async (m: Movie, ms: number) => {
  const found = await within(list(m), ms);
  return found === TIMED_OUT ? [] : found;
};

export async function choicesOf(input: Movie): Promise<SourceChoice[]> {
  const m = await hydrate(input);
  return choicesFor(m, await listWithin(m, SOURCES_TIMEOUT_MS));
}

const sourceName = (key: string) => key == SOURCE.ANILIBERTY ? "AniLiberty" : key == SOURCE.ANIMEVOST ? "AnimeVost" : "Collaps";

/** One direct source, given [DIRECT_TIMEOUT_MS] to answer before the next one is tried. */
async function tryDirect(m: Movie, key: string): Promise<Playable | null> {
  const run = async (): Promise<Playable | null> => {
    switch (key) {
      case SOURCE.ANILIBERTY:
        return await resolveAniLiberty(m, log);
      case SOURCE.ANIMEVOST:
        return await resolveAnimevost(m, log);
      case SOURCE.COLLAPS: {
        const found = await listWithin(m, COLLAPS_LIST_WAIT_MS);
        const picker = await pickerHost(mirror(), m.id);
        return await resolveCollaps(m.id, m.imdbId, found.find(isCollaps)?.iframeUrl ?? null, `https://${picker}/`, log);
      }
    }
    return null;
  };
  try {
    const r = await within(run(), DIRECT_TIMEOUT_MS);
    if (r !== TIMED_OUT) return r;
    log(`${sourceName(key)}: не ответил за ${DIRECT_TIMEOUT_MS / 1000} с`);
  } catch (e) {
    log((e as Error).message);
  }
  return null;
}

/**
 * Catches the stream of a page run hidden; if none comes within [SNIFF_HIDDEN_MS], the page is
 * shown to the viewer instead and catching goes on there (see site.showSite).
 */
async function sniffOrShow(m: Movie, key: string, name: string, base: Omit<ResolveResult, "choice" | "playable">, embedUrl?: string): Promise<ResolveResult> {
  const picker = await pickerHost(mirror(), m.id);
  const adBlock = store.settings().adBlock;
  log(`${name}: ловим поток со страницы плеера…`);
  const s = await site.sniff(mirror(), picker, m.id, { adBlock, manifestsOnly: !adBlock, embedUrl, timeoutMs: SNIFF_HIDDEN_MS }, log);
  // the page goes along: it plays the video itself if the app cannot
  if (s) {
    const hint = site.pageHint();
    if (hint) log(`Страница плеера: ${[hint.season && `сезон ${hint.season}`, hint.episode && `серия ${hint.episode}`].filter(Boolean).join(", ")}`);
    return { ...base, choice: key, playable: { kind: "single", sourceName: name, stream: s }, page: { embedUrl: embedUrl ?? null, name, catching: false }, hint };
  }
  log(`${name}: поток пока не пойман — показываем страницу плеера, приложение ждёт дальше`);
  return { ...base, choice: key, playable: null, page: { embedUrl: embedUrl ?? null, name, catching: true } };
}

export async function resolve(input: Movie): Promise<ResolveResult> {
  lines.length = 0;
  const m = await hydrate(input);
  log(`Фильм: ${m.title} (Кинопоиск ${m.id}${isAnime(m) ? ", аниме" : ""})`);
  void list(m);
  const order = directOrder(m);
  for (const key of order) {
    const playable = await tryDirect(m, key);
    if (playable) {
      log(`${playable.sourceName}: поток найден`);
      // the menu gets the site's players if they come in soon; it asks again when opened
      return { choices: choicesFor(m, await listWithin(m, 2_000)), order, movie: m, choice: key, playable };
    }
  }
  const base = { choices: choicesFor(m, await listWithin(m, SOURCES_TIMEOUT_MS)), order, movie: m };
  return sniffOrShow(m, SOURCE.SITE, "Сайт", base);
}

/** A specific source, picked in the "Источник" menu or next in the fallback chain. */
export async function choose(input: Movie, key: string): Promise<ResolveResult> {
  const m = await hydrate(input);
  const order = directOrder(m);
  const found = await listWithin(m, SOURCES_TIMEOUT_MS);
  const base = { choices: choicesFor(m, found), order, movie: m };
  if (key == SOURCE.SITE_PLAYER) return { ...base, choice: key, playable: null, page: { embedUrl: null, name: "Плеер сайта", catching: false } };
  if (key == SOURCE.SITE || key.startsWith("src:")) {
    const info = found.find(s => `src:${s.name}` == key);
    return sniffOrShow(m, key, info?.name ?? "Сайт", base, info?.iframeUrl);
  }
  const playable = await tryDirect(m, key);
  if (playable) log(`${playable.sourceName}: поток найден`);
  return playable
    ? { ...base, choice: key, playable }
    : { ...base, choice: null, playable: null, error: `${base.choices.find(c => c.key == key)?.title ?? key} не отдал поток для этого фильма` };
}
