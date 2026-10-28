import { createStore } from "./store";

export interface ToastAction {
  label: string;
  onClick: () => void;
}

export interface Toast {
  id: number;
  kind: "info" | "success" | "error";
  title: string;
  description?: string;
  action?: ToastAction;
}

export const toastStore = createStore<Toast[]>([]);

let nextId = 1;
const timers = new Map<number, ReturnType<typeof setTimeout>>();

export function dismissToast(id: number) {
  const timer = timers.get(id);
  if (timer != null) {
    clearTimeout(timer);
    timers.delete(id);
  }

  toastStore.set(list => list.filter(x => x.id != id));
}

/**
 * Shows a toast in the bottom-right corner. Toasts with an action (e.g. "Undo")
 * stay a little longer, so there's time to click it.
 */
export function showToast(toast: Omit<Toast, "id">, durationMs?: number) {
  const id = nextId++;
  const duration = durationMs ?? (toast.action != null ? 6000 : toast.kind == "error" ? 6000 : 3200);

  // Keep at most 4 toasts on screen.
  toastStore.set(list => [...list, { ...toast, id }].slice(-4));
  timers.set(id, setTimeout(() => dismissToast(id), duration));

  return id;
}

export const toast = {
  info: (title: string, description?: string, action?: ToastAction) =>
    showToast({ kind: "info", title, description, action }),

  success: (title: string, description?: string, action?: ToastAction) =>
    showToast({ kind: "success", title, description, action }),

  error: (title: string, description?: string, action?: ToastAction) =>
    showToast({ kind: "error", title, description, action }),
};
