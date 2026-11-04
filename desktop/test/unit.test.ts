// Pure logic shared with the Android app: run with `npm test` (bundled by esbuild, run by node --test).
import assert from "node:assert/strict";
import { test } from "node:test";
import { merge, valid, inSegment, skipRequest } from "../src/shared/segments";
import type { Movie, Segment } from "../src/shared/types";
import { stubMovie } from "../src/shared/types";
import { consensus, parseAniSkip, parseIntroDb, parseTheIntroDb, SkipLookup } from "../src/main/skip";
import { mapSeason, pickAnime, type AnimeEntry } from "../src/main/animeIds";
import { HttpError } from "../src/main/net";
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

test("AniSkip: the submission of the closest cut; an ending past a shorter cut is moved", () => {
  const text = `{"found":true,"results":[
    {"interval":{"startTime":10,"endTime":100},"skipType":"op","episodeLength":1420},
    {"interval":{"startTime":70,"endTime":160},"skipType":"op","episodeLength":1440.4},
    {"interval":{"startTime":1430,"endTime":1500},"skipType":"ed","episodeLength":1510}]}`;
  assert.equal(parseAniSkip(text, 1_440_000).find(x => x.kind == "INTRO")?.startMs, 70_000);
  const shorter = parseAniSkip(text, 1_420_000).find(x => x.kind == "CREDITS")!;
  assert.deepEqual([shorter.startMs, shorter.endMs], [1_340_000, 1_410_000]);
  assert.equal(parseAniSkip(text, 1_510_000).find(x => x.kind == "CREDITS")?.startMs, 1_430_000);
});

test("neighbouring episodes must agree on the intro", () => {
  const intro = (a: number, b: number) => seg("INTRO", a, b, "TheIntroDB");
  const agreed = consensus([intro(0, 90_000), intro(30_000, 120_000), intro(1_500, 91_000)])!;
  assert.deepEqual([agreed.startMs, agreed.endMs, agreed.source], [1_500, 91_000, "TheIntroDB, по соседним сериям"]);
  assert.equal(consensus([intro(0, 90_000), intro(30_000, 120_000)]), null);
  assert.equal(consensus([intro(0, 90_000)]), null);
});

test("an anime's first season is found by title and year", () => {
  const results: AnimeEntry[] = [
    { id: 1, kind: "ona", airedOn: "2020-06-01", episodes: 1, names: ["Jujutsu Kaisen PV"] },
    { id: 40748, kind: "tv", airedOn: "2020-10-03", episodes: 24, names: ["Jujutsu Kaisen", "Магическая битва"] },
    { id: 48561, kind: "movie", airedOn: "2021-12-24", episodes: 1, names: ["Jujutsu Kaisen 0", "Магическая битва 0"] },
    { id: 51009, kind: "tv", airedOn: "2023-07-06", episodes: 23, names: ["Jujutsu Kaisen 2nd Season", "Магическая битва 2"] },
  ];
  assert.equal(pickAnime({ title: "Магическая битва", year: 2020, series: true }, "Магическая битва", results), 40748);
  assert.equal(pickAnime({ title: "Магическая битва 0", year: 2021, series: false }, "Магическая битва 0", results), 48561);
  assert.equal(pickAnime({ title: "Магическая битва", year: 2016, series: true }, "Магическая битва", results), null);
});

test("seasons are mapped to MyAnimeList entries by episode counts", () => {
  const chain: AnimeEntry[] = [26, 7, 11, 11, 8].map((n, i) => ({ id: 100 + i, kind: "tv", airedOn: null, episodes: n }));
  const sizes = [26, 18, 11, 8];
  assert.deepEqual(mapSeason(chain, 0, 26, sizes), [100, 26]);
  assert.deepEqual(mapSeason(chain, 1, 7, sizes), [101, 7]);
  assert.deepEqual(mapSeason(chain, 1, 10, sizes), [102, 3]);
  assert.deepEqual(mapSeason(chain, 3, 8, sizes), [104, 8]);
  assert.equal(mapSeason(chain, 1, 3, [26, 20, 11, 8]), null);
  assert.deepEqual(mapSeason(chain, 3, 5, [26, 18, 11, 5]), [104, 5]);
  const airing: AnimeEntry[] = [{ id: 1, kind: "tv", airedOn: null, episodes: 24 }, { id: 2, kind: "tv", airedOn: null, episodes: 0 }];
  assert.deepEqual(mapSeason(airing, 1, 7, [24, 9]), [2, 7]);
  assert.deepEqual(mapSeason(chain, 0, 30, []), [101, 4]);
  assert.equal(mapSeason(chain, 1, 4, []), null);
});

/** Canned answers by address; anything else is a 404, as the databases answer for unknown titles. */
function fakeWeb(routes: Record<string, string>, asked: string[] = []) {
  return async (url: string) => {
    const u = decodeURIComponent(url);
    asked.push(u);
    const hit = Object.entries(routes).find(([k]) => u.includes(k));
    if (!hit) throw new HttpError(404, url);
    return hit[1];
  };
}

test("an anime without an ID is found on Shikimori and its season located", async () => {
  const asked: string[] = [];
  const lookup = new SkipLookup(fakeWeb({
    "/api/animes?limit=20&search=Клинок, рассекающий демонов": `[{"id":38000,"name":"Kimetsu no Yaiba","russian":"Клинок, рассекающий демонов","kind":"tv","episodes":26,"aired_on":"2019-04-06"}]`,
    "/api/animes/38000/related": `[{"relation":"Sequel","anime":{"id":40456,"kind":"movie","episodes":1,"aired_on":"2020-10-16"}},
      {"relation":"Sequel","anime":{"id":49926,"kind":"tv","episodes":7,"aired_on":"2021-10-10"}}]`,
    "/api/animes/49926/related": `[{"relation":"Sequel","anime":{"id":47778,"kind":"tv","episodes":11,"aired_on":"2021-12-05"}}]`,
    "/api/animes/38000": `{"id":38000,"kind":"tv","episodes":26,"aired_on":"2019-04-06"}`,
    "skip-times/47778/3?": `{"found":true,"results":[{"interval":{"startTime":95,"endTime":185},"skipType":"op","episodeLength":1440}]}`,
  }, asked));
  const got = await lookup.fetch({
    season: 2, episode: 10, durationMs: 1_440_000,
    anime: { title: "Клинок, рассекающий демонов", original: "鬼滅の刃", year: 2019, series: true },
    seasonSizes: [26, 18],
  });
  assert.deepEqual(got, [seg("INTRO", 95_000, 185_000, "AniSkip")]);
  assert.ok(asked.some(u => u.includes("skip-times/47778/3?") && u.includes("episodeLength=0")));
  assert.ok(!asked.some(u => u.includes("/api/animes/47778/related")));
});

test("a caught episode of a show gets the intro its episodes agree on", async () => {
  const lookup = new SkipLookup(fakeWeb({
    "imdb_id=tt1&season=1&episode=1": `{"intro":[{"start_ms":60000,"end_ms":100000}]}`,
    "imdb_id=tt1&season=1&episode=2": `{"intro":[{"start_ms":61000,"end_ms":101500}]}`,
    "imdb_id=tt1&season=1&episode=3": `{"intro":[{"start_ms":200000,"end_ms":240000}]}`,
  }));
  const got = await lookup.fetch({ imdbId: "tt1", season: 1, episode: null, durationMs: 2_700_000, series: true });
  assert.deepEqual([got[0]?.startMs, got[0]?.source, got.length], [61_000, "TheIntroDB, по соседним сериям", 1]);
  assert.deepEqual(await lookup.fetch({ imdbId: "tt1", season: null, episode: null, durationMs: 2_700_000, series: true }), []);
  const fifth = await lookup.fetch({ imdbId: "tt1", season: 1, episode: 5, durationMs: 2_700_000 });
  assert.equal(fifth[0]?.source, "TheIntroDB, по соседним сериям");
});

test("skip request: the page's numbers for a caught stream, the source's own otherwise", () => {
  const show: Movie = { ...stubMovie(1, "Клинок, рассекающий демонов"), type: "SERIES", genres: ["Аниме"], year: 2019, seasons: 4, imdbId: "tt9335498" };
  const series = { kind: "series" as const, sourceName: "Collaps", seasons: [1, 2].map(n => ({ number: n, episodes: Array.from({ length: n == 1 ? 26 : 18 }, (_, i) => ({ season: n, number: i + 1, title: null, stream: null as never })) })) };
  const own = skipRequest(show, series, 1_440_000, 2, 10, { season: 5, episode: 1 });
  assert.deepEqual([own.season, own.episode, own.seasonIndex, own.seasonSizes, own.anime?.title], [2, 10, 1, [26, 18], "Клинок, рассекающий демонов"]);
  const caught = skipRequest(show, null, 1_440_000, null, null, { season: 3, episode: 4 });
  assert.deepEqual([caught.season, caught.episode, caught.series], [3, 4, true]);
  // several seasons and the page says nothing: no season is assumed
  assert.equal(skipRequest(show, null, 1_440_000, null, null, null).season, null);
  // a single season needs no telling
  assert.equal(skipRequest({ ...show, seasons: 1 }, null, 1_440_000, null, null, null).season, 1);
  // sources that skip a season number give no counts to line up with
  assert.deepEqual(skipRequest(show, { ...series, seasons: [series.seasons[1]] }, 1_440_000, 2, 3, null).seasonSizes, []);
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
