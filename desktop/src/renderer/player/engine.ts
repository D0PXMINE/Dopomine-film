import Hls, { ErrorData } from "hls.js";
import { MediaPlayer, type MediaPlayerClass, type MediaInfo } from "dashjs";
import type { Stream, Variant } from "../../shared/types";

export interface Track {
  key: string;
  label: string;
  detail?: string;
  selected: boolean;
}

export const AUTO = "auto";
export const OFF = "off";

/** "01. Дубляж (RUS)" -> "Дубляж (RUS)" */
const cleanName = (name: string) => name.replace(/^\s*\d{1,3}[.)]\s*/, "").trim();

/** Wide films are 1920×800: name the quality by width, the way players usually do. */
function qualityName(width: number, height: number): string {
  const w = Math.max(width, Math.round((height * 16) / 9));
  if (w >= 3800) return "2160p";
  if (w >= 2500) return "1440p";
  if (w >= 1900) return "1080p";
  if (w >= 1260) return "720p";
  if (w >= 840) return "540p";
  if (w >= 700) return "480p";
  return height > 0 ? `${height}p` : "Видео";
}

/** Several renditions can share a name ("Оригинал. Комментарии" ×4): number the repeats. */
function numbered(labels: string[]): string[] {
  const seen = new Map<string, number>();
  return labels.map(l => {
    const n = (seen.get(l) ?? 0) + 1;
    seen.set(l, n);
    return n > 1 ? `${l} · ${n}` : l;
  });
}

export type EngineError = { message: string; network: boolean };

/** Collaps names renditions it no longer offers "delete"; they stay out of the menu. */
const removed = (name: string | undefined) => (name ?? "").trim().toLowerCase() == "delete";

/**
 * The video element plus hls.js (adaptive HLS) or dash.js (DASH: Collaps up to 1080p), with
 * voice-overs, qualities and subtitles, or a plain file. Streams with one file per quality
 * (AniLiberty, AnimeVost) switch files in place.
 */
export class Engine {
  private hls: Hls | null = null;
  private dash: MediaPlayerClass | null = null;
  private stream: Stream | null = null;
  private networkRetries = 0;
  private mediaRecovered = false;
  preferredVoice: string | null = null;
  preferredQuality: string | null = null;
  onChange: () => void = () => {};
  onError: (e: EngineError) => void = () => {};

  constructor(readonly video: HTMLVideoElement) {
    video.crossOrigin = "anonymous";
  }

  get current() {
    return this.stream;
  }

  load(input: Stream, startSec = 0, autoplay = true) {
    this.destroy();
    const variant = input.variants.find(v => v.label == this.preferredQuality);
    const stream = variant ? { ...input, url: variant.url, format: variant.url.includes(".m3u8") ? "HLS" as const : "PROGRESSIVE" as const } : input;
    this.stream = stream;
    this.networkRetries = 0;
    this.mediaRecovered = false;
    const video = this.video;
    video.querySelectorAll("track").forEach(t => t.remove());
    for (const sub of stream.subtitles) {
      const track = document.createElement("track");
      track.kind = "subtitles";
      track.label = sub.label;
      if (sub.language) track.srclang = sub.language;
      track.src = sub.url;
      video.appendChild(track);
    }

    if (stream.format == "DASH") {
      this.loadDash(stream, startSec, autoplay);
      return;
    }
    if (stream.format == "HLS" && Hls.isSupported()) {
      const hls = new Hls({
        enableWorker: true,
        startPosition: startSec > 0 ? startSec : -1,
        maxBufferLength: 60,
        backBufferLength: 90,
        fragLoadingMaxRetry: 4,
        manifestLoadingMaxRetry: 2,
        levelLoadingMaxRetry: 3,
      });
      this.hls = hls;
      hls.on(Hls.Events.MANIFEST_PARSED, () => {
        this.applyPreferredVoice();
        if (autoplay) void video.play().catch(() => {});
        this.onChange();
      });
      for (const ev of [Hls.Events.AUDIO_TRACKS_UPDATED, Hls.Events.AUDIO_TRACK_SWITCHED, Hls.Events.LEVEL_SWITCHED, Hls.Events.LEVELS_UPDATED, Hls.Events.SUBTITLE_TRACKS_UPDATED, Hls.Events.SUBTITLE_TRACK_SWITCH]) {
        hls.on(ev, () => this.onChange());
      }
      hls.on(Hls.Events.ERROR, (_e, data) => this.handleHlsError(data));
      hls.loadSource(stream.url);
      hls.attachMedia(video);
    } else {
      video.src = stream.url;
      const start = () => {
        if (startSec > 0) video.currentTime = startSec;
        if (autoplay) void video.play().catch(() => {});
        this.onChange();
      };
      video.addEventListener("loadedmetadata", start, { once: true });
      video.onerror = () => {
        const code = video.error?.code;
        this.onError({ message: code == 2 ? "Нет соединения с источником" : code == 4 ? "Источник отдал видео в непонятном формате" : "Видео не воспроизводится", network: code == 2 });
      };
    }
  }

  private loadDash(stream: Stream, startSec: number, autoplay: boolean) {
    const dash = MediaPlayer().create();
    this.dash = dash;
    dash.updateSettings({
      debug: { logLevel: 0 },
      streaming: { buffer: { fastSwitchEnabled: true }, abr: { autoSwitchBitrate: { video: true } } },
    });
    const events = MediaPlayer.events;
    dash.on(events.STREAM_INITIALIZED, () => {
      this.applyPreferredVoice();
      this.onChange();
    });
    for (const ev of [events.QUALITY_CHANGE_RENDERED, events.TRACK_CHANGE_RENDERED, events.PLAYBACK_METADATA_LOADED]) dash.on(ev, () => this.onChange());
    dash.on(events.ERROR, (e: { error?: { code?: number; message?: string } | string }) => {
      const err = typeof e.error == "object" ? e.error : { message: e.error };
      // 25: download error of a manifest or segment (network or refused)
      const network = err?.code != null && err.code >= 25 && err.code <= 28;
      this.onError({ message: network ? "Нет соединения с источником" : `Видео не воспроизводится (${err?.message ?? "DASH"})`, network });
    });
    dash.initialize(this.video, stream.url, autoplay, startSec > 0 ? startSec : undefined);
  }

  private handleHlsError(data: ErrorData) {
    if (!data.fatal || !this.hls) return;
    if (data.type == Hls.ErrorTypes.NETWORK_ERROR && this.networkRetries < 2) {
      // a dropped connection is not the source's fault: retry in place before giving up on it
      this.networkRetries++;
      setTimeout(() => this.hls?.startLoad(), 1500 * this.networkRetries);
      return;
    }
    if (data.type == Hls.ErrorTypes.MEDIA_ERROR && !this.mediaRecovered) {
      this.mediaRecovered = true;
      this.hls.recoverMediaError();
      return;
    }
    const status = (data.response as { code?: number } | undefined)?.code;
    this.onError({
      message: status ? `Источник отказал в доступе к видео (HTTP ${status})` : data.type == Hls.ErrorTypes.NETWORK_ERROR ? "Нет соединения с источником" : `Видео не воспроизводится (${data.details})`,
      network: data.type == Hls.ErrorTypes.NETWORK_ERROR,
    });
  }

  destroy() {
    this.hls?.destroy();
    this.hls = null;
    if (this.dash) {
      try { this.dash.destroy(); } catch { /* already torn down */ }
      this.dash = null;
    }
    this.video.onerror = null;
    this.video.removeAttribute("src");
    this.video.load();
  }

  // ---- voice-overs ----------------------------------------------------------------------------

  private dashAudio(): MediaInfo[] {
    try {
      return this.dash?.getTracksFor("audio") ?? [];
    } catch {
      return [];
    }
  }

  audioTracks(): Track[] {
    const names = this.stream?.audioNames ?? [];
    const keepAll = names.length > 0 && names.every(removed);
    const hls = this.hls;
    if (hls) {
      if (hls.audioTracks.length < 2) return [];
      const labels = numbered(hls.audioTracks.map((t, i) => cleanName(names[i] || t.name || t.lang || `Дорожка ${i + 1}`)));
      return hls.audioTracks
        .map((t, i) => ({ key: String(i), label: labels[i], detail: t.channels && Number(t.channels) > 2 ? `${t.channels}` : undefined, selected: hls.audioTrack == i }))
        .filter((_, i) => keepAll || !removed(names[i]));
    }
    const tracks = this.dashAudio();
    if (tracks.length < 2 || !this.dash) return [];
    const current = this.dash.getCurrentTrackFor("audio");
    const labels = numbered(tracks.map((t, i) => cleanName(names[i] || t.labels?.[0]?.text || t.lang || `Дорожка ${i + 1}`)));
    return tracks
      .map((t, i) => ({ key: String(i), label: labels[i], selected: current != null && (current.id == t.id && current.index == t.index) }))
      .filter((_, i) => keepAll || !removed(names[i]));
  }

  setAudio(key: string): string | null {
    const track = this.audioTracks().find(t => t.key == key);
    if (this.hls) this.hls.audioTrack = Number(key);
    else if (this.dash) {
      const info = this.dashAudio()[Number(key)];
      if (!info) return null;
      this.dash.setCurrentTrack(info);
    } else return null;
    if (track) this.preferredVoice = track.label;
    return track?.label ?? null;
  }

  /** Keeps the voice-over the viewer picked when the next episode starts (and skips removed ones). */
  private applyPreferredVoice() {
    const tracks = this.audioTracks();
    const wanted = (this.preferredVoice && tracks.find(t => t.label == this.preferredVoice)) || (tracks.some(t => t.selected) ? null : tracks[0]);
    if (wanted && !wanted.selected) this.setAudio(wanted.key);
  }

  // ---- quality --------------------------------------------------------------------------------

  qualities(): Track[] {
    const s = this.stream;
    if (s && s.variants.length > 1) return s.variants.map((v, i) => ({ key: `v:${i}`, label: v.label, selected: v.url == s.url }));
    const dash = this.dash;
    if (dash) {
      let reps: { id: string; width: number; height: number; bandwidth: number }[] = [];
      try { reps = dash.getRepresentationsByType("video"); } catch { /* not ready */ }
      if (reps.length < 2) return [];
      const auto = dash.getSettings().streaming?.abr?.autoSwitchBitrate?.video !== false;
      const current = dash.getCurrentRepresentationForType("video");
      const seen = new Set<string>();
      const levels = reps
        .map(r => ({ id: r.id, label: qualityName(r.width, r.height), height: r.height, bitrate: r.bandwidth }))
        .sort((a, b) => b.height - a.height)
        .filter(l => !seen.has(l.label) && !!seen.add(l.label));
      return [
        { key: AUTO, label: "Авто", detail: auto && current ? qualityName(current.width, current.height) : undefined, selected: auto },
        ...levels.map(l => ({ key: `d:${l.id}`, label: l.label, detail: l.bitrate ? `${(l.bitrate / 1e6).toFixed(1)} Мбит/с` : undefined, selected: !auto && current?.id == l.id })),
      ];
    }
    const hls = this.hls;
    if (!hls || hls.levels.length < 2) return [];
    const auto = hls.autoLevelEnabled;
    const seen = new Set<string>();
    const levels = hls.levels
      .map((l, i) => ({ i, label: qualityName(l.width, l.height), height: l.height, bitrate: l.bitrate }))
      .sort((a, b) => b.height - a.height)
      .filter(l => !seen.has(l.label) && !!seen.add(l.label));
    return [
      { key: AUTO, label: "Авто", detail: auto && hls.currentLevel >= 0 ? qualityName(hls.levels[hls.currentLevel].width, hls.levels[hls.currentLevel].height) : undefined, selected: auto },
      ...levels.map(l => ({ key: `l:${l.i}`, label: l.label, detail: l.bitrate ? `${(l.bitrate / 1e6).toFixed(1)} Мбит/с` : undefined, selected: !auto && hls.currentLevel == l.i })),
    ];
  }

  setQuality(key: string) {
    const s = this.stream;
    if (key.startsWith("v:") && s) {
      const v: Variant | undefined = s.variants[Number(key.slice(2))];
      if (!v) return;
      this.preferredQuality = v.label;
      if (v.url != s.url) {
        const at = this.video.currentTime;
        const playing = !this.video.paused;
        this.load({ ...s, url: v.url }, at, playing);
      }
      return;
    }
    if (this.dash) {
      const auto = key == AUTO;
      this.dash.updateSettings({ streaming: { abr: { autoSwitchBitrate: { video: auto } } } });
      if (!auto) this.dash.setRepresentationForTypeById("video", key.slice(2), true);
      this.onChange();
      return;
    }
    if (!this.hls) return;
    this.hls.currentLevel = key == AUTO ? -1 : Number(key.slice(2));
    this.onChange();
  }

  // ---- subtitles --------------------------------------------------------------------------------

  texts(): Track[] {
    const out: Track[] = [];
    const hls = this.hls;
    hls?.subtitleTracks.forEach((t, i) => out.push({ key: `h:${i}`, label: cleanName(t.name || t.lang || `Субтитры ${i + 1}`), selected: hls.subtitleTrack == i && hls.subtitleDisplay }));
    const external = [...this.video.textTracks].filter(t => t.kind == "subtitles" && !(hls && t.label && hls.subtitleTracks.some(h => h.name == t.label)));
    external.forEach((t, i) => out.push({ key: `t:${i}`, label: t.label || `Субтитры ${i + 1}`, selected: t.mode == "showing" }));
    if (out.length == 0) return [];
    return [{ key: OFF, label: "Выключены", selected: !out.some(t => t.selected) }, ...out];
  }

  setText(key: string) {
    const hls = this.hls;
    const external = [...this.video.textTracks].filter(t => t.kind == "subtitles" && !(hls && t.label && hls.subtitleTracks.some(h => h.name == t.label)));
    external.forEach(t => (t.mode = "disabled"));
    if (hls) {
      hls.subtitleTrack = key.startsWith("h:") ? Number(key.slice(2)) : -1;
      hls.subtitleDisplay = key.startsWith("h:");
    }
    if (key.startsWith("t:")) {
      const t = external[Number(key.slice(2))];
      if (t) t.mode = "showing";
    }
    this.onChange();
  }
}
