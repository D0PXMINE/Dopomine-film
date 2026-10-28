import { app } from "electron";
import fs from "node:fs";
import path from "node:path";
import zlib from "node:zlib";
import initSqlJs, { Database, SqlValue } from "sql.js";
import type { CatalogInfo, CatalogQuery, ContentType, Kind, Movie, MovieDetails, Page, Section, Sort, UpdateResult } from "../shared/types";
import { isSeries, stubMovie } from "../shared/types";
import { fetchBuffer, fetchJson, fetchText, Memo } from "./net";

// ---- genres & queries (same as the Android app and tools/catalog/build_catalog.py) ---------------

export const GENRES = [
  "Боевик", "Комедия", "Драма", "Фантастика", "Ужасы", "Триллер", "Криминал", "Детектив", "Приключения", "Фэнтези",
  "Мелодрама", "Мультфильм", "Аниме", "Семейный", "Детский", "Военный", "История", "Биография", "Документальный",
  "Вестерн", "Мюзикл", "Музыка", "Спорт", "Супергерои",
];

export const genreBit = (name: string) => {
  const i = GENRES.indexOf(name);
  if (i < 0) throw new Error(`unknown genre ${name}`);
  return 2 ** i;
};

const genreNames = (mask: number) => GENRES.filter((_, i) => Math.floor(mask / 2 ** i) % 2 == 1);

const KIND_SQL: Record<Kind, string> = {
  FILMS: "type = 0",
  SERIES: "type IN (1, 2)",
  ANIME: `(genres & ${genreBit("Аниме")}) != 0`,
  MINI: "type = 2",
  SHOWS: "type = 3",
  ALL: "1 = 1",
};

export const SORT_SQL: Record<Sort, string> = {
  TREND: "trend DESC",
  POPULAR: "popularity DESC",
  RATING: "kp_rating DESC, kp_votes DESC",
  NEW: "year DESC, popularity DESC",
};

export function where(q: CatalogQuery): [string, SqlValue[]] {
  const parts = [KIND_SQL[q.kind]];
  const args: SqlValue[] = [];
  if (q.genre) { parts.push("(genres & ?) != 0"); args.push(genreBit(q.genre)); }
  if (q.animated != null) { parts.push("animated = ?"); args.push(q.animated ? 1 : 0); }
  if (q.country) { parts.push("countries LIKE ?"); args.push(`%${q.country}%`); }
  if (q.yearFrom != null) { parts.push("year >= ?"); args.push(q.yearFrom); }
  if (q.yearTo != null) { parts.push("year <= ?"); args.push(q.yearTo); }
  if (q.minRating != null) { parts.push("kp_rating >= ?"); args.push(q.minRating); }
  // rating sorts need enough votes to mean something
  const votes = q.sort == "RATING" ? Math.max(q.minVotes ?? 0, q.kind == "FILMS" ? 20_000 : 5_000) : q.minVotes ?? 0;
  if (votes > 0) { parts.push("kp_votes >= ?"); args.push(votes); }
  // like Kinopoisk's own top: no documentaries or shorts unless that is what was asked for
  if (q.sort == "RATING" && q.kind == "FILMS") {
    if (q.genre != "Документальный") { parts.push("(genres & ?) = 0"); args.push(genreBit("Документальный")); }
    if (q.genre != "Мультфильм") parts.push("(duration IS NULL OR duration >= 40)");
  }
  if (q.sort == "NEW") parts.push("year IS NOT NULL");
  return [parts.join(" AND "), args];
}

const year = () => new Date().getFullYear();
const s = (key: string, title: string, query: CatalogQuery): Section => ({ key, title, query });

export function sections(mode: "films" | "series" | "anime"): Section[] {
  const y = year();
  if (mode == "films") return [
    s("f_trend", "Популярные фильмы", { kind: "FILMS", sort: "TREND", minVotes: 1000 }),
    s("f_new", "Новинки", { kind: "FILMS", sort: "POPULAR", yearFrom: y - 1 }),
    s("f_top", "Топ Кинопоиска", { kind: "FILMS", sort: "RATING", minVotes: 100_000 }),
    s("f_action", "Боевики", { kind: "FILMS", sort: "TREND", genre: "Боевик", minVotes: 2000 }),
    s("f_comedy", "Комедии", { kind: "FILMS", sort: "TREND", genre: "Комедия", minVotes: 2000 }),
    s("f_scifi", "Фантастика", { kind: "FILMS", sort: "TREND", genre: "Фантастика", minVotes: 2000 }),
    s("f_thriller", "Триллеры", { kind: "FILMS", sort: "TREND", genre: "Триллер", minVotes: 2000 }),
    s("f_horror", "Ужасы", { kind: "FILMS", sort: "TREND", genre: "Ужасы", minVotes: 2000 }),
    s("f_drama", "Драмы", { kind: "FILMS", sort: "TREND", genre: "Драма", minVotes: 2000 }),
    s("f_crime", "Криминал", { kind: "FILMS", sort: "TREND", genre: "Криминал", minVotes: 2000 }),
    s("f_anim", "Мультфильмы", { kind: "FILMS", sort: "TREND", animated: true, minVotes: 1000 }),
    s("f_family", "Для всей семьи", { kind: "FILMS", sort: "TREND", genre: "Семейный", minVotes: 1000 }),
    s("f_hero", "Супергерои", { kind: "FILMS", sort: "POPULAR", genre: "Супергерои" }),
    s("f_adventure", "Приключения", { kind: "FILMS", sort: "TREND", genre: "Приключения", minVotes: 2000 }),
    s("f_fantasy", "Фэнтези", { kind: "FILMS", sort: "TREND", genre: "Фэнтези", minVotes: 2000 }),
    s("f_romance", "Мелодрамы", { kind: "FILMS", sort: "TREND", genre: "Мелодрама", minVotes: 2000 }),
    s("f_ru", "Российское кино", { kind: "FILMS", sort: "TREND", country: "Россия", yearFrom: 2000, minVotes: 1000 }),
    s("f_ussr", "Советская классика", { kind: "FILMS", sort: "POPULAR", country: "СССР" }),
    s("f_war", "Военные", { kind: "FILMS", sort: "POPULAR", genre: "Военный" }),
    s("f_doc", "Документальные", { kind: "FILMS", sort: "POPULAR", genre: "Документальный" }),
  ];
  if (mode == "series") return [
    s("s_trend", "Популярные сериалы", { kind: "SERIES", sort: "TREND", minVotes: 1000 }),
    s("s_new", "Новые сериалы", { kind: "SERIES", sort: "POPULAR", yearFrom: y - 1 }),
    s("s_top", "Лучшие сериалы", { kind: "SERIES", sort: "RATING", minVotes: 30_000 }),
    s("s_mini", "Мини-сериалы", { kind: "MINI", sort: "TREND" }),
    s("s_drama", "Драмы", { kind: "SERIES", sort: "TREND", genre: "Драма", minVotes: 1000 }),
    s("s_comedy", "Комедийные", { kind: "SERIES", sort: "TREND", genre: "Комедия", minVotes: 1000 }),
    s("s_crime", "Криминал", { kind: "SERIES", sort: "TREND", genre: "Криминал", minVotes: 1000 }),
    s("s_detective", "Детективы", { kind: "SERIES", sort: "TREND", genre: "Детектив", minVotes: 1000 }),
    s("s_scifi", "Фантастика", { kind: "SERIES", sort: "TREND", genre: "Фантастика", minVotes: 1000 }),
    s("s_fantasy", "Фэнтези", { kind: "SERIES", sort: "TREND", genre: "Фэнтези", minVotes: 1000 }),
    s("s_thriller", "Триллеры", { kind: "SERIES", sort: "TREND", genre: "Триллер", minVotes: 1000 }),
    s("s_anim", "Мультсериалы", { kind: "SERIES", sort: "TREND", animated: true }),
    s("s_ru", "Российские сериалы", { kind: "SERIES", sort: "TREND", country: "Россия", minVotes: 1000 }),
    s("s_kr", "Корейские дорамы", { kind: "SERIES", sort: "TREND", country: "Корея" }),
    s("s_doc", "Документальные", { kind: "SERIES", sort: "POPULAR", genre: "Документальный" }),
    s("s_show", "ТВ-шоу", { kind: "SHOWS", sort: "TREND" }),
  ];
  return [
    s("a_trend", "Популярное аниме", { kind: "ANIME", sort: "TREND", minVotes: 300 }),
    s("a_series", "Аниме-сериалы", { kind: "SERIES", sort: "TREND", genre: "Аниме", minVotes: 300 }),
    s("a_films", "Полнометражное аниме", { kind: "FILMS", sort: "TREND", genre: "Аниме", minVotes: 300 }),
    s("a_new", "Новинки аниме", { kind: "ANIME", sort: "POPULAR", yearFrom: y - 1 }),
    s("a_top", "Лучшее аниме", { kind: "ANIME", sort: "RATING", minVotes: 10_000 }),
    s("a_action", "Экшен", { kind: "ANIME", sort: "TREND", genre: "Боевик" }),
    s("a_fantasy", "Фэнтези и исекай", { kind: "ANIME", sort: "TREND", genre: "Фэнтези" }),
    s("a_scifi", "Фантастика и меха", { kind: "ANIME", sort: "TREND", genre: "Фантастика" }),
    s("a_comedy", "Комедии", { kind: "ANIME", sort: "TREND", genre: "Комедия" }),
    s("a_romance", "Романтика", { kind: "ANIME", sort: "TREND", genre: "Мелодрама" }),
    s("a_drama", "Драмы", { kind: "ANIME", sort: "TREND", genre: "Драма" }),
    s("a_mystery", "Детективы и мистика", { kind: "ANIME", sort: "TREND", genre: "Детектив" }),
    s("a_classic", "Классика аниме", { kind: "ANIME", sort: "POPULAR", yearTo: 2005 }),
  ];
}

export const sectionByKey = (key: string) =>
  [...sections("films"), ...sections("series"), ...sections("anime")].find(x => x.key == key) ?? null;

export const normalize = (text: string) =>
  text.toLowerCase().replace(/ё/g, "е").replace(/[^0-9a-zа-я]+/g, " ").trim();

// ---- the database --------------------------------------------------------------------------

const COLUMNS = "kp, title, original, year, end_year, type, animated, genres, countries, kp_rating, kp_votes, " +
  "imdb_rating, imdb_votes, duration, imdb, seasons, episodes, wiki, mal";
/** Catalog schema this app reads; matches SCHEMA_VERSION in build_catalog.py. */
const SCHEMA = 2;
const TYPES: ContentType[] = ["FILM", "SERIES", "MINI", "SHOW"];

const bundledFile = () => app.isPackaged
  ? path.join(process.resourcesPath, "catalog", "catalog.db")
  : path.join(app.getAppPath(), "resources", "catalog.db");
const dir = () => path.join(app.getPath("userData"), "catalog");
const installedFile = () => path.join(dir(), "catalog.db");
const pendingFile = () => path.join(dir(), "catalog-new.db");

let sqlPromise: ReturnType<typeof initSqlJs> | null = null;
const sql = () => sqlPromise ??= initSqlJs({ locateFile: (f: string) => path.join(__dirname, f) });

let dbPromise: Promise<Database> | null = null;

function meta(d: Database, key: string): string | null {
  const r = d.exec("SELECT value FROM meta WHERE key = ?", [key]);
  return (r[0]?.values[0]?.[0] as string | undefined) ?? null;
}

/** Version of a catalog file this app can read, or null. */
async function versionOf(file: string): Promise<string | null> {
  if (!fs.existsSync(file)) return null;
  try {
    const d = new (await sql()).Database(fs.readFileSync(file));
    try {
      const schema = Number(meta(d, "schema") ?? 1);
      return schema >= SCHEMA ? meta(d, "version") : null;
    } finally {
      d.close();
    }
  } catch {
    return null;
  }
}

async function open(): Promise<Database> {
  fs.mkdirSync(dir(), { recursive: true });
  // a downloaded update is swapped in before the database is opened
  if (fs.existsSync(pendingFile())) {
    if (await versionOf(pendingFile())) fs.renameSync(pendingFile(), installedFile());
    else fs.rmSync(pendingFile(), { force: true });
  }
  const bundled = await versionOf(bundledFile());
  const installed = await versionOf(installedFile());
  const file = installed && (!bundled || installed > bundled) ? installedFile() : bundledFile();
  return new (await sql()).Database(fs.readFileSync(file));
}

const database = () => dbPromise ??= open();

function toMovie(r: SqlValue[]): Movie {
  const num = (v: SqlValue) => (v == null ? null : Number(v));
  return {
    id: Number(r[0]),
    title: String(r[1]),
    originalTitle: (r[2] as string | null) ?? null,
    year: num(r[3]),
    endYear: num(r[4]),
    type: TYPES[Number(r[5])] ?? "FILM",
    animated: Number(r[6]) == 1,
    genres: genreNames(Number(r[7])),
    countries: r[8] ? String(r[8]).split(", ").filter(Boolean) : [],
    ratingKp: r[9] != null && Number(r[9]) > 0 ? Number(r[9]) : null,
    votesKp: Number(r[10] ?? 0),
    ratingImdb: r[11] != null && Number(r[11]) > 0 ? Number(r[11]) : null,
    votesImdb: Number(r[12] ?? 0),
    duration: num(r[13]),
    imdbId: (r[14] as string | null) ?? null,
    seasons: num(r[15]),
    episodes: num(r[16]),
    wiki: (r[17] as string | null) ?? null,
    malId: num(r[18]),
  };
}

async function query(whereSql: string, args: SqlValue[], orderBy: string, limit: number, offset: number): Promise<Movie[]> {
  const d = await database();
  const r = d.exec(`SELECT ${COLUMNS} FROM titles WHERE ${whereSql} ORDER BY ${orderBy} LIMIT ${limit} OFFSET ${offset}`, args);
  return (r[0]?.values ?? []).map(toMovie);
}

export const byId = async (id: number) => (await query("kp = ?", [id], "kp", 1, 0))[0] ?? null;

async function byIds(ids: number[]): Promise<Map<number, Movie>> {
  if (ids.length == 0) return new Map();
  const list = await query(`kp IN (${ids.map(() => "?").join(",")})`, ids, "kp", ids.length, 0);
  return new Map(list.map(m => [m.id, m]));
}

// ---- repository ------------------------------------------------------------------------------

const PAGE = 30;

export async function page(q: CatalogQuery, pageNo = 1, size = PAGE): Promise<Page> {
  const [w, args] = where(q);
  const items = await query(w, args, SORT_SQL[q.sort], size + 1, (pageNo - 1) * size);
  return { items: items.slice(0, size), page: pageNo, hasMore: items.length > size };
}

/** Offline title search: every word must match the Russian or original title. */
export async function search(text: string, pageNo = 1): Promise<Page> {
  const tokens = normalize(text).split(" ").filter(Boolean).slice(0, 6);
  if (tokens.length == 0) return { items: [], page: pageNo, hasMore: false };
  const w = tokens.map(() => "search LIKE ?").join(" AND ");
  const args: SqlValue[] = [...tokens.map(t => `%${t}%`), normalize(text) + "%"];
  const items = await query(w, args, "CASE WHEN search LIKE ? THEN 0 ELSE 1 END, popularity DESC", PAGE + 1, (pageNo - 1) * PAGE);
  return { items: items.slice(0, PAGE), page: pageNo, hasMore: items.length > PAGE };
}

/** Same kind, overlapping genres, close in time, then popularity. */
export async function similar(movie: Movie, limit = 20): Promise<Movie[]> {
  const mask = movie.genres.reduce((acc, g) => (GENRES.includes(g) ? acc | genreBit(g) : acc), 0);
  if (mask == 0) return [];
  const kind = isSeries(movie.type) ? "type IN (1, 2)" : "type = 0";
  return query(
    `${kind} AND kp != ? AND (genres & ?) != 0 AND animated = ?`,
    [movie.id, mask, movie.animated ? 1 : 0],
    `ABS(COALESCE(year, 2000) - ${movie.year ?? 2000}) / 15 ASC, popularity DESC`,
    limit, 0,
  );
}

export async function info(): Promise<CatalogInfo> {
  const d = await database();
  const count = Number(d.exec("SELECT COUNT(*) FROM titles")[0]?.values[0]?.[0] ?? 0);
  return { version: meta(d, "version"), count };
}

// ---- keyless web sources ---------------------------------------------------------------------

const ratingsMemo = new Memo<{ kp: number | null; kpVotes: number; imdb: number | null; imdbVotes: number }>(12 * 3600_000);

/** Fresh Kinopoisk and IMDb ratings; the public XML endpoint needs no key. */
async function ratings(id: number) {
  const hit = ratingsMemo.get(String(id));
  if (hit) return hit;
  const text = await fetchText(`https://rating.kinopoisk.ru/${id}.xml`).catch(() => null);
  if (!text) return null;
  const k = /<kp_rating num_vote="(\d+)"[^>]*>([\d.]+)</.exec(text);
  const i = /<imdb_rating num_vote="(\d+)"[^>]*>([\d.]+)</.exec(text);
  if (!k && !i) return null;
  const r = {
    kp: k && Number(k[2]) > 0 ? Number(k[2]) : null,
    kpVotes: k ? Number(k[1]) : 0,
    imdb: i && Number(i[2]) > 0 ? Number(i[2]) : null,
    imdbVotes: i ? Number(i[1]) : 0,
  };
  ratingsMemo.set(String(id), r);
  return r;
}

/** Russian Wikipedia: the lead section of the article is a good description. */
async function wikiSummary(article: string): Promise<string | null> {
  const p = encodeURIComponent(article.replace(/ /g, "_"));
  const obj = await fetchJson<{ extract?: string }>(`https://ru.wikipedia.org/api/rest_v1/page/summary/${p}`).catch(() => null);
  const text = obj?.extract?.trim();
  return text && text.length > 40 ? text : null;
}

type Binding = Record<string, { value: string } | undefined>;

async function sparql(q: string): Promise<Binding[] | null> {
  const url = "https://query.wikidata.org/sparql?format=json&query=" + encodeURIComponent(q);
  const r = await fetchJson<{ results?: { bindings?: Binding[] } }>(url, { Accept: "application/sparql-results+json" }).catch(() => null);
  return r?.results?.bindings ?? null;
}

const v = (b: Binding, k: string) => b[k]?.value ?? null;

/** Basic facts for any Kinopoisk ID Wikidata knows. */
async function wikidataMovie(id: number): Promise<Movie | null> {
  const rows = await sparql(`
    SELECT ?ru ?en ?ot ?date ?start ?imdb ?wiki ?mal (GROUP_CONCAT(DISTINCT ?typeLabel; separator="|") AS ?types) WHERE {
      ?f wdt:P2603 "${id}" .
      OPTIONAL { ?f rdfs:label ?ru FILTER(LANG(?ru) = "ru") }
      OPTIONAL { ?f rdfs:label ?en FILTER(LANG(?en) = "en") }
      OPTIONAL { ?f wdt:P1476 ?ot }
      OPTIONAL { ?f wdt:P577 ?date }
      OPTIONAL { ?f wdt:P580 ?start }
      OPTIONAL { ?f wdt:P345 ?imdb }
      OPTIONAL { ?f wdt:P4086 ?mal }
      OPTIONAL { ?a schema:about ?f ; schema:isPartOf <https://ru.wikipedia.org/> ; schema:name ?wiki }
      OPTIONAL { ?f wdt:P31 ?t . ?t rdfs:label ?typeLabel FILTER(LANG(?typeLabel) = "en") }
    } GROUP BY ?ru ?en ?ot ?date ?start ?imdb ?wiki ?mal LIMIT 1`);
  const row = rows?.[0];
  if (!row) return null;
  const title = v(row, "ru") ?? v(row, "en") ?? v(row, "ot");
  if (!title) return null;
  const types = (v(row, "types") ?? "").toLowerCase();
  const type: ContentType = types.includes("miniseries") ? "MINI" : types.includes("series") ? "SERIES"
    : types.includes("television program") || types.includes("show") ? "SHOW" : "FILM";
  const original = v(row, "ot") ?? v(row, "en");
  return {
    ...stubMovie(id, title),
    originalTitle: original != title ? original : null,
    year: Number((v(row, "date") ?? v(row, "start") ?? "").slice(0, 4)) || null,
    type,
    imdbId: v(row, "imdb"),
    wiki: v(row, "wiki"),
    malId: Number(v(row, "mal")) || null,
  };
}

/** Directors and main cast in Russian. */
async function people(id: number): Promise<[string[], string[]]> {
  const rows = await sparql(`
    SELECT ?role ?name WHERE {
      ?f wdt:P2603 "${id}" .
      { ?f wdt:P57 ?p . BIND("d" AS ?role) } UNION { ?f wdt:P161 ?p . BIND("c" AS ?role) }
      ?p rdfs:label ?name FILTER(LANG(?name) = "ru")
    } LIMIT 40`);
  const directors: string[] = [];
  const cast: string[] = [];
  for (const r of rows ?? []) {
    const name = v(r, "name");
    if (!name) continue;
    (v(r, "role") == "d" ? directors : cast).push(name);
  }
  return [[...new Set(directors)], [...new Set(cast)].slice(0, 16)];
}

/** Titles Wikidata knows but the offline snapshot does not (new or obscure ones). */
export async function searchOnline(text: string, exclude: number[]): Promise<Movie[]> {
  const url = "https://www.wikidata.org/w/api.php?action=query&list=search&srlimit=20&format=json&srsearch=" +
    encodeURIComponent(`haswbstatement:P2603 ${text}`);
  const r = await fetchJson<{ query?: { search?: { title: string }[] } }>(url).catch(() => null);
  const qids = (r?.query?.search ?? []).map(x => x.title);
  if (qids.length == 0) return [];
  const rows = await sparql(`
    SELECT ?f ?kp ?ru ?en WHERE {
      VALUES ?f { ${qids.map(q => "wd:" + q).join(" ")} }
      ?f wdt:P2603 ?kp .
      OPTIONAL { ?f rdfs:label ?ru FILTER(LANG(?ru) = "ru") }
      OPTIONAL { ?f rdfs:label ?en FILTER(LANG(?en) = "en") }
    }`) ?? [];
  const byQid = new Map(rows.map(b => [String(v(b, "f")).split("/").pop()!, [Number(v(b, "kp")), v(b, "ru") ?? v(b, "en") ?? ""] as const]));
  const hits = qids.map(q => byQid.get(q)).filter((x): x is readonly [number, string] => !!x && x[0] > 0 && x[1] != "");
  const unique = [...new Map(hits.map(h => [h[0], h])).values()].filter(h => !exclude.includes(h[0]));
  const known = await byIds(unique.map(h => h[0]));
  return unique.map(([id, title]) => known.get(id) ?? stubMovie(id, title));
}

/** A title by Kinopoisk ID: from the catalog, else Wikidata, else a bare stub (still playable). */
export async function movie(id: number): Promise<Movie> {
  return (await byId(id)) ?? (await wikidataMovie(id).catch(() => null)) ?? stubMovie(id);
}

const detailsMemo = new Memo<MovieDetails>(6 * 3600_000, 64);

export async function details(id: number): Promise<MovieDetails> {
  const hit = detailsMemo.get(String(id));
  if (hit) return hit;
  const base = await movie(id);
  const [r, cast, text] = await Promise.all([
    ratings(id),
    people(id).catch(() => [[], []] as [string[], string[]]),
    (async () => {
      const article = base.wiki ?? (base.title.startsWith("Кинопоиск #") ? null : (await wikidataMovie(id).catch(() => null))?.wiki);
      return article ? wikiSummary(article) : null;
    })(),
  ]);
  const m = r ? {
    ...base,
    ratingKp: r.kp ?? base.ratingKp, votesKp: Math.max(r.kpVotes, base.votesKp),
    ratingImdb: r.imdb ?? base.ratingImdb, votesImdb: Math.max(r.imdbVotes, base.votesImdb),
  } : base;
  const d: MovieDetails = { movie: m, description: text, directors: cast[0], cast: cast[1] };
  detailsMemo.set(String(id), d);
  return d;
}

// ---- updates ---------------------------------------------------------------------------------

export const CATALOG_UPDATE_URL = "https://github.com/D0PXMINE/Dopomine-film/releases/download/catalog/catalog.db.gz";

/** Downloads a newer snapshot (rebuilt weekly by GitHub Actions); it is used from the next start. */
export async function checkUpdate(): Promise<UpdateResult> {
  try {
    const current = (await info()).version;
    // a few bytes first: the archive is downloaded only when it is actually newer
    const published = (await fetchText(CATALOG_UPDATE_URL.replace(/[^/]+$/, "version.txt"))).trim();
    if (current && published <= current) return { kind: "upToDate" };
    const data = zlib.gunzipSync(await fetchBuffer(CATALOG_UPDATE_URL));
    const tmp = path.join(dir(), "download.tmp");
    fs.writeFileSync(tmp, data);
    const version = await versionOf(tmp);
    if (!version) {
      fs.rmSync(tmp, { force: true });
      return { kind: "failed", message: "Скачанный каталог повреждён или устарел" };
    }
    if (current && version <= current) {
      fs.rmSync(tmp, { force: true });
      return { kind: "upToDate" };
    }
    fs.renameSync(tmp, pendingFile());
    return { kind: "staged", version };
  } catch (e) {
    const status = (e as { status?: number }).status;
    return { kind: "failed", message: status ? `Обновление недоступно (HTTP ${status})` : String((e as Error).message ?? e) };
  }
}
