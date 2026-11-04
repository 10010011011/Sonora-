import { Capacitor, registerPlugin, type PluginListenerHandle } from "@capacitor/core";
import type { Lyrics, Settings, Track } from "./store";
import { kvGet } from "./db";

/* ------------------------------------------------------------------ plugin surface */

export interface NativeTrackRecord {
  id: string;
  key: string;
  fileName: string;
  mime: string;
  size: number;
  lastModified: number;
  path: string;
  title: string;
  artist: string;
  album: string;
  duration?: number;
  coverName?: string;
  rgTrack?: number;
  rgAlbum?: number;
  embeddedLyrics?: string;
  tagsDone?: boolean;
  sidecarFile?: string;
  sidecarName?: string;
  sidecarSize?: number;
  sidecarLastModified?: number;
  fp?: string;
  srcIds?: string[];
  storage?: "copy" | "tree";
  treeUri?: string;
  addedAt?: number;
  playCount?: number;
  lastPlayedAt?: number;
  edited?: boolean;
}

export interface ImportFailure {
  name: string;
  reason: string;
  /** Present for files that came from the picker or a folder, so "Retry" can try them again. */
  uri?: string;
}

export interface ImportResult {
  added: NativeTrackRecord[];
  updated: NativeTrackRecord[];
  /** Same audio as an existing track under a new name: the track (and its id) was kept, only its name changed. */
  rematched: NativeTrackRecord[];
  skipped: number;
  unmatchedLyrics: number;
  failed: ImportFailure[];
  cancelled: boolean;
  /** Folder scans: tracks dropped because their file is gone. */
  removed?: number;
  /** Folder scans: false when the folder could not be listed completely. */
  complete?: boolean;
}

export interface FolderInfo {
  uri: string;
  name: string;
  addedAt: number;
  lastScanAt?: number;
  count: number;
  /** false when Android no longer lets Sonora read this folder (access revoked, card removed). */
  accessible: boolean;
}

export interface AudioOutputDevice {
  id: string;
  label: string;
  key: string;
}

export interface SessionUpdate {
  playing: boolean;
  title: string;
  artist: string;
  album: string;
  coverName: string;
  durationMs: number;
  positionMs: number;
  speed: number;
  hasPrev: boolean;
  hasNext: boolean;
}

export type MediaCommand = {
  action: "play" | "pause" | "next" | "previous" | "stop" | "seekTo" | "seekBy";
  positionMs?: number;
};
export type FocusEvent = { type: "loss" | "lossTransient" | "duck" | "gain" };

interface SonoraPlayerPlugin {
  pickAndImport(): Promise<ImportResult>;
  retryImport(o: { uris: string[] }): Promise<ImportResult>;
  cancelImport(): Promise<void>;
  pickFolder(): Promise<ImportResult>;
  rescanFolders(): Promise<ImportResult>;
  listFolders(): Promise<{ folders: FolderInfo[] }>;
  removeFolder(o: { uri: string }): Promise<{ removed: number }>;
  consumeOpenIntent(): Promise<{ record: NativeTrackRecord | null }>;
  saveUserData(o: { json: string }): Promise<void>;
  loadUserData(): Promise<{ json: string | null }>;
  saveTextFile(o: { name: string; text: string }): Promise<void>;
  pickTextFile(): Promise<{ text: string }>;
  scanLegacyFiles(): Promise<{ added: NativeTrackRecord[]; updated: NativeTrackRecord[]; skipped: number; failed: { name: string; reason: string }[] }>;
  migrationBegin(o: { id: string; name: string; size: number; lastModified: number; mime: string }): Promise<{ token?: string; skip?: boolean }>;
  migrationChunk(o: { token: string; data: string }): Promise<void>;
  migrationEnd(o: { token: string }): Promise<{ record: NativeTrackRecord | null }>;
  migrationAbort(o: { token: string }): Promise<void>;
  loadLibrary(): Promise<{ tracks: NativeTrackRecord[] }>;
  patchTracks(o: { patches: Partial<NativeTrackRecord>[] }): Promise<void>;
  removeTracks(o: { ids: string[] }): Promise<void>;
  getStats(): Promise<{ tracks: number; bytes: number; freeBytes: number }>;
  saveState(o: { json: string }): Promise<void>;
  loadState(): Promise<{ json: string | null }>;
  updateSession(o: SessionUpdate): Promise<void>;
  getAudioOutputs(): Promise<{ outs: AudioOutputDevice[]; supported: boolean }>;
  requestFocus(): Promise<{ granted: boolean }>;
  requestNotificationPermission(): Promise<{ granted: boolean }>;
  setBackHandled(o: { enabled: boolean }): Promise<void>;
  moveToBackground(): Promise<void>;
  setAppIcon(o: { name: string }): Promise<void>;
  addListener(event: "mediaCommand", cb: (e: MediaCommand) => void): Promise<PluginListenerHandle>;
  addListener(event: "audioFocus", cb: (e: FocusEvent) => void): Promise<PluginListenerHandle>;
  addListener(event: "becomingNoisy" | "backButton" | "openIntent" | "audioDevicesChanged", cb: () => void): Promise<PluginListenerHandle>;
  addListener(event: "importProgress", cb: (e: { done: number; total: number; name: string }) => void): Promise<PluginListenerHandle>;
  addListener(event: "serviceError", cb: (e: { message: string }) => void): Promise<PluginListenerHandle>;
}

export const SonoraPlayer = registerPlugin<SonoraPlayerPlugin>("SonoraPlayer");
export const isNative = () => Capacitor.isNativePlatform();

/** Launcher icon id per theme (matches the Icon* activity-aliases in the manifest). Other themes use the default icon. */
export const THEME_ICON: Record<string, string> = {
  dark: "general",
  light: "general",
  system: "general",
  vinyl: "general",
  miku: "miku",
  teto: "teto",
  neru: "neru",
};

export async function applyAppIcon(name: string): Promise<void> {
  if (!Capacitor.isNativePlatform()) return;
  try {
    await SonoraPlayer.setAppIcon({ name });
  } catch (error) {
    console.warn("setAppIcon failed", error);
  }
}

/* ------------------------------------------------------------------ records <-> tracks */

const origin = () => (typeof location !== "undefined" ? location.origin : "https://localhost");

export function coverNameFromUrl(url?: string): string {
  const m = url?.match(/\/_sonora_cover_\/([0-9a-f]{16}\.jpg)$/);
  return m ? m[1] : "";
}

export function trackFromRecord(r: NativeTrackRecord): Track {
  const o = origin();
  return {
    id: r.id,
    url: `${o}/_sonora_media_/${r.key}`,
    fileName: r.fileName,
    size: r.size,
    mime: r.mime,
    lastModified: r.lastModified,
    path: r.path || r.fileName,
    title: r.title || r.fileName,
    artist: r.artist || "Unknown artist",
    album: r.album || "Local audio",
    duration: r.duration,
    cover: r.coverName ? `${o}/_sonora_cover_/${r.coverName}` : undefined,
    thumb: r.coverName ? `${o}/_sonora_thumb_/${r.coverName}` : undefined,
    addedAt: r.addedAt,
    playCount: r.playCount,
    lastPlayedAt: r.lastPlayedAt,
    fp: r.fp,
    storage: r.storage === "tree" ? "tree" : "copy",
    edited: r.edited,
    rgTrack: r.rgTrack,
    rgAlbum: r.rgAlbum,
    embeddedLyrics: r.embeddedLyrics,
    tagsDone: r.tagsDone,
    sidecar: r.sidecarFile
      ? {
          url: `${o}/_sonora_lyrics_/${r.sidecarFile}`,
          name: r.sidecarName || "lyrics.lrc",
          size: r.sidecarSize ?? 0,
          lastModified: r.sidecarLastModified ?? 0,
        }
      : undefined,
  };
}

/* ------------------------------------------------------------------ library */

export async function restoreNativeTracks(): Promise<Track[]> {
  if (!isNative()) return [];
  const { tracks } = await SonoraPlayer.loadLibrary();
  return tracks.map(trackFromRecord);
}

type Progress = (done: number, total: number, name: string) => void;

/** Runs one native import-style call with progress events; a cancelled picker resolves to null. */
async function withProgress(run: () => Promise<ImportResult>, onProgress?: Progress): Promise<ImportResult | null> {
  const handle = onProgress
    ? await SonoraPlayer.addListener("importProgress", (e) => onProgress(e.done, e.total, e.name))
    : null;
  try {
    const r = await run();
    return { ...r, rematched: r.rematched ?? [] };
  } catch (error) {
    if ((error as { code?: string })?.code === "CANCELLED") return null;
    throw error;
  } finally {
    await handle?.remove();
  }
}

/** Opens the Android picker, copies only tracks that are new, and returns metadata. */
export const importNative = (onProgress?: Progress) => withProgress(() => SonoraPlayer.pickAndImport(), onProgress);

/** Tries files that failed earlier again (same picker access). */
export const retryNative = (uris: string[], onProgress?: Progress) =>
  withProgress(() => SonoraPlayer.retryImport({ uris }), onProgress);

/** Asks for a folder once, then indexes it in place (no copy). */
export const importFolderNative = (onProgress?: Progress) => withProgress(() => SonoraPlayer.pickFolder(), onProgress);

/** Incremental rescan of every granted folder: unchanged files are not read at all. */
export const rescanFoldersNative = (onProgress?: Progress) => withProgress(() => SonoraPlayer.rescanFolders(), onProgress);

export async function listFolders(): Promise<FolderInfo[]> {
  if (!isNative()) return [];
  return (await SonoraPlayer.listFolders()).folders;
}

export async function removeFolder(uri: string) {
  if (!isNative()) return 0;
  return (await SonoraPlayer.removeFolder({ uri })).removed;
}

/** Saves small metadata changes (ReplayGain, embedded lyrics). Never rewrites audio. */
export async function patchNativeTracks(patches: Partial<NativeTrackRecord>[]) {
  if (!isNative() || !patches.length) return;
  await SonoraPlayer.patchTracks({ patches });
}

export async function removeNativeTracks(ids: string[]) {
  if (!isNative() || !ids.length) return;
  await SonoraPlayer.removeTracks({ ids });
}

export async function getLibraryStats() {
  if (!isNative()) return null;
  return SonoraPlayer.getStats();
}

/* ------------------------------------------------------------------ app state (settings, lyric edits, queue) */

export interface PersistedAppState {
  settings?: Partial<Settings>;
  lyrics?: Record<string, Lyrics>;
  queueIds?: string[];
  currentTrackId?: string | null;
}

let stateQueue: Promise<void> = Promise.resolve();

export async function persistNativeAppState(
  settings: Partial<Settings>,
  lyrics: Record<string, Lyrics>,
  playback?: { queueIds?: string[]; currentTrackId?: string | null },
) {
  if (!isNative()) return;
  const json = JSON.stringify({ version: 2, settings, lyrics, ...playback });
  const job = stateQueue.then(() => SonoraPlayer.saveState({ json }));
  stateQueue = job.catch(() => {});
  await job;
}

export async function restoreNativeAppState(): Promise<PersistedAppState | null> {
  if (!isNative()) return null;
  try {
    const { json } = await SonoraPlayer.loadState();
    if (json) return JSON.parse(json) as PersistedAppState;
  } catch {
    // fall through to the older IndexedDB mirror
  }
  return (await kvGet<PersistedAppState>("native-app-state")) ?? null;
}
