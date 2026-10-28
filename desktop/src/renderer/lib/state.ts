import { useEffect, useState } from "react";
import type { HistoryItem, Movie, Settings } from "../../shared/types";
import { api } from "./api";
import { createStore, useStore } from "./store";

// ---- navigation ------------------------------------------------------------------------------

export type View =
  | { kind: "home" }
  | { kind: "catalog" }
  | { kind: "search"; query: string }
  | { kind: "details"; id: number }
  | { kind: "collection"; key: string; title: string }
  | { kind: "favorites" }
  | { kind: "history" }
  | { kind: "settings" };

interface Nav {
  stack: View[];
  /** The title playing in the player overlay, if any. */
  playing: Movie | null;
}

export const navStore = createStore<Nav>({ stack: [{ kind: "home" }], playing: null });

export const useView = () => useStore(navStore, s => s.stack[s.stack.length - 1]);
export const usePlaying = () => useStore(navStore, s => s.playing);
export const canGoBack = () => navStore.get().stack.length > 1;

/** Top-level sections replace the history; details and collections stack on top of it. */
export function navigate(view: View) {
  navStore.set(s => {
    const top = s.stack[s.stack.length - 1];
    if (JSON.stringify(top) == JSON.stringify(view)) return s;
    const root = view.kind == "home" || view.kind == "catalog" || view.kind == "favorites" || view.kind == "history" || view.kind == "settings";
    if (view.kind == "search" && top.kind == "search") return { ...s, stack: [...s.stack.slice(0, -1), view] };
    return { ...s, stack: root ? [view] : [...s.stack, view].slice(-30) };
  });
}

export function goBack() {
  navStore.set(s => (s.stack.length > 1 ? { ...s, stack: s.stack.slice(0, -1) } : s));
}

export const openDetails = (id: number) => navigate({ kind: "details", id });

export function play(movie: Movie) {
  navStore.set(s => ({ ...s, playing: movie }));
  void api.library.markOpened(movie);
}

export const closePlayer = () => navStore.set(s => ({ ...s, playing: null }));

// ---- settings --------------------------------------------------------------------------------

export const settingsStore = createStore<Settings | null>(null);
export const useSettings = () => useStore(settingsStore);

export async function setSetting(patch: Partial<Settings>) {
  settingsStore.set(await api.settings.set(patch));
}

export async function initSettings() {
  settingsStore.set(await api.settings.get());
  api.settings.onChange(s => settingsStore.set(s));
}

// ---- library -----------------------------------------------------------------------------------

/** Re-reads something from the library whenever it changes (favourites, history, positions). */
export function useLibrary<T>(read: () => Promise<T>, initial: T): T {
  const [value, setValue] = useState<T>(initial);
  useEffect(() => {
    let alive = true;
    const load = () => void read().then(v => alive && setValue(v));
    load();
    const off = api.library.onChange(load);
    return () => {
      alive = false;
      off();
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);
  return value;
}

export const useFavorites = () => useLibrary<Movie[]>(() => api.library.favorites(), []);
export const useHistory = () => useLibrary<HistoryItem[]>(() => api.library.history(), []);

/** Loads something once per key; null while loading. */
export function useAsync<T>(load: () => Promise<T>, deps: unknown[]): { value: T | null; error: string | null; reload: () => void } {
  const [state, setState] = useState<{ value: T | null; error: string | null }>({ value: null, error: null });
  const [n, setN] = useState(0);
  useEffect(() => {
    let alive = true;
    setState(s => ({ value: s.value, error: null }));
    load().then(
      value => alive && setState({ value, error: null }),
      e => alive && setState({ value: null, error: String((e as Error)?.message ?? e) }),
    );
    return () => {
      alive = false;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [...deps, n]);
  return { ...state, reload: () => setN(x => x + 1) };
}
