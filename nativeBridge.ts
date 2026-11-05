import { engine } from "./engine";
import { currentTrack, library, player, settings, toast } from "./store";
import {
  SonoraPlayer,
  coverNameFromUrl,
  isNative,
  type FocusEvent,
  type MediaCommand,
} from "./nativeLibrary";
import { handleBack, setBackSync } from "./back";
import { diag } from "./diag";
import { handleBecomingNoisy } from "./devices";

let started = false;
const NOTIF_ASKED = "sonora.notifAsked";
const NOTIF_HINTED = "sonora.notifHinted";
let notifAskedThisSession = false;
let republish: () => void = () => {};

const lsGet = (k: string) => {
  try { return localStorage.getItem(k); } catch { return null; }
};
const lsSet = (k: string, v: string) => {
  try { localStorage.setItem(k, v); } catch { /* storage can be unavailable; the prompt just repeats */ }
};

/**
 * Asks Android for notification access (media controls need it from Android 13) and reports the truth.
 * `manual` is a tap on Settings → Notification access: it also opens system settings when Android will no longer
 * show the prompt. Automatic calls ask at most once per app launch and never nag with repeated toasts.
 */
export async function requestNotificationAccess(manual = false): Promise<boolean> {
  if (!isNative()) return true;
  if (!manual && notifAskedThisSession) return lsGet(NOTIF_ASKED) === "granted";
  notifAskedThisSession = true;
  try {
    const { granted, blocked } = await SonoraPlayer.requestNotificationPermission();
    lsSet(NOTIF_ASKED, granted ? "granted" : "denied");
    if (granted) {
      if (manual) toast("Notification controls are on");
      republish(); // the notification could not be shown before the grant, so post it now
    } else if (manual) {
      if (blocked) {
        toast("Turn notifications on for Sonora in Android settings");
        await SonoraPlayer.openNotificationSettings().catch(() => {});
      } else {
        toast("Notifications are off, so lock-screen controls will not show");
      }
    } else if (!lsGet(NOTIF_HINTED)) {
      lsSet(NOTIF_HINTED, "1");
      toast("Notifications are off. Enable them in Settings → Notification access for playback controls");
    }
    return granted;
  } catch {
    if (manual) toast("Could not check notification access");
    return false;
  }
}

/** Same command arriving twice within this window is one physical press (some headsets and cars repeat). */
const COMMAND_DEBOUNCE_MS = 400;

/**
 * Wires the web engine to Android: foreground service + MediaSession (notification, lock screen,
 * headset/Bluetooth buttons), audio focus, headphone-unplug pause and the Back button.
 * The engine stays the single source of truth; native only mirrors state and relays commands.
 */
export function initNativeBridge() {
  if (!isNative() || started) return;
  started = true;
  const ignore = () => {};

  /* ---------------- Back button ---------------- */
  setBackSync((has) => void SonoraPlayer.setBackHandled({ enabled: has }).catch(ignore));
  void SonoraPlayer.addListener("backButton", () => {
    // Nothing left to close: leave the app running in the background so music keeps playing.
    if (!handleBack()) void SonoraPlayer.moveToBackground().catch(ignore);
  });

  /* ---------------- Session mirror (notification + lock screen) ---------------- */
  let publishTimer: number | undefined;
  let resumeOnGain = false;

  const publishNow = () => {
    const t = currentTrack();
    if (!t) return;
    const ps = player.get();
    void SonoraPlayer.updateSession({
      playing: ps.playing,
      title: t.title,
      artist: t.artist,
      album: t.album,
      coverName: coverNameFromUrl(t.cover),
      durationMs: Math.round((ps.duration || t.duration || 0) * 1000),
      positionMs: Math.round(engine.time * 1000),
      speed: settings.get().speed,
      hasPrev: library.get().queue.length > 0,
      hasNext: library.get().queue.length > 0,
    }).catch(ignore);
  };

  const schedulePublish = () => {
    window.clearTimeout(publishTimer);
    // Starts publish immediately. Pauses wait briefly so a track-to-track blip never reaches native and
    // demotes the foreground service (restarting it from the background is restricted on Android 12+).
    publishTimer = window.setTimeout(publishNow, player.get().playing ? 0 : 400);
  };

  republish = schedulePublish;

  engine.onChange(() => {
    if (player.get().playing) resumeOnGain = false;
    schedulePublish();
  });

  /* ---------------- Audio focus ---------------- */
  engine.hooks.beforePlay = async () => {
    void requestNotificationAccess();
    try {
      return (await SonoraPlayer.requestFocus()).granted;
    } catch {
      return true; // a bridge hiccup must never make the play button dead
    }
  };

  void SonoraPlayer.addListener("audioFocus", (e: FocusEvent) => {
    diag("audio-focus", e.type);
    switch (e.type) {
      case "loss":
        resumeOnGain = false;
        engine.setDuck(false);
        engine.pause();
        break;
      case "lossTransient":
        if (player.get().playing) {
          resumeOnGain = true;
          engine.pause();
        }
        break;
      case "duck":
        engine.setDuck(true);
        break;
      case "gain":
        engine.setDuck(false);
        if (resumeOnGain) {
          resumeOnGain = false;
          void engine.play();
        }
        break;
    }
  });

  /* ---------------- Headphones / Bluetooth unplug ---------------- */
  void SonoraPlayer.addListener("becomingNoisy", () => {
    diag("becoming-noisy");
    resumeOnGain = false;
    handleBecomingNoisy();
  });

  /* ---------------- Notification / lock screen / headset buttons ---------------- */
  let lastSkip = { action: "", at: 0 };
  void SonoraPlayer.addListener("mediaCommand", (e: MediaCommand) => {
    diag("media-command", e.action);
    if (e.action === "next" || e.action === "previous") {
      const now = Date.now();
      if (lastSkip.action === e.action && now - lastSkip.at < COMMAND_DEBOUNCE_MS) {
        diag("media-command-duplicate", e.action);
        return;
      }
      lastSkip = { action: e.action, at: now };
    }
    resumeOnGain = false;
    switch (e.action) {
      case "play":
        void engine.play();
        break;
      case "pause":
      case "stop":
        engine.pause();
        break;
      case "next":
        engine.next();
        break;
      case "previous":
        engine.prev();
        break;
      case "seekTo":
        if (e.positionMs != null) engine.seek(e.positionMs / 1000);
        break;
      case "seekBy":
        engine.seek(engine.time + (e.positionMs ?? 0) / 1000);
        break;
    }
  });

  void SonoraPlayer.addListener("serviceError", (e) => {
    diag("service-error", e.message);
    toast("Background playback is unavailable right now");
  });

  document.addEventListener("visibilitychange", () => {
    diag("visibility", { hidden: document.hidden, playing: player.get().playing });
    if (!document.hidden) schedulePublish(); // resync after returning to the app
  });
}
