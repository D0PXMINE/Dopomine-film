// Vite for the window, esbuild watching the main process, Electron on top.
import { spawn } from "node:child_process";
import { createServer } from "vite";

const server = await createServer({ configFile: new URL("../vite.config.ts", import.meta.url).pathname });
await server.listen();
const url = server.resolvedUrls.local[0];
spawn(process.execPath, ["scripts/build-main.mjs", "--watch"], { stdio: "inherit" });
setTimeout(() => {
  const electron = spawn("npx", ["electron", "."], { stdio: "inherit", env: { ...process.env, VITE_DEV_SERVER_URL: url } });
  electron.on("exit", () => process.exit(0));
}, 1500);
