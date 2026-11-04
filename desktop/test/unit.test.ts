// Pure logic shared with the Android app: run with `npm test` (bundled by esbuild, run by node --test).
import assert from "node:assert/strict";
import { test } from "node:test";
import { merge, valid, inSegment } from "../src/shared/segments";
import type { Movie, Segment } from "../src/shared/types";
import { stubMovie } from "../src/shared/types";
import { parseAniSkip, parseIntroDb, parseTheIntroDb } from "../src/main/skip";
import { balanced, parseCollaps, parseLenient } from "../src/main/resolve/collaps";
import { base, match, queries, EXACT, PARTIAL, NONE } from "../src/main/resolve/animeTitles";
import { cleanShikimori, normalize, where } from "../src/main/catalog";
import { isExcluded } from "../src/main/resolve/playerSite";

const hour = 3_600_000;
const seg = (kind: Segment["kind"], startMs: number, endMs: number | null, source = "x"): Segment => ({ kind, startMs, endMs, source });

test("AniSkip answer becomes intro, credits and recap", () => {
  const s = parseAniSkip(`{"found":true,"results":[{"interval":{"startTime":6.576,"endTime":96.576},"skipType":"op"},
    {"interval":{"startTime":1460,"endTime":1550},"skipType":"ed"},{"interval":{"startTime":0,"endTime":40},"skipType":"recap"}]}`);
  assert.deepEqual(s.map(x => x.kind), ["INTRO", "CREDITS", "RECAP"]);
  assert.equal(s[0].startMs, 6576);
  assert.equal(s[0].endMs, 96576);
});

test("TheIntroDB open ends: intro from the start, credits to the end", () => {
  const s = parseTheIntroDb(`{"tmdb_id":603,"type":"movie","intro":[{"start_ms":null,"end_ms":40000}],"credits":[{"start_ms":7726802,"end_ms":null}]}`);
  assert.deepEqual(s[0], seg("INTRO", 0, 40000, "TheIntroDB"));
  assert.deepEqual(s[1], seg("CREDITS", 7726802, null, "TheIntroDB"));
});

test("IntroDB honours no_intro and tolerates errors", () => {
  const s = parseIntroDb(`{"intro":{"start_ms":2500,"end_ms":58000},"recap":null,"outro":{"start_ms":3431000,"end_ms":3500000},"no_intro":true}`);
  assert.deepEqual(s.map(x => x.kind), ["CREDITS"]);
  assert.deepEqual(parseIntroDb(`{"error":"not found"}`), []);
  assert.deepEqual(parseAniSkip("garbage"), []);
});

test("merge takes the first plausible source per kind", () => {
  const merged = merge(1_560_000, [seg("INTRO", 1000, 89000, "AniLiberty")], [seg("INTRO", 6000, 96000, "AniSkip"), seg("CREDITS", 1_460_000, 1_550_000, "AniSkip")]);
  assert.deepEqual(merged.map(x => x.source), ["AniLiberty", "AniSkip"]);
});

test("implausible segments are dropped or clamped", () => {
  assert.equal(valid(seg("CREDITS", 600_000, null), hour), null);
  assert.equal(valid(seg("INTRO", 0, 3000), hour), null);
  assert.equal(valid(seg("INTRO", 0, 600_000), hour), null);
  assert.equal(valid(seg("CREDITS", 3_300_000, 4_000_000), hour)?.endMs, hour);
  assert.equal(merge(hour, [seg("INTRO", 0, 600_000, "bad")], [seg("INTRO", 30_000, 90_000, "good")])[0].source, "good");
  assert.ok(inSegment(seg("INTRO", 10_000, 70_000), 10_000, hour));
  assert.ok(!inSegment(seg("INTRO", 10_000, 70_000), 69_500, hour));
});

test("Collaps movie page: HLS, voice-overs, subtitles", () => {
  const html = `<script>makePlayer({ hls: "https:\\/\\/cdn.example\\/m.m3u8?a=1\\u0026b=2", audio: {"names":["Дубляж","Оригинал"]},
    cc: [{"url":"https://cdn.example/ru.vtt","name":"Русские"}] })</script>`;
  const p = parseCollaps(html, { Referer: "https://x/" });
  assert.equal(p.kind, "single");
  if (p.kind != "single") return;
  assert.equal(p.stream.url, "https://cdn.example/m.m3u8?a=1&b=2");
  assert.deepEqual(p.stream.audioNames, ["Дубляж", "Оригинал"]);
  assert.equal(p.stream.subtitles[0].label, "Русские");
});

test("Collaps: DASH up to 1080p first, HLS behind it", () => {
  const html = `makePlayer({ source: { hls: "https://c/a.m3u8", dash: "https://c/a.mpd", dasha: "https://c/a2.mpd", audio: {"names":["Дубляж","delete"]} } });`;
  const p = parseCollaps(html, {});
  assert.equal(p.kind, "single");
  if (p.kind != "single") return;
  assert.equal(p.stream.format, "DASH");
  assert.equal(p.stream.url, "https://c/a2.mpd");
  assert.equal(p.stream.fallback?.format, "HLS");
  assert.equal(p.stream.fallback?.url, "https://c/a.m3u8");
  assert.deepEqual(p.stream.fallback?.audioNames, ["Дубляж", "delete"]);
  const hlsOnly = parseCollaps(`makePlayer({ source: { hls: "https://c/b.m3u8" } });`, {});
  assert.ok(hlsOnly.kind == "single" && hlsOnly.stream.format == "HLS" && !hlsOnly.stream.fallback);
});

test("Shikimori markup becomes plain text", () => {
  assert.equal(
    cleanShikimori("Ремейк «[anime=121]Стальной алхимик[/anime]».\n\nСкучающий [[Синигами]] Рюк [リューク] бросает тетрадь. Детектив [character=71]L[/character] и [[Ягами|Лайт]]."),
    "Ремейк «Стальной алхимик».\nСкучающий Синигами Рюк бросает тетрадь. Детектив L и Лайт.",
  );
  assert.equal(cleanShikimori("[i]Нет[/i]"), null);
});

test("VeoVeo is not offered", () => {
  assert.ok(isExcluded("Veoveo") && isExcluded("VeoVeo 4K") && !isExcluded("Turbo"));
});

test("Collaps series with JS-style keys", () => {
  const html = `seasons: [{season: 1, episodes: [{episode: 2, hls: 'https://c/2.m3u8'}, {episode: 1, hls: 'https://c/1.m3u8', audio: {names: ['A', 'B']}}]}],`;
  const p = parseCollaps(html, {});
  assert.equal(p.kind, "series");
  if (p.kind != "series") return;
  assert.deepEqual(p.seasons[0].episodes.map(e => e.number), [1, 2]);
  assert.deepEqual(p.seasons[0].episodes[0].stream.audioNames, ["A", "B"]);
  assert.equal(balanced("x[1,[2,']'],3]y", 1), "[1,[2,']'],3]");
  assert.deepEqual(parseLenient("{a: 1, 'b': \"x\",}"), { a: 1, b: "x" });
});

test("anime title matching: season markers, short catalog names", () => {
  const titan: Movie = { ...stubMovie(749374, "Атака титанов"), originalTitle: "Attack on Titan", year: 2013, type: "SERIES" };
  assert.equal(match(titan, "Вторжение Гигантов (четвёртый сезон)", "Attack on Titan: The Final Season"), EXACT);
  assert.equal(base("Вторжение Гигантов (второй сезон)"), "вторжение гигантов");
  const frieren: Movie = { ...stubMovie(5401195, "Фрирен"), originalTitle: "Frieren", year: 2023, type: "SERIES" };
  assert.equal(match(frieren, "Провожающая в последний путь Фрирен", "Sousou no Frieren"), PARTIAL);
  assert.equal(match(frieren, "Наруто", "Naruto"), NONE);
  const reze: Movie = { ...stubMovie(5430477, "Человек-бензопила. Фильм: История Резе"), originalTitle: "Chainsaw Man – The Movie: Reze Arc", year: 2025 };
  assert.ok(queries(reze).includes("Человек-бензопила"));
  assert.equal(match(reze, "Человек-бензопила: Резе", "Chainsaw Man Movie: Reze-hen"), PARTIAL);
});

test("catalog queries match the Android app", () => {
  const [sql, args] = where({ kind: "FILMS", sort: "RATING", genre: "Комедия", yearFrom: 2000 });
  assert.ok(sql.startsWith("type = 0"));
  assert.ok(sql.includes("kp_votes >= ?"));
  assert.ok(sql.includes("duration >= 40"));
  assert.deepEqual(args, [2, 2000, 20000, 2 ** 18]);
  assert.ok(where({ kind: "ANIME", sort: "TREND" })[0].includes(`(genres & ${2 ** 12}) != 0`));
  assert.equal(normalize("Ёлки-палки: 2!"), "елки палки 2");
});
