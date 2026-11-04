// Checks the shared page script (Android assets/player/frames.js, also used here) in Chromium
// against stand-ins for typical player pages: a player that waits for a click, a manifest behind
// a redirect with no extension, a fetch loader, a late player behind a bot check, nested iframes.
// Run: node test/frames.mjs
import { createRequire } from "node:module";
import fs from "node:fs";
import http from "node:http";
import path from "node:path";
import { fileURLToPath } from "node:url";

const require = createRequire(import.meta.url);
const { chromium } = require("playwright");
const root = path.dirname(path.dirname(fileURLToPath(import.meta.url)));
const FRAMES = fs.readFileSync(path.join(root, "..", "app", "src", "main", "assets", "player", "frames.js"), "utf8");
const M3U8 = '#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=1280x720\nv720/index.m3u8\n';

const pages = {
  // Playerjs-like: a full-size overlay without "play" in its name; loads the manifest by XHR on click,
  // through a redirect, from an address without an extension, served as text/plain
  '/clickplayer': `<!doctype html><html><body style="margin:0">
    <div id="pl" style="position:fixed;inset:0;background:#000"><video style="width:100%;height:100%"></video>
    <div id="ov" style="position:absolute;inset:0;display:flex;align-items:center;justify-content:center"><div style="width:90px;height:90px;border-radius:50%;background:#fff"></div></div></div>
    <script>
      document.getElementById('ov').addEventListener('click', function () {
        window.__clicked = (window.__clicked || 0) + 1;
        var x = new XMLHttpRequest(); x.open('GET', '/go?id=5'); x.responseType = 'text'; x.send();
      });
    </script></body></html>`,
  // a bot check that answers late, then shows a player with a real play button loading via fetch
  '/slowcheck': `<!doctype html><html><body style="margin:0"><div id="app">Проверка браузера…</div>
    <script>
      setTimeout(function () {
        document.getElementById('app').innerHTML = '<video style="width:100%;height:300px"></video><button class="vjs-big-play-button" style="position:fixed;left:45%;top:40%;width:80px;height:60px">▶</button>';
        document.querySelector('button').onclick = function () { fetch('/manifest.mpd?t=1').then(function (r) { return r.text(); }); };
      }, 4000);
    </script></body></html>`,
  // an iframe player inside a wrapper page (like the picker embedding a player)
  '/wrapper': `<!doctype html><html><body style="margin:0"><iframe src="/clickplayer" style="position:fixed;inset:0;width:100%;height:100%;border:0"></iframe></body></html>`,
  // a playlist link inside a link: must not be followed (ads hide behind links)
  '/adlink': `<!doctype html><html><body><video></video><a href="https://ads.example/click" class="play-button" style="display:block;width:120px;height:80px;margin:100px auto">▶</a>
    <script>document.querySelector('a').addEventListener('click', function (e) { e.preventDefault(); window.__adClicked = true; });</script></body></html>`,
};

const server = http.createServer((req, res) => {
  const u = new URL(req.url, 'http://x');
  if (pages[u.pathname]) { res.writeHead(200, { 'content-type': 'text/html; charset=utf-8' }); return res.end(pages[u.pathname]); }
  if (u.pathname === '/go') { res.writeHead(302, { location: '/cdn/stream?sig=abc' }); return res.end(); }
  if (u.pathname === '/cdn/stream') { res.writeHead(200, { 'content-type': 'text/plain' }); return res.end(M3U8); }
  if (u.pathname === '/manifest.mpd') { res.writeHead(200, { 'content-type': 'application/dash+xml' }); return res.end('<?xml version="1.0"?><MPD></MPD>'); }
  res.writeHead(404); res.end();
});

(async () => {
  await new Promise(r => server.listen(0, '127.0.0.1', r));
  const base = `http://127.0.0.1:${server.address().port}`;
  const browser = await chromium.launch();
  const results = [];
  const check = (name, ok, extra) => { results.push([name, ok]); console.log(`${ok ? 'ok  ' : 'FAIL'} ${name}${extra ? ' — ' + extra : ''}`); };

  async function open(path, flags) {
    const ctx = await browser.newContext({ viewport: { width: 1000, height: 600 } });
    const page = await ctx.newPage();
    await page.addInitScript(({ flags }) => {
      window.__msgs = [];
      window.dpmBridge = { postMessage: function (m) { window.__msgs.push(JSON.parse(m)); try { window.top.__all && window.top.__all.push(JSON.parse(m)); } catch (e) {} }, onmessage: null };
      if (window === window.top) window.__all = [];
      Object.assign(window, flags);
    }, { flags });
    await page.addInitScript(FRAMES);
    await page.goto(base + path);
    return { page, ctx };
  }
  const media = async (page) => (await page.evaluate(() => window.__all || [])).filter(m => m.type === 'media');

  // 1. hidden sniff: the overlay player gets clicked, the redirected extension-less manifest is reported
  {
    const { page, ctx } = await open('/clickplayer', { __dpmSniff: true });
    await page.waitForTimeout(6000);
    const got = await media(page);
    const clicked = await page.evaluate(() => window.__clicked || 0);
    check('hidden page: player clicked once', clicked === 1, `clicks ${clicked}`);
    check('redirected manifest reported by content', got.some(m => m.url.endsWith('/cdn/stream?sig=abc') && m.kind === 'hls'), JSON.stringify(got));
    check('hello sent', (await page.evaluate(() => window.__msgs)).some(m => m.type === 'hello'));
    const natives = await page.evaluate(() => [XMLHttpRequest.prototype.open.toString(), Function.prototype.toString.call(XMLHttpRequest.prototype.send), String(window.fetch), Function.prototype.toString.toString()]);
    check('wrapped functions read as native', natives.every(t => t.includes('[native code]')), natives.join(' | '));
    await ctx.close();
  }
  // 2. not sniffing: nothing reported, nothing clicked
  {
    const { page, ctx } = await open('/clickplayer', {});
    await page.waitForTimeout(4000);
    check('normal page: no clicks', (await page.evaluate(() => window.__clicked || 0)) === 0);
    await page.click('#ov');
    await page.waitForTimeout(500);
    check('normal page: no reports', (await media(page)).length === 0);
    await ctx.close();
  }
  // 3. late player behind a check, fetch loader, DASH kind
  {
    const { page, ctx } = await open('/slowcheck', { __dpmSniff: true });
    await page.waitForTimeout(9000);
    const got = await media(page);
    check('late player clicked and DASH reported via fetch', got.some(m => m.url.includes('/manifest.mpd') && m.kind === 'dash'), JSON.stringify(got));
    await ctx.close();
  }
  // 4. nested iframe player
  {
    const { page, ctx } = await open('/wrapper', { __dpmSniff: true });
    await page.waitForTimeout(6000);
    const frame = page.frames().find(f => f.url().endsWith('/clickplayer'));
    const msgs = await frame.evaluate(() => window.__msgs);
    check('player inside an iframe caught', msgs.some(m => m.type === 'media' && m.url.endsWith('/cdn/stream?sig=abc')), JSON.stringify(msgs.filter(m => m.type !== 'hello')));
    await ctx.close();
  }
  // 5. shown page: keeps helping until the viewer touches it
  {
    const { page, ctx } = await open('/clickplayer', { __dpmSniff: true, __dpmVisible: true });
    await page.mouse.click(10, 10);
    const own = await page.evaluate(() => window.__clicked || 0);
    await page.waitForTimeout(5000);
    check('shown page: no clicks after the viewer touched it', (await page.evaluate(() => window.__clicked || 0)) === own, `viewer ${own}`);
    await ctx.close();
  }
  // 6. links are never pressed
  {
    const { page, ctx } = await open('/adlink', { __dpmSniff: true });
    await page.waitForTimeout(5000);
    check('play button inside a link left alone', !(await page.evaluate(() => window.__adClicked)));
    await ctx.close();
  }
  // 7. commands: mode/mute/release
  {
    const { page, ctx } = await open('/clickplayer', {});
    const state = await page.evaluate(() => {
      const send = (o) => window.dpmBridge.onmessage({ data: JSON.stringify(o) });
      send({ cmd: 'mode', sniff: true, visible: true });
      const a = [window.__dpmSniff, window.__dpmVisible];
      send({ cmd: 'mute' });
      const b = window.__dpmSilent;
      send({ cmd: 'release' });
      return [a, b, window.__dpmSilent];
    });
    check('commands switch modes', JSON.stringify(state) === '[[true,true],true,false]', JSON.stringify(state));
    await ctx.close();
  }
  await browser.close();
  server.close();
  const failed = results.filter(r => !r[1]).length;
  console.log(`\n${results.length - failed}/${results.length} checks passed`);
  process.exit(failed ? 1 : 0);
})().catch(e => { console.error(e); process.exit(2); });
