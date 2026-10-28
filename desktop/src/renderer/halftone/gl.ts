import { Scene, heartShadeBytes } from "./field";
import {
  HEART_BEAT_DELAY, HEART_BEAT_GROW, HEART_BEAT_SWELL, HEART_HEIGHT, HEART_TEX_H, HEART_TEX_W, heartPhase
} from "./heart";

// Renders halftone art with a fragment shader: every pixel finds its nearest
// lattice point, evaluates the intensity field there and tests whether it lies
// inside that point's diamond. The GPU does all the work, so an animation
// frame costs the main thread a few uniform updates and one draw call.
//
// The shader mirrors field.ts - keep the two in sync.

const VERTEX = `
attribute vec2 aPos;
void main() {
  gl_Position = vec4(aPos, 0.0, 1.0);
}
`;

/** A number as a GLSL float literal. */
const glf = (x: number) => (Number.isInteger(x) ? x.toFixed(1) : String(x));

const FRAGMENT = `
precision highp float;

uniform vec2 uRes;      // canvas size in device pixels
uniform float uDpr;
uniform float uCell;    // lattice spacing in CSS pixels
uniform float uTime;
uniform float uAnimated;
uniform vec2 uSeed;
uniform vec3 uColor;
uniform float uVariant; // 0 cloud, 1 banner, 2 glyph, 3 heart
uniform sampler2D uHeart; // the heart's look over its tone map (heartShade in field.ts)
uniform vec2 uHeartBox;   // the tone map's size on the canvas, in uv units
uniform float uBeatPhase; // how far through a heartbeat (0..1), or -1 for a heart at rest

float hash12(vec2 p) {
  vec3 p3 = fract(vec3(p.xyx) * 0.1031);
  p3 += dot(p3, p3.yzx + 33.33);
  return fract((p3.x + p3.y) * p3.z);
}

float vnoise(vec2 p) {
  vec2 i = floor(p);
  vec2 f = p - i;
  vec2 u = f * f * (3.0 - 2.0 * f);
  float a = hash12(i + uSeed);
  float b = hash12(i + vec2(1.0, 0.0) + uSeed);
  float c = hash12(i + vec2(0.0, 1.0) + uSeed);
  float d = hash12(i + vec2(1.0, 1.0) + uSeed);
  return a + (b - a) * u.x + (c - a) * u.y + (a - b - c + d) * u.x * u.y;
}

float fbm2(vec2 p) {
  float s = 0.0;
  s += 0.5 * vnoise(p);   p = p * 2.03 + vec2(17.1, 9.2);
  s += 0.25 * vnoise(p);
  return s / 0.75;
}

float fbm4(vec2 p) {
  float s = 0.0;
  s += 0.5 * vnoise(p);    p = p * 2.03 + vec2(17.1, 9.2);
  s += 0.25 * vnoise(p);   p = p * 2.03 + vec2(17.1, 9.2);
  s += 0.125 * vnoise(p);  p = p * 2.03 + vec2(17.1, 9.2);
  s += 0.0625 * vnoise(p);
  return s / 0.9375;
}

// The heartbeat (heartBeat in heart.ts): a strong "lub", a softer "dub", a rest.
float bump(float x, float center, float rise, float fall) {
  float d = x - center;
  float w = d < 0.0 ? rise : fall;
  return exp(-(d * d) / (w * w));
}

float heartBeat(float phase) {
  return bump(phase, 0.1, 0.035, 0.08) + 0.55 * bump(phase, 0.31, 0.035, 0.095);
}

// The beating heart (heartValue in field.ts).
float heartField(vec2 uv) {
  float grow = uBeatPhase < 0.0 ? 0.0 : heartBeat(uBeatPhase) * ${glf(HEART_BEAT_GROW)};
  vec2 hp = (uv - 0.5) / (uHeartBox * (1.0 + grow)) + 0.5;
  if (hp.x <= 0.0 || hp.x >= 1.0 || hp.y <= 0.0 || hp.y >= 1.0) {
    return 0.0;
  }

  float value = texture2D(uHeart, hp).r * 1.2;
  if (uBeatPhase < 0.0) {
    return value;
  }

  float d = length(vec2((hp.x - 0.5) * ${glf(HEART_TEX_W / HEART_TEX_H)}, hp.y - 0.5));
  return value * (1.0 + heartBeat(fract(uBeatPhase - d * ${glf(HEART_BEAT_DELAY)})) * ${glf(HEART_BEAT_SWELL)});
}

float field(vec2 uv, float aspect, float t) {
  // The heart doesn't drift with the smoke: it beats.
  if (uVariant > 2.5) {
    return heartField(uv);
  }

  vec2 p = vec2(uv.x * aspect, uv.y);
  float wx = fbm2(p * 1.5 + vec2(t * 0.07, -t * 0.045));
  float wy = fbm2(p * 1.5 + vec2(5.2 - t * 0.05, 1.3 + t * 0.06));
  float n = fbm4(p * 2.4 + vec2(wx, wy) * 1.3 + vec2(t * 0.03, 0.0));

  if (uVariant > 1.5) {
    vec2 d = vec2((uv.x - 0.5) * aspect, uv.y - 0.5);
    float dist = length(d) + (n - 0.5) * 0.14;
    return (1.0 - smoothstep(0.17, 0.44, dist)) * (0.42 + n * 0.95);
  }

  float envelope;
  if (uVariant < 0.5) {
    vec2 d = (uv - vec2(0.5 + 0.05 * sin(t * 0.13), 0.5 + 0.04 * cos(t * 0.11))) * 2.0;
    envelope = 1.0 - smoothstep(0.32, 0.78, length(d) + (wx - 0.5) * 0.5);
  } else {
    float front = 0.26 + (fbm2(vec2(p.y * 2.2 + t * 0.05, 7.3 + t * 0.02)) - 0.5) * 0.8;
    envelope = smoothstep(front - 0.22, front + 0.3, uv.x + (wx - 0.5) * 0.5 + (n - 0.5) * 0.4) * smoothstep(0.0, 0.2, uv.x);
    return envelope * (0.03 + smoothstep(0.24, 0.82, n) * 1.25);
  }

  return envelope * (0.2 + smoothstep(0.2, 0.78, n) * 0.98);
}

void main() {
  vec2 size = uRes / uDpr;
  // CSS pixels, origin at the top left.
  vec2 px = vec2(gl_FragCoord.x, uRes.y - gl_FragCoord.y) / uDpr;

  // Nearest lattice point, in lattice coordinates and in CSS pixels.
  vec2 q = vec2(px.x + px.y, px.x - px.y) / uCell;
  vec2 id = floor(q + 0.5);
  vec2 f = q - id;
  vec2 center = vec2(id.x + id.y, id.x - id.y) * (uCell * 0.5);

  // Fade out towards the edges, so no dot is ever cut off - except for the
  // banner, which runs off the edges.
  float fade = 1.0;
  if (abs(uVariant - 1.0) > 0.5) {
    float e = min(min(center.x, size.x - center.x), min(center.y, size.y - center.y));
    fade = smoothstep(uCell * 0.6, uCell * 3.6, e);
    if (fade <= 0.0) {
      discard;
    }
  }

  // Skip the noise for pixels that are certainly outside the art's shape.
  vec2 uv = center / size;
  if (uv.x <= 0.0 && abs(uVariant - 1.0) < 0.5) {
    discard;
  }
  if (uVariant < 0.5 && length((uv - 0.5) * 2.0) > 1.15) {
    discard;
  }
  if (abs(uVariant - 2.0) < 0.5 && length(vec2((uv.x - 0.5) * size.x / size.y, uv.y - 0.5)) > 0.52) {
    discard;
  }
  if (uVariant > 2.5) {
    vec2 hp = (uv - 0.5) / uHeartBox + 0.5;
    if (hp.x < -0.05 || hp.x > 1.05 || hp.y < -0.05 || hp.y > 1.05) {
      discard;
    }
  }

  float t = uTime;
  float base = field(uv, size.x / size.y, t) * fade;
  float value = base;

  float h1 = hash12(id + vec2(3.7, 11.3));
  // The heart has no wave or shimmer: its motion is the heartbeat.
  if (uAnimated > 0.5 && uVariant < 2.5) {
    float h2 = hash12(id + vec2(19.19, 7.77));
    float wave = 0.86 + 0.14 * sin(((center.x + center.y) / uCell) * 0.32 - t * 1.1);
    float shimmer = (h1 - 0.5) * 0.16 * (0.6 + 0.4 * sin(t * 1.7 + h2 * 6.2832));
    value = value * wave + shimmer;
  } else {
    value += (h1 - 0.5) * 0.16;
  }

  // The banner dissolves into scattered dots where it thins out.
  if (abs(uVariant - 1.0) < 0.5) {
    if (hash12(id + vec2(5.3, 2.9)) >= smoothstep(0.02, 0.3, base)) {
      discard;
    }
    value = max(value, 0.17);
  }

  if (value < 0.14) {
    discard;
  }

  float r = 0.5 * min(1.0, sqrt(value));
  // Signed distance to the diamond's edge, in device pixels.
  float dist = (max(abs(f.x), abs(f.y)) - r) * uCell * 0.70710678 * uDpr;
  float alpha = clamp(0.5 - dist, 0.0, 1.0);
  if (alpha <= 0.0) {
    discard;
  }

  gl_FragColor = vec4(uColor * alpha, alpha);
}
`;

function compile(gl: WebGLRenderingContext, type: number, source: string) {
  const shader = gl.createShader(type);
  if (shader == null)
    return null;

  gl.shaderSource(shader, source);
  gl.compileShader(shader);

  if (!gl.getShaderParameter(shader, gl.COMPILE_STATUS)) {
    console.warn("halftone shader failed to compile:", gl.getShaderInfoLog(shader));
    gl.deleteShader(shader);
    return null;
  }

  return shader;
}

/** Whether to accept software-rendered WebGL (used to test the shader in headless browsers). */
const allowSoftwareGl = () => new URLSearchParams(location.search).has("softgl");

export class GlHalftone {
  private constructor(
    private gl: WebGLRenderingContext,
    private program: WebGLProgram,
    private buffer: WebGLBuffer,
    private loc: Record<string, WebGLUniformLocation | null>
  ) {}

  private width = 0;
  private height = 0;
  private variant: Scene["variant"] = "cloud";
  private heartTexture: WebGLTexture | null = null;
  private heartKey: string | null = null;

  /** Creates a renderer, or returns `null` if WebGL isn't available (or is software-only). */
  static create(canvas: HTMLCanvasElement, scene: Scene): GlHalftone | null {
    let gl: WebGLRenderingContext | null = null;
    try {
      gl = canvas.getContext("webgl", {
        alpha: true,
        premultipliedAlpha: true,
        antialias: false,
        depth: false,
        stencil: false,
        preserveDrawingBuffer: false,
        powerPreference: "low-power",
        // Software rendering would burn CPU; the Canvas 2D fallback is cheaper.
        failIfMajorPerformanceCaveat: !allowSoftwareGl()
      });
    } catch {
      gl = null;
    }

    if (gl == null || gl.isContextLost())
      return null;

    // Some platforms ignore `failIfMajorPerformanceCaveat` and hand out a
    // CPU-emulated context anyway; check the renderer's name as well.
    if (!allowSoftwareGl()) {
      const info = gl.getExtension("WEBGL_debug_renderer_info");
      const renderer = info != null ? String(gl.getParameter(info.UNMASKED_RENDERER_WEBGL) ?? "") : "";
      if (/llvmpipe|softpipe|swiftshader|software|basic render/i.test(renderer)) {
        gl.getExtension("WEBGL_lose_context")?.loseContext();
        return null;
      }
    }

    const vs = compile(gl, gl.VERTEX_SHADER, VERTEX);
    const fs = compile(gl, gl.FRAGMENT_SHADER, FRAGMENT);
    const program = gl.createProgram();
    if (vs == null || fs == null || program == null)
      return null;

    gl.attachShader(program, vs);
    gl.attachShader(program, fs);
    gl.linkProgram(program);
    gl.deleteShader(vs);
    gl.deleteShader(fs);

    if (!gl.getProgramParameter(program, gl.LINK_STATUS)) {
      console.warn("halftone shader failed to link:", gl.getProgramInfoLog(program));
      return null;
    }

    const buffer = gl.createBuffer();
    if (buffer == null)
      return null;

    // One triangle covering the whole viewport.
    gl.bindBuffer(gl.ARRAY_BUFFER, buffer);
    gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-1, -1, 3, -1, -1, 3]), gl.STATIC_DRAW);

    gl.useProgram(program);
    const aPos = gl.getAttribLocation(program, "aPos");
    gl.enableVertexAttribArray(aPos);
    gl.vertexAttribPointer(aPos, 2, gl.FLOAT, false, 0, 0);

    const loc: Record<string, WebGLUniformLocation | null> = {};
    for (const name of ["uRes", "uDpr", "uCell", "uTime", "uAnimated", "uSeed", "uColor", "uVariant", "uHeart", "uHeartBox", "uBeatPhase"]) {
      loc[name] = gl.getUniformLocation(program, name);
    }

    const renderer = new GlHalftone(gl, program, buffer, loc);
    renderer.setScene(scene);
    gl.clearColor(0, 0, 0, 0);
    return renderer;
  }

  /** Whether the context was lost (e.g. the GPU was reset); the renderer can't be used anymore. */
  get lost() {
    return this.gl.isContextLost();
  }

  setScene(scene: Scene) {
    const { gl, loc } = this;
    gl.useProgram(this.program);
    gl.uniform2f(loc.uSeed, scene.seedX, scene.seedY);
    gl.uniform1f(loc.uVariant, { cloud: 0, banner: 1, glyph: 2, heart: 3 }[scene.variant]);
    this.variant = scene.variant;

    const heartKey = `${scene.seedX},${scene.seedY}`;
    if (scene.variant == "heart" && this.heartKey != heartKey) {
      // The heart's look, as a small luminance texture (made once per canvas and seed).
      const texture = this.heartTexture ?? gl.createTexture();
      gl.activeTexture(gl.TEXTURE0);
      gl.bindTexture(gl.TEXTURE_2D, texture);
      gl.pixelStorei(gl.UNPACK_ALIGNMENT, 1);
      gl.texImage2D(gl.TEXTURE_2D, 0, gl.LUMINANCE, HEART_TEX_W, HEART_TEX_H, 0, gl.LUMINANCE, gl.UNSIGNED_BYTE, heartShadeBytes(scene));
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
      gl.uniform1i(loc.uHeart, 0);
      this.heartTexture = texture;
      this.heartKey = heartKey;
    }
  }

  resize(width: number, height: number, dpr: number) {
    this.width = width;
    this.height = height;

    const canvas = this.gl.canvas as HTMLCanvasElement;
    const w = Math.max(1, Math.round(width * dpr));
    const h = Math.max(1, Math.round(height * dpr));
    if (canvas.width != w || canvas.height != h) {
      canvas.width = w;
      canvas.height = h;
    }

    this.gl.viewport(0, 0, w, h);
  }

  draw(time: number, color: [number, number, number], cell: number, animated: boolean) {
    const { gl, loc } = this;
    if (this.width <= 0 || this.height <= 0 || gl.isContextLost())
      return;

    const canvas = gl.canvas as HTMLCanvasElement;
    gl.useProgram(this.program);
    gl.uniform2f(loc.uRes, canvas.width, canvas.height);
    // The effective ratio, so CSS sizes map exactly onto the (rounded) buffer.
    gl.uniform1f(loc.uDpr, canvas.width / this.width);
    gl.uniform1f(loc.uCell, cell);
    gl.uniform1f(loc.uTime, time);
    gl.uniform1f(loc.uAnimated, animated ? 1 : 0);
    gl.uniform3f(loc.uColor, color[0], color[1], color[2]);

    if (this.variant == "heart") {
      const aspect = this.width / this.height;
      gl.uniform2f(loc.uHeartBox, (HEART_HEIGHT * HEART_TEX_W / HEART_TEX_H) / aspect, HEART_HEIGHT);
      gl.uniform1f(loc.uBeatPhase, animated ? heartPhase(time) : -1);
    }

    gl.clear(gl.COLOR_BUFFER_BIT);
    gl.drawArrays(gl.TRIANGLES, 0, 3);
  }

  dispose() {
    const { gl } = this;
    gl.deleteBuffer(this.buffer);
    if (this.heartTexture != null)
      gl.deleteTexture(this.heartTexture);
    gl.deleteProgram(this.program);
    // Free the context right away - browsers cap how many can exist at once.
    gl.getExtension("WEBGL_lose_context")?.loseContext();
  }
}
