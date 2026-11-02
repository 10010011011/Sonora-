import { clock, library, player } from "./store";
import { engine } from "./engine";
import { patchNativeTracks, isNative } from "./nativeLibrary";
import { flushUserData, saveResume, startPositionFor } from "./userdata";

/**
 * Two small listeners on the engine's clock:
 *  - resume position: saved about every 5 s while playing and on pause / backgrounding;
 *  - play counts: a play counts once the track has been heard for 30 s (or 80% of a shorter track).
 * Neither starts playback. Restoring only sets the start position, paused.
 */

const SAVE_EVERY_MS = 5000;
let started = false;

export function initTracking() {
  if (started) return;
  started = true;
  engine.hooks.startAt = startPositionFor;

  let lastSave = 0;
  const saveNow = () => {
    const id = player.get().trackId;
    if (!id) return;
    lastSave = Date.now();
    saveResume(id, engine.time, player.get().duration);
  };

  let counted: string | null = null;
  const maybeCount = (t: number) => {
    const id = player.get().trackId;
    if (t < 2) counted = null; // restarted or looped: it can count again
    if (!id || counted === id) return;
    const dur = player.get().duration;
    if (t < Math.min(30, dur > 0 ? dur * 0.8 : 30)) return;
    counted = id;
    const track = library.get().tracks.find((x) => x.id === id);
    if (!track) return;
    const playCount = (track.playCount ?? 0) + 1;
    const lastPlayedAt = Date.now();
    library.set((s) => ({ tracks: s.tracks.map((x) => (x.id === id ? { ...x, playCount, lastPlayedAt } : x)) }));
    if (isNative()) void patchNativeTracks([{ id, playCount, lastPlayedAt }]).catch(() => {});
  };

  clock.sub(() => {
    if (!player.get().playing) return;
    maybeCount(clock.get().t);
    if (Date.now() - lastSave >= SAVE_EVERY_MS) saveNow();
  });

  engine.onChange(() => {
    if (!player.get().playing) saveNow();
  });

  const onHide = () => {
    saveNow();
    flushUserData();
  };
  document.addEventListener("visibilitychange", () => document.visibilityState === "hidden" && onHide());
  window.addEventListener("pagehide", onHide);
}
