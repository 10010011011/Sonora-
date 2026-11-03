import { useEffect, useRef, useState } from "react";
import { Capacitor } from "@capacitor/core";
import { useStore, settings, player, toast, type ThemeName } from "./store";
import { devices, requestDeviceNames } from "./devices";
import { Icon } from "./ui";
import { Calibrate } from "./SharedAudioViews";
import { getLibraryStats } from "./nativeLibrary";
import { diagReport } from "./diag";
import { useBackHandler } from "./useBack";
import { useFocusTrap } from "./focusTrap";
import { BackupSetting, BehaviourSetting, FoldersSetting } from "./LibrarySettings";

const fmtBytes = (n: number) =>
  n >= 1e9 ? (n / 1e9).toFixed(1) + " GB" : n >= 1e6 ? Math.round(n / 1e6) + " MB" : Math.max(0, Math.round(n / 1e3)) + " KB";

export const THEMES: {
  id: ThemeName;
  name: string;
  badge: string;
  desc: string;
}[] = [
  { id: "dark", name: "Nocturne", badge: "DARK", desc: "Warm obsidian & lime-chartreuse" },
  { id: "light", name: "Daylight", badge: "LIGHT", desc: "Soft paper & botanical olive" },
  { id: "system", name: "System", badge: "AUTO", desc: "Follows device appearance" },
  { id: "miku", name: "Miku", badge: "RIBBON", desc: "Soft waveform grid with ribbon-like accents" },
  { id: "teto", name: "Teto", badge: "SPIRAL", desc: "Twin spiral details in rose and warm gold" },
  { id: "neru", name: "Neru", badge: "LCD", desc: "Compact LCD-style dots and amber highlights" },
  { id: "vinyl", name: "Vinyl", badge: "GLASS", desc: "Frosted glass, cover-art glow and a spinning record" },
];

export default function SettingsModal({ onClose }: { onClose: () => void }) {
  const s = useStore(settings, (x) => x);
  const sleepUntil = useStore(player, (p) => p.sleepAt);
  const sleepEot = useStore(player, (p) => p.sleepEndOfTrack);
  const sleepTracks = useStore(player, (p) => p.sleepTracksRemaining);
  const panel = useRef<HTMLDivElement>(null);
  useFocusTrap(panel);
  const named = useStore(devices, (d) => d.named);

  const [calib, setCalib] = useState(false);
  const [stats, setStats] = useState<{ tracks: number; bytes: number; freeBytes: number } | null>(null);
  const set = settings.set;

  useBackHandler(true, onClose);
  useBackHandler(calib, () => setCalib(false));

  useEffect(() => {
    if (Capacitor.isNativePlatform()) getLibraryStats().then(setStats).catch(() => {});
  }, []);

  const copyDiagnostics = async () => {
    try {
      await navigator.clipboard.writeText(diagReport());
      toast("Diagnostics copied");
    } catch {
      toast("Could not copy diagnostics");
    }
  };

  const sleepMinutes = sleepEot
    ? -1
    : sleepTracks > 0
      ? -100 - sleepTracks
      : sleepUntil
        ? Math.max(1, Math.ceil((sleepUntil - Date.now()) / 60000))
        : 0;

  return (
    <div
      className="modal-backdrop"
      onMouseDown={(e) => {
        if (e.target === e.currentTarget) onClose();
      }}
    >
      <div ref={panel} tabIndex={-1} className="settings-modal" role="dialog" aria-modal="true" aria-label="Preferences">
        <div className="modal-head">
          <div>
            <span className="eyebrow">MAKE IT YOURS</span>
            <h2>Preferences</h2>
          </div>
          <button type="button" onClick={onClose} aria-label="Close">
            <Icon n="x" s={21} />
          </button>
        </div>

        <div className="modal-scroll">
          {Capacitor.isNativePlatform() && (
            <div className="settings-group">
              <h3>Library</h3>
              <div className="setting-line">
                <div>
                  <b>{stats ? `${stats.tracks} track${stats.tracks === 1 ? "" : "s"} on this device` : "Library storage"}</b>
                  <span>
                    {stats
                      ? `${fmtBytes(stats.bytes)} of copied songs · ${fmtBytes(stats.freeBytes)} free. A picked file is copied once and recognised if you add it again, even renamed.`
                      : "A picked file is copied once and recognised if you add it again, even renamed."}
                  </span>
                </div>
              </div>
              <FoldersSetting />
              <div className="setting-line">
                <div>
                  <b>Diagnostics</b>
                  <span>Copies background-playback timing and events for the device test checklist</span>
                </div>
                <button type="button" className="outline-button h-8 px-3 text-xs" onClick={copyDiagnostics}>
                  Copy report
                </button>
              </div>
            </div>
          )}
          <div className="settings-group">
            <h3>Behaviour &amp; backup</h3>
            <BehaviourSetting />
            <BackupSetting />
          </div>
          {/* Appearance & Special Vocaloid Themes */}
          <div className="settings-group">
            <h3>Appearance</h3>
            <div className="theme-grid">
              {THEMES.map((t) => {
                const chosen = s.theme === t.id;
                return (
                  <button
                    type="button"
                    key={t.id}
                    className={`theme-choice ${chosen ? "chosen" : ""}`}
                    onClick={() => {
                      set({
                        theme: t.id,
                        colormap:
                          t.id === "miku" || t.id === "teto" || t.id === "neru"
                            ? t.id
                            : s.colormap,
                      });
                    }}
                    title={t.desc}
                  >
                    <span className={`theme-swatch ${t.id}`} />
                    <span className="flex min-w-0 flex-col text-left">
                      <span className="truncate leading-tight">{t.name}</span>
                      <span className="truncate font-mono text-[9px] font-normal opacity-70">{t.badge}</span>
                    </span>
                    {chosen && <Icon n="check" s={14} />}
                  </button>
                );
              })}
            </div>
            {Capacitor.isNativePlatform() && (
              <div className="setting-line">
                <div>
                  <b>Sync launcher icon with theme</b>
                  <span>When on, the launcher icon follows the selected theme. Turn it off to restore Sonora's default waveform icon. Your launcher may take a moment to update.</span>
                </div>
                <button
                  type="button"
                  className={`switch ${s.themeIcon ? "on" : ""}`}
                  onClick={() => set({ themeIcon: !s.themeIcon })}
                  aria-label="Match app icon to theme"
                  aria-pressed={s.themeIcon}
                >
                  <span />
                </button>
              </div>
            )}
          </div>

          {/* Playback & Transitions */}
          <div className="settings-group">
            <h3>Playback &amp; Transitions</h3>

            <div className="setting-line">
              <div>
                <b>Gapless playback</b>
                <span>Pre-buffers next track on a second deck for zero-gap transitions</span>
              </div>
              <button
                type="button"
                className={`switch ${s.gapless ? "on" : ""}`}
                onClick={() => set({ gapless: !s.gapless })}
                aria-label="Gapless playback"
                aria-pressed={s.gapless}
              >
                <span />
              </button>
            </div>

            <div className="setting-line">
              <div>
                <b>Smooth play / pause fade</b>
                <span>Gently ramp volume when starting or pausing to avoid clicks</span>
              </div>
              <select
                value={s.playPauseFadeMs}
                aria-label="Smooth play / pause fade"
                onChange={(e) => set({ playPauseFadeMs: Number(e.target.value) })}
              >
                <option value={0}>Instant (Off)</option>
                <option value={150}>150 ms</option>
                <option value={220}>220 ms</option>
                <option value={350}>350 ms</option>
                <option value={500}>500 ms</option>
              </select>
            </div>

            <div className="setting-line">
              <div>
                <b>Crossfade tracks</b>
                <span>Smoothly overlap ending and starting tracks</span>
              </div>
              <select
                value={s.crossfade}
                aria-label="Crossfade tracks"
                onChange={(e) => set({ crossfade: Number(e.target.value) })}
              >
                <option value={0}>Off</option>
                <option value={2}>2 sec</option>
                <option value={4}>4 sec</option>
                <option value={6}>6 sec</option>
                <option value={8}>8 sec</option>
                <option value={12}>12 sec</option>
              </select>
            </div>

            <div className="setting-line">
              <div>
                <b>Repeat each track N times</b>
                <span>Play a song multiple times before advancing to the next track</span>
              </div>
              <select
                value={s.repeatCountTarget}
                aria-label="Repeat each track N times"
                onChange={(e) => {
                  const n = Number(e.target.value);
                  set({ repeatCountTarget: n });
                  player.set({ repeatRemaining: Math.max(0, n - 1) });
                }}
              >
                <option value={1}>1× (Normal)</option>
                <option value={2}>2× per track</option>
                <option value={3}>3× per track</option>
                <option value={5}>5× per track</option>
              </select>
            </div>

            <div className="setting-line">
              <div>
                <b>Playback speed</b>
                <span>Adjust how fast the track plays</span>
              </div>
              <select
                value={s.speed}
                aria-label="Playback speed"
                onChange={(e) => set({ speed: Number(e.target.value) })}
              >
                {[0.5, 0.75, 0.85, 0.9, 1, 1.1, 1.25, 1.5, 2].map((n) => (
                  <option key={n} value={n}>
                    {n}x
                  </option>
                ))}
              </select>
            </div>

            <div className="setting-line">
              <div>
                <b>Preserve pitch</b>
                <span>Keep the musical key unchanged when changing speed</span>
              </div>
              <button
                type="button"
                className={`switch ${s.keepPitch ? "on" : ""}`}
                onClick={() => set({ keepPitch: !s.keepPitch })}
                aria-label="Preserve pitch"
                aria-pressed={s.keepPitch}
              >
                <span />
              </button>
            </div>

            <div className="setting-line">
              <div>
                <b>Waveform seekbar</b>
                <span>Show interactive waveform bars and bookmark pins in Now Playing</span>
              </div>
              <button
                type="button"
                className={`switch ${s.waveformSeekbar ? "on" : ""}`}
                onClick={() => set({ waveformSeekbar: !s.waveformSeekbar })}
                aria-label="Waveform seekbar"
                aria-pressed={s.waveformSeekbar}
              >
                <span />
              </button>
            </div>

            <div className="setting-line">
              <div>
                <b>Pause when volume reaches 0</b>
                <span>Automatically pause playback when the volume slider is dragged to zero</span>
              </div>
              <button
                type="button"
                className={`switch ${s.pauseOnVolume0 ? "on" : ""}`}
                onClick={() => set({ pauseOnVolume0: !s.pauseOnVolume0 })}
                aria-label="Pause when volume reaches 0"
                aria-pressed={s.pauseOnVolume0}
              >
                <span />
              </button>
            </div>

            <div className="setting-line">
              <div>
                <b>Sleep timer</b>
                <span>
                  {sleepEot
                    ? "Stops at the end of current track"
                    : sleepTracks > 0
                      ? `Stops after ${sleepTracks} more track${sleepTracks === 1 ? "" : "s"}`
                      : sleepUntil
                        ? `Stops in about ${sleepMinutes} min (with 20s fade-out)`
                        : "Stop playing after minutes or a number of tracks"}
                </span>
              </div>
              <select
                value={
                  sleepEot
                    ? -1
                    : sleepTracks > 0
                      ? -100 - sleepTracks
                      : [0, 15, 30, 45, 60, 90].includes(sleepMinutes)
                        ? sleepMinutes
                        : 15
                }
                aria-label="Sleep timer"
                onChange={(e) => {
                  const n = Number(e.target.value);
                  if (n === -1) player.set({ sleepEndOfTrack: true, sleepAt: null, sleepTracksRemaining: 0 });
                  else if (n < -100) player.set({ sleepEndOfTrack: false, sleepAt: null, sleepTracksRemaining: Math.abs(n + 100) });
                  else player.set({ sleepEndOfTrack: false, sleepTracksRemaining: 0, sleepAt: n > 0 ? Date.now() + n * 60000 : null });
                }}
              >
                <option value={0}>Off</option>
                <option value={15}>15 min</option>
                <option value={30}>30 min</option>
                <option value={45}>45 min</option>
                <option value={60}>60 min</option>
                <option value={90}>90 min</option>
                <option value={-1}>End of current track</option>
                <option value={-102}>After 2 tracks</option>
                <option value={-103}>After 3 tracks</option>
                <option value={-105}>After 5 tracks</option>
              </select>
            </div>
          </div>

          {/* Lyrics & Devices */}
          <div className="settings-group">
            <h3>Lyrics &amp; Devices</h3>

            <div className="setting-line">
              <div>
                <b>Tap reaction offset</b>
                <span>Subtract your response delay from every tap-sync stamp</span>
              </div>
              <div className="flex items-center gap-2">
                <div className="stepper">
                  <button
                    type="button"
                    aria-label="Decrease tap offset"
                    onClick={() => set({ tapLatency: Math.max(0, s.tapLatency - 25) })}
                  >
                    <Icon n="minus" s={14} />
                  </button>
                  <span className="tabular-nums">{s.tapLatency} ms</span>
                  <button
                    type="button"
                    aria-label="Increase tap offset"
                    onClick={() => set({ tapLatency: Math.min(600, s.tapLatency + 25) })}
                  >
                    <Icon n="plus" s={14} />
                  </button>
                </div>
                <button type="button" className="outline-button h-7 px-2 text-[10px]" onClick={() => setCalib(true)}>
                  Calibrate
                </button>
              </div>
            </div>

            <div className="setting-line">
              <div>
                <b>Pause when headphones unplug</b>
                <span>Immediately pause playback if an audio output disconnects</span>
              </div>
              <button
                type="button"
                className={`switch ${s.pauseOnDisconnect ? "on" : ""}`}
                onClick={() => set({ pauseOnDisconnect: !s.pauseOnDisconnect })}
                aria-label="Pause when headphones unplug"
                aria-pressed={s.pauseOnDisconnect}
              >
                <span />
              </button>
            </div>

            <div className="setting-line">
              <div>
                <b>Resume on output reconnect</b>
                <span>Automatically resume when your headset reconnects</span>
              </div>
              <button
                type="button"
                className={`switch ${s.autoResume ? "on" : ""}`}
                onClick={() => set({ autoResume: !s.autoResume })}
                aria-label="Resume on output reconnect"
                aria-pressed={s.autoResume}
              >
                <span />
              </button>
            </div>

            {!named && (
              <div className="setting-line">
                <div>
                  <b>Headset device names</b>
                  <span>Unlock Bluetooth / USB headset names for automatic EQ profile switching</span>
                </div>
                <button type="button" className="outline-button h-8 px-3 text-xs" onClick={requestDeviceNames}>
                  Unlock names
                </button>
              </div>
            )}
          </div>

          <p className="modal-note">
            Your settings, liked songs, and imported music are saved on this device.
          </p>
        </div>
      </div>
      {calib && <Calibrate onClose={() => setCalib(false)} />}
    </div>
  );
}
