import { library, lyricsStore, settings, toast, type Lyrics, type Settings, type Track } from "./store";
import { SonoraPlayer, isNative, patchNativeTracks } from "./nativeLibrary";
import { mergeUserData, normalizeUserData, remapUserData, userdata, type UserData } from "./userdata";
import { saveLyrics } from "./library";

/**
 * Metadata-only backup: no audio. Restoring matches tracks by id, then by content fingerprint, then by
 * file name + size, so it works after a reinstall (when ids differ). Import the music first, then restore.
 */

interface BackupTrack {
  id: string;
  fp?: string;
  fileName: string;
  size: number;
  title: string;
  artist: string;
  album: string;
  addedAt?: number;
  playCount?: number;
  lastPlayedAt?: number;
  edited?: boolean;
}

export interface BackupFile {
  app: "sonora";
  version: 1;
  createdAt: number;
  tracks: BackupTrack[];
  settings: Settings;
  userdata: UserData;
  lyrics: Record<string, Lyrics>;
}

export function buildBackup(): BackupFile {
  return {
    app: "sonora",
    version: 1,
    createdAt: Date.now(),
    tracks: library.get().tracks
      .map((t) => ({
        id: t.id, fp: t.fp, fileName: t.fileName, size: t.size, title: t.title, artist: t.artist, album: t.album,
        addedAt: t.addedAt, playCount: t.playCount, lastPlayedAt: t.lastPlayedAt, edited: t.edited,
      })),
    settings: settings.get(),
    userdata: userdata.get(),
    lyrics: lyricsStore.get().map,
  };
}

const stamp = () => new Date().toISOString().slice(0, 10);

/** Saves the backup: Android's "save as" sheet, or a download in the browser. */
export async function exportBackup(): Promise<boolean> {
  const text = JSON.stringify(buildBackup());
  const name = `sonora-backup-${stamp()}.json`;
  try {
    if (isNative()) {
      await SonoraPlayer.saveTextFile({ name, text });
    } else {
      const url = URL.createObjectURL(new Blob([text], { type: "application/json" }));
      const a = Object.assign(document.createElement("a"), { href: url, download: name });
      a.click();
      setTimeout(() => URL.revokeObjectURL(url), 4000);
    }
    toast("Backup saved");
    return true;
  } catch (error) {
    if ((error as { code?: string })?.code !== "CANCELLED") toast("Could not save the backup");
    return false;
  }
}

/** Lets the user pick a backup file and returns its text, or null if they cancel. */
export async function pickBackupText(): Promise<string | null> {
  if (isNative()) {
    try {
      return (await SonoraPlayer.pickTextFile()).text;
    } catch {
      return null;
    }
  }
  return new Promise((resolve) => {
    const input = Object.assign(document.createElement("input"), { type: "file", accept: "application/json,.json" });
    input.onchange = async () => resolve((await input.files?.[0]?.text()) ?? null);
    input.oncancel = () => resolve(null);
    input.click();
  });
}

export interface RestoreSummary {
  matched: number;
  unmatched: number;
  playlists: number;
  likes: number;
}

/** @throws Error with a readable message when the file is not a Sonora backup. */
export async function restoreBackup(text: string): Promise<RestoreSummary> {
  let data: Partial<BackupFile>;
  try {
    data = JSON.parse(text);
  } catch {
    throw new Error("This file is not valid JSON");
  }
  if (data.app !== "sonora" || !Array.isArray(data.tracks)) throw new Error("This is not a Sonora backup");

  const current = library.get().tracks;
  const byId = new Map(current.map((t) => [t.id, t]));
  const byFp = new Map(current.filter((t) => t.fp).map((t) => [t.fp!, t]));
  const bySig = new Map(current.map((t) => [`${t.fileName}|${t.size}`, t]));

  const idMap = new Map<string, string>();
  const patches: Parameters<typeof patchNativeTracks>[0] = [];
  const updates = new Map<string, Partial<Track>>();
  for (const b of data.tracks) {
    const hit = byId.get(b.id) ?? (b.fp ? byFp.get(b.fp) : undefined) ?? bySig.get(`${b.fileName}|${b.size}`);
    if (!hit) continue;
    idMap.set(b.id, hit.id);
    const next: Partial<Track> = {};
    if (b.edited) Object.assign(next, { title: b.title, artist: b.artist, album: b.album, edited: true });
    if ((b.playCount ?? 0) > (hit.playCount ?? 0)) Object.assign(next, { playCount: b.playCount, lastPlayedAt: b.lastPlayedAt });
    if (Object.keys(next).length) {
      updates.set(hit.id, next);
      patches.push({ id: hit.id, ...next });
    }
  }
  if (updates.size) {
    library.set((s) => ({ tracks: s.tracks.map((t) => (updates.has(t.id) ? { ...t, ...updates.get(t.id) } : t)) }));
    if (isNative()) await patchNativeTracks(patches).catch(() => {});
  }

  const map = (id: string) => idMap.get(id) ?? id;
  const incoming = remapUserData(normalizeUserData(data.userdata), idMap);
  userdata.set(mergeUserData(userdata.get(), incoming));

  const likedBefore = settings.get().likedIds;
  const liked = [...new Set([...likedBefore, ...(data.settings?.likedIds ?? []).map(map)])];
  settings.set({ ...(data.settings ?? {}), likedIds: liked });

  for (const [oldId, lyr] of Object.entries(data.lyrics ?? {})) {
    const id = map(oldId);
    if (!lyricsStore.get().map[id]) saveLyrics(id, lyr); // never overwrite lyrics edited on this device
  }

  return {
    matched: idMap.size,
    unmatched: data.tracks.length - idMap.size,
    playlists: incoming.playlists.length,
    likes: liked.length - likedBefore.length,
  };
}
