import { createContext } from "react";

// One shared animation loop for all animated halftone art. It keeps the cost
// low: frames are capped per animation, nothing runs while the app window is
// in the background (e.g. while you're working in your DAW) or minimized, and
// art that's scrolled out of view or on a hidden screen is skipped. The
// "Animated dots" setting turns the animations off entirely.

export interface Animation {
  /** Draws a frame for the given animation time (seconds). */
  tick: (time: number) => void;
  /** Whether this animation should currently run (e.g. it's on screen). */
  active: () => boolean;
  /** Frame cap for this animation. */
  fps: number;
  /** Timestamp of the last frame drawn. */
  last: number;
}


const animations = new Set<Animation>();

/** Tests (in a plain browser) can force animations to run without window focus. */
const forced = new URLSearchParams(location.search).has("motion");

let enabled = true;
let raf = 0;
let clock = 8;       // seconds of animation time; only advances while running
let lastNow = 0;
/** Set once tests or screenshots set the clock: from then on it stays where they put it. */
let held = false;


export function motionAllowed() {
  return enabled;
}

/** Turns the animations on or off (the "Animated dots" setting). */
export function setMotionEnabled(value: boolean) {
  if (enabled == value)
    return;

  enabled = value;

  // Redraw everything right away: a still frame, or the first animated one.
  for (const animation of animations) {
    animation.tick(clock);
  }

  wakeMotion();
}

function suspended() {
  if (!enabled)
    return true;

  if (forced)
    return false;

  return document.visibilityState == "hidden" || !document.hasFocus();
}

function loop(now: number) {
  raf = 0;

  if (suspended()) {
    lastNow = 0;
    return; // focus events start the loop again
  }

  // Advance the clock by real time, but never jump after a pause.
  const dt = lastNow == 0 ? 0 : Math.min(now - lastNow, 100);
  lastNow = now;
  if (!held)
    clock += dt / 1000;

  let running = false;
  for (const animation of animations) {
    if (!animation.active())
      continue;

    running = true;
    if (now - animation.last >= 1000 / animation.fps - 4) {
      animation.last = now;
      animation.tick(clock);
    }
  }

  if (running) {
    raf = requestAnimationFrame(loop);
  } else {
    lastNow = 0;
  }
}

/** Starts the loop if anything should be animating. Cheap to call often. */
export function wakeMotion() {
  if (raf == 0 && animations.size > 0 && !suspended()) {
    raf = requestAnimationFrame(loop);
  }
}

let openDialogs = 0;

/**
 * Dialogs blur what's behind them, so animating art back there would force
 * the blur to be recomputed every frame. Art outside dialogs pauses while one
 * is open (dialogs report themselves here).
 */
export function dialogOpened() {
  openDialogs++;
  return () => {
    openDialogs = Math.max(0, openDialogs - 1);
    wakeMotion();
  };
}

export function dialogsOpen() {
  return openDialogs > 0;
}

export function registerAnimation(animation: Animation) {
  animations.add(animation);
  wakeMotion();

  return () => {
    animations.delete(animation);
  };
}

/** The current animation time, for drawing a still frame. */
export function motionClock() {
  return clock;
}

window.addEventListener("focus", wakeMotion);
window.addEventListener("pointerdown", wakeMotion, { capture: true, passive: true });
document.addEventListener("visibilitychange", wakeMotion);


/**
 * Set to `true` by screens that are hidden but stay mounted (like the browse
 * view while another view is open), so their art stops animating.
 */
export const MotionPaused = createContext(false);

/** How many animations would currently run (for tests). */
export function activeAnimations() {
  let count = 0;
  for (const animation of animations) {
    if (animation.active())
      count++;
  }
  return count;
}

/** Hooks for tests and screenshots in a plain browser: set the clock and redraw what's animating. */
export function setMotionClock(time: number) {
  held = true;
  clock = time;
  for (const animation of animations) {
    if (animation.active())
      animation.tick(clock);
  }
}
