import type { Stream, StreamFormat } from "../../shared/types";

export class ResolveError extends Error {}

export function formatOf(url: string): StreamFormat {
  const p = url.split("?")[0].toLowerCase();
  if (p.includes(".m3u8")) return "HLS";
  if (p.endsWith(".mpd")) return "DASH";
  return "PROGRESSIVE";
}

export function stream(url: string, format: StreamFormat, extra: Partial<Stream> = {}): Stream {
  return { url, format, headers: {}, audioNames: [], subtitles: [], variants: [], segments: [], ...extra };
}

export const str = (o: unknown, key: string): string | null => {
  const v = (o as Record<string, unknown> | null)?.[key];
  return v == null ? null : String(v).trim() || null;
};

export const int = (o: unknown, key: string): number | null => {
  const v = (o as Record<string, unknown> | null)?.[key];
  if (v == null || v === "") return null;
  const n = Number(v);
  return Number.isFinite(n) ? Math.trunc(n) : null;
};

export type Log = (line: string) => void;
