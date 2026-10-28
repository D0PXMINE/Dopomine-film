import { HEART_ROWS } from "./heartGrid";

// The heart on the welcome screen, in halftone dots like the rest of the art:
// a smooth tone map of the pixel art (blurred a little so its dithered
// shading turns back into tones, which the dots then re-draw), with an empty
// margin around it so nothing is cut off. It doesn't drift like the other
// art - it beats (`heartBeat`): the field (field.ts / gl.ts) grows the heart
// and swells its dots with every beat.

/** Tone-map texels per pixel of the heart's grid. */
const SCALE = 4;

/** Empty margin around the heart, in grid pixels. */
const PAD = 3;

export const HEART_GRID_W = HEART_ROWS[0].length;
export const HEART_GRID_H = HEART_ROWS.length;

/** The tone map's size in texels. */
export const HEART_TEX_W = (HEART_GRID_W + PAD * 2) * SCALE;
export const HEART_TEX_H = (HEART_GRID_H + PAD * 2) * SCALE;

/** Where the heart itself sits in the tone map, as fractions of its size. */
export const HEART_INSET = {
  x: PAD / (HEART_GRID_W + PAD * 2),
  y: PAD / (HEART_GRID_H + PAD * 2)
};

function blur1d(src: Float32Array, w: number, h: number, sigma: number, horizontal: boolean) {
  const radius = Math.ceil(sigma * 3);
  const kernel: number[] = [];
  let sum = 0;
  for (let i = -radius; i <= radius; i++) {
    const k = Math.exp(-(i * i) / (2 * sigma * sigma));
    kernel.push(k);
    sum += k;
  }

  const out = new Float32Array(w * h);
  for (let y = 0; y < h; y++) {
    for (let x = 0; x < w; x++) {
      let acc = 0;
      for (let i = -radius; i <= radius; i++) {
        const sx = horizontal ? x + i : x;
        const sy = horizontal ? y : y + i;
        if (sx >= 0 && sx < w && sy >= 0 && sy < h)
          acc += src[sy * w + sx] * kernel[i + radius];
      }
      out[y * w + x] = acc / sum;
    }
  }

  return out;
}

let tone: Float32Array | null = null;

/** The heart's tone map: `HEART_TEX_W` x `HEART_TEX_H`, row by row from the top, 0 (paper) to 1 (ink). */
export function heartTone() {
  if (tone != null)
    return tone;

  const w = HEART_TEX_W;
  const h = HEART_TEX_H;
  const raw = new Float32Array(w * h);

  for (let gy = 0; gy < HEART_GRID_H; gy++) {
    const row = HEART_ROWS[gy];
    for (let gx = 0; gx < HEART_GRID_W; gx++) {
      if (row[gx] != "#")
        continue;

      for (let sy = 0; sy < SCALE; sy++) {
        for (let sx = 0; sx < SCALE; sx++) {
          raw[((gy + PAD) * SCALE + sy) * w + (gx + PAD) * SCALE + sx] = 1;
        }
      }
    }
  }

  // Blur by a bit over half a grid pixel: dithered areas become mid-tones,
  // while the outline and the solid parts stay crisp enough to read.
  const sigma = 0.6 * SCALE;
  tone = blur1d(blur1d(raw, w, h, sigma, true), w, h, sigma, false);
  return tone;
}

/** The value at (s, t) in [0, 1]² of a map the size of the tone map (bilinear; 0 outside). */
export function sampleHeartMap(map: Float32Array, s: number, t: number) {
  if (s <= 0 || s >= 1 || t <= 0 || t >= 1)
    return 0;

  const x = s * HEART_TEX_W - 0.5;
  const y = t * HEART_TEX_H - 0.5;
  const x0 = Math.max(0, Math.floor(x));
  const y0 = Math.max(0, Math.floor(y));
  const x1 = Math.min(HEART_TEX_W - 1, x0 + 1);
  const y1 = Math.min(HEART_TEX_H - 1, y0 + 1);
  const fx = Math.min(1, Math.max(0, x - x0));
  const fy = Math.min(1, Math.max(0, y - y0));

  const a = map[y0 * HEART_TEX_W + x0];
  const b = map[y0 * HEART_TEX_W + x1];
  const c = map[y1 * HEART_TEX_W + x0];
  const d = map[y1 * HEART_TEX_W + x1];
  return a + (b - a) * fx + (c - a) * fy + (a - b - c + d) * fx * fy;
}

/**
 * Where the tone map sits on the canvas: centered, `HEIGHT` of the canvas
 * tall (times `scale`, which the heartbeat grows). Returns the tone-map
 * coordinates for canvas coordinates (u, v).
 */
export const HEART_HEIGHT = 0.9;

export function heartCoords(u: number, v: number, aspect: number, scale = 1): [number, number] {
  const texAspect = HEART_TEX_W / HEART_TEX_H;
  const boxH = HEART_HEIGHT * scale;
  const boxW = (boxH * texAspect) / aspect;
  return [(u - 0.5) / boxW + 0.5, (v - 0.5) / boxH + 0.5];
}

// ---------------------------------------------------------------------------
// The heartbeat (mirrored by the shader in gl.ts)
// ---------------------------------------------------------------------------

/** The heart's pace, in beats per minute. */
export const HEART_BPM = 62;

/** How much the heart grows at the peak of a beat, as a fraction of its size. */
export const HEART_BEAT_GROW = 0.075;

/** How much its dots swell (darken) at the peak of a beat. */
export const HEART_BEAT_SWELL = 0.4;

/**
 * How much later (as a fraction of a beat) the squeeze reaches the heart's
 * outline than its middle, per unit of distance (the tone map's height):
 * every beat runs outwards through the heart like a pulse.
 */
export const HEART_BEAT_DELAY = 0.14;

/** How far through a beat (0..1) the heart is at time `t` (seconds). */
export function heartPhase(t: number) {
  const beats = t * HEART_BPM / 60;
  return beats - Math.floor(beats);
}

/** A bump at `center` with a fast rise and a slower fall, like a muscle contracting and relaxing. */
function bump(x: number, center: number, rise: number, fall: number) {
  const d = x - center;
  const w = d < 0 ? rise : fall;
  return Math.exp(-(d * d) / (w * w));
}

/**
 * How hard the heart is contracting at `phase` (0..1 through a beat): a
 * strong first beat ("lub"), a softer second one ("dub") right after it,
 * then a rest until the next beat. 0 at rest, about 1 at the peak.
 */
export function heartBeat(phase: number) {
  return bump(phase, 0.1, 0.035, 0.08) + 0.55 * bump(phase, 0.31, 0.035, 0.095);
}
