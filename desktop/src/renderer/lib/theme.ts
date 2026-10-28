import { settingsStore } from "./state";

const media = window.matchMedia("(prefers-color-scheme: dark)");

export function resolvedTheme(): "light" | "dark" {
  const pref = settingsStore.get()?.theme ?? "system";
  return pref == "system" ? (media.matches ? "dark" : "light") : pref;
}

function refresh() {
  document.documentElement.dataset.theme = resolvedTheme();
}

/** Applies the theme now and keeps it in sync with the setting and the system preference. */
export function initTheme() {
  refresh();
  media.addEventListener("change", refresh);
  settingsStore.subscribe(refresh);
}
