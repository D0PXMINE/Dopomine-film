import {
  KeyboardEvent as ReactKeyboardEvent, ReactNode, RefObject, useCallback, useEffect,
  useLayoutEffect, useRef, useState
} from "react";
import { createPortal } from "react-dom";
import { Icon, IconName } from "./icons";
import { dialogOpened } from "../halftone/motion";

// Small, dependency-free UI building blocks: popovers, menus, modals, switches
// and segmented controls. They replace the HeroUI component library, which
// pulled in a large runtime for the handful of widgets the app needs.

const FOCUSABLE = 'button:not([disabled]), [href], input:not([disabled]), select, textarea, [tabindex]:not([tabindex="-1"])';

// ---------------------------------------------------------------------------
// Popover
// ---------------------------------------------------------------------------

export type Placement = "bottom-start" | "bottom-end" | "top-start" | "top-end";

export interface PopoverProps {
  anchor: RefObject<HTMLElement | null>;
  open: boolean;
  onClose: () => void;
  placement?: Placement;
  /** Makes the popover at least as wide as its anchor. */
  matchWidth?: boolean;
  className?: string;
  children: ReactNode;
  /** Focus the first focusable element inside when opened (default) instead of the popover itself. */
  autoFocusFirst?: boolean;
  role?: string;
  "aria-label"?: string;
}

export function Popover({
  anchor, open, onClose, placement = "bottom-start", matchWidth, className, children,
  autoFocusFirst = true, role = "dialog", ...aria
}: PopoverProps) {
  const ref = useRef<HTMLDivElement | null>(null);
  const [pos, setPos] = useState<{ top: number, left: number, minWidth?: number } | null>(null);

  const reposition = useCallback(() => {
    const a = anchor.current;
    const el = ref.current;
    if (a == null || el == null)
      return;

    const rect = a.getBoundingClientRect();
    const width = el.offsetWidth;
    const height = el.offsetHeight;
    const margin = 8;
    const gap = 6;

    const fitsBelow = rect.bottom + gap + height <= window.innerHeight - margin;
    const fitsAbove = rect.top - gap - height >= margin;
    const wantsTop = placement.startsWith("top");
    const top = (wantsTop && fitsAbove) || (!wantsTop && !fitsBelow && fitsAbove)
      ? rect.top - gap - height
      : Math.min(rect.bottom + gap, window.innerHeight - margin - height);

    let left = placement.endsWith("end") ? rect.right - width : rect.left;
    left = Math.max(margin, Math.min(left, window.innerWidth - margin - width));

    setPos({ top: Math.max(margin, top), left, minWidth: matchWidth ? rect.width : undefined });
  }, [anchor, placement, matchWidth]);

  useLayoutEffect(() => {
    if (!open) {
      setPos(null);
      return;
    }

    reposition();
    const observer = new ResizeObserver(reposition);
    if (ref.current != null)
      observer.observe(ref.current);

    window.addEventListener("resize", reposition);
    window.addEventListener("scroll", reposition, true);

    return () => {
      observer.disconnect();
      window.removeEventListener("resize", reposition);
      window.removeEventListener("scroll", reposition, true);
    };
  }, [open, reposition]);

  // Focus inside on open, and give focus back to the trigger on close.
  useEffect(() => {
    if (!open)
      return;

    const trigger = anchor.current;
    const frame = requestAnimationFrame(() => {
      const el = ref.current;
      if (el == null)
        return;

      const first = autoFocusFirst ? el.querySelector<HTMLElement>(FOCUSABLE) : null;
      (first ?? el).focus({ preventScroll: true });
    });

    return () => {
      cancelAnimationFrame(frame);
      if (trigger != null && document.activeElement != null && ref.current?.contains(document.activeElement))
        trigger.focus({ preventScroll: true });
    };
  }, [open, anchor, autoFocusFirst]);

  // Close on outside clicks and Escape.
  const onCloseRef = useRef(onClose);
  onCloseRef.current = onClose;

  useEffect(() => {
    if (!open)
      return;

    const onDown = (ev: MouseEvent) => {
      const target = ev.target as Node;
      if (ref.current?.contains(target) || anchor.current?.contains(target))
        return;

      onCloseRef.current();
    };

    const onKey = (ev: KeyboardEvent) => {
      if (ev.key == "Escape") {
        ev.stopPropagation();
        onCloseRef.current();
      }
    };

    document.addEventListener("mousedown", onDown, true);
    document.addEventListener("keydown", onKey, true);
    return () => {
      document.removeEventListener("mousedown", onDown, true);
      document.removeEventListener("keydown", onKey, true);
    };
  }, [open, anchor]);

  if (!open)
    return null;

  return createPortal(
    <div
      ref={ref}
      role={role}
      aria-label={aria["aria-label"]}
      tabIndex={-1}
      className={`popover ${className ?? ""}`}
      style={pos == null
        ? { top: 0, left: 0, visibility: "hidden" }
        : { top: pos.top, left: pos.left, minWidth: pos.minWidth }}
    >
      {children}
    </div>,
    document.body
  );
}

/** State for a popover anchored to a trigger button. */
export function usePopover<T extends HTMLElement = HTMLButtonElement>() {
  const anchor = useRef<T | null>(null);
  const [open, setOpen] = useState(false);
  const close = useCallback(() => setOpen(false), []);
  const toggle = useCallback(() => setOpen(x => !x), []);

  return { anchor, open, setOpen, close, toggle };
}

// ---------------------------------------------------------------------------
// Menu
// ---------------------------------------------------------------------------

/** Moves focus between menu items with the arrow keys. */
export function menuKeyDown(ev: ReactKeyboardEvent<HTMLElement>) {
  if (ev.key != "ArrowDown" && ev.key != "ArrowUp" && ev.key != "Home" && ev.key != "End")
    return;

  const items = [...ev.currentTarget.querySelectorAll<HTMLElement>("[data-menu-item]:not([disabled])")];
  if (items.length == 0)
    return;

  ev.preventDefault();
  const idx = items.indexOf(document.activeElement as HTMLElement);

  let next = 0;
  if (ev.key == "ArrowDown") next = idx < 0 ? 0 : (idx + 1) % items.length;
  else if (ev.key == "ArrowUp") next = idx <= 0 ? items.length - 1 : idx - 1;
  else if (ev.key == "End") next = items.length - 1;

  items[next].focus();
}

export function Menu({ children, className, label }: { children: ReactNode, className?: string, label?: string }) {
  return (
    <div role="menu" aria-label={label} className={`menu scroll ${className ?? ""}`} onKeyDown={menuKeyDown}>
      {children}
    </div>
  );
}

export function MenuItem({ icon, iconNode, children, onSelect, hint, checked, danger, disabled }: {
  icon?: IconName,
  iconNode?: ReactNode,
  children: ReactNode,
  onSelect: () => void,
  hint?: ReactNode,
  checked?: boolean,
  danger?: boolean,
  disabled?: boolean
}) {
  return (
    <button
      type="button"
      role={checked == null ? "menuitem" : "menuitemcheckbox"}
      aria-checked={checked}
      data-menu-item
      disabled={disabled}
      className={`menu-item ${danger ? "danger" : ""}`}
      onClick={onSelect}
    >
      {iconNode ?? (icon != null && <Icon name={icon} />)}
      <span className="truncate" style={{ flex: 1, minWidth: 0 }}>{children}</span>
      {hint != null && <span className="hint">{hint}</span>}
      {checked && <Icon name="check" className="check" />}
    </button>
  );
}

export function MenuSeparator() {
  return <div className="menu-sep" role="separator" />;
}

export function MenuLabel({ children }: { children: ReactNode }) {
  return <div className="menu-label caps">{children}</div>;
}

// ---------------------------------------------------------------------------
// Modal
// ---------------------------------------------------------------------------

export function Modal({ open, onClose, dismissable = true, className, children, label }: {
  open: boolean,
  onClose: () => void,
  dismissable?: boolean,
  className?: string,
  children: ReactNode,
  label?: string
}) {
  const ref = useRef<HTMLDivElement | null>(null);

  useEffect(() => open ? dialogOpened() : undefined, [open]);

  // Parents often pass a new onClose on every render; keep the latest in a ref
  // so focus handling below doesn't re-run (and steal focus) on each render.
  const onCloseRef = useRef(onClose);
  onCloseRef.current = onClose;

  useEffect(() => {
    if (!open)
      return;

    const previous = document.activeElement as HTMLElement | null;
    const frame = requestAnimationFrame(() => {
      const el = ref.current;
      if (el == null)
        return;

      const autofocus = el.querySelector<HTMLElement>("[data-autofocus]");
      (autofocus ?? el).focus({ preventScroll: true });
    });

    const onKey = (ev: KeyboardEvent) => {
      if (ev.key == "Escape" && dismissable) {
        ev.stopPropagation();
        onCloseRef.current();
        return;
      }

      // Keep keyboard focus inside the dialog.
      if (ev.key == "Tab" && ref.current != null) {
        const items = [...ref.current.querySelectorAll<HTMLElement>(FOCUSABLE)];
        if (items.length == 0)
          return;

        const first = items[0];
        const last = items[items.length - 1];
        if (ev.shiftKey && document.activeElement == first) {
          ev.preventDefault();
          last.focus();
        } else if (!ev.shiftKey && document.activeElement == last) {
          ev.preventDefault();
          first.focus();
        }
      }
    };

    document.addEventListener("keydown", onKey);
    return () => {
      cancelAnimationFrame(frame);
      document.removeEventListener("keydown", onKey);
      previous?.focus?.({ preventScroll: true });
    };
  }, [open, dismissable]);

  if (!open)
    return null;

  return createPortal(
    <div
      className="backdrop"
      onMouseDown={ev => {
        if (dismissable && ev.target == ev.currentTarget)
          onClose();
      }}
    >
      <div ref={ref} role="dialog" aria-modal="true" aria-label={label} tabIndex={-1} className={`modal ${className ?? ""}`}>
        {dismissable &&
          <button type="button" className="icon-btn modal-close" onClick={onClose} aria-label="Close">
            <Icon name="x" />
          </button>}
        {children}
      </div>
    </div>,
    document.body
  );
}

// ---------------------------------------------------------------------------
// Controls
// ---------------------------------------------------------------------------

export function Switch({ checked, onChange, label }: { checked: boolean, onChange: (value: boolean) => void, label: string }) {
  return (
    <button
      type="button"
      role="switch"
      aria-checked={checked}
      aria-label={label}
      className="switch"
      onClick={() => onChange(!checked)}
    />
  );
}

export function Segmented<T extends string>({ value, options, onChange, block, label }: {
  value: T,
  options: { value: T, label: ReactNode }[],
  onChange: (value: T) => void,
  block?: boolean,
  label?: string
}) {
  return (
    <div className={`segmented ${block ? "block" : ""}`} role="group" aria-label={label}>
      {options.map(x =>
        <button key={x.value} type="button" aria-pressed={x.value == value} onClick={() => onChange(x.value)}>
          {x.label}
        </button>
      )}
    </div>
  );
}

export function Spinner({ size = 16 }: { size?: number }) {
  return <Icon name="loader-circle" size={size} className="spin" />;
}

/** Runs `handler` for clicks outside of the referenced element while `active`. */
export function useOutsideClick(ref: RefObject<HTMLElement | null>, active: boolean, handler: () => void) {
  useEffect(() => {
    if (!active)
      return;

    const onDown = (ev: MouseEvent) => {
      if (ref.current != null && !ref.current.contains(ev.target as Node))
        handler();
    };

    document.addEventListener("mousedown", onDown, true);
    return () => document.removeEventListener("mousedown", onDown, true);
  }, [ref, active, handler]);
}
