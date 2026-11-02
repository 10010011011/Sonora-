import { toast } from "./store";
import { SonoraPlayer, isNative, trackFromRecord, type NativeTrackRecord } from "./nativeLibrary";
import type { Track } from "./store";

/**
 * One-time move of the pre-native library (S1-X1) into the native one, so an app update does not make the
 * user's songs vanish. Two sources, both optional:
 *  1. audio files the old build kept in app-private storage ("sonora-library/") — moved natively;
 *  2. Blobs in the old IndexedDB database ("sonora-audio-library") — streamed to native in small chunks,
 *     so a whole file is never held in the JS heap.
 * Records keep the old "name|size|mtime" id, so likes and lyrics keyed by it still point at the right track.
 *
 * The old database's layout is not in this repository any more, so records are recognised by shape (a Blob or
 * File, directly or as a property of an object) rather than by a known schema.
 */

const OLD_DB = "sonora-audio-library";
const DONE_KEY = "sonora.legacyMigrated.v1";
const CHUNK = 768 * 1024;
const AUDIO = /\.(mp3|flac|wav|ogg|oga|opus|m4a|aac|webm|weba|mp4|wma|aiff?|alac)$/i;
const EXT_FOR_MIME: Record<string, string> = {
  "audio/mpeg": "mp3", "audio/flac": "flac", "audio/wav": "wav", "audio/ogg": "ogg", "audio/mp4": "m4a",
  "audio/aac": "aac", "audio/webm": "webm",
};

export interface LegacyItem {
  id: string;
  name: string;
  size: number;
  lastModified: number;
  mime: string;
  blob: Blob;
}

const str = (v: unknown) => (typeof v === "string" && v ? v : undefined);
const num = (v: unknown) => (typeof v === "number" && isFinite(v) ? v : undefined);

function firstBlob(v: unknown): Blob | null {
  if (v instanceof Blob) return v;
  if (v instanceof ArrayBuffer) return new Blob([v]);
  if (ArrayBuffer.isView(v)) return new Blob([v as unknown as BlobPart]);
  if (v && typeof v === "object") {
    for (const x of Object.values(v as Record<string, unknown>)) {
      if (x instanceof Blob) return x;
      if (x instanceof ArrayBuffer) return new Blob([x]);
    }
  }
  return null;
}

/** Turns one stored record into something importable, or null when it is not audio. Exported for tests. */
export function legacyItemFrom(key: IDBValidKey, value: unknown): LegacyItem | null {
  const blob = firstBlob(value);
  if (!blob || blob.size === 0) return null;
  const meta = (value instanceof Blob || !value || typeof value !== "object" ? {} : value) as Record<string, unknown>;
  const file = blob as Partial<File>;
  let name = str(meta.name) ?? str(meta.fileName) ?? str(meta.filename) ?? str(file.name) ?? (typeof key === "string" ? key : "");
  name = name.split("/").pop() ?? name;
  const ext = EXT_FOR_MIME[blob.type];
  if (!AUDIO.test(name)) {
    if (!blob.type.startsWith("audio/") || !name) return null;
    name = `${name}.${ext ?? "mp3"}`;
  }
  const lastModified = num(meta.lastModified) ?? num(meta.mtime) ?? num(meta.modified) ?? num(file.lastModified) ?? 0;
  const size = blob.size;
  const storedId = str(meta.id);
  const id = storedId && /\|\d+\|\d+$/.test(storedId) ? storedId : `${name}|${size}|${lastModified}`;
  return { id, name, size, lastModified, mime: blob.type || str(meta.type) || str(meta.mime) || "", blob };
}

async function openLegacyDb(): Promise<IDBDatabase | null> {
  if (typeof indexedDB === "undefined") return null;
  try {
    const list = await indexedDB.databases?.();
    if (list && !list.some((d) => d.name === OLD_DB)) return null; // never create an empty database just to look
  } catch {
    // databases() unavailable: fall through to open + abort-if-created
  }
  return new Promise((resolve) => {
    const req = indexedDB.open(OLD_DB);
    let created = false;
    req.onupgradeneeded = () => {
      created = true; // it did not exist; undo
      req.transaction?.abort();
    };
    req.onsuccess = () => {
      if (created) { req.result.close(); resolve(null); } else resolve(req.result);
    };
    req.onerror = () => resolve(null);
  });
}

async function readAll(db: IDBDatabase): Promise<LegacyItem[]> {
  const items: LegacyItem[] = [];
  for (const name of Array.from(db.objectStoreNames)) {
    const rows = await new Promise<{ key: IDBValidKey; value: unknown }[]>((resolve) => {
      const out: { key: IDBValidKey; value: unknown }[] = [];
      const req = db.transaction(name, "readonly").objectStore(name).openCursor();
      req.onsuccess = () => {
        const c = req.result;
        if (!c) return resolve(out);
        out.push({ key: c.key, value: c.value }); // Blob handles only; no bytes are read here
        c.continue();
      };
      req.onerror = () => resolve(out);
    });
    for (const r of rows) {
      const item = legacyItemFrom(r.key, r.value);
      if (item) items.push(item);
    }
  }
  const seen = new Set<string>();
  return items.filter((i) => !seen.has(i.id) && seen.add(i.id));
}

const toBase64 = (blob: Blob) =>
  new Promise<string>((resolve, reject) => {
    const fr = new FileReader();
    fr.onload = () => resolve(String(fr.result).split(",", 2)[1] ?? "");
    fr.onerror = () => reject(fr.error);
    fr.readAsDataURL(blob);
  });

async function sendItem(item: LegacyItem): Promise<NativeTrackRecord | null | "skip"> {
  const begin = await SonoraPlayer.migrationBegin({
    id: item.id, name: item.name, size: item.size, lastModified: item.lastModified, mime: item.mime,
  });
  if (begin.skip || !begin.token) return "skip";
  const token = begin.token;
  try {
    for (let at = 0; at < item.size; at += CHUNK) {
      await SonoraPlayer.migrationChunk({ token, data: await toBase64(item.blob.slice(at, at + CHUNK)) });
    }
    return (await SonoraPlayer.migrationEnd({ token })).record;
  } catch (error) {
    await SonoraPlayer.migrationAbort({ token }).catch(() => {});
    throw error;
  }
}

export interface MigrationProgress { done: number; total: number; name: string }

/**
 * @param onTracks called with batches of migrated tracks (so the UI fills in while it runs)
 * @returns how many tracks were moved; 0 when there was nothing to do
 */
export async function migrateLegacyLibrary(
  onTracks: (tracks: Track[]) => unknown,
  onProgress?: (p: MigrationProgress) => void,
): Promise<number> {
  if (!isNative() || localStorage.getItem(DONE_KEY)) return 0;
  let moved = 0;
  let failures = 0;

  // 1) files in app-private storage
  try {
    const res = await SonoraPlayer.scanLegacyFiles();
    failures += res.failed.length;
    if (res.added.length) {
      moved += res.added.length;
      await onTracks(res.added.map(trackFromRecord));
    }
  } catch {
    failures++;
  }

  // 2) blobs in the old IndexedDB library
  const db = await openLegacyDb();
  if (db) {
    try {
      const items = await readAll(db);
      let done = 0;
      let batch: Track[] = [];
      for (const item of items) {
        onProgress?.({ done, total: items.length, name: item.name });
        try {
          const rec = await sendItem(item);
          if (rec && rec !== "skip") { batch.push(trackFromRecord(rec)); moved++; }
        } catch {
          failures++;
        }
        done++;
        if (batch.length >= 8) { await onTracks(batch); batch = []; }
      }
      if (batch.length) await onTracks(batch);
      onProgress?.({ done: items.length, total: items.length, name: "" });
    } catch {
      failures++;
    } finally {
      db.close();
    }
    // Only delete the old copy when everything made it across; otherwise try again next launch (dupes are skipped).
    if (failures === 0) await new Promise<void>((r) => { const q = indexedDB.deleteDatabase(OLD_DB); q.onsuccess = q.onerror = q.onblocked = () => r(); });
  }

  if (failures === 0) localStorage.setItem(DONE_KEY, "1");
  else toast("Some songs from the previous version could not be moved — will retry next launch");
  return moved;
}
