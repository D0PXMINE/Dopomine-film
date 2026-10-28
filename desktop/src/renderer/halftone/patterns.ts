import {
  THRESHOLD, diamond, dotRadius, edgeFade, fbm, forEachLatticePoint, hash12, makeScene, rng, sceneField, smoothstep
} from "./field";

// Random, static dot patterns used as drum kit covers. Each pattern picks one
// of several families (a cloud, a shaded orb, ripples, waves...) with random
// parameters, and is drawn with the same diamond dots as the rest of the app.
// A new kit gets one saved as `cover.png` in its folder.

type Field = (u: number, v: number) => number;

interface CoverPattern {
  /** Lattice cells across the image. */
  cells: number;
  field: Field;
}

const TAU = Math.PI * 2;

const FAMILIES: ((seed: number, rand: () => number) => Field)[] = [
  // A drifting cloud, like the art elsewhere in the app.
  (seed, rand) => {
    const scene = makeScene("cloud", seed);
    const t = rand() * 60;
    return (u, v) => sceneField(scene, u, v, 1, t) * 1.05;
  },

  // A shaded sphere: small dots in the light, big ones in the shadow.
  (_, rand) => {
    const cx = 0.42 + rand() * 0.16;
    const cy = 0.4 + rand() * 0.16;
    const r = 0.25 + rand() * 0.08;
    const angle = rand() * TAU;
    const lx = Math.cos(angle) * 0.75;
    const ly = Math.sin(angle) * 0.75;
    const lz = Math.sqrt(1 - 0.75 * 0.75);

    return (u, v) => {
      const dx = (u - cx) / r;
      const dy = (v - cy) / r;
      const d2 = dx * dx + dy * dy;
      if (d2 > 1) {
        // A faint cast shadow on the "ground".
        const sx = (u - cx + lx * r * 0.5) / (r * 1.1);
        const sy = (v - (cy + r * 1.05)) / (r * 0.28);
        const s = sx * sx + sy * sy;
        return s < 1 ? 0.45 * (1 - s) : 0;
      }

      const z = Math.sqrt(1 - d2);
      const light = Math.max(0, dx * lx + dy * ly + z * lz);
      return 0.12 + 0.9 * Math.pow(1 - light, 1.3);
    };
  },

  // Concentric ripples fading outwards.
  (_, rand) => {
    const cx = 0.38 + rand() * 0.24;
    const cy = 0.38 + rand() * 0.24;
    const k = 4 + rand() * 4;
    const phase = rand() * TAU;

    return (u, v) => {
      const d = Math.hypot(u - cx, v - cy);
      return (0.5 + 0.5 * Math.cos(TAU * k * d + phase)) * (1 - smoothstep(0.18, 0.5, d));
    };
  },

  // Bands of waves, warped by noise.
  (seed, rand) => {
    const angle = rand() * Math.PI;
    const ca = Math.cos(angle);
    const sa = Math.sin(angle);
    const k = 2.5 + rand() * 3;
    const warp = 0.08 + rand() * 0.14;
    const sx = (seed % 997) * 1.618;
    const sy = (seed % 991) * 2.414;

    return (u, v) => {
      const s = u * ca + v * sa + warp * fbm(u * 2.2, v * 2.2, sx, sy, 3);
      const ring = Math.hypot(u - 0.5, v - 0.5);
      return (0.5 + 0.5 * Math.sin(TAU * k * s)) * (1 - smoothstep(0.32, 0.5, ring));
    };
  },

  // A soft gradient sweeping across the square.
  (seed, rand) => {
    const angle = rand() * TAU;
    const ca = Math.cos(angle);
    const sa = Math.sin(angle);
    const sx = (seed % 997) * 1.618;
    const sy = (seed % 991) * 2.414;

    return (u, v) => {
      const s = (u - 0.5) * ca + (v - 0.5) * sa + 0.5;
      const n = fbm(u * 3, v * 3, sx, sy, 3) - 0.5;
      // Within a soft, ragged disc, so it never runs into the edges.
      const ring = Math.hypot(u - 0.5, v - 0.5) + n * 0.12;
      return smoothstep(0.08, 0.95, s + n * 0.35) * (1 - smoothstep(0.34, 0.46, ring));
    };
  },

  // A scatter of soft clusters.
  (_, rand) => {
    const bumps = Array.from({ length: 5 + Math.floor(rand() * 5) }, () => ({
      x: 0.2 + rand() * 0.6,
      y: 0.2 + rand() * 0.6,
      s: 0.05 + rand() * 0.09,
      a: 0.5 + rand() * 0.7
    }));

    return (u, v) => {
      let sum = 0;
      for (const b of bumps) {
        const d2 = (u - b.x) ** 2 + (v - b.y) ** 2;
        sum += b.a * Math.exp(-d2 / (2 * b.s * b.s));
      }
      return Math.min(1.1, sum);
    };
  },

  // Two sets of rings interfering, like a moiré.
  (_, rand) => {
    const ax = 0.3 + rand() * 0.15;
    const ay = 0.3 + rand() * 0.4;
    const bx = 0.55 + rand() * 0.15;
    const by = 0.3 + rand() * 0.4;
    const k = 5 + rand() * 4;

    return (u, v) => {
      const d1 = Math.hypot(u - ax, v - ay);
      const d2 = Math.hypot(u - bx, v - by);
      const ring = Math.hypot(u - 0.5, v - 0.5);
      return (0.5 + 0.5 * Math.cos(TAU * k * d1) * Math.cos(TAU * k * d2)) * (1 - smoothstep(0.3, 0.5, ring));
    };
  },
];

export function coverPattern(seed: number): CoverPattern {
  const rand = rng(seed ^ 0x5bd1e995);
  const family = FAMILIES[Math.floor(rand() * FAMILIES.length)];

  return {
    cells: 24 + Math.floor(rand() * 18),
    field: family(seed, rand)
  };
}

/** Draws a cover pattern into a `size`×`size` area, optionally on a background. */
export function drawCoverPattern(
  ctx: CanvasRenderingContext2D,
  size: number,
  seed: number,
  ink: string,
  paper: string | null
) {
  const pattern = coverPattern(seed);
  const cell = size / pattern.cells;

  ctx.clearRect(0, 0, size, size);
  if (paper != null) {
    ctx.fillStyle = paper;
    ctx.fillRect(0, 0, size, size);
  }

  ctx.fillStyle = ink;
  ctx.beginPath();

  forEachLatticePoint(size, size, cell, (x, y, u, v) => {
    const fade = edgeFade(x, y, size, size, cell, 2);
    if (fade <= 0)
      return;

    const value = pattern.field(x / size, y / size) * fade + (hash12(u + seed % 101, v + 3.7) - 0.5) * 0.16;
    if (value < THRESHOLD)
      return;

    diamond(ctx, x, y, dotRadius(value) * cell);
  });

  ctx.fill();
}

/** A random seed for a new pattern. */
export function randomSeed() {
  const buf = new Uint32Array(1);
  crypto.getRandomValues(buf);
  return buf[0];
}

// ---------------------------------------------------------------------------
// PNG export
// ---------------------------------------------------------------------------

/** Written into generated covers, so the app can tell them from user images (and invert them in dark mode). */
export const GENERATED_COVER_MARK = "splicer dot pattern";

/** What every generated cover's mark ends with, including covers saved by earlier versions. */
const COVER_MARK_SUFFIX = " dot pattern";

const INK = "#0e0e10";
const PAPER = "#ffffff";

let crcTable: Uint32Array | null = null;

function crc32(data: Uint8Array) {
  if (crcTable == null) {
    crcTable = new Uint32Array(256);
    for (let n = 0; n < 256; n++) {
      let c = n;
      for (let k = 0; k < 8; k++) {
        c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
      }
      crcTable[n] = c >>> 0;
    }
  }

  let crc = 0xffffffff;
  for (let i = 0; i < data.length; i++) {
    crc = crcTable[(crc ^ data[i]) & 0xff] ^ (crc >>> 8);
  }

  return (crc ^ 0xffffffff) >>> 0;
}

/** Inserts a tEXt chunk right after a PNG's IHDR chunk. */
function withTextChunk(png: Uint8Array, keyword: string, text: string) {
  const ihdrEnd = 8 + 4 + 4 + 13 + 4;
  const payload = new TextEncoder().encode(`${keyword}\0${text}`);

  const chunk = new Uint8Array(12 + payload.length);
  const view = new DataView(chunk.buffer);
  view.setUint32(0, payload.length);
  chunk.set(new TextEncoder().encode("tEXt"), 4);
  chunk.set(payload, 8);
  view.setUint32(8 + payload.length, crc32(chunk.subarray(4, 8 + payload.length)));

  const out = new Uint8Array(png.length + chunk.length);
  out.set(png.subarray(0, ihdrEnd), 0);
  out.set(chunk, ihdrEnd);
  out.set(png.subarray(ihdrEnd), ihdrEnd + chunk.length);
  return out;
}

/** Renders a cover pattern as a PNG file (black dots on white). */
export async function renderCoverPng(seed: number, size = 512): Promise<Uint8Array<ArrayBuffer>> {
  const canvas = document.createElement("canvas");
  canvas.width = size;
  canvas.height = size;

  const ctx = canvas.getContext("2d");
  if (ctx == null)
    throw new Error("Canvas 2D is unavailable");

  drawCoverPattern(ctx, size, seed, INK, PAPER);

  const blob = await new Promise<Blob>((resolve, reject) =>
    canvas.toBlob(b => b != null ? resolve(b) : reject(new Error("Failed to encode the cover")), "image/png")
  );

  const png = new Uint8Array(await blob.arrayBuffer());
  return withTextChunk(png, "Software", GENERATED_COVER_MARK);
}

const PNG_SIGNATURE = [0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a];

/** Whether an image file is a cover generated by the app. */
export function isGeneratedCover(bytes: Uint8Array) {
  if (bytes.length < 64 || PNG_SIGNATURE.some((b, i) => bytes[i] != b))
    return false;

  const head = new TextDecoder("latin1").decode(bytes.subarray(0, 160));
  return head.includes(COVER_MARK_SUFFIX);
}
