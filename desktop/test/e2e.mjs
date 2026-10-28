// Drives the built app with Playwright: screens render, real streams play, intro marks load.
// Run under a display: xvfb-run -a node test/e2e.mjs [--shots dir] [--online]
import { createRequire } from "node:module";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";

const require = createRequire(import.meta.url);
const { _electron: electron } = require("playwright");
const root = path.dirname(path.dirname(fileURLToPath(import.meta.url)));
const args = process.argv.slice(2);
const shots = args.includes("--shots") ? args[args.indexOf("--shots") + 1] : path.join(root, "test", "shots");
const online = args.includes("--online");
fs.mkdirSync(shots, { recursive: true });

const userData = fs.mkdtempSync(path.join(os.tmpdir(), "dpm-e2e-"));
const app = await electron.launch({
  executablePath: require("electron"),
  args: [root, "--no-sandbox"],
  env: { ...process.env, DPM_USER_DATA: userData },
});
const page = await app.firstWindow();
page.on("console", m => m.type() == "error" && console.log("[renderer]", m.text()));
await page.setViewportSize({ width: 1360, height: 860 });
const results = [];
const check = (name, ok, detail = "") => {
  results.push({ name, ok });
  console.log(`${ok ? "ok  " : "FAIL"} ${name}${detail ? " — " + detail : ""}`);
};
const shot = async name => page.screenshot({ path: path.join(shots, `${name}.png`) });

try {
  await page.waitForSelector(".movie-card .movie-card-title", { timeout: 30_000 });
  check("home renders shelves", (await page.locator(".shelf").count()) >= 3);
  await page.waitForTimeout(online ? 4000 : 800);
  await shot("01_home");

  await page.getByRole("tab", { name: "Аниме" }).click();
  await page.waitForTimeout(online ? 3000 : 800);
  check("anime tab", (await page.locator(".hero .page-title").textContent()) == "Аниме");
  await shot("02_home_anime");

  await page.locator(".nav-item", { hasText: "Каталог" }).click();
  await page.waitForSelector(".movie-grid .movie-card");
  check("catalog grid", (await page.locator(".movie-grid .movie-card").count()) >= 20);
  await shot("03_catalog");

  await page.locator(".main-head input").fill("матрица");
  await page.waitForSelector(".search .movie-card");
  check("offline search", (await page.locator(".search .movie-card-title").first().textContent())?.includes("Матрица"));
  await shot("04_search");

  await page.locator(".search .movie-card").first().click();
  await page.waitForSelector(".details-title");
  check("details", (await page.locator(".details-title").textContent()) == "Матрица");
  await page.waitForTimeout(online ? 5000 : 500);
  await shot("05_details");

  await page.locator(".nav-item", { hasText: "Настройки" }).click();
  await page.waitForSelector(".settings-page");
  await shot("06_settings");
  await page.locator(".segmented button", { hasText: "Тёмная" }).click();
  await page.locator(".nav-item", { hasText: "Главная" }).click();
  await page.waitForTimeout(online ? 3000 : 800);
  check("dark theme", (await page.evaluate(() => document.documentElement.dataset.theme)) == "dark");
  await shot("07_home_dark");

  if (online) {
    /** Opens the player for a title and waits until its video really advances. */
    const playAndWait = async (id, label) => {
      await page.evaluate(id => window.__dpmTestPlay?.(id), id);
      const started = Date.now();
      let t = 0;
      while (Date.now() - started < 120_000) {
        await page.waitForTimeout(2000);
        t = await page.evaluate(() => document.querySelector(".player-video")?.currentTime ?? 0);
        if (t > 3) break;
        const err = await page.locator(".player[data-phase=error]").count();
        if (err) break;
      }
      const log = await page.evaluate(() => window.dpm.player.log());
      check(`${label} plays`, t > 3, `t=${t.toFixed(1)}s; ${log.filter(l => /поток найден|Ошибка|не отдал|HTTP/.test(l)).slice(-3).join(" | ")}`);
      return t > 3;
    };

    if (await playAndWait(301, "Матрица (VeoVeo)")) {
      await page.mouse.move(600, 400);
      await page.waitForTimeout(500);
      await shot("08_player");
      const voices = await page.evaluate(() => [...document.querySelectorAll(".p-pill")].map(b => b.textContent));
      check("player controls", voices.some(v => v?.includes("Автопропуск")), voices.join(", "));
      await page.locator(".p-pill", { hasText: /Дубляж|Многоголосый|Озвучка|Jask/ }).first().click().catch(() => {});
      await page.waitForTimeout(500);
      const options = await page.locator(".player-panel .p-option").count();
      check("voice-overs listed", options >= 2, `${options} voice-overs`);
      await shot("09_player_voices");
      await page.keyboard.press("Escape");
      await page.keyboard.press("Escape");
      await page.waitForTimeout(500);
    }

    if (await playAndWait(464963, "Игра престолов (сериал)")) {
      await page.waitForTimeout(6000);
      const segments = await page.locator(".seek-segment").count();
      check("intro marks on the seek bar", segments >= 1, `${segments} segment(s)`);
      await page.mouse.move(600, 400);
      await page.locator(".p-pill", { hasText: "Заставка" }).click();
      await page.waitForTimeout(400);
      await shot("10_player_skip_panel");
      await page.keyboard.press("Escape");
      await page.keyboard.press("Escape");
      await page.waitForTimeout(500);
    }

    if (await playAndWait(5401195, "Фрирен (AniLiberty)")) {
      await page.waitForTimeout(3000);
      const log = await page.evaluate(() => window.dpm.player.log());
      check("anime goes to AniLiberty", log.some(l => l.includes("AniLiberty: поток найден")));
      await page.mouse.move(600, 400);
      await page.locator(".p-pill", { hasText: /1080p|720p|480p/ }).first().click().catch(() => {});
      await page.waitForTimeout(400);
      await shot("11_player_anime_quality");
      await page.keyboard.press("Escape");
      await page.keyboard.press("Escape");
    }
  }

  if (args.includes("--site")) {
    // the fallbacks: catch the stream from the hidden page, then the site's own player
    await page.evaluate(() => window.__dpmTestPlay?.(301));
    await page.waitForSelector(".player .p-pill", { timeout: 90_000 }).catch(() => {});
    await page.mouse.move(600, 400);
    await page.locator(".p-pill", { hasText: "Источник" }).click();
    await page.locator(".p-option", { hasText: "Авто" }).click();
    const started = Date.now();
    let phase = "";
    while (Date.now() - started < 60_000) {
      await page.waitForTimeout(2000);
      phase = await page.locator(".player").getAttribute("data-phase");
      const t = await page.evaluate(() => document.querySelector(".player-video")?.currentTime ?? 0);
      if (phase == "playing" && t > 2) break;
      if (phase == "error") break;
    }
    const log = await page.evaluate(() => window.dpm.player.log());
    check("hidden page: stream caught or reported", phase == "playing" || phase == "error", `${phase}; ${log.filter(l => /Найден поток|не появился|Ловим/.test(l)).slice(-2).join(" | ")}`);
    await page.mouse.move(600, 400);
    await page.locator(".p-pill", { hasText: "Источник" }).click().catch(() => page.locator(".btn", { hasText: "Другой источник" }).click());
    await page.locator(".p-option", { hasText: "Плеер сайта" }).click();
    await page.waitForTimeout(8000);
    const views = await app.evaluate(({ webContents }) => webContents.getAllWebContents().map(w => w.getURL()));
    check("site player view opened", views.some(u => u.includes("troutcdn")), views.filter(u => u.startsWith("http")).join(", "));
    await shot("12_player_site");
    await page.keyboard.press("Escape");
    await page.keyboard.press("Escape");
    await page.waitForTimeout(1000);
    const after = await app.evaluate(({ webContents }) => webContents.getAllWebContents().map(w => w.getURL()));
    check("site player closed with the player", !after.some(u => u.includes("troutcdn")));
  }
} catch (e) {
  check("no exceptions", false, String(e));
  await shot("zz_failure").catch(() => {});
} finally {
  await app.close();
  fs.rmSync(userData, { recursive: true, force: true });
}

const failed = results.filter(r => !r.ok);
console.log(`\n${results.length - failed.length}/${results.length} checks passed`);
process.exit(failed.length ? 1 : 0);
