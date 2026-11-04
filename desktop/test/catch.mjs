// Catching streams from player pages, end to end, against local stand-ins for the player site
// (no network needed). Needs ffmpeg for the test video. Run: xvfb-run -a node test/catch.mjs
//  1. a player that waits for a click and loads its playlist by script, through a redirect,
//     from an address without an extension, served as text/plain;
//  2. a playlist known only by its Content-Type;
//  3. a page nothing can start on its own: shown to the viewer, caught after their click;
//  4. a DASH stream, played by dash.js.
import { createRequire } from "node:module";
import { execFileSync } from "node:child_process";
import fs from "node:fs";
import http from "node:http";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";

const require = createRequire(import.meta.url);
const { _electron: electron } = require("playwright");
const root = path.dirname(path.dirname(fileURLToPath(import.meta.url)));

// ---- test video: 20 s, HLS and DASH ------------------------------------------------------------
const media = fs.mkdtempSync(path.join(os.tmpdir(), "dpm-media-"));
const ff = (...args) => execFileSync("ffmpeg", ["-hide_banner", "-loglevel", "error", "-y", "-f", "lavfi", "-i", "testsrc=size=640x360:rate=25", "-f", "lavfi", "-i", "sine=frequency=440", "-t", "20", "-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p", "-c:a", "aac", ...args]);
fs.mkdirSync(path.join(media, "hls"));
fs.mkdirSync(path.join(media, "dash"));
ff("-f", "hls", "-hls_time", "4", "-hls_playlist_type", "vod", path.join(media, "hls", "index.m3u8"));
ff("-f", "dash", "-seg_duration", "4", path.join(media, "dash", "manifest.mpd"));

let base = "";
const playlist = () => fs.readFileSync(path.join(media, "hls", "index.m3u8"), "utf8").replace(/^(?!#)(\S+)$/gm, l => `${base}/hls/${l}`);

const clickPlayer = (src, how, extra = "") => `<!doctype html><html><body style="margin:0;background:#000">${extra}
  <div id="ov" style="position:fixed;inset:0;display:flex;align-items:center;justify-content:center">
    <div style="width:90px;height:90px;border-radius:50%;background:#fff"></div></div>
  <video style="width:100%;height:100%"></video>
  <script>document.getElementById('ov').addEventListener('click', function () { ${how}('${src}'); });
    function xhr(u) { var x = new XMLHttpRequest(); x.open('GET', u); x.send(); }
    function media(u) { document.querySelector('video').src = u; }</script></body></html>`;

const pages = {
  // the "site": the picker page embeds the player in an iframe
  "/301": `<iframe src="/p/click" allowfullscreen style="position:fixed;inset:0;width:100%;height:100%;border:0"></iframe>`,
  "/302": `<iframe src="/p/typed" allowfullscreen style="position:fixed;inset:0;width:100%;height:100%;border:0"></iframe>`,
  "/303": `<iframe src="/p/manual" allowfullscreen style="position:fixed;inset:0;width:100%;height:100%;border:0"></iframe>`,
  "/304": `<iframe src="/p/dash" allowfullscreen style="position:fixed;inset:0;width:100%;height:100%;border:0"></iframe>`,
  // with the season and episode pickers of a show's player
  "/p/click": clickPlayer("/go", "xhr", `<select name="season" style="position:fixed;top:0;left:0"><option>1 сезон</option><option selected>2 сезон</option></select>
    <select name="series" style="position:fixed;top:0;left:120px"><option>4 серия</option><option selected>5 серия</option></select>`),
  "/p/typed": clickPlayer("/cdn/typed?t=1", "media"),
  // a small button in a corner, named like nothing a script would press
  "/p/manual": `<!doctype html><html><body style="margin:0;background:#000">
    <span id="go" style="position:fixed;right:8px;bottom:8px;color:#fff;font:14px sans-serif">▶</span>
    <script>document.getElementById('go').addEventListener('click', function () { var x = new XMLHttpRequest(); x.open('GET', '/go'); x.send(); });</script></body></html>`,
  "/p/dash": clickPlayer("/dash/manifest.mpd", "xhr"),
};

const server = http.createServer((req, res) => {
  const u = new URL(req.url, base);
  if (pages[u.pathname]) {
    res.writeHead(200, { "content-type": "text/html; charset=utf-8" });
    return res.end(pages[u.pathname]);
  }
  if (u.pathname == "/go") {
    res.writeHead(302, { location: "/cdn/stream?sig=1" });
    return res.end();
  }
  if (u.pathname == "/cdn/stream") {
    res.writeHead(200, { "content-type": "text/plain", "access-control-allow-origin": "*" });
    return res.end(playlist());
  }
  if (u.pathname == "/cdn/typed") {
    res.writeHead(200, { "content-type": "application/vnd.apple.mpegurl", "access-control-allow-origin": "*" });
    return res.end(playlist());
  }
  const file = path.join(media, u.pathname);
  if (file.startsWith(media) && fs.existsSync(file) && fs.statSync(file).isFile()) {
    const type = file.endsWith(".mpd") ? "application/dash+xml" : file.endsWith(".m3u8") ? "application/vnd.apple.mpegurl" : file.endsWith(".ts") ? "video/mp2t" : "video/mp4";
    res.writeHead(200, { "content-type": type, "access-control-allow-origin": "*" });
    return res.end(fs.readFileSync(file));
  }
  res.writeHead(404);
  res.end();
});
await new Promise(r => server.listen(0, "127.0.0.1", r));
base = `http://127.0.0.1:${server.address().port}`;

// ---- the app -----------------------------------------------------------------------------------
const userData = fs.mkdtempSync(path.join(os.tmpdir(), "dpm-catch-"));
const app = await electron.launch({ executablePath: require("electron"), args: [root, "--no-sandbox"], env: { ...process.env, DPM_USER_DATA: userData } });
const page = await app.firstWindow();
await page.setViewportSize({ width: 1360, height: 860 });
const results = [];
const check = (name, ok, detail = "") => {
  results.push({ name, ok });
  console.log(`${ok ? "ok  " : "FAIL"} ${name}${detail ? " — " + detail : ""}`);
};
const phase = () => page.evaluate(() => document.querySelector(".player")?.dataset.phase ?? null);
const playing = () => page.evaluate(() => document.querySelector(".player")?.dataset.phase == "playing" && (document.querySelector(".player-video")?.currentTime ?? 0) > 1);
const until = async (cond, seconds) => {
  for (let i = 0; i < seconds * 2; i++) {
    if (await cond()) return true;
    await page.waitForTimeout(500);
  }
  return false;
};
const caughtLine = async () => (await page.evaluate(() => window.dpm.player.log())).filter(l => /Найден поток|поток пойман|показываем страницу/.test(l)).slice(-2).join(" | ");
const closePlayer = async () => {
  await page.keyboard.press("Escape");
  await page.keyboard.press("Escape");
  await page.waitForTimeout(800);
};

try {
  // the first start copies the catalog into a fresh profile: give it time
  await page.waitForSelector(".shelf", { timeout: 90_000, state: "attached" });
  await page.evaluate(host => window.dpm.settings.set({ playerHost: host }), base);

  // 1. clicked by the app, extension-less playlist behind a redirect, recognised by content
  await page.evaluate(() => window.__dpmTestPlay?.(301));
  check("player waiting for a click: caught and played", await until(playing, 60), await caughtLine());
  check("caught at its final address", (await caughtLine()).includes("/cdn/stream"));
  const noted = (await page.evaluate(() => window.dpm.player.log())).find(l => l.startsWith("Страница плеера")) ?? "";
  check("the season and episode the page showed are noted", noted.includes("сезон 2, серия 5"), noted);
  await closePlayer();

  // 2. known only by its Content-Type
  await page.evaluate(() => window.__dpmTestPlay?.(302));
  check("playlist known by its Content-Type: caught and played", await until(playing, 60), await caughtLine());
  await closePlayer();

  // 3. nothing starts by itself: the page is shown, the viewer presses play, the app takes over
  await page.evaluate(() => window.__dpmTestPlay?.(303));
  const shown = await until(async () => (await phase()) == "site", 60);
  const bar = await page.locator(".player-site-bar").innerText().catch(() => "");
  check("page shown while catching goes on", shown && /нажмите ▶/.test(bar), bar.slice(0, 90));
  const pressed = await app.evaluate(async ({ webContents }) => {
    for (const wc of webContents.getAllWebContents()) {
      for (const f of wc.mainFrame?.framesInSubtree ?? []) {
        if (f.url.endsWith("/p/manual")) {
          await f.executeJavaScript("document.getElementById('go').click()");
          return true;
        }
      }
    }
    return false;
  });
  check("viewer's press reached the page", pressed);
  check("stream caught after the press and played by the app", await until(playing, 30), await caughtLine());
  await closePlayer();

  // 4. DASH through dash.js
  await page.evaluate(() => window.__dpmTestPlay?.(304));
  check("DASH stream played", await until(playing, 60), await caughtLine());
  await page.mouse.move(600, 400);
  await page.waitForTimeout(500);
  await closePlayer();
} catch (e) {
  check("no exceptions", false, String(e));
} finally {
  await app.close();
  server.close();
  fs.rmSync(userData, { recursive: true, force: true });
  fs.rmSync(media, { recursive: true, force: true });
}

const failed = results.filter(r => !r.ok);
console.log(`\n${results.length - failed.length}/${results.length} checks passed`);
process.exit(failed.length ? 1 : 0);
