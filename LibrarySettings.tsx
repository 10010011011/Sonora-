import { useCallback, useEffect, useState } from "react";
import { useStore, settings, toast } from "./store";
import { IN_PLACE_FOLDERS, importFolderFromDevice, rescanFolders, reloadFromNative } from "./library";
import { askConfirm } from "./sheets";
import { listFolders, removeFolder, type FolderInfo } from "./nativeLibrary";
import { exportBackup, pickBackupText, restoreBackup } from "./backup";

const when = (ms?: number) => (ms ? new Date(ms).toLocaleString() : "never");

/** Folders Sonora indexes in place (Android). */
export function FoldersSetting() {
  const [folders, setFolders] = useState<FolderInfo[]>([]);
  const refresh = useCallback(() => void listFolders().then(setFolders).catch(() => {}), []);
  useEffect(refresh, [refresh]);
  if (!IN_PLACE_FOLDERS) return null; // copy-only build: nothing is indexed in place

  const act = async (fn: () => Promise<unknown>) => {
    await fn();
    refresh();
  };

  return (
    <>
      <div className="setting-line">
        <div>
          <b>Folders</b>
          <span>
            Songs in a folder stay where they are (no copy). Rescan finds new, moved and deleted files without
            re-reading unchanged ones.
          </span>
        </div>
        <div className="setting-actions">
          <button type="button" className="outline-button h-8 px-3 text-xs" onClick={() => act(importFolderFromDevice)}>
            Add folder
          </button>
          <button
            type="button"
            className="outline-button h-8 px-3 text-xs"
            disabled={!folders.length}
            onClick={() => act(rescanFolders)}
          >
            Rescan all
          </button>
        </div>
      </div>
      {folders.map((f) => (
        <div className="setting-line" key={f.uri}>
          <div>
            <b>{f.name}</b>
            <span>
              {f.count} track{f.count === 1 ? "" : "s"} · scanned {when(f.lastScanAt)}
              {f.accessible ? "" : " · access was revoked: add the folder again"}
            </span>
          </div>
          <button
            type="button"
            className="outline-button h-8 px-3 text-xs"
            onClick={() =>
              askConfirm("Remove folder", `“${f.name}” is removed from Sonora. Your music files are not touched.`, () => void act(async () => {
                  await removeFolder(f.uri);
                  await reloadFromNative();
                }), "Remove")
            }
          >
            Remove
          </button>
        </div>
      ))}
    </>
  );
}

/** Metadata-only backup and restore (no audio). */
export function BackupSetting() {
  const [busy, setBusy] = useState(false);
  const restore = async () => {
    const text = await pickBackupText();
    if (!text) return;
    setBusy(true);
    try {
      const r = await restoreBackup(text);
      toast(
        `Restored: ${r.matched} songs matched${r.unmatched ? `, ${r.unmatched} not in this library yet` : ""}` +
          `${r.playlists ? ` · ${r.playlists} playlists` : ""}`,
      );
    } catch (error) {
      toast(error instanceof Error ? error.message : "Could not restore");
    } finally {
      setBusy(false);
    }
  };
  return (
    <div className="setting-line">
      <div>
        <b>Backup</b>
        <span>
          Saves liked songs, playlists, bookmarks, lyrics edits, tag edits, play counts and settings. No audio.
          To restore on a new install, add your music first, then restore.
        </span>
      </div>
      <div className="setting-actions">
        <button type="button" className="outline-button h-8 px-3 text-xs" onClick={exportBackup}>
          Export
        </button>
        <button type="button" className="outline-button h-8 px-3 text-xs" disabled={busy} onClick={restore}>
          Restore
        </button>
      </div>
    </div>
  );
}

/** Simple / Pro and resume threshold. */
export function BehaviourSetting() {
  const mode = useStore(settings, (s) => s.uiMode);
  const resume = useStore(settings, (s) => s.resumeMinSec);
  return (
    <>
      <div className="setting-line">
        <div>
          <b>Simple mode</b>
          <span>Hides the Sound lab and the analyzer. Everything else stays.</span>
        </div>
        <button
          type="button"
          className={`switch ${mode === "simple" ? "on" : ""}`}
          onClick={() => settings.set({ uiMode: mode === "simple" ? "pro" : "simple" })}
          aria-label="Simple mode"
          aria-pressed={mode === "simple"}
        >
          <span />
        </button>
      </div>
      <div className="setting-line">
        <div>
          <b>Remember position</b>
          <span>Tracks at least this long reopen where you stopped. The last song always does.</span>
        </div>
        <select
          className="sort-select"
          aria-label="Remember position for tracks longer than"
          value={resume}
          onChange={(e) => settings.set({ resumeMinSec: Number(e.target.value) })}
        >
          <option value={300}>5 min</option>
          <option value={600}>10 min</option>
          <option value={1200}>20 min</option>
          <option value={3600}>60 min</option>
          <option value={999999}>Never</option>
        </select>
      </div>
    </>
  );
}

