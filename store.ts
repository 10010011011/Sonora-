import { useSyncExternalStore } from "react";

export type Store<T> = {
  get: () => T;
  set: (p: Partial<T> | ((s: T) => Partial<T>)) => void;
  sub: (l: () => void) => () => void;
};

export function createStore<T extends object>(init: T, persistKey?: string): Store<T> {
  let s = init;
  if (persistKey && typeof localStorage !== "undefined") {
    try {
      const raw = localStorage.getItem(persistKey);
      if (raw) s = { ...init, ...JSON.parse(raw) };
    } catch {}
  }
  const ls = new Set<() => void>();
  let saveT: number | undefined;
  return {
    get: () => s,
    set: (p) => {
      const patch = typeof p === "function" ? p(s) : p;
      let changed = false;
      for (const k of Object.keys(patch) as (keyof T)[]) {
        if (!Object.is(s[k], patch[k])) {
          changed = true;
          break;
        }
      }
      if (!changed) return;
      s = { ...s, ...patch };
      ls.forEach((l) => l());
      if (persistKey && typeof localStorage !== "undefined") {
        clearTimeout(saveT);
        saveT = window.setTimeout(() => {
          try {
            localStorage.setItem(persistKey, JSON.stringify(s));
          } catch {}
        }, 200);
      }
    },
    sub: (l) => {
      ls.add(l);
      return () => ls.delete(l);
    },
  };
}

export function useStore<T extends object, U>(store: Store<T>, sel: (s: T) => U): U {
  return useSyncExternalStore(store.sub, () => sel(store.get()));
}

/* ---------------- Types ---------------- */

/** A lyric sidecar (.lrc/.srt/.vtt) that lives next to a track. Fetch `url` to read the text. */
export interface Sidecar {
  url: string;
  name: string;
  size: number;
  lastModified: number;
}

/**
 * Library entry. This is metadata plus URLs only: audio bytes are never held in JavaScript.
 * On Android `url`/`cover`/`sidecar.url` point at the native media server; on the web they are blob: URLs.
 */
export interface Track {
  id: string;
  url: string;
  fileName: string;
  size: number;
  mime: string;
  lastModified: number;
  path: string;
  title: string;
  artist: string;
  album: string;
  duration?: number;
  cover?: string;
  rgTrack?: number;
  rgAlbum?: number;
  embeddedLyrics?: string;
  sidecar?: Sidecar;
  tagsDone?: boolean;
  subtitleTag?: string;
  /** Small (128 px) cover for list rows; falls back to `cover`. */
  thumb?: string;
  /** Epoch ms the track entered the library. */
  addedAt?: number;
  playCount?: number;
  lastPlayedAt?: number;
  /** Content fingerprint (size + hash of first/last 64 KB): how a renamed file is recognised. */
  fp?: string;
  /** "tree" = audio stays in a folder the user granted; otherwise Sonora holds its own copy. */
  storage?: "copy" | "tree";
  /** Title/artist/album were edited by the user (kept in the index only, never written into the audio file). */
  edited?: boolean;
}

export interface LyricWord {
  /** Absolute playback time in seconds at which this word begins. */
  t: number;
  /** Text spoken/sung from this timestamp until the next word. */
  text: string;
}

export interface LyricLine {
  t: number | null;
  text: string;
  /** Present only when the source provides real word-level timestamps (enhanced LRC/WebVTT). */
  words?: LyricWord[];
}

export interface Lyrics {
  lines: LyricLine[];
  offset: number; // seconds, live preview offset (display = t + offset)
  source?: string;
}

export interface SoundProfile {
  id: string;
  name: string;
  eq: number[];
  preampDb: number;
  bassDb: number;
  midsDb: number;
  trebleDb: number;
  width: number;
  crossfeed: number;
}

export type ThemeName = "dark" | "light" | "system" | "miku" | "teto" | "neru" | "vinyl";
export type StereoMode = "normal" | "mono" | "karaoke" | "vocal";

export const EQ_FREQS = [31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000];

export const WIDGET_IDS = ["now", "lyrics", "viz", "next", "quick", "sleep", "loop"] as const;
export type WidgetId = (typeof WIDGET_IDS)[number];

export interface Settings {
  theme: ThemeName;
  /** When enabled, the Android launcher icon follows the selected theme. */
  themeIcon: boolean;
  volume: number;
  muted: boolean;
  boostDb: number;
  limiter: boolean;
  eqOn: boolean;
  eq: number[];
  preampDb: number;
  autoPreamp: boolean;
  bassDb: number;
  midsDb: number;
  trebleDb: number;
  stereoMode: StereoMode;
  width: number;
  crossfeed: number;
  orbit: boolean;
  orbitSpeed: number;
  room: number;
  rgMode: "off" | "track" | "album";
  rgPreamp: number;
  rgAnalyze: boolean;
  gapless: boolean;
  crossfade: number;
  /** Smoothly ramp gain on play and pause to avoid clicks/harsh cuts (0 = instant). */
  playPauseFadeMs: number;
  /** Automatically pause playback when volume slider is brought to 0. */
  pauseOnVolume0: boolean;
  /** Show remaining track time (-02:14) instead of total duration in the seekbar. */
  showRemainingTime: boolean;
  /** Render interactive waveform bars inside the Now Playing seekbar. */
  waveformSeekbar: boolean;
  /** Seek step in seconds for keyboard arrows / media skip buttons. */
  seekStepSec: number;
  /** Repeat current track N times before advancing (1 = normal). */
  repeatCountTarget: number;
  speed: number;
  keepPitch: boolean;
  tapLatency: number; // ms
  profiles: SoundProfile[];
  deviceMap: Record<string, string>;
  autoResume: boolean;
  pauseOnDisconnect: boolean;
  widgets: { id: WidgetId; on: boolean }[];
  showRoomWidgets: boolean;
  floatingMini: boolean;
  colormap: "magma" | "viridis" | "gray" | "miku" | "teto" | "neru";
  specLog: boolean;
  fftSize: number;
  shuffle: boolean;
  repeat: "off" | "all" | "one";
  likedIds: string[];
  /** Simple hides the Sound lab and Visual analyzer; Pro shows everything. */
  uiMode: "simple" | "pro";
  /** Tracks at least this long (seconds) remember where you stopped. The last track always does. */
  resumeMinSec: number;
  librarySort: SortKey;
}

export type SortKey = "title" | "artist" | "album" | "recent" | "played" | "duration" | "manual";

export const defaultSettings: Settings = {
  theme: "dark",
  themeIcon: false,
  volume: 0.8,
  muted: false,
  boostDb: 0,
  limiter: true,
  eqOn: true,
  eq: [0, 0, 0, 0, 0, 0, 0, 0, 0, 0],
  preampDb: 0,
  autoPreamp: true,
  bassDb: 0,
  midsDb: 0,
  trebleDb: 0,
  stereoMode: "normal",
  width: 1,
  crossfeed: 0,
  orbit: false,
  orbitSpeed: 0.12,
  room: 0,
  rgMode: "track",
  rgPreamp: 0,
  rgAnalyze: true,
  gapless: true,
  crossfade: 0,
  playPauseFadeMs: 220,
  pauseOnVolume0: true,
  showRemainingTime: false,
  waveformSeekbar: true,
  seekStepSec: 5,
  repeatCountTarget: 1,
  speed: 1,
  keepPitch: true,
  tapLatency: 100,
  profiles: [],
  deviceMap: {},
  autoResume: true,
  pauseOnDisconnect: true,
  widgets: WIDGET_IDS.map((id) => ({ id, on: id !== "now" && id !== "next" })),
  showRoomWidgets: false,
  floatingMini: false,
  colormap: "miku",
  specLog: false,
  fftSize: 2048,
  shuffle: false,
  repeat: "all",
  likedIds: [],
  uiMode: "pro",
  resumeMinSec: 600,
  librarySort: "title",
};

export const settings = createStore<Settings>(defaultSettings, "sonora.settings.v1");

export function toggleLikeTrack(id: string) {
  const cur = settings.get().likedIds;
  const liked = cur.includes(id);
  const next = liked ? cur.filter((x) => x !== id) : [...cur, id];
  settings.set({ likedIds: next });
  return !liked;
}

/**
 * Maps Sonora's 3-band Mids slider (-12..+12 dB) into a smooth bump across
 * the 500 Hz, 1 kHz, and 2 kHz bands of the 10-band EQ.
 */
export function applyMidsToEq(eq: number[], midsDb: number): number[] {
  const next = [...eq];
  next[4] = Math.round(midsDb * 0.75 * 2) / 2; // 500 Hz
  next[5] = midsDb;                            // 1 kHz
  next[6] = Math.round(midsDb * 0.75 * 2) / 2; // 2 kHz
  return next;
}

/**
 * Parses an AutoEQ / Parametric / GraphicEQ text preset into Sonora's 10-band EQ + preamp.
 */
export function parseAutoEqText(raw: string): { eq: number[]; preampDb: number; bassDb: number; midsDb: number; trebleDb: number } | null {
  const lines = raw.split(/\r?\n/);
  let preamp = 0;
  const filters: { f: number; g: number; q: number; type: "PK" | "LS" | "HS" }[] = [];

  for (const line of lines) {
    const preMatch = line.match(/Preamp\s*:\s*([+-]?\d+(?:\.\d+)?)\s*dB/i);
    if (preMatch) {
      preamp = parseFloat(preMatch[1]) || 0;
      continue;
    }
    const fMatch = line.match(/Filter\s+\d+\s*:\s*ON\s+(PK|PEQ|LS|LSC|HS|HSC)\s+Fc\s+(\d+(?:\.\d+)?)\s*Hz\s+Gain\s+([+-]?\d+(?:\.\d+)?)\s*dB(?:\s+Q\s+(\d+(?:\.\d+)?))?/i);
    if (fMatch) {
      const tRaw = fMatch[1].toUpperCase();
      const type = tRaw.startsWith("L") ? "LS" : tRaw.startsWith("H") ? "HS" : "PK";
      filters.push({
        type,
        f: parseFloat(fMatch[2]),
        g: parseFloat(fMatch[3]),
        q: fMatch[4] ? parseFloat(fMatch[4]) : 1.0,
      });
      continue;
    }
    if (/GraphicEQ\s*:/i.test(line)) {
      const body = line.replace(/^.*GraphicEQ\s*:/i, "");
      for (const pair of body.split(";")) {
        const parts = pair.trim().split(/\s+/);
        if (parts.length >= 2) {
          const f = parseFloat(parts[0]);
          const g = parseFloat(parts[1]);
          if (isFinite(f) && isFinite(g)) filters.push({ type: "PK", f, g, q: 1.4 });
        }
      }
    }
  }

  if (!filters.length) return null;

  const eq = EQ_FREQS.map((fc) => {
    let sum = 0;
    for (const flt of filters) {
      if (flt.type === "LS") {
        const ratio = fc <= flt.f ? 1 : Math.max(0, 1 - Math.log2(fc / flt.f) * 0.7);
        sum += flt.g * ratio;
      } else if (flt.type === "HS") {
        const ratio = fc >= flt.f ? 1 : Math.max(0, 1 - Math.log2(flt.f / fc) * 0.7);
        sum += flt.g * ratio;
      } else {
        const oct = Math.abs(Math.log2(fc / Math.max(1, flt.f)));
        const bw = 1 / Math.max(0.25, flt.q);
        const w = Math.exp(-(oct * oct) / (0.36 * bw * bw));
        sum += flt.g * w;
      }
    }
    return Math.max(-12, Math.min(12, Math.round(sum * 2) / 2));
  });

  const bassDb = Math.round(((eq[0] + eq[1]) / 2) * 2) / 2;
  const midsDb = Math.round(((eq[4] + eq[5] + eq[6]) / 3) * 2) / 2;
  const trebleDb = Math.round(((eq[8] + eq[9]) / 2) * 2) / 2;
  return {
    eq,
    preampDb: Math.max(-15, Math.min(6, Math.round(preamp * 2) / 2)),
    bassDb,
    midsDb,
    trebleDb,
  };
}

export type LibraryFilter = "all" | "liked" | "mostPlayed" | "rediscover" | "withLyrics";

export interface LibState {
  tracks: Track[];
  queue: number[]; // indices into tracks
  /** The queue's order before shuffling (null = plain library order). Used to un-shuffle. */
  orderBase: number[] | null;
  qpos: number; // position in queue
  /** Offset from qpos for consecutive "Play next" insertions so multiple queued tracks stay in order. */
  insertCursor: number;
  loading: boolean;
  folderName: string;
  filter: LibraryFilter;
}

export const library = createStore<LibState>({
  tracks: [],
  queue: [],
  orderBase: null,
  qpos: -1,
  insertCursor: 0,
  loading: false,
  folderName: "Sonora Sessions",
  filter: "all",
});

export interface PlayerState {
  playing: boolean;
  duration: number;
  trackId: string | null;
  abA: number | null;
  abB: number | null;
  sleepAt: number | null; // epoch ms
  sleepEndOfTrack: boolean;
  /** Stop playback after this many tracks finish (0 = off). */
  sleepTracksRemaining: number;
  /** How many times the current track has repeated towards settings.repeatCountTarget. */
  repeatRemaining: number;
  limiting: number; // dB of gain reduction
  clipping: boolean;
  rgApplied: number; // dB
  outputId: string;
  outputLabel: string;
  toast: string;
  lyricsVisible: boolean;
}

export const player = createStore<PlayerState>({
  playing: false,
  duration: 20,
  trackId: null,
  abA: null,
  abB: null,
  sleepAt: null,
  sleepEndOfTrack: false,
  sleepTracksRemaining: 0,
  repeatRemaining: 0,
  limiting: 0,
  clipping: false,
  rgApplied: 0,
  outputId: "default",
  outputLabel: "",
  toast: "",
  lyricsVisible: true,
});

export const clock = createStore<{ t: number }>({ t: 0 });
export const lyricsStore = createStore<{ map: Record<string, Lyrics> }>({ map: {} });

let toastT: number | undefined;
export function toast(msg: string) {
  player.set({ toast: msg });
  clearTimeout(toastT);
  toastT = window.setTimeout(() => player.set({ toast: "" }), 2800);
}

export function currentTrack(): Track | undefined {
  const { tracks, queue, qpos } = library.get();
  return tracks[queue[qpos]] ?? tracks[0];
}

export function fmt(sec: number | null | undefined) {
  if (sec == null || !isFinite(sec)) return "--:--";
  const neg = sec < 0;
  const s = Math.max(0, Math.abs(sec));
  const m = Math.floor(s / 60);
  return `${neg ? "-" : ""}${m}:${String(Math.floor(s % 60)).padStart(2, "0")}`;
}
