import type { DpmApi } from "../../shared/types";

declare global {
  interface Window {
    dpm: DpmApi;
  }
}

/** The main process: catalog, library, settings and the player sites (see src/preload). */
export const api: DpmApi = window.dpm;
