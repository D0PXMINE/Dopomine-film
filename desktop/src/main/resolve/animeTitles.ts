import type { Movie } from "../../shared/types";
import { normalize } from "../catalog";

/**
 * Title matching for the anime sources. They name releases in Russian and in romaji or English,
 * one release per season ("Вторжение гигантов (второй сезон) / Attack on Titan 2nd Season"),
 * while the catalog has one entry for the whole show.
 */
const ORDINALS = ["перв", "втор", "трет", "четв", "пят", "шест", "седьм", "восьм", "девят", "десят"];
const SEASON_MARKERS = [
  new RegExp(`\\(?\\s*(${ORDINALS.join("|")})\\S*\\s+сезон\\S*\\s*\\)?`, "giu"),
  /\(?\s*\d{1,2}(?:-?й)?\s+сезон\S*\s*\)?/giu,
  /\b\d{1,2}(?:st|nd|rd|th)\s+season\b/gi,
  /\bseason\s*\d{1,2}\b/gi,
  /:?\s*the final season\b/gi,
  /(?:\bpart|часть)\s*\d{1,2}\b/giu,
];
const SUBTITLE = /\s*(?::|\.\s|\s[–—-]\s).*$/u;

export const EXACT = 2;
export const PARTIAL = 1;
export const NONE = 0;

const norm = (text: string | null | undefined) => (text ? normalize(text) || null : null);

/** A name without its subtitle: «Человек-бензопила. Фильм: История Резе» -> «Человек-бензопила». */
function head(name: string | null | undefined): string | null {
  if (!name) return null;
  const h = name.replace(SUBTITLE, "").trim();
  return h && h != name ? h : null;
}

export const names = (m: Movie) => new Set([norm(m.title), norm(m.originalTitle)].filter((x): x is string => !!x));

/** What to type into a source's search: full names first, then without a subtitle. */
export function queries(m: Movie): string[] {
  const list = [m.title, m.originalTitle, head(m.title), head(m.originalTitle)]
    .filter((x): x is string => !!x).map(x => x.trim()).filter(x => x.length >= 2);
  return [...new Set(list)].slice(0, 4);
}

/** A release name without its season marker, normalized: the name of the show itself. */
export function base(name: string | null | undefined): string | null {
  if (!name) return null;
  let text = name;
  for (const re of SEASON_MARKERS) text = text.replace(re, " ");
  return norm(text);
}

/**
 * EXACT when a release name is the title (season marker aside), PARTIAL when the title appears
 * in one as whole words (the catalog has «Фрирен» where the source has the full name).
 */
export function match(m: Movie, ...releaseNames: (string | null | undefined)[]): number {
  const wanted = names(m);
  if (releaseNames.some(n => wanted.has(norm(n) ?? "\0") || wanted.has(base(n) ?? "\0"))) return EXACT;
  const words = [...wanted, norm(head(m.title)), norm(head(m.originalTitle))].filter((x): x is string => !!x && x.length >= 5);
  const partial = releaseNames.some(n => {
    const text = ` ${norm(n) ?? ""} `;
    return words.some(w => text.includes(` ${w} `));
  });
  return partial ? PARTIAL : NONE;
}

export const yearFits = (m: Movie, y: number | null | undefined) => m.year == null || y == null || Math.abs(m.year - y) <= 1;
