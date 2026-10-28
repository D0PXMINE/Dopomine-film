import { useStore } from "../lib/store";
import { Icon } from "./icons";
import { dismissToast, toastStore } from "../lib/toast";

export default function Toaster() {
  const toasts = useStore(toastStore);

  return (
    <div className="toasts" role="status" aria-live="polite">
      {toasts.map(toast =>
        <div key={toast.id} className={`toast ${toast.kind}`}>
          <div className="toast-text">
            <div className="toast-title">{toast.title}</div>
            {toast.description != null && <div className="toast-desc">{toast.description}</div>}
          </div>

          {toast.action != null &&
            <button
              type="button"
              className="toast-action"
              onClick={() => {
                toast.action!.onClick();
                dismissToast(toast.id);
              }}
            >
              {toast.action.label}
            </button>}

          <button type="button" className="toast-close" aria-label="Закрыть" onClick={() => dismissToast(toast.id)}>
            <Icon name="x" size={14} />
          </button>
        </div>
      )}
    </div>
  );
}
