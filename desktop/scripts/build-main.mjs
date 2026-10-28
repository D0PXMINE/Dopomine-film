// Bundles the main process and the preload script, and gathers the files they read at runtime.
import { build } from "esbuild";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const root = path.dirname(path.dirname(fileURLToPath(import.meta.url)));
const android = path.join(root, "..", "app", "src", "main", "assets");
const out = path.join(root, "dist", "main");
const watch = process.argv.includes("--watch");

fs.mkdirSync(path.join(out, "assets"), { recursive: true });

// shared with the Android app: the ad list and the scripts that tame the player site
const copies = [
  [path.join(android, "adblock", "hosts.txt"), path.join(out, "assets", "hosts.txt")],
  [path.join(android, "player", "top.js"), path.join(out, "assets", "top.js")],
  [path.join(android, "player", "frames.js"), path.join(out, "assets", "frames.js")],
  [path.join(root, "build", "icon.png"), path.join(out, "assets", "icon.png")],
  [path.join(root, "node_modules", "sql.js", "dist", "sql-wasm.wasm"), path.join(out, "sql-wasm.wasm")],
  // the offline catalog: the same snapshot the Android app ships
  [path.join(android, "catalog", "catalog.db"), path.join(root, "resources", "catalog.db")],
];
for (const [from, to] of copies) {
  fs.mkdirSync(path.dirname(to), { recursive: true });
  fs.copyFileSync(from, to);
}

const options = {
  entryPoints: { main: path.join(root, "src/main/main.ts"), preload: path.join(root, "src/preload/preload.ts") },
  outdir: out,
  bundle: true,
  platform: "node",
  format: "cjs",
  target: "node22",
  external: ["electron"],
  sourcemap: watch ? "inline" : false,
  minify: !watch,
  logLevel: "info",
};

if (watch) await (await import("esbuild")).context(options).then(c => c.watch());
else await build(options);
