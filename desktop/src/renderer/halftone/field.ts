// The math behind the halftone art: diamond-shaped dots on a 45° lattice,
// sized by an intensity field. The WebGL shader (gl.ts) mirrors the functions
// in this file, so the animated art and its Canvas 2D fallback look the same.
//
// Lattice: with spacing `cell`, dots sit at ((u + v) * cell / 2, (u - v) * cell / 2)
// for integer (u, v) - rows `cell / 2` apart, every other row offset by half a
// cell. A dot of radius r (center to vertex, 0..cell/2) is the diamond
// |dx| + |dy| <= r; at r = cell/2 neighbours touch and the area turns solid.

import {
  HEART_BEAT_DELAY, HEART_BEAT_GROW, HEART_BEAT_SWELL, HEART_TEX_H, HEART_TEX_W, heartBeat, heartCoords, heartPhase,
  heartTone, sampleHeartMap
} from "./heart";

export type Variant = "cloud" | "banner" | "glyph" | "heart";

export interface Scene {
  variant: Variant;
  /** Offset into the noise domain, derived from the seed. */
  seedX: number;
  seedY: number;
}

/** A small, fast PRNG (xorshift32). */
export function rng(seed: number) {
  let s = (seed >>> 0) || 0x9e3779b9;
  return () => {
    s ^= s << 13; s >>>= 0;
    s ^= s >>> 17;
    s ^= s << 5; s >>>= 0;
    return s / 4294967296;
  };
}

/** Hashes a string (e.g. a kit name) into a seed. */
export function seedOf(text: string) {
  let h = 2166136261;
  for (let i = 0; i < text.length; i++) {
    h ^= text.charCodeAt(i);
    h = Math.imul(h, 16777619);
  }
  return h >>> 0;
}

export function makeScene(variant: Variant, seed: number): Scene {
  // Small offsets keep the hash precise in the shader's 32-bit floats.
  return {
    variant,
    seedX: (seed % 97) * 1.618,
    seedY: (seed % 89) * 2.414
  };
}

const fract = (x: number) => x - Math.floor(x);

/** "Hash without sine" (Dave Hoskins) - the same function the shader uses. */
export function hash12(x: number, y: number) {
  let ax = fract(x * 0.1031);
  let ay = fract(y * 0.1031);
  let az = ax;
  const d = ax * (ay + 33.33) + ay * (az + 33.33) + az * (ax + 33.33);
  ax += d; ay += d; az += d;
  return fract((ax + ay) * az);
}

function vnoise(x: number, y: number, sx: number, sy: number) {
  const ix = Math.floor(x);
  const iy = Math.floor(y);
  const fx = x - ix;
  const fy = y - iy;
  const ux = fx * fx * (3 - 2 * fx);
  const uy = fy * fy * (3 - 2 * fy);

  const a = hash12(ix + sx, iy + sy);
  const b = hash12(ix + 1 + sx, iy + sy);
  const c = hash12(ix + sx, iy + 1 + sy);
  const d = hash12(ix + 1 + sx, iy + 1 + sy);

  return a + (b - a) * ux + (c - a) * uy + (a - b - c + d) * ux * uy;
}

export function fbm(x: number, y: number, sx: number, sy: number, octaves = 4) {
  let sum = 0;
  let amp = 0.5;
  let norm = 0;

  for (let o = 0; o < octaves; o++) {
    sum += amp * vnoise(x, y, sx, sy);
    norm += amp;
    x = x * 2.03 + 17.1;
    y = y * 2.03 + 9.2;
    amp *= 0.5;
  }

  return sum / norm;
}

export const smoothstep = (e0: number, e1: number, x: number) => {
  const t = Math.min(1, Math.max(0, (x - e0) / (e1 - e0)));
  return t * t * (3 - 2 * t);
};

/**
 * The art's intensity at (u, v) in [0, 1]² at time t (seconds), before the
 * per-dot effects and the edge fade: smoky noise inside a soft envelope whose
 * outline is roughened by the same noise, so the shape keeps changing.
 */
export function sceneField(scene: Scene, u: number, v: number, aspect: number, t: number) {
  // The heart doesn't drift with the smoke: it beats (see `heartValue`).
  if (scene.variant == "heart")
    return heartValue(heartShade(scene), u, v, aspect, null);

  const px = u * aspect;
  const py = v;
  const { seedX: sx, seedY: sy } = scene;

  // Domain warping makes the noise flow like smoke as time passes.
  const wx = fbm(px * 1.5 + t * 0.07, py * 1.5 - t * 0.045, sx, sy, 2);
  const wy = fbm(px * 1.5 + 5.2 - t * 0.05, py * 1.5 + 1.3 + t * 0.06, sx, sy, 2);
  const n = fbm(px * 2.4 + wx * 1.3 + t * 0.03, py * 2.4 + wy * 1.3, sx, sy, 4);

  if (scene.variant == "glyph") {
    const dx = (u - 0.5) * aspect;
    const dy = v - 0.5;
    const d = Math.sqrt(dx * dx + dy * dy) + (n - 0.5) * 0.14;
    return (1 - smoothstep(0.17, 0.44, d)) * (0.42 + n * 0.95);
  }

  let envelope: number;
  if (scene.variant == "cloud") {
    // A big ellipse filling the area, drifting a little.
    const dx = (u - 0.5 - 0.05 * Math.sin(t * 0.13)) * 2;
    const dy = (v - 0.5 - 0.04 * Math.cos(t * 0.11)) * 2;
    envelope = 1 - smoothstep(0.32, 0.78, Math.sqrt(dx * dx + dy * dy) + (wx - 0.5) * 0.5);
  } else {
    // Bleeds off the top, right and bottom edges; towards the left (where the
    // banner's text is) it thins out along a ragged, drifting front and is
    // always empty at the very left edge.
    const front = 0.26 + (fbm(py * 2.2 + t * 0.05, 7.3 + t * 0.02, sx, sy, 2) - 0.5) * 0.8;
    envelope = smoothstep(front - 0.22, front + 0.3, u + (wx - 0.5) * 0.5 + (n - 0.5) * 0.4) * smoothstep(0, 0.2, u);
    return envelope * (0.03 + smoothstep(0.24, 0.82, n) * 1.25);
  }

  // A light base inside the envelope keeps the shape readable; the smoke adds the darks.
  return envelope * (0.2 + smoothstep(0.2, 0.78, n) * 0.98);
}

/** Animated per-dot effects: a slow wave that makes dots breathe, and a shimmer at the edges. */
export function dotEffects(value: number, cellU: number, cellV: number, x: number, y: number, cell: number, t: number, animated: boolean) {
  const h1 = hash12(cellU + 3.7, cellV + 11.3);

  if (!animated)
    return value + (h1 - 0.5) * 0.16;

  const h2 = hash12(cellU + 19.19, cellV + 7.77);
  const wave = 0.86 + 0.14 * Math.sin(((x + y) / cell) * 0.32 - t * 1.1);
  const shimmer = (h1 - 0.5) * 0.16 * (0.6 + 0.4 * Math.sin(t * 1.7 + h2 * 6.2832));
  return value * wave + shimmer;
}

/** Fades the art out towards the canvas edges, so no dot is ever cut off. */
export function edgeFade(x: number, y: number, width: number, height: number, cell: number, margin: number) {
  const e = Math.min(x, width - x, y, height - y);
  return smoothstep(cell * 0.6, cell * (0.6 + margin), e);
}

/**
 * Whether a variant runs off the canvas edges (cut off by its container, like
 * a photo) instead of fading out before them.
 */
export const bleeds = (variant: Variant) => variant == "banner";

/** Below this intensity, no dot is drawn. */
export const THRESHOLD = 0.14;

/**
 * For variants that bleed (the banner): where the art thins out, dots are
 * dropped at random instead of shrinking away all at once, so the shape
 * dissolves into scattered dots. Returns the value to draw, or -1 for none.
 */
export function scatter(base: number, value: number, h3: number) {
  if (h3 >= smoothstep(0.02, 0.3, base))
    return -1;

  return Math.max(value, 0.17);
}

/** Dot radius (center to vertex) as a fraction of the cell, for an intensity. */
export const dotRadius = (value: number) => 0.5 * Math.min(1, Math.sqrt(value));

/**
 * Calls `fn` for every lattice point whose whole dot fits inside the area -
 * or, with `bleed`, for every point whose dot is at least partly inside it.
 * (u, v) are the point's integer lattice coordinates.
 */
export function forEachLatticePoint(width: number, height: number, cell: number, fn: (x: number, y: number, u: number, v: number) => void, bleed = false) {
  const half = cell / 2;
  const min = bleed ? -half : half;

  for (let row = 0; ; row++) {
    const y = row * half;
    if (y > height - min)
      break;

    if (y < min)
      continue;

    const offset = row % 2 == 0 ? 0 : half;
    for (let x = offset; x <= width - min; x += cell) {
      if (x < min)
        continue;

      fn(x, y, Math.round((x + y) / cell), Math.round((x - y) / cell));
    }
  }
}

/** Adds a diamond with radius `r` centered at (x, y) to the current path. */
export function diamond(ctx: CanvasRenderingContext2D | OffscreenCanvasRenderingContext2D, x: number, y: number, r: number) {
  ctx.moveTo(x, y - r);
  ctx.lineTo(x + r, y);
  ctx.lineTo(x, y + r);
  ctx.lineTo(x - r, y);
  ctx.closePath();
}

// ---------------------------------------------------------------------------
// The beating heart
// ---------------------------------------------------------------------------

/**
 * The noise time the heart's smoky shading is taken at: the heart keeps one
 * look (it beats instead of drifting).
 */
const HEART_STILL_TIME = 20;

/** The heart's shading as seen by the welcome screen's canvas (about 440 x 517). */
const HEART_REF_ASPECT = 0.85;

const heartShades = new Map<string, Float32Array>();

/**
 * The heart's look, over its tone map: the tones, nudged and shaded by the
 * same smoky noise as the rest of the art, frozen in time. 0 (paper) to about 1.15.
 */
export function heartShade(scene: Scene) {
  const key = `${scene.seedX},${scene.seedY}`;
  const cached = heartShades.get(key);
  if (cached != null)
    return cached;

  const tone = heartTone();
  const w = HEART_TEX_W;
  const h = HEART_TEX_H;
  const texAspect = w / h;
  const { seedX: sx, seedY: sy } = scene;
  const t = HEART_STILL_TIME;
  const out = new Float32Array(w * h);

  for (let y = 0; y < h; y++) {
    for (let x = 0; x < w; x++) {
      const hs = (x + 0.5) / w;
      const ht = (y + 0.5) / h;
      // The noise is laid out as on the canvas, where the tone map is 90% of the height.
      const px = (hs - 0.5) * 0.9 * texAspect + 0.5 * HEART_REF_ASPECT;
      const py = (ht - 0.5) * 0.9 + 0.5;
      const wx = fbm(px * 1.5 + t * 0.07, py * 1.5 - t * 0.045, sx, sy, 2);
      const wy = fbm(px * 1.5 + 5.2 - t * 0.05, py * 1.5 + 1.3 + t * 0.06, sx, sy, 2);
      const n = fbm(px * 2.4 + wx * 1.3 + t * 0.03, py * 2.4 + wy * 1.3, sx, sy, 4);
      out[y * w + x] = sampleHeartMap(tone, hs + (wx - 0.5) * 0.035, ht + (wy - 0.5) * 0.035) * (0.86 + (n - 0.5) * 0.55);
    }
  }

  heartShades.set(key, out);
  return out;
}

/** `heartShade` as 8-bit luminance for a WebGL texture (1.2 maps to 255). */
export function heartShadeBytes(scene: Scene) {
  const shade = heartShade(scene);
  const bytes = new Uint8Array(shade.length);
  for (let i = 0; i < shade.length; i++) {
    bytes[i] = Math.round(Math.min(1, Math.max(0, shade[i] / 1.2)) * 255);
  }
  return bytes;
}

/**
 * The heart's intensity at canvas (u, v). With `phase` (how far through a
 * beat it is, see `heartPhase`), the heart grows with the beat and its dots
 * swell, the squeeze running outwards from the middle; `null` draws it at rest.
 */
export function heartValue(shade: Float32Array, u: number, v: number, aspect: number, phase: number | null) {
  const grow = phase == null ? 0 : heartBeat(phase) * HEART_BEAT_GROW;
  const [hs, ht] = heartCoords(u, v, aspect, 1 + grow);
  const value = sampleHeartMap(shade, hs, ht);
  if (phase == null || value <= 0)
    return value;

  const d = Math.hypot((hs - 0.5) * HEART_TEX_W / HEART_TEX_H, ht - 0.5);
  const local = phase - d * HEART_BEAT_DELAY;
  return value * (1 + heartBeat(local - Math.floor(local)) * HEART_BEAT_SWELL);
}

/**
 * Draws a scene with Canvas 2D - the fallback when WebGL isn't available.
 * Built to be cheap enough to animate: the lattice and its per-dot constants
 * are computed once per size, the (smooth) field is evaluated on a coarse
 * grid and interpolated, and dots are drawn as squares in a 45°-rotated space
 * with `fillRect`, which is much faster than building a path.
 */
export class Canvas2DHalftone {
  private width = 0;
  private height = 0;
  private dpr = 1;

  // Per lattice point: position, edge fade and the two per-dot hashes.
  private xs = new Float32Array(0);
  private ys = new Float32Array(0);
  private fades = new Float32Array(0);
  private h1 = new Float32Array(0);
  private h2 = new Float32Array(0);
  private h3 = new Float32Array(0);
  private bleed = false;

  // The coarse grid the field is sampled on.
  private gridStep = 8;
  private gridW = 0;
  private gridH = 0;
  private grid = new Float32Array(0);

  constructor(private ctx: CanvasRenderingContext2D, private scene: Scene, private cell: number) {}

  setScene(scene: Scene, cell: number) {
    const relayout = cell != this.cell || bleeds(scene.variant) != bleeds(this.scene.variant);
    this.scene = scene;
    this.cell = cell;
    if (relayout)
      this.resize(this.width, this.height, this.dpr);
  }

  resize(width: number, height: number, dpr: number) {
    this.width = width;
    this.height = height;
    this.dpr = dpr;

    const canvas = this.ctx.canvas;
    canvas.width = Math.max(1, Math.round(width * dpr));
    canvas.height = Math.max(1, Math.round(height * dpr));

    const xs: number[] = [];
    const ys: number[] = [];
    const fades: number[] = [];
    const h1: number[] = [];
    const h2: number[] = [];
    const h3: number[] = [];
    const cell = this.cell;

    const bleed = bleeds(this.scene.variant);
    forEachLatticePoint(width, height, cell, (x, y, u, v) => {
      const fade = bleed ? 1 : edgeFade(x, y, width, height, cell, 3);
      if (fade <= 0)
        return;

      xs.push(x);
      ys.push(y);
      fades.push(fade);
      h1.push(hash12(u + 3.7, v + 11.3));
      h2.push(hash12(u + 19.19, v + 7.77));
      h3.push(hash12(u + 5.3, v + 2.9));
    }, bleed);

    this.xs = Float32Array.from(xs);
    this.ys = Float32Array.from(ys);
    this.fades = Float32Array.from(fades);
    this.h1 = Float32Array.from(h1);
    this.h2 = Float32Array.from(h2);
    this.h3 = Float32Array.from(h3);
    this.bleed = bleed;

    this.gridStep = Math.max(cell * 1.5, 8);
    this.gridW = Math.ceil(width / this.gridStep) + 2;
    this.gridH = Math.ceil(height / this.gridStep) + 2;
    this.grid = new Float32Array(this.gridW * this.gridH);
  }

  draw(t: number, color: string, animated: boolean) {
    const { ctx, width, height, cell, gridStep, gridW, gridH, grid } = this;
    if (width <= 0 || height <= 0)
      return;

    // Sample the field on the coarse grid - except for the heart, whose
    // detail is finer than that grid: it's evaluated at every dot.
    const aspect = width / Math.max(1, height);
    const heart = this.scene.variant == "heart" ? heartShade(this.scene) : null;
    const phase = heart != null && animated ? heartPhase(t) : null;
    if (heart == null) {
      for (let j = 0; j < gridH; j++) {
        for (let i = 0; i < gridW; i++) {
          grid[j * gridW + i] = sceneField(this.scene, (i * gridStep) / width, (j * gridStep) / height, aspect, t);
        }
      }
    }

    ctx.setTransform(1, 0, 0, 1, 0, 0);
    ctx.clearRect(0, 0, ctx.canvas.width, ctx.canvas.height);

    // Rotate by 45°, so every diamond becomes an axis-aligned square.
    const k = Math.SQRT1_2 * this.dpr;
    ctx.setTransform(k, k, -k, k, 0, 0);
    ctx.fillStyle = color;

    const { xs, ys, fades, h1, h2, h3, bleed } = this;
    const waveT = t * 1.1;
    const shimmerT = t * 1.7;

    for (let n = 0; n < xs.length; n++) {
      const x = xs[n];
      const y = ys[n];

      let base: number;
      if (heart != null) {
        base = heartValue(heart, x / width, y / height, aspect, phase) * fades[n];
      } else {
        // Bilinear interpolation of the field.
        const gx = x / gridStep;
        const gy = y / gridStep;
        const i0 = gx | 0;
        const j0 = gy | 0;
        const fx = gx - i0;
        const fy = gy - j0;
        const row = j0 * gridW + i0;
        const a = grid[row];
        const b = grid[row + 1];
        const c = grid[row + gridW];
        const d = grid[row + gridW + 1];
        base = (a + (b - a) * fx + (c - a) * fy + (a - b - c + d) * fx * fy) * fades[n];
      }
      let value = base;

      // The heart has no wave or shimmer: its motion is the heartbeat.
      if (animated && heart == null) {
        const wave = 0.86 + 0.14 * Math.sin(((x + y) / cell) * 0.32 - waveT);
        value = value * wave + (h1[n] - 0.5) * 0.16 * (0.6 + 0.4 * Math.sin(shimmerT + h2[n] * 6.2832));
      } else {
        value += (h1[n] - 0.5) * 0.16;
      }

      if (bleed)
        value = scatter(base, value, h3[n]);

      if (value < THRESHOLD)
        continue;

      // In the rotated space the dot is a square with half-side r / √2.
      const half = dotRadius(value) * cell * Math.SQRT1_2;
      const rx = (x + y) * Math.SQRT1_2;
      const ry = (y - x) * Math.SQRT1_2;
      ctx.fillRect(rx - half, ry - half, half * 2, half * 2);
    }
  }
}
