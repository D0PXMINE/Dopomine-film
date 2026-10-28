import { CSSProperties, useContext, useEffect, useRef, useState } from "react";
import { Canvas2DHalftone, Scene, Variant, makeScene } from "./field";
import { GlHalftone } from "./gl";
import { MotionPaused, dialogsOpen, motionAllowed, motionClock, registerAnimation, wakeMotion } from "./motion";
import { drawCoverPattern } from "./patterns";

export { seedOf } from "./field";

/** Set once WebGL fails anywhere, so every other canvas goes straight to Canvas 2D. */
let glUnavailable = false;

/**
 * Renderers by canvas. A canvas keeps its WebGL context for its whole life, so
 * a renderer is reused when the art's settings change (and when React runs
 * effects twice in development), and only freed once its canvas is gone.
 */
const glRenderers = new WeakMap<HTMLCanvasElement, GlHalftone>();

function glRendererFor(canvas: HTMLCanvasElement, scene: Scene) {
  const existing = glRenderers.get(canvas);
  if (existing != null && !existing.lost) {
    existing.setScene(scene);
    return existing;
  }

  if (existing != null)
    return null; // a lost context can't be recreated on the same canvas

  const created = GlHalftone.create(canvas, scene);
  if (created != null)
    glRenderers.set(canvas, created);

  return created;
}

/** Frees a canvas's renderer once the canvas has been removed from the page. */
function releaseWhenDetached(canvas: HTMLCanvasElement) {
  setTimeout(() => {
    const renderer = glRenderers.get(canvas);
    if (renderer != null && !canvas.isConnected) {
      renderer.dispose();
      glRenderers.delete(canvas);
    }
  }, 0);
}

function parseRgb(css: string): [number, number, number] {
  const parts = css.match(/[\d.]+/g);
  if (parts == null || parts.length < 3)
    return [0, 0, 0];

  return [Number(parts[0]) / 255, Number(parts[1]) / 255, Number(parts[2]) / 255];
}

/** Calls `redraw` when the theme (or, while following it, the system theme) changes. */
function useThemeRedraw(redraw: React.RefObject<() => void>) {
  useEffect(() => {
    redraw.current();

    // the theme lives on <html data-theme>; the system theme may change it too
    const onChange = () => requestAnimationFrame(() => redraw.current());
    const observer = new MutationObserver(onChange);
    observer.observe(document.documentElement, { attributes: true, attributeFilter: ["data-theme"] });
    const media = window.matchMedia("(prefers-color-scheme: dark)");
    media.addEventListener("change", onChange);
    return () => {
      observer.disconnect();
      media.removeEventListener("change", onChange);
    };
  }, [redraw]);
}

/**
 * Animated halftone art, drawn in the current text color. It's rendered with
 * WebGL when available (falling back to Canvas 2D at a lower frame rate), and
 * only animates while visible - see motion.ts.
 */
export function Halftone({ variant, seed, cell = 6, className, style }: {
  variant: Variant,
  seed: number,
  /** Dot spacing in CSS pixels. */
  cell?: number,
  className?: string,
  style?: CSSProperties
}) {
  const ref = useRef<HTMLCanvasElement | null>(null);
  const paused = useContext(MotionPaused);
  const pausedRef = useRef(paused);
  pausedRef.current = paused;

  const [mode, setMode] = useState<"gl" | "2d">(glUnavailable ? "2d" : "gl");
  const redraw = useRef<() => void>(() => {});

  useEffect(() => {
    const canvas = ref.current;
    if (canvas == null)
      return;

    const scene = makeScene(variant, seed);
    let gl: GlHalftone | null = null;
    let cpu: Canvas2DHalftone | null = null;

    if (mode == "gl") {
      gl = glRendererFor(canvas, scene);
      if (gl == null) {
        glUnavailable = true;
        setMode("2d");
        return;
      }
    } else {
      const ctx = canvas.getContext("2d");
      if (ctx == null)
        return;

      cpu = new Canvas2DHalftone(ctx, scene, cell);
    }

    let width = 0;
    let height = 0;
    let dpr = 1;
    let visible = true;
    let cssColor = "#000";
    let rgb: [number, number, number] = [0, 0, 0];

    const draw = (time: number) => {
      if (width <= 0 || height <= 0)
        return;

      const animated = motionAllowed();
      if (gl != null) {
        gl.draw(time, rgb, cell, animated);
      } else {
        cpu?.draw(time, cssColor, animated);
      }
    };

    const resize = () => {
      const rect = canvas.getBoundingClientRect();
      width = rect.width;
      height = rect.height;
      dpr = Math.min(window.devicePixelRatio || 1, 2);

      if (gl != null) {
        gl.resize(width, height, dpr);
      } else {
        cpu?.resize(width, height, dpr);
      }

      draw(motionClock());
    };

    redraw.current = () => {
      cssColor = getComputedStyle(canvas).color || "#000";
      rgb = parseRgb(cssColor);
      draw(motionClock());
    };

    redraw.current();
    resize();

    const resizeObserver = new ResizeObserver(resize);
    resizeObserver.observe(canvas);

    const intersection = new IntersectionObserver(entries => {
      visible = entries.some(x => x.isIntersecting);
      if (visible)
        wakeMotion();
    });
    intersection.observe(canvas);

    // Art inside a dialog keeps animating while it's open; art behind one pauses.
    const inDialog = canvas.closest(".modal") != null;

    const unregister = registerAnimation({
      tick: draw,
      active: () => visible && !pausedRef.current && width > 0 && (inDialog || !dialogsOpen()),
      // The heartbeat needs a smoother pace than the slow drift of the other art.
      fps: gl != null ? 30 : variant == "heart" ? 24 : 12,
      last: 0
    });

    const onContextLost = (ev: Event) => {
      ev.preventDefault();
      glUnavailable = true;
      setMode("2d");
    };
    canvas.addEventListener("webglcontextlost", onContextLost);

    return () => {
      unregister();
      resizeObserver.disconnect();
      intersection.disconnect();
      canvas.removeEventListener("webglcontextlost", onContextLost);
      redraw.current = () => {};
      if (gl != null)
        releaseWhenDetached(canvas);
    };
  }, [variant, seed, cell, mode]);

  useThemeRedraw(redraw);

  // Pick up where we left off when a hidden screen becomes visible again.
  useEffect(() => {
    if (!paused)
      wakeMotion();
  }, [paused]);

  // A new canvas element per mode: a canvas can't switch from WebGL to 2D.
  return <canvas key={mode} ref={ref} className={className} style={style} aria-hidden="true" />;
}

/**
 * A still dot pattern (as used for drum kit covers), drawn in the current text
 * color. Patterns never animate.
 */
export function StaticPattern({ seed, className, style }: {
  seed: number,
  className?: string,
  style?: CSSProperties
}) {
  const ref = useRef<HTMLCanvasElement | null>(null);
  const redraw = useRef<() => void>(() => {});

  useEffect(() => {
    const canvas = ref.current;
    const ctx = canvas?.getContext("2d");
    if (canvas == null || ctx == null)
      return;

    const draw = () => {
      const rect = canvas.getBoundingClientRect();
      if (rect.width < 1)
        return;

      const dpr = Math.min(window.devicePixelRatio || 1, 2);
      const size = Math.round(rect.width * dpr);
      canvas.width = size;
      canvas.height = size;
      drawCoverPattern(ctx, size, seed, getComputedStyle(canvas).color || "#000", null);
    };

    redraw.current = draw;
    draw();

    const observer = new ResizeObserver(draw);
    observer.observe(canvas);
    return () => {
      observer.disconnect();
      redraw.current = () => {};
    };
  }, [seed]);

  useThemeRedraw(redraw);

  return <canvas ref={ref} className={className} style={style} aria-hidden="true" />;
}
