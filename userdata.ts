import { createStore, library, settings, type Track } from "./store";
import { SonoraPlayer, isNative } from "./nativeLibrary";

/**
 * Playlists, bookmarks, per-track audio overrides, and resume positions. Small and written often, so it lives
 * apart from the big app-state blob. On Android a native file is the source of truth; localStorage is the fallback and the web store.
 */

export interface Playlist {
  id: string;
  name: string;
  trackIds: string[];
  createdAt: number;
  updatedAt: number;
}

export interface Bookmark {
  id: string;
  trackId: string;
  /** Seconds from the start of the track. */
  t: number;
  label: string;
  createdAt: number;
}

export interface TrackAudioConfig {
  speed?: number;
  keepPitch?: boolean;
  bassDb?: number;
  midsDb?: number;
  trebleDb?: number;
  boostDb?: number;
}

export interface ResumeState {
  /** The track that was playing last and where: restored (paused) at launch, whatever its length. */
  last: { trackId: string; ms: number } | null;
  /** Long tracks (see settings.resumeMinSec) remember their own position. */
  byTrack: Record<string, number>;
}

export interface UserData {
  version: 1;
  playlists: Playlist[];
  bookmarks: Bookmark[];
  resume: ResumeState;
  trackConfigs?: Record<string, TrackAudioConfig>;
  /** Stable track ids in the user's manual all-songs order. */
  libraryOrder: string[];
}

const empty = (): UserData => ({
  version: 1,
  playlists: [],
  bookmarks: [],
  resume: { last: null, byTrack: {} },
  trackConfigs: {},
  libraryOrder: [],
});

export const userdata = createStore<UserData>(empty(), "sonora.userdata.v1");

export const uid = () =>
  typeof crypto !== "undefined" && "randomUUID" in crypto
    ? crypto.randomUUID()
    : `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`;

/** Merges stored JSON over the defaults so older/partial files never break the app. */
export function normalizeUserData(raw: unknown): UserData {
  const base = empty();
  if (!raw || typeof raw !== "object") return base;
  const r = raw as Partial<UserData>;
  return {
    version: 1,
    playlists: Array.isArray(r.playlists) ? r.playlists.filter((p) => p && typeof p.id === "string") : [],
    bookmarks: Array.isArray(r.bookmarks) ? r.bookmarks.filter((b) => b && typeof b.trackId === "string") : [],
    resume: {
      last: r.resume?.last && typeof r.resume.last.trackId === "string" ? r.resume.last : null,
      byTrack: r.resume?.byTrack && typeof r.resume.byTrack === "object" ? { ...r.resume.byTrack } : {},
    },
    trackConfigs: r.trackConfigs && typeof r.trackConfigs === "object" ? { ...r.trackConfigs } : {},
    libraryOrder: Array.isArray(r.libraryOrder)
      ? [...new Set(r.libraryOrder.filter((id): id is string => typeof id === "string"))]
      : [],
  };
}

let started = false;
let saveTimer: number | undefined;
let retryTimer: number | undefined;

/** Playlist and bookmark edits are saved almost at once; resume positions tick along and can wait. */
const SAVE_SOON_MS = 400;
const SAVE_RESUME_MS = 8000;
const SAVE_RETRY_MS = 5000;

/** Loads the native copy (Android) and mirrors every later change back to it. Call before restoring the queue. */
export async function initUserData() {
  if (started) return;
  started = true;
  if (!isNative()) return;
  // Anything changed before the native copy arrives is kept and merged, never overwritten by the older file.
  let editedBeforeLoad = false;
  const markEdited = userdata.sub(() => { editedBeforeLoad = true; });
  try {
    const { json } = await SonoraPlayer.loadUserData();
    if (json) {
      const fromDisk = normalizeUserData(JSON.parse(json));
      userdata.set(editedBeforeLoad ? mergeUserData(userdata.get(), fromDisk) : fromDisk);
    }
  } catch {
    // keep whatever localStorage had
  }
  markEdited();
  let prev = userdata.get();
  userdata.sub(() => {
    const next = userdata.get();
    const onlyResume =
      next.playlists === prev.playlists &&
      next.bookmarks === prev.bookmarks &&
      next.trackConfigs === prev.trackConfigs &&
      next.libraryOrder === prev.libraryOrder;
    prev = next;
    // A pending quick save must not be pushed back by a later resume tick.
    if (onlyResume && saveTimer !== undefined) return;
    window.clearTimeout(saveTimer);
    saveTimer = window.setTimeout(flushUserData, onlyResume ? SAVE_RESUME_MS : SAVE_SOON_MS);
  });
  if (editedBeforeLoad) flushUserData();
}

export function flushUserData() {
  window.clearTimeout(saveTimer);
  saveTimer = undefined;
  if (!isNative()) return;
  void SonoraPlayer.saveUserData({ json: JSON.stringify(userdata.get()) }).then(
    () => window.clearTimeout(retryTimer),
    () => {
      // The write failed (storage busy or full). Try again rather than silently losing playlists.
      window.clearTimeout(retryTimer);
      retryTimer = window.setTimeout(flushUserData, SAVE_RETRY_MS);
    },
  );
}

/* ---------------- playlists & M3U export/import ---------------- */

export function createPlaylist(name: string, trackIds: string[] = []): string {
  const id = uid();
  const now = Date.now();
  const clean = name.trim() || "New playlist";
  userdata.set((s) => ({ playlists: [...s.playlists, { id, name: clean, trackIds: [...new Set(trackIds)], createdAt: now, updatedAt: now }] }));
  return id;
}

const editPlaylist = (id: string, fn: (p: Playlist) => Partial<Playlist>) =>
  userdata.set((s) => ({
    playlists: s.playlists.map((p) => (p.id === id ? { ...p, ...fn(p), updatedAt: Date.now() } : p)),
  }));

export const renamePlaylist = (id: string, name: string) => editPlaylist(id, () => ({ name: name.trim() || "Playlist" }));

export const deletePlaylist = (id: string) => userdata.set((s) => ({ playlists: s.playlists.filter((p) => p.id !== id) }));

/** Adds tracks that are not already in the playlist. @returns how many were added. */
export function addToPlaylist(id: string, trackIds: string[]): number {
  let added = 0;
  editPlaylist(id, (p) => {
    const have = new Set(p.trackIds);
    const fresh = trackIds.filter((t) => !have.has(t) && have.add(t));
    added = fresh.length;
    return { trackIds: [...p.trackIds, ...fresh] };
  });
  return added;
}

export const removeFromPlaylist = (id: string, trackId: string) =>
  editPlaylist(id, (p) => ({ trackIds: p.trackIds.filter((t) => t !== trackId) }));

export const movePlaylistTrack = (id: string, from: number, to: number) =>
  editPlaylist(id, (p) => {
    const ids = [...p.trackIds];
    if (from < 0 || from >= ids.length || to < 0 || to >= ids.length) return {};
    const [x] = ids.splice(from, 1);
    ids.splice(to, 0, x);
    return { trackIds: ids };
  });

/** Library indices for a playlist's tracks, skipping ones no longer in the library. */
export function playlistIndices(p: Playlist, tracks: Track[]): number[] {
  const byId = new Map(tracks.map((t, i) => [t.id, i]));
  return p.trackIds.map((id) => byId.get(id)).filter((i): i is number => i !== undefined);
}

/** Exports a playlist to standard extended M3U8 text. */
export function playlistToM3U(p: Playlist, tracks: Track[]): string {
  const lines = ["#EXTM3U", `#PLAYLIST:${p.name}`];
  const byId = new Map(tracks.map((t) => [t.id, t]));
  for (const id of p.trackIds) {
    const t = byId.get(id);
    if (!t) continue;
    const dur = Math.round(t.duration ?? -1);
    lines.push(`#EXTINF:${dur},${t.artist} - ${t.title}`);
    lines.push(t.path || t.fileName);
  }
  return lines.join("\n");
}

/** Parses an M3U/M3U8 file and matches entries against the current library by path, filename, or title. */
export function importM3UPlaylist(text: string, fallbackName: string, tracks: Track[]): { id: string; name: string; matched: number } {
  const lines = text.split(/\r?\n/).map((l) => l.trim()).filter(Boolean);
  let name = fallbackName.replace(/\.m3u8?$/i, "").trim() || "Imported playlist";
  const matchedIds: string[] = [];

  for (const line of lines) {
    if (line.startsWith("#PLAYLIST:")) {
      const custom = line.slice(10).trim();
      if (custom) name = custom;
      continue;
    }
    if (line.startsWith("#")) continue;
    const norm = line.replace(/\\/g, "/").toLowerCase();
    const base = norm.split("/").pop() ?? norm;
    const baseNoExt = base.replace(/\.[^.]+$/, "");
    const hit = tracks.find(
      (t) =>
        t.path.toLowerCase() === norm ||
        t.fileName.toLowerCase() === base ||
        t.fileName.replace(/\.[^.]+$/, "").toLowerCase() === baseNoExt ||
        `${t.artist} - ${t.title}`.toLowerCase() === baseNoExt ||
        t.title.toLowerCase() === baseNoExt
    );
    if (hit) matchedIds.push(hit.id);
  }

  const id = createPlaylist(name, matchedIds);
  return { id, name, matched: matchedIds.length };
}

/* ---------------- per-track audio config ---------------- */

export function getTrackAudioConfig(trackId: string): TrackAudioConfig | undefined {
  return userdata.get().trackConfigs?.[trackId];
}

export function saveTrackAudioConfig(trackId: string, config: TrackAudioConfig | null) {
  userdata.set((s) => {
    const next = { ...(s.trackConfigs ?? {}) };
    if (!config) delete next[trackId];
    else next[trackId] = config;
    return { trackConfigs: next };
  });
}

/* ---------------- bookmarks ---------------- */

export function addBookmark(trackId: string, t: number, label?: string): Bookmark {
  const b: Bookmark = { id: uid(), trackId, t: Math.max(0, t), label: label?.trim() || "", createdAt: Date.now() };
  userdata.set((s) => ({ bookmarks: [...s.bookmarks, b] }));
  return b;
}

export const removeBookmark = (id: string) => userdata.set((s) => ({ bookmarks: s.bookmarks.filter((b) => b.id !== id) }));

export const renameBookmark = (id: string, label: string) =>
  userdata.set((s) => ({ bookmarks: s.bookmarks.map((b) => (b.id === id ? { ...b, label: label.trim() } : b)) }));

/* ---------------- resume ---------------- */

const END_GUARD_SEC = 10; // within this of the end counts as finished

export function saveResume(trackId: string, seconds: number, durationSec: number) {
  const finished = durationSec > 0 && seconds >= durationSec - END_GUARD_SEC;
  const ms = finished || seconds < 5 ? 0 : Math.round(seconds * 1000);
  const { resume } = userdata.get();
  const long = durationSec >= settings.get().resumeMinSec;
  const byTrack = { ...resume.byTrack };
  if (long && ms > 0) byTrack[trackId] = ms;
  else delete byTrack[trackId];
  if (resume.last?.trackId === trackId && resume.last.ms === ms && byTrack[trackId] === resume.byTrack[trackId]) return;
  userdata.set({ resume: { last: { trackId, ms }, byTrack } });
}

/** Seconds to start a track at (engine hook). */
export function startPositionFor(t: Track, kind: "prime" | "play"): number {
  const { resume } = userdata.get();
  let ms = 0;
  if (kind === "prime" && resume.last?.trackId === t.id) ms = resume.last.ms;
  else if ((t.duration ?? 0) >= settings.get().resumeMinSec) ms = resume.byTrack[t.id] ?? 0;
  const sec = ms / 1000;
  return t.duration && sec >= t.duration - END_GUARD_SEC ? 0 : sec;
}

/* ---------------- backup support ---------------- */

/** Rewrites every track id in the user data (restore after a reinstall, when ids differ but fingerprints match). */
export function remapUserData(data: UserData, idMap: Map<string, string>): UserData {
  const m = (id: string) => idMap.get(id) ?? id;
  const byTrack: Record<string, number> = {};
  for (const [id, ms] of Object.entries(data.resume.byTrack)) byTrack[m(id)] = ms;
  const trackConfigs: Record<string, TrackAudioConfig> = {};
  for (const [id, cfg] of Object.entries(data.trackConfigs ?? {})) trackConfigs[m(id)] = cfg;
  return {
    version: 1,
    playlists: data.playlists.map((p) => ({ ...p, trackIds: [...new Set(p.trackIds.map(m))] })),
    bookmarks: data.bookmarks.map((b) => ({ ...b, trackId: m(b.trackId) })),
    resume: { last: data.resume.last ? { ...data.resume.last, trackId: m(data.resume.last.trackId) } : null, byTrack },
    trackConfigs,
    libraryOrder: [...new Set((data.libraryOrder ?? []).map(m))],
  };
}

/** Merges restored data into what is on this device (never deletes local playlists/bookmarks). */
export function mergeUserData(local: UserData, incoming: UserData): UserData {
  const playlistIds = new Set(local.playlists.map((p) => p.id));
  const bookmarkIds = new Set(local.bookmarks.map((b) => b.id));
  return {
    version: 1,
    playlists: [...local.playlists, ...incoming.playlists.filter((p) => !playlistIds.has(p.id))],
    bookmarks: [...local.bookmarks, ...incoming.bookmarks.filter((b) => !bookmarkIds.has(b.id))],
    resume: {
      last: local.resume.last ?? incoming.resume.last,
      byTrack: { ...incoming.resume.byTrack, ...local.resume.byTrack },
    },
    trackConfigs: { ...(incoming.trackConfigs ?? {}), ...(local.trackConfigs ?? {}) },
    libraryOrder: [...new Set([...(local.libraryOrder ?? []), ...(incoming.libraryOrder ?? [])])],
  };
}

/** Track list lookups used by several screens. */
export const trackById = (id: string): Track | undefined => library.get().tracks.find((t) => t.id === id);
