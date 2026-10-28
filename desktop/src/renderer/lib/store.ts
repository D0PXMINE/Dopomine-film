import { useSyncExternalStore } from "react";

/**
 * A minimal observable value. Components subscribe to it via `useStore`, and
 * re-render only when the slice they selected changes.
 */
export interface Store<T> {
  get(): T;
  set(next: T | ((prev: T) => T)): void;
  subscribe(listener: () => void): () => void;
}

export function createStore<T>(initial: T): Store<T> {
  let state = initial;
  const listeners = new Set<() => void>();

  return {
    get: () => state,

    set(next) {
      const value = typeof next === "function"
        ? (next as (prev: T) => T)(state)
        : next;

      if (Object.is(value, state))
        return;

      state = value;
      for (const listener of [...listeners]) {
        listener();
      }
    },

    subscribe(listener) {
      listeners.add(listener);
      return () => { listeners.delete(listener); };
    }
  };
}

/**
 * Subscribes a component to a store. The selector must return a value that is
 * stable between calls when nothing changed (a primitive, or a reference that
 * already lives in the state) - never a freshly built object or array.
 */
export function useStore<T>(store: Store<T>): T;
export function useStore<T, S>(store: Store<T>, selector: (state: T) => S): S;
export function useStore<T, S>(store: Store<T>, selector?: (state: T) => S): T | S {
  const get = () => selector ? selector(store.get()) : store.get();
  return useSyncExternalStore(store.subscribe, get, get);
}
