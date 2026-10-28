// Bundles the unit tests (TypeScript, importing main-process modules) and runs them with node --test.
import { build } from "esbuild";
import { spawnSync } from "node:child_process";
import path from "node:path";
import { fileURLToPath } from "node:url";

const root = path.dirname(path.dirname(fileURLToPath(import.meta.url)));
const out = path.join(root, "dist", "test", "unit.test.cjs");
await build({
  entryPoints: [path.join(root, "test", "unit.test.ts")],
  outfile: out,
  bundle: true,
  platform: "node",
  format: "cjs",
  target: "node22",
  // the tested code never calls into Electron; a stub keeps the import resolvable
  plugins: [{ name: "electron-stub", setup(b) { b.onResolve({ filter: /^electron$/ }, () => ({ path: "electron", namespace: "stub" })); b.onLoad({ filter: /.*/, namespace: "stub" }, () => ({ contents: "module.exports = { app: {}, net: {} };" })); } }],
  logLevel: "warning",
});
const r = spawnSync(process.execPath, ["--test", out], { stdio: "inherit" });
process.exit(r.status ?? 1);
