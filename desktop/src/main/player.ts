import type { Movie, Playable, ResolveResult, SourceChoice, SourceInfo, Stream } from "../shared/types";
import { isAnime, SOURCE } from "../shared/types";
import * as catalog from "./catalog";
import { resolveAniLiberty } from "./resolve/aniliberty";
import { resolveAnimevost } from "./resolve/animevost";
import { resolveCollaps } from "./resolve/collaps";
import { isCollaps, isVeoVeo, pickerHost, sources } from "./resolve/playerSite";
import { finalizeVeoVeo, resolveVeoVeo } from "./resolve/veoveo";
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
let listFor: { id: number; list: SourceInfo[] } | null = null;

/** Sources played natively, in the order they are tried; anime has its own dub teams first. */
const directOrder = (m: Movie) => [...(isAnime(m) ? [SOURCE.ANILIBERTY, SOURCE.ANIMEVOST] : []), SOURCE.VEOVEO, SOURCE.COLLAPS];

function choicesFor(m: Movie, list: SourceInfo[]): SourceChoice[] {
  const out: SourceChoice[] = [];
  if (isAnime(m)) {
    out.push({ key: SOURCE.ANILIBERTY, title: "AniLiberty", subtitle: "Аниме · озвучка AniLibria, до 1080p, все сезоны" });
    out.push({ key: SOURCE.ANIMEVOST, title: "AnimeVost", subtitle: "Аниме · озвучка AnimeVost, 720p" });
  }
  if (list.some(isVeoVeo)) out.push({ key: SOURCE.VEOVEO, title: "VeoVeo", subtitle: "Прямой поток · озвучки, субтитры, качество и серии" });
  out.push({ key: SOURCE.COLLAPS, title: "Collaps", subtitle: "Прямой поток · озвучки, качество и серии" });
  for (const s of list.filter(s => !isVeoVeo(s) && !isCollaps(s))) {
    out.push({ key: `src:${s.name}`, title: s.name, subtitle: "Поток через сайт" + (s.voices[0] ? ` · ${s.voices[0]}` : "") });
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

async function list(m: Movie): Promise<SourceInfo[]> {
  if (listFor?.id == m.id) return listFor.list;
  try {
    const found = await sources(mirror(), m.id, m.imdbId);
    if (found.length) log(`Плееры на сайте: ${found.map(s => s.name).join(", ")}`);
    listFor = { id: m.id, list: found };
    return found;
  } catch (e) {
    log(`Список плееров не получен: ${(e as Error).message}`);
    return [];
  }
}

async function tryDirect(m: Movie, key: string, found: SourceInfo[]): Promise<Playable | null> {
  try {
    switch (key) {
      case SOURCE.ANILIBERTY:
        return await resolveAniLiberty(m, log);
      case SOURCE.ANIMEVOST:
        return await resolveAnimevost(m, log);
      case SOURCE.VEOVEO: {
        const veo = found.find(isVeoVeo);
        return veo ? await resolveVeoVeo(veo.iframeUrl, log) : null;
      }
      case SOURCE.COLLAPS: {
        const picker = await pickerHost(mirror(), m.id);
        return await resolveCollaps(m.id, m.imdbId, found.find(isCollaps)?.iframeUrl ?? null, `https://${picker}/`, log);
      }
    }
  } catch (e) {
    log((e as Error).message);
  }
  return null;
}

async function sniffSite(m: Movie, adBlock: boolean, embedUrl?: string): Promise<Stream | null> {
  const picker = await pickerHost(mirror(), m.id);
  log(embedUrl ? "Ловим поток плеера…" : adBlock ? "Ловим поток со страницы плеера…" : "Пробуем ещё раз без фильтра рекламы…");
  const s = await site.sniff(mirror(), picker, m.id, { adBlock, manifestsOnly: !adBlock, embedUrl }, log);
  if (!s) log("Поток не появился за 30 с");
  return s;
}

export async function resolve(input: Movie): Promise<ResolveResult> {
  lines.length = 0;
  const m = await hydrate(input);
  log(`Фильм: ${m.title} (Кинопоиск ${m.id}${isAnime(m) ? ", аниме" : ""})`);
  const found = await list(m);
  const base = { choices: choicesFor(m, found), order: directOrder(m), movie: m };
  for (const key of directOrder(m)) {
    const playable = await tryDirect(m, key, found);
    if (playable) {
      log(`${playable.sourceName}: поток найден`);
      return { ...base, choice: key, playable };
    }
  }
  const adBlock = store.settings().adBlock;
  const caught = (await sniffSite(m, adBlock)) ?? (adBlock ? await sniffSite(m, false) : null);
  if (caught) return { ...base, choice: SOURCE.SITE, playable: { kind: "single", sourceName: "Сайт", stream: caught } };
  log("Прямой поток не найден — открываем плеер сайта");
  return { ...base, choice: SOURCE.SITE_PLAYER, playable: null };
}

/** A specific source, picked in the "Источник" menu or next in the fallback chain. */
export async function choose(input: Movie, key: string): Promise<ResolveResult> {
  const m = await hydrate(input);
  const found = await list(m);
  const base = { choices: choicesFor(m, found), order: directOrder(m), movie: m };
  if (key == SOURCE.SITE_PLAYER) return { ...base, choice: key, playable: null };
  if (key == SOURCE.SITE || key.startsWith("src:")) {
    const info = found.find(s => `src:${s.name}` == key);
    const caught = await sniffSite(m, store.settings().adBlock, info?.iframeUrl);
    return caught
      ? { ...base, choice: key, playable: { kind: "single", sourceName: info?.name ?? "Сайт", stream: caught } }
      : { ...base, choice: null, playable: null, error: `${info?.name ?? "Сайт"}: поток не найден` };
  }
  const playable = await tryDirect(m, key, found);
  if (playable) log(`${playable.sourceName}: поток найден`);
  return playable
    ? { ...base, choice: key, playable }
    : { ...base, choice: null, playable: null, error: `${base.choices.find(c => c.key == key)?.title ?? key} не отдал поток для этого фильма` };
}

export const finalize = (s: Stream) => finalizeVeoVeo(s);
