import { library, settings, lyricsStore, toast, createStore, type Track, type Lyrics, type Sidecar } from "./store";
import { readTags, titleFromName } from "./tags";
import { parseLyrics, toLRC } from "./lyrics";
import { kvGet, kvSet } from "./db";
import { shuffled } from "./shuffle";
import { playNow } from "./queue";
import { engine } from "./engine";
import {
  SonoraPlayer,
  importFolderNative,
  importNative,
  isNative,
  patchNativeTracks,
  removeNativeTracks,
  rescanFoldersNative,
  restoreNativeTracks,
  retryNative,
  trackFromRecord,
  type ImportFailure,
  type ImportResult,
  type NativeTrackRecord,
} from "./nativeLibrary";

/** Android folders: false = copy-only (decided). true brings back "Add folder" (in-place, no copy). */
export const IN_PLACE_FOLDERS = false;

const AUDIO = /\.(mp3|flac|wav|ogg|oga|opus|m4a|aac|webm|weba|mp4|wma|aiff?|alac)$/i;
const LYR = /\.(lrc|srt|vtt|txt)$/i;
const keyOf = (path: string) => path.replace(/\.[^./]+$/, "").toLowerCase();
const HEAD_BYTES = 4 * 1024 * 1024;

/**
 * Web only: the original File objects, kept so tag reading can slice their headers.
 * A File is a handle to browser-managed storage, not audio held in the JS heap.
 * On Android the library never has a File: tracks are played and read through native URLs.
 */
const webFiles = new Map<string, File>();

type WebInput = {
  file: File;
  path: string;
  cover?: string;
  subtitleTag?: string;
  duration?: number;
};

async function readSidecar(s: Sidecar): Promise<string> {
  const res = await fetch(s.url);
  if (!res.ok) throw new Error(`Could not read ${s.name} (HTTP ${res.status})`);
  return res.text();
}

const sidecarFromFile = (f: File): Sidecar => ({
  url: URL.createObjectURL(f),
  name: f.name,
  size: f.size,
  lastModified: f.lastModified,
});

/** Shared by the web importer and the Android importer: merge, keep the queue stable, parse new lyrics. */
async function commitTracks(
  incoming: Track[],
  sidecarPatches: Map<string, Sidecar | undefined>,
  opts: { folderName?: string; replace?: boolean; silent?: boolean; extraToast?: string[]; rematched?: Track[] },
) {
  const { replace = false } = opts;
  const before = library.get();
  const prev = replace ? [] : before.tracks;
  // Same audio under a new name: the track keeps its id (and likes/lyrics/ReplayGain); only names and paths change.
  const renamed = new Map((opts.rematched ?? []).map((t) => [t.id, t]));
  const updatedPrev = prev.map((t) => {
    let next = renamed.has(t.id) ? { ...renamed.get(t.id)!, duration: t.duration ?? renamed.get(t.id)!.duration } : t;
    if (sidecarPatches.has(t.id)) next = { ...next, sidecar: sidecarPatches.get(t.id) };
    return next;
  });
  const seen = new Set(updatedPrev.map((t) => t.id));
  const newTracks = incoming.filter((t) => !seen.has(t.id));
  const tracks = [...updatedPrev, ...newTracks];

  // The queue is "the whole library" until the user builds their own (play next, playlists). Only a whole-library
  // queue grows with imports; a custom queue is left exactly as it is. Either way the current position never shifts.
  const oldQueue = replace ? [] : before.queue;
  const queue =
    oldQueue.length === prev.length
      ? [...oldQueue, ...newTracks.map((_, i) => updatedPrev.length + i)]
      : oldQueue;
  const nextQpos = before.qpos >= 0 && !replace ? before.qpos : tracks.length ? 0 : -1;

  library.set({
    tracks,
    queue,
    qpos: nextQpos,
    loading: false,
    folderName: opts.folderName || before.folderName,
    ...(replace || oldQueue.length !== prev.length ? {} : { orderBase: null }),
  });

  // A matching .lrc/.vtt can arrive after its audio. Parse it now so live lyrics update immediately.
  const lyricImports = [
    ...[...sidecarPatches.keys()].map((id) => tracks.find((t) => t.id === id)),
    ...newTracks.filter((t) => t.sidecar),
  ].filter((t): t is Track => Boolean(t?.sidecar));
  await Promise.all(
    lyricImports.map(async (track) => {
      try {
        const parsed = parseLyrics(await readSidecar(track.sidecar!), track.sidecar!.name);
        if (parsed.lines.length) saveLyrics(track.id, parsed);
      } catch {
        // An unreadable sidecar just means no imported lyrics for this track.
      }
    }),
  );

  if (nextQpos >= 0 && !engine.el.src) engine.primeAt(nextQpos);
  void loadTagsInBackground();
  return { added: newTracks.length, attached: sidecarPatches.size, renamed: renamed.size };
}

/** Web import path (browsers). Android uses importFromDevice(). */
export async function ingest(files: WebInput[], folderName = "", replace = false, silent = false) {
  library.set({ loading: true });
  const audio = files.filter((f) => AUDIO.test(f.file.name) || f.file.type.startsWith("audio/"));
  const side = new Map<string, File>();
  files.filter((f) => LYR.test(f.file.name)).forEach((f) => side.set(keyOf(f.path), f.file));
  audio.sort((a, b) => a.path.localeCompare(b.path, undefined, { numeric: true }));

  const idOf = (f: WebInput) => `${f.file.name}|${f.file.size}|${f.file.lastModified}`;

  // Decide what is new first, so blob URLs are only created for tracks we keep.
  const existing = replace ? [] : library.get().tracks;
  const known = new Set(existing.map((t) => t.id));
  const fresh: Track[] = [];
  for (const f of audio) {
    const id = idOf(f);
    if (known.has(id)) continue;
    known.add(id);
    const n = titleFromName(f.file.name);
    const sc = side.get(keyOf(f.path));
    webFiles.set(id, f.file);
    fresh.push({
      id,
      url: URL.createObjectURL(f.file),
      fileName: f.file.name,
      size: f.file.size,
      mime: f.file.type,
      lastModified: f.file.lastModified,
      path: f.path,
      title: n.title,
      artist: n.artist || "Unknown artist",
      album: f.path.includes("/") ? f.path.split("/").slice(-2, -1)[0] : folderName || "Local audio",
      sidecar: sc ? sidecarFromFile(sc) : undefined,
      cover: f.cover,
      subtitleTag: f.subtitleTag,
      duration: f.duration,
    });
  }

  const patches = new Map<string, Sidecar | undefined>();
  for (const t of existing) {
    const sc = side.get(keyOf(t.path)) || side.get(keyOf(t.fileName));
    if (!sc || (t.sidecar?.size === sc.size && t.sidecar?.lastModified === sc.lastModified)) continue;
    patches.set(t.id, sidecarFromFile(sc));
  }

  const { added, attached } = await commitTracks(fresh, patches, { folderName, replace, silent });
  if (!silent && (added > 0 || attached > 0)) {
    toast(
      [
        added ? `${added} track${added === 1 ? "" : "s"} added` : "",
        attached ? `${attached} lyric file${attached === 1 ? "" : "s"} attached` : "",
        added && side.size ? `${side.size} LRC matched` : "",
      ]
        .filter(Boolean)
        .join(" · "),
    );
  }
}

/* ---------------- Android import (progress sheet, retry, error report) ---------------- */

export interface ImportState {
  phase: "idle" | "running" | "done";
  kind: "files" | "folder" | "rescan" | "retry" | "migration" | "";
  done: number;
  total: number;
  name: string;
  summary: string;
  failed: ImportFailure[];
}

const idleImport: ImportState = { phase: "idle", kind: "", done: 0, total: 0, name: "", summary: "", failed: [] };
export const importState = createStore<ImportState>(idleImport);
export const closeImportSheet = () => importState.set(idleImport);

/** Puts a native import/scan result into the library and returns the one-line summary. */
export async function applyImportResult(result: ImportResult, folderName = "Sonora Library"): Promise<string> {
  const patches = new Map<string, Sidecar | undefined>(result.updated.map((r) => [r.id, trackFromRecord(r).sidecar]));
  const { added, attached, renamed } = await commitTracks(result.added.map(trackFromRecord), patches, {
    folderName,
    rematched: result.rematched.map(trackFromRecord),
  });
  if (result.removed) await reloadFromNative(); // folder scan dropped tracks whose file is gone
  return [
    added ? `${added} track${added === 1 ? "" : "s"} added` : "",
    renamed ? `${renamed} matched to existing track${renamed === 1 ? "" : "s"} (renamed)` : "",
    result.removed ? `${result.removed} removed (file gone)` : "",
    result.skipped ? `${result.skipped} already in library` : "",
    attached ? `${attached} lyric file${attached === 1 ? "" : "s"} attached` : "",
    result.failed.length ? `${result.failed.length} failed` : "",
    result.cancelled ? "cancelled" : "",
  ]
    .filter(Boolean)
    .join(" · ") || "Nothing new";
}

/** Re-reads the native index and swaps it in, keeping the queue and the current track. */
/** Drops a track from the index (and Sonora's own copy, if it has one). A folder's original file is never touched. */
export async function removeTrackFromLibrary(id: string) {
  if (!isNative()) return;
  await removeNativeTracks([id]);
  await reloadFromNative();
}

export async function reloadFromNative() {
  const next = await restoreNativeTracks();
  const { tracks: old, queue, qpos } = library.get();
  const oldById = new Map(old.map((t) => [t.id, t]));
  const merged = next.map((t) => {
    const o = oldById.get(t.id);
    return o ? { ...t, duration: o.duration ?? t.duration } : t;
  });
  const index = new Map(merged.map((t, i) => [t.id, i]));
  const curId = old[queue[qpos]]?.id;
  let nq = queue.map((i) => old[i]?.id).filter((id): id is string => !!id).map((id) => index.get(id)).filter((i): i is number => i !== undefined);
  if (queue.length === old.length) {
    const used = new Set(nq);
    merged.forEach((_, i) => { if (!used.has(i)) nq.push(i); });
  }
  const cur = curId !== undefined ? index.get(curId) : undefined;
  const nextPos = cur !== undefined ? nq.indexOf(cur) : -1;
  library.set({ tracks: merged, queue: nq, qpos: nextPos >= 0 ? nextPos : nq.length ? 0 : -1, orderBase: null });
}

type Runner = (onProgress: (done: number, total: number, name: string) => void) => Promise<ImportResult | null>;

let importBusy = false; // survives the sheet being closed while a long import is still running

async function runNativeImport(kind: ImportState["kind"], runner: Runner, folderName?: string) {
  if (!isNative() || importBusy) return;
  importBusy = true;
  importState.set({ ...idleImport, phase: "running", kind });
  library.set({ loading: true });
  try {
    const result = await runner((done, total, name) => importState.set({ done, total, name }));
    if (!result) return void importState.set(idleImport); // picker closed without a choice
    const summary = await applyImportResult(result, folderName);
    if (result.failed.length) importState.set({ phase: "done", summary, failed: result.failed });
    else {
      importState.set(idleImport);
      toast(summary);
    }
  } catch (error) {
    importState.set({ phase: "done", summary: error instanceof Error ? error.message : "Import failed", failed: [] });
  } finally {
    importBusy = false;
    library.set({ loading: false });
  }
}

/** Adds tracks that native code already put in the index (legacy migration) to the in-memory library. */
export const addNativeTracks = (tracks: Track[]) => commitTracks(tracks, new Map(), { folderName: "Sonora Library" });

/** Android: pick files, copy only the new ones. */
export const importFromDevice = () => runNativeImport("files", importNative);

/** Android: pick a folder once and index it in place (no copy). */
export const importFolderFromDevice = () => runNativeImport("folder", importFolderNative, "Sonora Library");

/** Android: incremental rescan of every granted folder. */
export const rescanFolders = () => runNativeImport("rescan", rescanFoldersNative);

/** Android: try the files that failed again. */
export const retryFailed = (failed: ImportFailure[]) => {
  const uris = failed.map((f) => f.uri).filter((u): u is string => !!u);
  if (uris.length) void runNativeImport("retry", (p) => retryNative(uris, p));
};

/** Plain-text report of an import's failures, for pasting into a bug report. */
export function importErrorReport(failed: ImportFailure[]): string {
  return JSON.stringify({ generatedAt: new Date().toISOString(), failed }, null, 2);
}

/** Android "Open with Sonora": import the opened file (a no-op if known) and play it. */
export async function consumeOpenIntent() {
  if (!isNative()) return;
  try {
    const { record } = await SonoraPlayer.consumeOpenIntent();
    if (!record) return;
    const track = trackFromRecord(record);
    if (!library.get().tracks.some((t) => t.id === track.id)) {
      await commitTracks([track], new Map(), { folderName: "Sonora Library" });
    }
    const idx = library.get().tracks.findIndex((t) => t.id === track.id);
    if (idx >= 0) playNow(idx);
  } catch (error) {
    toast(error instanceof Error ? error.message : "Could not open this file");
  }
}

/** One entry point for every "Add music" button. */
export function addAudioFiles(input?: HTMLInputElement | null) {
  if (isNative()) void importFromDevice();
  else input?.click();
}

/** Edit title/artist/album. Stored in Sonora's index only (the audio file is never modified) and kept by rematch. */
export async function editTags(id: string, fields: { title?: string; artist?: string; album?: string }) {
  const clean: { title?: string; artist?: string; album?: string } = {};
  if (fields.title?.trim()) clean.title = fields.title.trim();
  if (fields.artist !== undefined) clean.artist = fields.artist.trim() || "Unknown artist";
  if (fields.album !== undefined) clean.album = fields.album.trim() || "Local audio";
  library.set((s) => ({ tracks: s.tracks.map((t) => (t.id === id ? { ...t, ...clean, edited: true } : t)) }));
  engine.refreshMeta();
  await patchNativeTracks([{ id, ...clean, edited: true }]).catch(() => toast("Could not save the tag edit"));
}

async function headOf(t: Track): Promise<Blob | undefined> {
  const f = webFiles.get(t.id);
  if (f) return f.slice(0, HEAD_BYTES);
  try {
    const res = await fetch(t.url, { headers: { Range: `bytes=0-${HEAD_BYTES - 1}` } });
    return res.ok ? await res.blob() : undefined;
  } catch {
    return undefined;
  }
}

let tagRun = 0;
export async function loadTagsInBackground() {
  const run = ++tagRun;
  const list = library.get().tracks;
  const native = isNative();
  const pending: Partial<NativeTrackRecord>[] = [];
  const flush = async () => {
    if (!pending.length) return;
    const batch = pending.splice(0, pending.length);
    try {
      await patchNativeTracks(batch);
    } catch {
      // Tag data is re-derivable; a failed save only means we rescan this track next launch.
    }
  };

  let dirty = 0;
  for (let i = 0; i < list.length; i++) {
    if (run !== tagRun) return void (await flush());
    const t = list[i];
    if (t.tagsDone) {
      t.tagsDone = true;
      continue;
    }
    const head = await headOf(t);
    const tg = head ? await readTags(head) : {};
    t.tagsDone = true;
    if (!native) {
      // On Android title/artist/album/cover already come from the native importer.
      if (tg.title) t.title = tg.title;
      if (tg.artist) t.artist = tg.artist;
      if (tg.album) t.album = tg.album;
      if (tg.cover) t.cover = URL.createObjectURL(tg.cover);
    }
    t.rgTrack = tg.rgTrack;
    t.rgAlbum = tg.rgAlbum;
    t.embeddedLyrics = tg.lyrics;
    if (native) {
      pending.push({ id: t.id, tagsDone: true, rgTrack: tg.rgTrack, rgAlbum: tg.rgAlbum, embeddedLyrics: tg.lyrics });
      if (pending.length >= 25) await flush();
    }
    if (++dirty % 12 === 0) library.set({ tracks: [...library.get().tracks] });
    if (i % 4 === 0) await new Promise((r) => setTimeout(r, 0));
  }
  library.set({ tracks: [...library.get().tracks] });
  await flush();
}

export function setShuffle(on: boolean) {
  settings.set({ shuffle: on });
  const { queue, qpos, orderBase } = library.get();
  const cur = queue[qpos] ?? -1;
  let nq: number[];
  if (on) {
    nq = shuffled(queue, cur); // shuffles whatever is queued (a playlist stays a playlist)
  } else {
    // Back to the order the queue was built in (library order when it was never customised).
    const inQueue = new Set(queue);
    const base = (orderBase ?? [...inQueue].sort((a, b) => a - b)).filter((i) => inQueue.has(i));
    const have = new Set(base);
    nq = [...base, ...queue.filter((i) => !have.has(i))];
  }
  library.set({ queue: nq, qpos: cur >= 0 ? nq.indexOf(cur) : -1 });
  engine.requeue();
}

export function playTrackIndex(trackIdx: number) {
  const { queue } = library.get();
  const pos = queue.indexOf(trackIdx);
  if (pos >= 0) engine.playAt(pos);
}

async function walk(dir: FileSystemDirectoryHandle, prefix: string, out: { file: File; path: string }[]) {
  for await (const h of dir.values()) {
    const p = prefix ? `${prefix}/${h.name}` : h.name;
    if (h.kind === "directory") await walk(h as unknown as FileSystemDirectoryHandle, p, out);
    else if (AUDIO.test(h.name) || LYR.test(h.name)) out.push({ file: await (h as any).getFile(), path: p });
  }
}

export async function openFolderHandle(handle?: FileSystemDirectoryHandle) {
  try {
    let dir = handle;
    if (!dir) {
      dir = await window.showDirectoryPicker!({ mode: "read", id: "music" });
      kvSet("dirHandle", dir);
    } else if (dir.queryPermission && (await dir.queryPermission({ mode: "read" })) !== "granted") {
      if ((await dir.requestPermission?.({ mode: "read" })) !== "granted") return;
    }
    library.set({ loading: true });
    const out: { file: File; path: string }[] = [];
    await walk(dir, "", out);
    await ingest(out, dir.name, false);
  } catch (e) {
    library.set({ loading: false });
    if ((e as Error)?.name !== "AbortError") toast("Could not open folder");
  }
}

export const savedFolder = () => kvGet<FileSystemDirectoryHandle>("dirHandle");

/* ---------------- Lyrics resolution ---------------- */
export async function loadLyricsFor(t: Track) {
  const inMemory: Lyrics | undefined = lyricsStore.get().map[t.id];
  const hasWordTiming = (lyrics?: Lyrics) =>
    Boolean(lyrics?.lines.some(line => line.words && line.words.length > 0));
  if (hasWordTiming(inMemory)) return;

  const cached: Lyrics | undefined = await kvGet<Lyrics>("lyr:" + t.id);
  const savedSource = await kvGet<{ text: string; name?: string }>("lyr:source:" + t.id);
  const savedSourceLyrics = savedSource?.text
    ? parseLyrics(savedSource.text, savedSource.name || "saved lyric source")
    : undefined;
  let sidecarLyrics: Lyrics | undefined;
  if (t.sidecar) {
    try {
      sidecarLyrics = parseLyrics(await readSidecar(t.sidecar), t.sidecar.name);
    } catch {
      sidecarLyrics = undefined;
    }
  }
  const embeddedLyrics = t.embeddedLyrics
    ? parseLyrics(t.embeddedLyrics, "embedded tag")
    : undefined;

  // Older Sonora builds discarded enhanced-LRC word timestamps. Upgrade both
  // the IndexedDB cache and an already-restored in-memory snapshot when the
  // retained original source contains real word-level timing.
  const richerSource = hasWordTiming(sidecarLyrics)
    ? sidecarLyrics
    : hasWordTiming(savedSourceLyrics)
      ? savedSourceLyrics
      : hasWordTiming(embeddedLyrics)
        ? embeddedLyrics
        : undefined;
  const needsUpgrade = Boolean(
    richerSource && !hasWordTiming(inMemory) && !hasWordTiming(cached)
  );

  const lyrics = needsUpgrade
    ? richerSource
    : hasWordTiming(cached)
      ? cached
      : inMemory || cached || sidecarLyrics || embeddedLyrics ;
  if (lyrics) {
    if (lyrics !== inMemory) {
      lyricsStore.set(state => ({ map: { ...state.map, [t.id]: lyrics } }));
    }
    // Persist parser upgrades so subsequent opens don't need to reparse the file.
    if (needsUpgrade || (!cached && (lyrics === sidecarLyrics || lyrics === embeddedLyrics))) {
      void kvSet("lyr:" + t.id, lyrics);
    }
  }
}

const saveTimers = new Map<string, number>();
export function saveLyrics(id: string, l: Lyrics) {
  lyricsStore.set((s) => ({ map: { ...s.map, [id]: l } }));
  const previousTimer = saveTimers.get(id);
  if (previousTimer !== undefined) clearTimeout(previousTimer);
  const timer = window.setTimeout(() => {
    saveTimers.delete(id);
    void kvSet("lyr:" + id, l);
    // Keep a text source in step with edits so an old word-timing source
    // cannot later overwrite a user's intentional line or timestamp changes.
    void kvSet("lyr:source:" + id, {
      text: toLRC(l),
      name: l.source || "saved lyrics",
    });
  }, 300);
  saveTimers.set(id, timer);
}
