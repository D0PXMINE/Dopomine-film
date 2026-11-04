import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import type { EpisodeHint, Movie, Playable, ResolveResult, Segment, SitePage, Stream } from "../../shared/types";
import { SOURCE } from "../../shared/types";
import { inSegment, merge, SEGMENT_TITLE, SKIP_LABEL, segmentEnd, skipRequest } from "../../shared/segments";
import { Halftone } from "../halftone/Halftone";
import { api } from "../lib/api";
import { clock } from "../lib/format";
import { closePlayer, setSetting, useSettings } from "../lib/state";
import { Icon, IconName } from "../components/icons";
import { Spinner } from "../components/primitives";
import { AUTO, Engine, Track } from "./engine";

type Phase = "resolving" | "playing" | "site" | "error";
type Panel = null | "sources" | "episodes" | "voice" | "quality" | "subs" | "speed" | "skip" | "log";

interface Item {
  stream: Stream;
  /** Where the position is saved: "movie", "s1e3", or null until a caught stream is recognised by duration. */
  key: string | null;
  season: number | null;
  episode: number | null;
  source: string;
}

const SPEEDS = [0.5, 0.75, 1, 1.25, 1.5, 1.75, 2];
const NEXT_COUNTDOWN = 5;

export default function Player({ movie: input }: { movie: Movie }) {
  const settings = useSettings();
  const root = useRef<HTMLDivElement | null>(null);
  const videoRef = useRef<HTMLVideoElement | null>(null);
  const siteArea = useRef<HTMLDivElement | null>(null);
  const engine = useRef<Engine | null>(null);

  const [movie, setMovie] = useState(input);
  const [phase, setPhase] = useState<Phase>("resolving");
  const [status, setStatus] = useState("Ищем, откуда сайт берёт видео…");
  const [error, setError] = useState<string | null>(null);
  const [result, setResult] = useState<ResolveResult | null>(null);
  const [item, setItem] = useState<Item | null>(null);
  const [panel, setPanel] = useState<Panel>(null);
  const [log, setLog] = useState<string[]>([]);
  const [, setTick] = useState(0);
  const rerender = useCallback(() => setTick(t => t + 1), []);
  const [controls, setControls] = useState(true);
  const [fullscreen, setFullscreen] = useState(false);
  const [resumedAt, setResumedAt] = useState<number | null>(null);

  // intro skipping
  const [segments, setSegments] = useState<Segment[]>([]);
  const [active, setActive] = useState<Segment | null>(null);
  const [skipped, setSkipped] = useState<Segment | null>(null);
  const [countdown, setCountdown] = useState<number | null>(null);
  const [introMark, setIntroMark] = useState<[number, number] | null>(null);
  const [markStart, setMarkStart] = useState<number | null>(null);
  const [sitePos, setSitePos] = useState<{ t: number; d: number } | null>(null);
  /** The page over the player area in "site" phase (the site itself, or one player's page). */
  const [sitePage, setSitePage] = useState<SitePage>({ embedUrl: null, name: "Плеер сайта", catching: false });
  /** The page a caught stream came from: it plays the video itself if the app cannot. */
  const caughtFrom = useRef<SitePage | null>(null);
  /** The other form of the current item was already tried (Collaps: HLS after DASH). */
  const fellBack = useRef(false);
  const done = useRef(new Set<Segment>());
  const countdownTimer = useRef<ReturnType<typeof setInterval> | null>(null);
  const segmentsFor = useRef<string | null>(null);

  const ctx = useRef({ result: null as ResolveResult | null, item: null as Item | null, movie: input });
  ctx.current.result = result;
  ctx.current.item = item;
  ctx.current.movie = movie;
  const autoSkip = settings?.autoSkip ?? false;
  const autoSkipRef = useRef(autoSkip);
  autoSkipRef.current = autoSkip;

  const video = () => videoRef.current!;
  const series = result?.playable?.kind == "series" ? result.playable : null;

  // ---- progress ------------------------------------------------------------------------------

  const saveProgress = useCallback(() => {
    const it = ctx.current.item;
    const v = videoRef.current;
    if (!it?.key || !v || !isFinite(v.duration) || v.currentTime <= 0) return;
    void api.library.saveProgress(ctx.current.movie.id, it.key, v.currentTime * 1000, v.duration * 1000, it.season, it.episode);
  }, []);

  // ---- playing ---------------------------------------------------------------------------------

  const resetSegments = useCallback(() => {
    done.current.clear();
    segmentsFor.current = null;
    setSegments([]);
    setActive(null);
    setSkipped(null);
    if (countdownTimer.current) clearInterval(countdownTimer.current);
    countdownTimer.current = null;
    setCountdown(null);
  }, []);

  const playItem = useCallback(async (next: Item) => {
    saveProgress();
    resetSegments();
    setPhase("playing");
    setError(null);
    setItem(next);
    fellBack.current = false;
    let stream: Stream;
    try {
      stream = await api.player.prepare(next.stream);
    } catch (e) {
      setError(`Не удалось получить ссылку: ${(e as Error).message}`);
      setPhase("error");
      return;
    }
    const s = settingsRef.current;
    const start = next.key && s?.autoResume ? (await api.library.position(ctx.current.movie.id, next.key)) ?? 0 : 0;
    setResumedAt(start > 30 ? start : null);
    engine.current?.load(stream, start > 30 ? start : 0);
    setControls(true);
  }, [resetSegments, saveProgress]);

  const settingsRef = useRef(settings);
  settingsRef.current = settings;

  const playEpisode = useCallback((p: Extract<Playable, { kind: "series" }>, seasonNo: number, episodeNo: number) => {
    const season = p.seasons.find(s => s.number == seasonNo);
    const ep = season?.episodes.find(e => e.number == episodeNo);
    if (!ep) return;
    void playItem({ stream: ep.stream, key: `s${seasonNo}e${episodeNo}`, season: seasonNo, episode: episodeNo, source: p.sourceName });
  }, [playItem]);

  const showPage = useCallback((page: SitePage) => {
    engine.current?.destroy();
    setSitePage(page);
    setPhase("site");
  }, []);

  const start = useCallback(async (r: ResolveResult) => {
    setResult(r);
    setMovie(r.movie);
    if (!r.playable) {
      if (r.page) showPage(r.page);
      else if (r.choice == SOURCE.SITE_PLAYER) showPage({ embedUrl: null, name: "Плеер сайта", catching: false });
      else {
        setError(r.error ?? "Видео не найдено");
        setPhase("error");
      }
      return;
    }
    const p = r.playable;
    setPageHint(shownHint(r.hint));
    if (p.kind == "single") {
      // a caught stream has no stable address; it is recognised by its duration instead
      const caught = r.choice == SOURCE.SITE || (r.choice ?? "").startsWith("src:");
      caughtFrom.current = caught ? { ...(r.page ?? { embedUrl: null, name: p.sourceName }), catching: false } : null;
      void playItem({ stream: p.stream, key: caught ? null : "movie", season: null, episode: null, source: p.sourceName });
      return;
    }
    caughtFrom.current = null;
    const last = await api.library.lastEpisode(r.movie.id);
    const season = p.seasons.find(s => s.number == last?.[0]) ?? p.seasons.find(s => s.number == p.startSeason) ?? p.seasons[0];
    const ep = season.episodes.find(e => e.number == last?.[1]) ?? season.episodes[0];
    playEpisode(p, season.number, ep.number);
  }, [playEpisode, playItem]);

  const choose = useCallback(async (key: string) => {
    setPanel(null);
    saveProgress();
    if (key == SOURCE.SITE_PLAYER) {
      setResult(r => (r ? { ...r, choice: key, playable: null } : r));
      showPage({ embedUrl: null, name: "Плеер сайта", catching: false });
      return;
    }
    engine.current?.destroy();
    setPhase("resolving");
    const title = ctx.current.result?.choices.find(c => c.key == key)?.title ?? key;
    setStatus(`${title}: получаем поток…`);
    const r = await api.player.choose(ctx.current.movie, key);
    await start(r);
  }, [saveProgress, start]);

  /** A source refused the video: go on down the chain, then catch the site's stream, then show the site. */
  const fallback = useCallback(async (reason: string) => {
    const r = ctx.current.result;
    if (!r) return;
    const order = r.order;
    const at = order.indexOf(r.choice ?? "");
    setPhase("resolving");
    for (const key of [...(at >= 0 ? order.slice(at + 1) : []), SOURCE.SITE]) {
      setStatus(`${reason} — пробуем ${r.choices.find(c => c.key == key)?.title ?? "другой источник"}…`);
      const next = await api.player.choose(ctx.current.movie, key);
      if (next.playable) {
        await start(next);
        return;
      }
    }
    setResult({ ...r, choice: SOURCE.SITE_PLAYER, playable: null });
    showPage({ embedUrl: null, name: "Плеер сайта", catching: false });
  }, [start, showPage]);

  /** The source menu; the site's list of players may have come in after playback started. */
  const openSources = useCallback(() => {
    if (panelRef.current == "sources") {
      setPanel(null);
      return;
    }
    setPanel("sources");
    void api.player.choices(ctx.current.movie).then(choices => setResult(r => (r ? { ...r, choices } : r))).catch(() => {});
  }, []);

  // ---- setup -------------------------------------------------------------------------------------

  useEffect(() => {
    const e = new Engine(video());
    engine.current = e;
    e.onChange = rerender;
    e.onError = err => {
      void api.player.log().then(setLog);
      const current = e.current;
      const other = ctx.current.item?.stream.fallback;
      // the same video in its other form (Collaps: HLS behind DASH) before another source
      if (other && !fellBack.current && current?.format != other.format) {
        fellBack.current = true;
        const at = videoRef.current?.currentTime ?? 0;
        void api.player.prepare(other).then(s => e.load(s, at));
        return;
      }
      // a caught stream that does not play here: the page it came from plays it itself
      if (caughtFrom.current) {
        showPage(caughtFrom.current);
        return;
      }
      void fallback(err.message);
    };
    let alive = true;
    void (async () => {
      e.preferredVoice = await api.library.voice(input.id);
      setIntroMark(await api.settings.introMark(input.id));
      const r = await api.player.resolve(input);
      if (alive) await start(r);
    })();
    const offLog = api.player.onLog(line => setLog(l => [...l.slice(-299), line]));
    void api.player.log().then(setLog);
    const saver = setInterval(saveProgress, 5000);
    const onFs = () => setFullscreen(document.fullscreenElement != null);
    document.addEventListener("fullscreenchange", onFs);
    return () => {
      alive = false;
      saveProgress();
      clearInterval(saver);
      offLog();
      document.removeEventListener("fullscreenchange", onFs);
      e.destroy();
      void api.player.hideSite();
      if (document.fullscreenElement) void document.exitFullscreen();
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // ---- episodes --------------------------------------------------------------------------------

  const nextRef = useCallback((): [number, number] | null => {
    const p = ctx.current.result?.playable;
    const it = ctx.current.item;
    if (p?.kind != "series" || it?.season == null || it.episode == null) return null;
    const season = p.seasons.find(s => s.number == it.season);
    const i = season?.episodes.findIndex(e => e.number == it.episode) ?? -1;
    if (season && i >= 0 && i + 1 < season.episodes.length) return [season.number, season.episodes[i + 1].number];
    const nextSeason = p.seasons.find(s => s.number > it.season!);
    return nextSeason?.episodes[0] ? [nextSeason.number, nextSeason.episodes[0].number] : null;
  }, []);

  const nextEpisode = useCallback(() => {
    const n = nextRef();
    const p = ctx.current.result?.playable;
    if (n && p?.kind == "series") playEpisode(p, n[0], n[1]);
  }, [nextRef, playEpisode]);

  // ---- intro skipping ----------------------------------------------------------------------------

  const loadSegments = useCallback(async (durationMs: number, season: number | null, episode: number | null, own: Segment[]) => {
    const mark: Segment[] = introMarkRef.current ? [{ kind: "INTRO", startMs: introMarkRef.current[0] * 1000, endMs: introMarkRef.current[1] * 1000, source: "Ваша разметка" }] : [];
    const request = skipRequest(ctx.current.movie, ctx.current.result?.playable, durationMs, season, episode, pageHintRef.current);
    const found = await api.player.segments(request).catch(() => []);
    setSegments(merge(durationMs, own, found, mark));
  }, []);
  /** The season and episode the player page shows: a stream caught from it carries no numbers. */
  const [pageHint, setPageHint] = useState<EpisodeHint | null>(null);
  const pageHintRef = useRef(pageHint);
  pageHintRef.current = pageHint;
  const shownHint = (h: EpisodeHint | null | undefined) => (h && (h.season != null || h.episode != null) ? h : null);
  useEffect(() => api.player.onSiteEpisode(h => setPageHint(shownHint(h))), []);
  /** Part of what segments were looked up with: only for videos without their own numbers. */
  const hintKey = (season: number | null, episode: number | null) => (season == null && episode == null ? `${pageHint?.season ?? ""}x${pageHint?.episode ?? ""}` : "");
  const introMarkRef = useRef(introMark);
  introMarkRef.current = introMark;

  const seekTo = useCallback((sec: number) => {
    if (phaseRef.current == "site") void api.player.siteSeek(sec);
    else if (videoRef.current) videoRef.current.currentTime = sec;
  }, []);
  const phaseRef = useRef(phase);
  phaseRef.current = phase;

  const cancelCountdown = useCallback(() => {
    if (countdownTimer.current) clearInterval(countdownTimer.current);
    countdownTimer.current = null;
    setCountdown(null);
  }, []);

  /** Called with the position a few times a second: shows the skip button, or skips on its own. */
  const checkSegments = useCallback((posMs: number, durMs: number, playing: boolean, list: Segment[]) => {
    const seg = list.find(s => inSegment(s, posMs, durMs)) ?? null;
    if (seg?.kind != "CREDITS" && countdownTimer.current) cancelCountdown();
    if (!seg) {
      setActive(null);
      return;
    }
    if (seg.kind == "CREDITS") {
      // a film's credits lead nowhere; a show's lead to the next episode
      if (!nextRef()) {
        setActive(null);
        return;
      }
      setActive(seg);
      if (autoSkipRef.current && !done.current.has(seg) && !countdownTimer.current && playing) {
        let left = NEXT_COUNTDOWN;
        setCountdown(left);
        countdownTimer.current = setInterval(() => {
          if (videoRef.current?.paused) {
            cancelCountdown();
            return;
          }
          left--;
          setCountdown(left);
          if (left <= 0) {
            cancelCountdown();
            done.current.add(seg);
            nextEpisode();
          }
        }, 1000);
      }
      return;
    }
    if (autoSkipRef.current && !done.current.has(seg)) {
      done.current.add(seg);
      seekTo(segmentEnd(seg, durMs) / 1000);
      setActive(null);
      setSkipped(seg);
      return;
    }
    setActive(seg);
  }, [cancelCountdown, nextEpisode, nextRef, seekTo]);

  // native playback: duration known -> ask the databases; then watch the position
  const v = videoRef.current;
  const durationMs = v && isFinite(v.duration) ? v.duration * 1000 : 0;
  useEffect(() => {
    if (phase != "playing" || !item || durationMs <= 0) return;
    const key = `${item.stream.url}:${introMark?.join(",")}:${hintKey(item.season, item.episode)}`;
    if (segmentsFor.current == key) return;
    segmentsFor.current = key;
    void loadSegments(durationMs, item.season, item.episode, item.stream.segments);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [phase, item, durationMs, loadSegments, introMark, pageHint]);

  // site player: the page's own video position
  useEffect(() => api.player.onSiteTime((t, d) => setSitePos({ t, d })), []);
  useEffect(() => {
    if (phase != "site" || !sitePos) return;
    const key = `site:${Math.round(sitePos.d)}:${introMark?.join(",")}:${hintKey(null, null)}`;
    if (segmentsFor.current != key) {
      segmentsFor.current = key;
      done.current.clear();
      void loadSegments(sitePos.d * 1000, null, null, []);
    }
    checkSegments(sitePos.t * 1000, sitePos.d * 1000, true, segments);
  }, [phase, sitePos, segments, introMark, loadSegments, checkSegments]);

  const skipActive = useCallback(() => {
    const seg = active;
    if (!seg) return;
    done.current.add(seg);
    setActive(null);
    if (seg.kind == "CREDITS" && nextRef()) {
      cancelCountdown();
      nextEpisode();
    } else {
      const d = phaseRef.current == "site" ? (sitePos?.d ?? 0) * 1000 : (videoRef.current?.duration ?? 0) * 1000;
      seekTo(segmentEnd(seg, d) / 1000);
    }
  }, [active, cancelCountdown, nextEpisode, nextRef, seekTo, sitePos]);

  const currentPos = () => (phaseRef.current == "site" ? sitePos?.t ?? 0 : videoRef.current?.currentTime ?? 0);

  const markIntro = () => {
    const pos = Math.floor(currentPos());
    if (markStart == null || pos <= markStart + 3) {
      setMarkStart(pos);
      return;
    }
    const mark: [number, number] = [markStart, pos];
    setMarkStart(null);
    setIntroMark(mark);
    void api.settings.setIntroMark(movie.id, mark);
  };

  const clearMark = () => {
    setIntroMark(null);
    setMarkStart(null);
    void api.settings.setIntroMark(movie.id, null);
  };

  // ---- video events ------------------------------------------------------------------------------

  useEffect(() => {
    const el = video();
    const onTime = () => {
      rerender();
      const it = ctx.current.item;
      if (!isFinite(el.duration) || el.duration <= 0) return;
      checkSegments(el.currentTime * 1000, el.duration * 1000, !el.paused, segmentsRef.current);
      // a caught stream: recognised by its duration, then it can resume and save like any other
      if (it && it.key == null && el.duration > 300) {
        const key = `d${Math.round(el.duration)}`;
        const updated = { ...it, key };
        ctx.current.item = updated;
        setItem(updated);
        if (settingsRef.current?.autoResume && el.currentTime < 15) {
          void (async () => {
            for (let delta = -5; delta <= 5; delta++) {
              const saved = await api.library.position(ctx.current.movie.id, `d${Math.round(el.duration) + delta}`);
              if (saved && saved > 30 && saved < el.duration - 90) {
                el.currentTime = saved;
                setResumedAt(saved);
                break;
              }
            }
          })();
        }
      }
    };
    const onEnded = () => {
      saveProgress();
      if (settingsRef.current?.autoNext && nextRef()) nextEpisode();
      else setControls(true);
    };
    const events: [string, () => void][] = [["timeupdate", onTime], ["play", rerender], ["pause", rerender], ["waiting", rerender], ["playing", rerender], ["progress", rerender], ["volumechange", rerender], ["ratechange", rerender], ["ended", onEnded], ["loadedmetadata", rerender]];
    events.forEach(([n, f]) => el.addEventListener(n, f));
    return () => events.forEach(([n, f]) => el.removeEventListener(n, f));
  }, [checkSegments, nextEpisode, nextRef, rerender, saveProgress]);
  const segmentsRef = useRef(segments);
  segmentsRef.current = segments;

  // volume follows the setting
  useEffect(() => {
    const el = video();
    if (settings) {
      el.volume = settings.volume;
      el.muted = settings.muted;
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // site player: shown over the player area by the main process
  useEffect(() => {
    if (phase != "site") {
      void api.player.hideSite();
      return;
    }
    const el = siteArea.current;
    if (!el) return;
    const bounds = () => {
      const r = el.getBoundingClientRect();
      return { x: Math.round(r.left), y: Math.round(r.top), width: Math.round(r.width), height: Math.round(r.height) };
    };
    void api.player.showSite(movie, bounds(), sitePage);
    const ro = new ResizeObserver(() => void api.player.siteBounds(bounds()));
    ro.observe(el);
    return () => {
      ro.disconnect();
      void api.player.hideSite();
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [phase, sitePage]);

  // the page's stream, caught once the viewer pressed play there: the app's player takes over
  useEffect(() => api.player.onCaught((stream, name, hint) => {
    if (phaseRef.current != "site") return;
    const page = { ...sitePageRef.current, catching: false };
    caughtFrom.current = page;
    setPageHint(shownHint(hint));
    setResult(r => (r ? { ...r, playable: { kind: "single", sourceName: name, stream } } : r));
    void playItem({ stream, key: null, season: null, episode: null, source: name });
  }), [playItem]);
  const sitePageRef = useRef(sitePage);
  sitePageRef.current = sitePage;

  // panels that would sit under the site's native view hide it while open
  useEffect(() => {
    if (phase == "site" && siteArea.current) {
      const r = siteArea.current.getBoundingClientRect();
      void api.player.siteBounds(panel ? { x: 0, y: 0, width: 0, height: 0 } : { x: Math.round(r.left), y: Math.round(r.top), width: Math.round(r.width), height: Math.round(r.height) });
    }
  }, [panel, phase]);

  // ---- controls ----------------------------------------------------------------------------------

  const hideTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const poke = useCallback(() => {
    setControls(true);
    if (hideTimer.current) clearTimeout(hideTimer.current);
    hideTimer.current = setTimeout(() => {
      if (videoRef.current && !videoRef.current.paused && !panelRef.current) setControls(false);
    }, 3000);
  }, []);
  const panelRef = useRef(panel);
  panelRef.current = panel;

  const toggle = useCallback(() => {
    const el = videoRef.current;
    if (!el || phaseRef.current != "playing") return;
    if (el.ended) el.currentTime = 0;
    if (el.paused) void el.play();
    else el.pause();
    poke();
  }, [poke]);

  const seekBy = useCallback((sec: number) => {
    const el = videoRef.current;
    if (!el || !isFinite(el.duration)) return;
    el.currentTime = Math.max(0, Math.min(el.duration - 1, el.currentTime + sec));
    poke();
  }, [poke]);

  const setVolume = useCallback((value: number, muted = false) => {
    const el = videoRef.current;
    if (!el) return;
    el.volume = Math.max(0, Math.min(1, value));
    el.muted = muted;
    void setSetting({ volume: el.volume, muted });
  }, []);

  const toggleFullscreen = useCallback(() => {
    if (document.fullscreenElement) void document.exitFullscreen();
    else void root.current?.requestFullscreen();
  }, []);

  const toggleAutoSkip = useCallback(() => void setSetting({ autoSkip: !autoSkipRef.current }), []);

  const close = useCallback(() => {
    saveProgress();
    closePlayer();
  }, [saveProgress]);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if ((e.target as HTMLElement)?.tagName == "INPUT" && (e.target as HTMLInputElement).type == "text") return;
      const el = videoRef.current;
      switch (e.key) {
        case " ":
        case "k":
        case "л":
          e.preventDefault();
          toggle();
          break;
        case "ArrowLeft":
        case "j":
          e.preventDefault();
          seekBy(e.shiftKey ? -30 : -10);
          break;
        case "ArrowRight":
        case "l":
          e.preventDefault();
          seekBy(e.shiftKey ? 30 : 10);
          break;
        case "ArrowUp":
          e.preventDefault();
          if (el) setVolume(el.volume + 0.05);
          poke();
          break;
        case "ArrowDown":
          e.preventDefault();
          if (el) setVolume(el.volume - 0.05);
          poke();
          break;
        case "m":
        case "ь":
          if (el) setVolume(el.volume, !el.muted);
          break;
        case "f":
        case "а":
          toggleFullscreen();
          break;
        case "n":
        case "т":
          nextEpisode();
          break;
        case "s":
        case "ы":
          skipActive();
          break;
        case "Escape":
          if (panelRef.current) setPanel(null);
          else if (document.fullscreenElement) void document.exitFullscreen();
          else close();
          break;
      }
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [close, nextEpisode, poke, seekBy, setVolume, skipActive, toggle, toggleFullscreen]);

  useEffect(() => {
    if (!skipped) return;
    const t = setTimeout(() => setSkipped(s => (s == skipped ? null : s)), 6000);
    return () => clearTimeout(t);
  }, [skipped]);

  useEffect(() => {
    if (resumedAt == null) return;
    const t = setTimeout(() => setResumedAt(null), 7000);
    return () => clearTimeout(t);
  }, [resumedAt]);

  // ---- render --------------------------------------------------------------------------------------

  const e = engine.current;
  const audio: Track[] = e?.audioTracks() ?? [];
  const qualities: Track[] = e?.qualities() ?? [];
  const texts: Track[] = e?.texts() ?? [];
  const el = videoRef.current;
  const pos = el?.currentTime ?? 0;
  const dur = el && isFinite(el.duration) ? el.duration : 0;
  const playing = !!el && !el.paused && !el.ended;
  const buffering = phase == "playing" && !!el && el.readyState < 3 && !el.paused;
  const subtitle = item ? [item.source, item.season != null && item.episode != null ? `${item.season} сезон · ${item.episode} серия` : null].filter(Boolean).join(" · ") : null;
  const hasNext = nextRef() != null;
  const showChrome = controls || !playing || panel != null || phase != "playing";

  return (
    <div ref={root} className={`player ${showChrome ? "" : "idle"}`} onMouseMove={poke} data-phase={phase}>
      <video ref={videoRef} className="player-video" playsInline onClick={toggle} onDoubleClick={toggleFullscreen} style={{ visibility: phase == "playing" ? "visible" : "hidden" }} />

      {phase == "resolving" && (
        <div className="player-state">
          <Halftone className="player-art" variant="glyph" cell={6} seed={movie.id % 991} />
          <div className="player-state-title">{movie.title}</div>
          <div className="mono player-state-status">{status}</div>
          <div className="player-state-actions">
            <button type="button" className="btn" onClick={() => setPanel(panel == "log" ? null : "log")}><Icon name="scroll-text" size={14} /> Журнал</button>
            <button type="button" className="btn" onClick={() => void choose(SOURCE.SITE_PLAYER)}><Icon name="monitor-play" size={14} /> Плеер сайта</button>
          </div>
        </div>
      )}

      {phase == "error" && (
        <div className="player-state">
          <Halftone className="player-art" variant="glyph" cell={6} seed={7} />
          <div className="player-state-title">Не удалось запустить видео</div>
          <div className="mono player-state-status">{error}</div>
          <div className="player-state-actions">
            <button type="button" className="btn primary" onClick={openSources}><Icon name="settings-2" size={14} /> Другой источник</button>
            <button type="button" className="btn" onClick={() => void choose(SOURCE.SITE_PLAYER)}><Icon name="monitor-play" size={14} /> Плеер сайта</button>
            <button type="button" className="btn" onClick={() => setPanel("log")}><Icon name="scroll-text" size={14} /> Журнал</button>
          </div>
        </div>
      )}

      {phase == "site" && <div className="player-site" ref={siteArea} />}

      {buffering && <div className="player-buffering"><Spinner size={40} /></div>}

      <div className={`player-top ${showChrome ? "" : "hidden"}`}>
        <button type="button" className="p-icon" title="Закрыть (Esc)" onClick={close}><Icon name="arrow-left" size={20} /></button>
        <div className="player-titles">
          <div className="player-title truncate">{movie.title}</div>
          <div className="mono player-subtitle truncate">{phase == "site" ? sitePage.name : subtitle ?? " "}</div>
        </div>
        {series && <PillBtn icon="list-video" label="Серии" on={panel == "episodes"} onClick={() => setPanel(panel == "episodes" ? null : "episodes")} />}
        {result && <PillBtn icon="settings-2" label="Источник" on={panel == "sources"} onClick={openSources} />}
        <button type="button" className="p-icon" title="Журнал" onClick={() => setPanel(panel == "log" ? null : "log")}><Icon name="scroll-text" size={18} /></button>
        {phase == "playing" && <button type="button" className="p-icon" title="Картинка в картинке" onClick={() => void video().requestPictureInPicture().catch(() => {})}><Icon name="picture-in-picture-2" size={18} /></button>}
        <button type="button" className="p-icon" title="Во весь экран (F)" onClick={toggleFullscreen}><Icon name={fullscreen ? "minimize" : "maximize"} size={18} /></button>
      </div>

      {phase == "playing" && !playing && !buffering && (
        <button type="button" className="player-center" aria-label="Играть" onClick={toggle}><Icon name="play" size={34} filled /></button>
      )}

      {phase == "playing" && (
        <div className={`player-bottom ${showChrome ? "" : "hidden"}`}>
          <SeekBar pos={pos} dur={dur} buffered={el} segments={segments} onSeek={t => { if (el) el.currentTime = t; }} />
          <div className="player-row">
            <button type="button" className="p-icon" title={playing ? "Пауза (пробел)" : "Играть (пробел)"} onClick={toggle}><Icon name={playing ? "pause" : "play"} size={20} filled /></button>
            <button type="button" className="p-icon" title="−10 с (←)" onClick={() => seekBy(-10)}><Icon name="rotate-ccw" size={18} /></button>
            <button type="button" className="p-icon" title="+10 с (→)" onClick={() => seekBy(10)}><Icon name="rotate-cw" size={18} /></button>
            {hasNext && <button type="button" className="p-icon" title="Следующая серия (N)" onClick={nextEpisode}><Icon name="skip-forward" size={18} filled /></button>}
            <Volume el={el} onChange={setVolume} />
            <span className="mono player-time">{clock(pos)} / {clock(dur)}</span>
            <span className="spacer" />
            {audio.length > 1 && <PillBtn icon="mic" label={audio.find(t => t.selected)?.label.slice(0, 26) ?? "Озвучка"} on={panel == "voice"} onClick={() => setPanel(panel == "voice" ? null : "voice")} />}
            {qualities.length > 1 && <PillBtn icon="gauge" label={qualities.find(t => t.selected)?.label ?? "Качество"} on={panel == "quality"} onClick={() => setPanel(panel == "quality" ? null : "quality")} />}
            {texts.length > 1 && <PillBtn icon="captions" label={texts.find(t => t.selected && t.key != "off") ? "Субтитры вкл." : "Субтитры"} on={panel == "subs"} onClick={() => setPanel(panel == "subs" ? null : "subs")} />}
            <PillBtn icon="fast-forward" label="Автопропуск" primary={autoSkip} onClick={toggleAutoSkip} title="Пропускать заставки автоматически" />
            <PillBtn icon="timer" label={segments.some(s => s.kind == "INTRO") ? "Заставка" : "Заставка ?"} on={panel == "skip"} onClick={() => setPanel(panel == "skip" ? null : "skip")} />
            <PillBtn icon="clock" label={`${el?.playbackRate ?? 1}×`} on={panel == "speed"} onClick={() => setPanel(panel == "speed" ? null : "speed")} />
          </div>
        </div>
      )}

      {phase == "site" && (
        <div className="player-site-bar">
          {sitePage.catching
            ? <span className="mono"><Spinner size={12} /> Если видео не запускается само, нажмите ▶ в плеере — приложение подхватит поток</span>
            : <span className="mono muted">Плеер сайта: реклама и переходы на чужие сайты заблокированы</span>}
          <span className="spacer" />
          <PillBtn icon="fast-forward" label="Автопропуск" primary={autoSkip} onClick={toggleAutoSkip} />
          <PillBtn icon="timer" label="Заставка" on={panel == "skip"} onClick={() => setPanel(panel == "skip" ? null : "skip")} />
        </div>
      )}

      {resumedAt != null && phase == "playing" && (
        <div className="player-toast">
          <span className="mono">Продолжаем с {clock(resumedAt)}</span>
          <button type="button" className="btn sm" onClick={() => { if (el) el.currentTime = 0; setResumedAt(null); }}>С начала</button>
        </div>
      )}

      {skipped && (
        <div className="player-toast">
          <span className="mono">{SEGMENT_TITLE[skipped.kind]} пропущен{skipped.kind == "INTRO" ? "а" : ""}</span>
          <button type="button" className="btn sm" onClick={() => { seekTo(skipped.startMs / 1000); setSkipped(null); }}>Вернуть</button>
        </div>
      )}

      {active && panel == null && (
        <div className={`player-skip ${phase == "site" ? "site" : ""}`}>
          {countdown != null && <button type="button" className="btn" onClick={() => { done.current.add(active); cancelCountdown(); }}>Отмена</button>}
          <button type="button" className="btn primary lg" onClick={skipActive}>
            <Icon name="skip-forward" size={15} filled />
            {active.kind == "CREDITS" ? (countdown != null ? `Следующая серия через ${countdown}` : "Следующая серия") : SKIP_LABEL[active.kind]}
          </button>
        </div>
      )}

      {panel && (
        <aside className="player-panel" onMouseMove={ev => ev.stopPropagation()}>
          <div className="player-panel-head">
            <span className="caps">{({ sources: "Источник", episodes: "Серии", voice: "Озвучка", quality: "Качество", subs: "Субтитры", speed: "Скорость", skip: "Пропуск заставок", log: "Журнал" } as const)[panel]}</span>
            <button type="button" className="p-icon sm" onClick={() => setPanel(null)}><Icon name="x" size={16} /></button>
          </div>
          <div className="player-panel-body scroll">
            {panel == "sources" && result && (
              <>
                <p className="panel-note">AniLiberty, AnimeVost и Collaps играют прямо в приложении; остальные — поток, пойманный со страницы плеера.</p>
                {result.choices.map(c => <Option key={c.key} title={c.title} subtitle={c.subtitle} selected={c.key == result.choice} onClick={() => void choose(c.key)} />)}
              </>
            )}
            {panel == "episodes" && series && <Episodes series={series} item={item} onPick={(s, ep) => { setPanel(null); playEpisode(series, s, ep); }} />}
            {panel == "voice" && audio.map(t => <Option key={t.key} title={t.label} subtitle={t.detail ? `${t.detail} каналов` : undefined} selected={t.selected} onClick={() => { const label = e?.setAudio(t.key); if (label) void api.library.setVoice(movie.id, label); setPanel(null); }} />)}
            {panel == "quality" && qualities.map(t => <Option key={t.key} title={t.label} subtitle={t.key == AUTO ? t.detail ?? "Подбирается под скорость сети" : t.detail} selected={t.selected} onClick={() => { e?.setQuality(t.key); setPanel(null); }} />)}
            {panel == "subs" && texts.map(t => <Option key={t.key} title={t.label} selected={t.selected} onClick={() => { e?.setText(t.key); setPanel(null); }} />)}
            {panel == "speed" && SPEEDS.map(s => <Option key={s} title={`${s}×`} subtitle={s == 1 ? "Обычная" : undefined} selected={(el?.playbackRate ?? 1) == s} onClick={() => { if (el) el.playbackRate = s; setPanel(null); }} />)}
            {panel == "skip" && (
              <SkipPanel autoSkip={autoSkip} segments={segments} introMark={introMark} markStart={markStart} position={currentPos()}
                onToggle={toggleAutoSkip} onMark={markIntro} onClear={clearMark} />
            )}
            {panel == "log" && <div className="player-log mono">{log.map((l, i) => <div key={i}>{l}</div>)}</div>}
          </div>
        </aside>
      )}
    </div>
  );
}

function PillBtn({ icon, label, on, primary, onClick, title }: { icon: IconName; label: string; on?: boolean; primary?: boolean; onClick: () => void; title?: string }) {
  return (
    <button type="button" className={`p-pill ${on ? "on" : ""} ${primary ? "primary" : ""}`} title={title ?? label} onClick={onClick}>
      <Icon name={icon} size={15} />
      <span className="truncate">{label}</span>
    </button>
  );
}

function Option({ title, subtitle, selected, onClick }: { title: string; subtitle?: string; selected?: boolean; onClick: () => void }) {
  return (
    <button type="button" className={`p-option ${selected ? "selected" : ""}`} onClick={onClick}>
      <span className="text">
        <span className="title">{title}</span>
        {subtitle && <span className="mono sub">{subtitle}</span>}
      </span>
      {selected && <Icon name="check" size={16} />}
    </button>
  );
}

function SeekBar({ pos, dur, buffered, segments, onSeek }: { pos: number; dur: number; buffered: HTMLVideoElement | null; segments: Segment[]; onSeek: (t: number) => void }) {
  const bar = useRef<HTMLDivElement | null>(null);
  const [hover, setHover] = useState<number | null>(null);
  const [drag, setDrag] = useState<number | null>(null);
  const at = (clientX: number) => {
    const r = bar.current!.getBoundingClientRect();
    return Math.max(0, Math.min(1, (clientX - r.left) / r.width)) * dur;
  };
  let bufferedEnd = 0;
  if (buffered && buffered.buffered.length) {
    for (let i = 0; i < buffered.buffered.length; i++) if (buffered.buffered.start(i) <= pos + 1) bufferedEnd = Math.max(bufferedEnd, buffered.buffered.end(i));
  }
  const shown = drag ?? pos;
  const pct = (t: number) => (dur > 0 ? `${(100 * t) / dur}%` : "0%");
  return (
    <div
      ref={bar}
      className="seekbar"
      onMouseMove={e => setHover(at(e.clientX))}
      onMouseLeave={() => setHover(null)}
      onMouseDown={e => {
        if (dur <= 0) return;
        setDrag(at(e.clientX));
        const move = (ev: MouseEvent) => setDrag(at(ev.clientX));
        const up = (ev: MouseEvent) => {
          onSeek(at(ev.clientX));
          setDrag(null);
          window.removeEventListener("mousemove", move);
          window.removeEventListener("mouseup", up);
        };
        window.addEventListener("mousemove", move);
        window.addEventListener("mouseup", up);
      }}
    >
      <div className="seek-track">
        <div className="seek-buffered" style={{ width: pct(bufferedEnd) }} />
        {segments.map((s, i) => (
          <div key={i} className={`seek-segment ${s.kind.toLowerCase()}`} title={SEGMENT_TITLE[s.kind]} style={{ left: pct(s.startMs / 1000), width: pct((segmentEnd(s, dur * 1000) - s.startMs) / 1000) }} />
        ))}
        <div className="seek-progress" style={{ width: pct(shown) }} />
      </div>
      <div className="seek-thumb" style={{ left: pct(shown) }} />
      {hover != null && <div className="seek-hover mono" style={{ left: pct(hover) }}>{clock(hover)}</div>}
    </div>
  );
}

function Volume({ el, onChange }: { el: HTMLVideoElement | null; onChange: (v: number, muted?: boolean) => void }) {
  const muted = !el || el.muted || el.volume == 0;
  const value = el ? (el.muted ? 0 : el.volume) : 1;
  return (
    <div className="volume">
      <button type="button" className="p-icon" title="Звук (M)" onClick={() => el && onChange(el.volume || 1, !el.muted)}>
        <Icon name={muted ? "volume-x" : value < 0.5 ? "volume-1" : "volume-2"} size={18} />
      </button>
      <input type="range" min={0} max={1} step={0.01} value={value} aria-label="Громкость" onChange={e => onChange(Number(e.target.value), false)} style={{ "--fill": `${value * 100}%` } as React.CSSProperties} />
    </div>
  );
}

function Episodes({ series, item, onPick }: { series: Extract<Playable, { kind: "series" }>; item: Item | null; onPick: (season: number, episode: number) => void }) {
  const [season, setSeason] = useState(item?.season ?? series.seasons[0]?.number);
  const list = useMemo(() => series.seasons.find(s => s.number == season)?.episodes ?? [], [series, season]);
  return (
    <>
      {series.seasons.length > 1 && (
        <div className="season-chips">
          {series.seasons.map(s => <button key={s.number} type="button" className={`chip ${s.number == season ? "on" : ""}`} onClick={() => setSeason(s.number)}>{s.number} сезон</button>)}
        </div>
      )}
      {list.map(ep => (
        <Option key={ep.number} title={`${ep.number} серия`} subtitle={ep.title ?? undefined} selected={item?.season == ep.season && item?.episode == ep.number} onClick={() => onPick(ep.season, ep.number)} />
      ))}
    </>
  );
}

function SkipPanel({ autoSkip, segments, introMark, markStart, position, onToggle, onMark, onClear }: {
  autoSkip: boolean; segments: Segment[]; introMark: [number, number] | null; markStart: number | null; position: number;
  onToggle: () => void; onMark: () => void; onClear: () => void;
}) {
  return (
    <>
      <p className="panel-note">Разметка берётся у источника и из открытых баз TheIntroDB, IntroDB и AniSkip. Если её нет — отметьте заставку сами: она будет пропускаться во всех сериях.</p>
      <Option title="Пропускать автоматически" subtitle="Заставки и пересказы — сразу, на титрах — следующая серия" selected={autoSkip} onClick={onToggle} />
      <div className="caps" style={{ margin: "14px 4px 8px" }}>Эта серия</div>
      {segments.length == 0 && <div className="muted" style={{ padding: "0 4px 8px" }}>Разметки пока нет</div>}
      {segments.map((s, i) => (
        <div key={i} className="segment-row">
          <span>{SEGMENT_TITLE[s.kind]}</span>
          <span className="mono">{clock(s.startMs / 1000)} – {s.endMs != null ? clock(s.endMs / 1000) : "конец"}</span>
          <span className="mono muted">{s.source}</span>
        </div>
      ))}
      <div style={{ height: 10 }} />
      <Option title={markStart == null ? "Здесь начинается заставка" : "Здесь заканчивается заставка"} subtitle={markStart == null ? `Отметить сейчас — ${clock(position)}` : `Начало ${clock(markStart)}, конец — ${clock(position)}`} onClick={onMark} />
      {introMark && <Option title="Удалить мою разметку" subtitle={`${clock(introMark[0])} – ${clock(introMark[1])}, для всех серий`} onClick={onClear} />}
    </>
  );
}
