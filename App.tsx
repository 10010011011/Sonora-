import { useEffect, useRef, useState, lazy, Suspense, type ChangeEvent } from "react";
import { Capacitor } from "@capacitor/core";
import { useStore, settings, player, library, lyricsStore, toast, type Settings } from "./store";
import { engine } from "./engine";
import { initDevices } from "./devices";
import {
  ingest, openFolderHandle, loadTagsInBackground, addAudioFiles, importFolderFromDevice, IN_PLACE_FOLDERS,
  consumeOpenIntent, addNativeTracks, importState,
} from "./library";
import { restoreNativeTracks, restoreNativeAppState, persistNativeAppState, SonoraPlayer, isNative, applyAppIcon, THEME_ICON } from "./nativeLibrary";
import { initUserData } from "./userdata";
import { diag } from "./diag";
import { initTracking } from "./tracking";
import { migrateLegacyLibrary } from "./migrateLegacy";
import { openQueue } from "./sheets";
import { initNativeBridge } from "./nativeBridge";
import { useBackHandler } from "./useBack";
import { PlayerBar, useCurrent } from "./Player";
import { RightLyricsPanel } from "./SharedAudioViews";
import { Icon } from "./ui";
import Home from "./Home";
import Library from "./Library";
import SettingsModal from "./Settings";
import NowPlaying from "./NowPlaying";
import { SheetsHost } from "./Sheets";

const LyricsPage = lazy(() => import("./Lyrics"));
const VisualPage = lazy(() => import("./Visual"));
const Sound = lazy(() => import("./Sound"));

export type View = "room" | "library" | "lyrics" | "sound" | "visual" | "player";

function useTheme() {
  const theme = useStore(settings, (s) => s.theme);
  useEffect(() => {
    const mq = matchMedia("(prefers-color-scheme: light)");
    const apply = () => {
      const t = theme === "system" ? (mq.matches ? "light" : "dark") : theme;
      document.documentElement.dataset.theme = t;
      let meta = document.querySelector('meta[name="theme-color"]') as HTMLMetaElement | null;
      if (!meta) {
        meta = document.createElement("meta");
        meta.name = "theme-color";
        document.head.appendChild(meta);
      }
      meta.content = getComputedStyle(document.documentElement).getPropertyValue("--bg").trim() || "#111415";
    };
    apply();
    mq.addEventListener("change", apply);
    return () => mq.removeEventListener("change", apply);
  }, [theme]);
}

/** Vinyl theme: cover-art glow behind the UI. Launcher icon follows the theme when the user opted in. */
function useThemeExtras() {
  const theme = useStore(settings, (s) => s.theme);
  const themeIcon = useStore(settings, (s) => s.themeIcon);
  const cover = useCurrent()?.cover;
  const lastIcon = useRef<string | null>(null);

  useEffect(() => {
    document.documentElement.style.setProperty(
      "--cover-url",
      theme === "vinyl" && cover ? `url(${JSON.stringify(cover)})` : "none",
    );
  }, [theme, cover]);

  useEffect(() => {
    if (!isNative()) return;
    const want = themeIcon ? (THEME_ICON[theme] ?? "default") : "default";
    if (lastIcon.current === null && want === "default") {
      lastIcon.current = want; // fresh start with the default icon: nothing to change
      return;
    }
    if (lastIcon.current === want) return;
    lastIcon.current = want;
    void applyAppIcon(want);
  }, [theme, themeIcon]);
}

export default function App() {
  useTheme();
  useThemeExtras();
  const [view, setView] = useState<View>("room");
  const [settingsOpen, setSettingsOpen] = useState(false);

  // Android Back: close sub-screens one level at a time; at Home the app moves to the background.
  useBackHandler(view !== "room", () =>
    setView((v) => (v === "visual" ? "sound" : "room")),
  );

  const simple = useStore(settings, (s) => s.uiMode === "simple");
  // Simple mode hides the Sound lab and analyzer; leave them if the mode is switched while one is open.
  useEffect(() => {
    if (simple && (view === "sound" || view === "visual")) setView("room");
  }, [simple, view]);

  const tracks = useStore(library, (s) => s.tracks);
  const loading = useStore(library, (s) => s.loading);
  const likedIds = useStore(settings, (s) => s.likedIds);
  const toastMsg = useStore(player, (s) => s.toast);

  const filesRef = useRef<HTMLInputElement>(null);
  const folderRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    let cancelled = false;
    let saveTimer: number | undefined;
    let unsubscribeSettings: (() => void) | undefined;
    let unsubscribeLyrics: (() => void) | undefined;
    let unsubscribeLibrary: (() => void) | undefined;
    const initialSettings = settings.get();
    const initialLyricsMap = lyricsStore.get().map;

    const persistStateNow = () => {
      window.clearTimeout(saveTimer);
      const state = library.get();
      const queueIds = state.queue.map((index) => state.tracks[index]?.id).filter((id): id is string => Boolean(id));
      const currentTrackId = state.tracks[state.queue[state.qpos]]?.id ?? null;
      void persistNativeAppState(settings.get(), lyricsStore.get().map, { queueIds, currentTrackId });
    };

    const schedulePersistentState = () => {
      window.clearTimeout(saveTimer);
      saveTimer = window.setTimeout(persistStateNow, 450);
    };

    const onVisibilityChange = () => {
      if (document.visibilityState === "hidden") persistStateNow();
    };

    const restore = async () => {
      const [savedTracks, savedState] = await Promise.all([
        restoreNativeTracks().catch(() => []),
        restoreNativeAppState().catch(() => null),
        initUserData(), // resume positions must be known before the first track is primed
      ]).then(([tracks, state]) => [tracks, state] as const);
      if (cancelled) return;

      if (savedState?.settings) {
        const currentSettings = settings.get();
        const userChanges: Partial<Settings> = {};
        for (const key of Object.keys(currentSettings) as (keyof Settings)[]) {
          if (currentSettings[key] !== initialSettings[key]) {
            Object.assign(userChanges, { [key]: currentSettings[key] });
          }
        }
        settings.set({ ...savedState.settings, ...userChanges });
      }
      if (savedState?.lyrics) {
        const currentLyrics = lyricsStore.get().map;
        lyricsStore.set({
          map: currentLyrics === initialLyricsMap
            ? savedState.lyrics
            : { ...savedState.lyrics, ...currentLyrics },
        });
      }

      const pendingState = library.get();
      const pendingTracks = pendingState.tracks;
      const mergedTracks = [...savedTracks];
      const indexById = new Map(mergedTracks.map((track, index) => [track.id, index]));
      for (const track of pendingTracks) {
        const existingIndex = indexById.get(track.id);
        if (existingIndex === undefined) {
          indexById.set(track.id, mergedTracks.length);
          mergedTracks.push(track);
        } else {
          // An import performed while the restore was running is the newest in-memory copy.
          mergedTracks[existingIndex] = track;
        }
      }

      if (mergedTracks.length) {
        const restoredQueue = (savedState?.queueIds || [])
          .map((id) => indexById.get(id))
          .filter((index): index is number => index !== undefined);
        const pendingQueueIds = pendingState.queue
          .map((index) => pendingTracks[index]?.id)
          .filter((id): id is string => Boolean(id));
        const pendingQueue = pendingQueueIds
          .map((id) => indexById.get(id))
          .filter((index): index is number => index !== undefined);
        const queueOrder = [...new Set([...restoredQueue, ...pendingQueue])];
        const used = new Set(queueOrder);
        const queue = [...queueOrder, ...mergedTracks.map((_, index) => index).filter((index) => !used.has(index))];
        const pendingCurrentId = player.get().trackId ||
          pendingTracks[pendingState.queue[pendingState.qpos]]?.id;
        const currentId = pendingCurrentId || savedState?.currentTrackId || null;
        const currentIndex = currentId ? indexById.get(currentId) : undefined;
        const qpos = currentIndex === undefined ? 0 : Math.max(0, queue.indexOf(currentIndex));
        library.set({ tracks: mergedTracks, queue, qpos, folderName: "Sonora Library" });
        if (!engine.el.src) engine.primeAt(qpos);
        if (mergedTracks.some((track) => !track.tagsDone)) void loadTagsInBackground();
      }

      // Subscribe after restoring so the restored snapshot is not overwritten by defaults.
      unsubscribeSettings = settings.sub(schedulePersistentState);
      unsubscribeLyrics = lyricsStore.sub(schedulePersistentState);
      unsubscribeLibrary = library.sub(schedulePersistentState);
      schedulePersistentState();
    };

    initTracking();
    void restore()
      .then(async () => {
        if (cancelled || !isNative()) return;
        // Order matters: move the previous version's library in first, then handle a file the user opened us with.
        const moved = await migrateLegacyLibrary(addNativeTracks, (p) =>
          importState.set({ phase: "running", kind: "migration", done: p.done, total: p.total, name: p.name }),
        );
        if (moved) toast(`Moved ${moved} song${moved === 1 ? "" : "s"} from the previous version`);
        if (importState.get().kind === "migration") importState.set({ phase: "idle", kind: "", done: 0, total: 0, name: "", summary: "", failed: [] });
        await consumeOpenIntent();
      })
      .catch((error) => diag("startup-error", String(error)));
    if (isNative()) void SonoraPlayer.addListener("openIntent", () => void consumeOpenIntent());
    initDevices();
    initNativeBridge();

    const onKey = (e: KeyboardEvent) => {
      if (e.defaultPrevented) return;
      const tag = (e.target as HTMLElement).tagName;
      if (tag === "INPUT" || tag === "TEXTAREA" || tag === "SELECT") return;
      if (e.code === "Space") {
        e.preventDefault();
        engine.toggle();
      } else if (e.code === "ArrowRight" && e.shiftKey) engine.next();
      else if (e.code === "ArrowLeft" && e.shiftKey) engine.prev();
      else if (e.code === "ArrowRight") engine.seek(engine.time + (settings.get().seekStepSec || 5));
      else if (e.code === "ArrowLeft") engine.seek(engine.time - (settings.get().seekStepSec || 5));
    };
    window.addEventListener("keydown", onKey);
    window.addEventListener("pagehide", persistStateNow);
    document.addEventListener("visibilitychange", onVisibilityChange);
    return () => {
      cancelled = true;
      window.removeEventListener("keydown", onKey);
      window.removeEventListener("pagehide", persistStateNow);
      document.removeEventListener("visibilitychange", onVisibilityChange);
      window.clearTimeout(saveTimer);
      unsubscribeSettings?.();
      unsubscribeLyrics?.();
      unsubscribeLibrary?.();
    };
  }, []);

  // Track whether lyrics UI is visible so engine can throttle seekbar/lyrics clock to 10Hz when hidden
  useEffect(() => {
    const checkVisibility = () => {
      const panelShown = window.innerWidth > 1020 || view === "lyrics" || view === "player";
      if (player.get().lyricsVisible !== panelShown) {
        player.set({ lyricsVisible: panelShown });
      }
    };
    checkVisibility();
    window.addEventListener("resize", checkVisibility);
    return () => window.removeEventListener("resize", checkVisibility);
  }, [view]);

  const handleFilesSelected = (e: ChangeEvent<HTMLInputElement>) => {
    const fl = e.target.files;
    if (!fl || !fl.length) return;
    const arr = Array.from(fl).map((f) => ({
      file: f,
      path: (f as any).webkitRelativePath || f.name,
    }));
    const root = arr[0].path.includes("/") ? arr[0].path.split("/")[0] : "Local files";
    ingest(arr, root, false);
    e.target.value = "";
  };

  const triggerOpenFolder = () => {
    // Android folders are indexed in place only when IN_PLACE_FOLDERS is on (copy-only by default).
    if (Capacitor.isNativePlatform()) {
      if (IN_PLACE_FOLDERS) void importFolderFromDevice();
      return;
    }
    if (window.showDirectoryPicker) openFolderHandle();
    else folderRef.current?.click();
  };

  const navigate = (next: View) => {
    setView(next);
  };

  const viewTitle =
    view === "room"
      ? "Listening room"
      : view === "library"
        ? "Your library"
        : view === "lyrics"
          ? "Lyrics studio"
          : view === "visual"
            ? "Visual analyzer"
            : view === "player"
              ? "Now playing"
              : "Sound lab";

  return (
    <div className="app-shell">
      <input
        ref={filesRef}
        type="file"
        multiple
        accept="audio/*,.lrc,.srt,.vtt,.flac,.opus,.m4a,.aiff"
        hidden
        onChange={handleFilesSelected}
      />
      <input
        ref={folderRef}
        type="file"
        multiple
        hidden
        onChange={handleFilesSelected}
        {...({ webkitdirectory: "", directory: "" } as React.InputHTMLAttributes<HTMLInputElement>)}
      />

      {/* Left Sidebar (Desktop) / 4-Tab Bottom Bar (Mobile) */}
      <aside className="sidebar">
        <div className="brand">
          <div className="brand-mark">
            <img src="./favicon.svg" alt="" className="brand-logo" />
          </div>
          <span>
            sonora<span className="brand-dot">.</span>
          </span>
        </div>

        <div className="side-scroll">
          <div className="side-title">YOUR SPACE</div>
          <nav className="side-nav" aria-label="Primary">
            <button
              type="button"
              className={view === "room" || view === "player" ? "active" : ""}
              onClick={() => navigate("room")}
            >
              <Icon n="home" s={19} /> Home
            </button>
            <button
              type="button"
              className={view === "library" ? "active" : ""}
              onClick={() => navigate("library")}
            >
              <Icon n="list-music" s={19} /> Library
            </button>
            <button
              type="button"
              className={view === "lyrics" ? "active" : ""}
              onClick={() => navigate("lyrics")}
            >
              <Icon n="lyrics" s={19} /> Lyrics
            </button>
            {!simple && (
              <button
                type="button"
                className={view === "sound" || view === "visual" ? "active" : ""}
                onClick={() => navigate("sound")}
              >
                <Icon n="sliders" s={19} /> Sound
              </button>
            )}
          </nav>

          <div className="side-divider" />

          <div className="side-heading">
            <span>YOUR LIBRARY</span>
            <button
              type="button"
              onClick={() => addAudioFiles(filesRef.current)}
              title="Add files"
              aria-label="Add audio files"
            >
              <Icon n="plus" s={17} />
            </button>
          </div>

          <button
            type="button"
            className="side-library"
            onClick={() => {
              library.set({ filter: "all" });
              navigate("library");
            }}
          >
            <span className="side-mini">
              <Icon n="music" s={17} />
            </span>
            <span>
              <b>All songs</b>
              <small>
                {tracks.length} {tracks.length === 1 ? "track" : "tracks"}
              </small>
            </span>
          </button>

          <button
            type="button"
            className="side-library"
            onClick={() => {
              library.set({ filter: "liked" });
              navigate("library");
            }}
          >
            <span className="side-mini pink">
              <Icon n="heart" s={17} />
            </span>
            <span>
              <b>Liked songs</b>
              <small>{likedIds.length} saved</small>
            </span>
          </button>

          <div className="side-divider" />

          <div className="side-title">QUICK ACCESS</div>
          {(!Capacitor.isNativePlatform() || IN_PLACE_FOLDERS) && (
          <button type="button" className="quick-link" onClick={triggerOpenFolder}>
            <Icon n="folder-open" s={17} /> {Capacitor.isNativePlatform() ? "Add a folder" : "Open a folder"}
          </button>
          )}
          <button type="button" className="quick-link" onClick={openQueue}>
            <Icon n="list-music" s={17} /> Queue
          </button>
          <button type="button" className="quick-link" onClick={() => addAudioFiles(filesRef.current)}>
            <Icon n="upload" s={17} /> Add audio files
          </button>
        </div>

        <div className="sidebar-bottom">
          <div className="side-note">
            <Icon n="sparkles" s={17} />
            <span>
              <b>Made for your music</b>
              <small>Gapless DSP · ReplayGain · Offline first.</small>
            </span>
          </div>
          <button type="button" className="settings-link" onClick={() => setSettingsOpen(true)}>
            <Icon n="gear" s={18} /> Settings <Icon n="right" s={16} />
          </button>
        </div>
      </aside>

      {/* Main Content Area */}
      <main className="main-area">
        <header className="topbar">
          <div className="breadcrumb">
            <span className="mobile-brand">
              <img src="./favicon.svg" alt="" />
              <b>Sonora</b>
            </span>
            <span className="desktop-breadcrumb">
              YOUR SPACE <Icon n="right" s={13} /> <strong>{viewTitle}</strong>
              {loading && <span className="ml-2 text-accent">· Scanning…</span>}
            </span>
          </div>

          <div className="top-actions">
            <span className="local-badge">LOCAL FIRST</span>
            <button
              type="button"
              className="top-icon"
              title="Preferences & Themes"
              aria-label="Settings"
              onClick={() => setSettingsOpen(true)}
            >
              <Icon n="gear" s={19} />
            </button>
            <button type="button" className="import-button" onClick={() => addAudioFiles(filesRef.current)}>
              <Icon n="plus" s={18} /> Import music
            </button>
          </div>
        </header>

        <Suspense fallback={<div className="main-content inner-content text-sm text-muted">Loading studio…</div>}>
          {view === "room" && <Home onNavigate={navigate} />}
          {view === "player" && <NowPlaying onNavigate={navigate} />}
          {view === "library" && <Library dirRef={folderRef} fileRef={filesRef} />}
          {view === "lyrics" && <LyricsPage />}
          {view === "sound" && !simple && <Sound onOpenVisual={() => navigate("visual")} />}
          {view === "visual" && !simple && <VisualPage onBack={() => navigate("sound")} />}
        </Suspense>
      </main>

      {/* Right-hand Live Lyrics Panel (Desktop) */}
      <RightLyricsPanel onOpenStudio={() => navigate("lyrics")} />

      {/* Persistent Bottom Player Bar */}
      {view !== "player" && (
        <PlayerBar onOpenSettings={() => setSettingsOpen(true)} onNavigate={navigate} />
      )}

      <SheetsHost />

      {/* Preferences Modal */}
      {settingsOpen && <SettingsModal onClose={() => setSettingsOpen(false)} />}

      {/* Sonora Toast Notification */}
      {toastMsg && (
        <div className="toast" role="status" aria-live="polite">
          <Icon n="check" s={16} /> {toastMsg}
        </div>
      )}
    </div>
  );
}
