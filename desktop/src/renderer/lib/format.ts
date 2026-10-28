import type { ContentType, Movie } from "../../shared/types";

export const rating = (v: number | null | undefined) => (v == null ? "—" : v.toFixed(1));

export function votes(v: number | null | undefined): string | null {
  if (!v || v <= 0) return null;
  if (v >= 1_000_000) return `${(v / 1_000_000).toFixed(1).replace(".", ",")} млн`;
  if (v >= 1_000) return `${Math.floor(v / 1_000)} тыс.`;
  return String(v);
}

export function minutes(v: number | null | undefined): string | null {
  if (!v || v <= 0) return null;
  const h = Math.floor(v / 60);
  const m = v % 60;
  return h == 0 ? `${m} мин` : m == 0 ? `${h} ч` : `${h} ч ${m} мин`;
}

export function clock(totalSeconds: number): string {
  const s = Math.max(0, Math.floor(totalSeconds));
  const h = Math.floor(s / 3600);
  const m = Math.floor((s % 3600) / 60);
  const sec = String(s % 60).padStart(2, "0");
  return h > 0 ? `${h}:${String(m).padStart(2, "0")}:${sec}` : `${m}:${sec}`;
}

export const typeLabel = (t: ContentType) => ({ FILM: "фильм", SERIES: "сериал", MINI: "мини-сериал", SHOW: "шоу" })[t];

/** "2024 · драма, комедия" in lower case, like splicer's mono tag lines. */
export const meta = (m: Movie, genres = 2) =>
  [m.year ? String(m.year) : null, m.genres.slice(0, genres).map(g => g.toLowerCase()).join(", ") || null].filter(Boolean).join(" · ");

export function plural(n: number, one: string, few: string, many: string) {
  const m10 = n % 10;
  const m100 = n % 100;
  const word = m10 == 1 && m100 != 11 ? one : m10 >= 2 && m10 <= 4 && (m100 < 12 || m100 > 14) ? few : many;
  return `${n} ${word}`;
}

export const seasons = (n: number) => plural(n, "сезон", "сезона", "сезонов");
export const episodes = (n: number) => plural(n, "серия", "серии", "серий");
export const titles = (n: number) => plural(n, "фильм", "фильма", "фильмов");

/** Kinopoisk links and bare IDs typed into search. */
export function kpIdFrom(text: string): number | null {
  const t = text.trim();
  const m = /kinopoisk\.ru\/(?:film|series)\/(\d+)/.exec(t) ?? /^(\d{2,9})$/.exec(t);
  return m ? Number(m[1]) : null;
}
