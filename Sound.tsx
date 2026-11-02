import { useEffect, useRef, useState } from "react";
import { useStore, settings, player, EQ_FREQS, applyMidsToEq, parseAutoEqText, toast } from "./store";
import { engine } from "./engine";
import { useCurrent } from "./Player";
import { devices, saveProfile, applyProfile, requestDeviceNames } from "./devices";
import { userdata, saveTrackAudioConfig } from "./userdata";
import { Slider, Toggle, Chip, Icon } from "./ui";

const SIMPLE_PRESETS: { name: string; bass: number; mids: number; treble: number; eq: number[] }[] = [
  { name: "Flat", bass: 0, mids: 0, treble: 0, eq: [0, 0, 0, 0, 0, 0, 0, 0, 0, 0] },
  { name: "Deep bass", bass: 6, mids: -2, treble: 2, eq: [6, 5, 3, 1, -1.5, -2, -1.5, 0, 1.5, 2] },
  { name: "Clarity", bass: 2, mids: 3, treble: 4, eq: [2, 1.5, 0, 1, 2.5, 3, 2.5, 3.5, 4, 4] },
  { name: "V-shape", bass: 4, mids: -1, treble: 4, eq: [5, 4, 2, 0, -1, -1.5, -1, 2, 4, 5] },
  { name: "Vocaloid pop", bass: 3, mids: 3, treble: 3, eq: [3, 2, 0, -1, 1.5, 3, 3.5, 4, 3, 2] },
  { name: "Harman-ish", bass: 4, mids: 1, treble: -1, eq: [4, 3.5, 2, 0, 0.5, 1, 1.5, 2.5, 0, -2] },
];

const fq = (f: number) => (f >= 1000 ? `${f / 1000}k` : `${f}`);

export function ResponseCurve() {
  const s = useStore(settings, (x) => x);
  const ref = useRef<HTMLCanvasElement>(null);

  useEffect(() => {
    const c = ref.current;
    if (!c) return;
    engine.ensure();
    const id = requestAnimationFrame(() => {
      const W = (c.width = c.clientWidth * devicePixelRatio),
        H = (c.height = c.clientHeight * devicePixelRatio);
      const g = c.getContext("2d")!;
      const n = 200;
      const freqs = new Float32Array(n);
      for (let i = 0; i < n; i++) freqs[i] = 20 * Math.pow(1000, i / (n - 1));
      const total = new Float32Array(n);
      const mag = new Float32Array(n),
        ph = new Float32Array(n);
      [...engine.eq, engine.bass, engine.treble].forEach((f) => {
        f.getFrequencyResponse(freqs, mag, ph);
        for (let i = 0; i < n; i++) total[i] += 20 * Math.log10(mag[i] || 1);
      });
      const css = getComputedStyle(document.documentElement);
      g.clearRect(0, 0, W, H);
      g.strokeStyle = css.getPropertyValue("--border");
      g.lineWidth = 1;
      g.beginPath();
      g.moveTo(0, H / 2);
      g.lineTo(W, H / 2);
      g.stroke();
      const y = (db: number) => H / 2 - (db / 15) * (H / 2 - 6);
      const grad = g.createLinearGradient(0, 0, W, 0);
      grad.addColorStop(0, css.getPropertyValue("--accent").trim() || "#cbef79");
      grad.addColorStop(1, css.getPropertyValue("--accent2").trim() || "#39c5bb");
      g.strokeStyle = grad;
      g.lineWidth = 2.2 * devicePixelRatio;
      g.beginPath();
      for (let i = 0; i < n; i++) (i ? g.lineTo : g.moveTo).call(g, (i / (n - 1)) * W, y(total[i]));
      g.stroke();
      g.lineTo(W, H / 2);
      g.lineTo(0, H / 2);
      g.globalAlpha = 0.14;
      g.fillStyle = grad;
      g.fill();
      g.globalAlpha = 1;
    });
    return () => cancelAnimationFrame(id);
  }, [s.eq, s.bassDb, s.trebleDb, s.eqOn, s.theme]);

  return <canvas ref={ref} className="h-24 w-full rounded border border-line bg-bg/50" />;
}

export function LimiterMeter() {
  const red = useStore(player, (s) => s.limiting);
  const clip = useStore(player, (s) => s.clipping);
  const lim = useStore(settings, (s) => s.limiter);
  const w = Math.min(100, (-red / 12) * 100);
  return (
    <div className="mt-2 flex items-center gap-2 text-[11px] text-muted">
      <span className="font-semibold text-fg">
        {clip ? "CLIPPING DETECTED — enable limiter or lower boost" : lim ? "Brick-wall limiter active" : "Limiter bypassed"}
      </span>
      {lim && (
        <>
          <div className="h-1.5 flex-1 overflow-hidden rounded-full bg-line">
            <div className="h-full bg-accent transition-all" style={{ width: `${w}%` }} />
          </div>
          <span className="w-14 text-right font-mono tabular-nums">{red.toFixed(1)} dB</span>
        </>
      )}
    </div>
  );
}

export default function Sound({ onOpenVisual }: { onOpenVisual: () => void }) {
  const s = useStore(settings, (x) => x);
  const track = useCurrent();
  const trackConfigs = useStore(userdata, (u) => u.trackConfigs);
  const outs = useStore(devices, (d) => d.outs);
  const named = useStore(devices, (d) => d.named);
  const outputId = useStore(player, (p) => p.outputId);
  const rgApplied = useStore(player, (p) => p.rgApplied);
  const [advancedOpen, setAdvancedOpen] = useState(false);
  const [autoEqOpen, setAutoEqOpen] = useState(false);
  const [autoEqText, setAutoEqText] = useState("");
  const [profileName, setProfileName] = useState("");

  const set = settings.set;
  const setBand = (i: number, v: number) => set({ eq: s.eq.map((x, k) => (k === i ? v : x)) });
  const isNormalized = s.rgMode !== "off";
  const currentTrackCfg = track ? trackConfigs?.[track.id] : undefined;
  const semitones = Math.round(12 * Math.log2(Math.max(0.25, s.speed)));

  return (
    <div className="main-content inner-content">
      <div className="section-kicker">
        <span /> SHAPE YOUR SOUND
      </div>
      <h1>
        Sound <em>lab.</em>
      </h1>
      <p className="subtitle">A finer listening experience, tuned by you.</p>

      {/* Sonora Primary Sound Panel */}
      <div className="sound-panel">
        <div className="sound-panel-head">
          <span className="sound-icon">
            <Icon n="sliders" s={24} />
          </span>
          <div>
            <span className="eyebrow">THREE-BAND EQUALIZER</span>
            <h2>Make it yours</h2>
          </div>
          <button
            type="button"
            className="reset-button"
            onClick={() =>
              set({
                bassDb: 0,
                midsDb: 0,
                trebleDb: 0,
                boostDb: 0,
                eq: [0, 0, 0, 0, 0, 0, 0, 0, 0, 0],
              })
            }
          >
            Reset
          </button>
        </div>

        <div className="eq-presets">
          {SIMPLE_PRESETS.map((p) => {
            const active = s.bassDb === p.bass && s.midsDb === p.mids && s.trebleDb === p.treble;
            return (
              <button
                type="button"
                key={p.name}
                className={active ? "border-accent text-accent" : ""}
                onClick={() =>
                  set({
                    bassDb: p.bass,
                    midsDb: p.mids,
                    trebleDb: p.treble,
                    eq: [...p.eq],
                    eqOn: true,
                  })
                }
              >
                {p.name}
              </button>
            );
          })}
        </div>

        <div className="eq-sliders">
          <Slider
            label="Bass"
            min={-12}
            max={12}
            step={0.5}
            value={s.bassDb}
            onChange={(v) => set({ bassDb: v, eqOn: true })}
          />
          <Slider
            label="Mids"
            min={-12}
            max={12}
            step={0.5}
            value={s.midsDb}
            onChange={(v) => set({ midsDb: v, eq: applyMidsToEq(s.eq, v), eqOn: true })}
          />
          <Slider
            label="Treble"
            min={-12}
            max={12}
            step={0.5}
            value={s.trebleDb}
            onChange={(v) => set({ trebleDb: v, eqOn: true })}
          />
        </div>

        <div className="sound-divider" />

        <div className="sound-setting">
          <div>
            <b>Safe sound boost</b>
            <p>Increase gain behind a brick-wall limiter to prevent clipping.</p>
          </div>
          <span className="tabular-nums">+{s.boostDb} dB</span>
        </div>
        <Slider
          label="Boost level"
          min={0}
          max={15}
          step={0.5}
          value={s.boostDb}
          onChange={(v) => set({ boostDb: v, limiter: true })}
        />
        <LimiterMeter />

        <div className="sound-setting normalization">
          <div>
            <b>Smart normalization (ReplayGain / EBU R128)</b>
            <p>
              Analyze track loudness in a background worker and even out playback levels
              {isNormalized ? ` (active: ${rgApplied > 0 ? "+" : ""}${rgApplied.toFixed(1)} dB)` : "."}
            </p>
          </div>
          <button
            type="button"
            className={`switch ${isNormalized ? "on" : ""}`}
            onClick={() => set({ rgMode: isNormalized ? "off" : "track", rgAnalyze: true })}
            aria-label="Smart normalization"
            aria-pressed={isNormalized}
          >
            <span />
          </button>
        </div>

        {track && (
          <div className="mt-4 flex flex-wrap items-center justify-between gap-2 rounded-md border border-line bg-surface2 px-3 py-2.5 text-xs">
            <div>
              <b className="text-fg">Per-track sound memory: {track.title}</b>
              <p className="text-[11px] text-muted">
                {currentTrackCfg
                  ? `Custom tuning pinned (${(currentTrackCfg.speed ?? 1).toFixed(2)}× · Bass ${currentTrackCfg.bassDb ?? 0}dB · Mids ${currentTrackCfg.midsDb ?? 0}dB · Treble ${currentTrackCfg.trebleDb ?? 0}dB)`
                  : "Pin the current EQ & speed specifically to this song so it restores whenever played."}
              </p>
            </div>
            <div className="flex items-center gap-2">
              <Chip
                active={Boolean(currentTrackCfg)}
                onClick={() => {
                  saveTrackAudioConfig(track.id, {
                    speed: s.speed,
                    keepPitch: s.keepPitch,
                    bassDb: s.bassDb,
                    midsDb: s.midsDb,
                    trebleDb: s.trebleDb,
                    boostDb: s.boostDb,
                  });
                  engine.apply(settings.get());
                  toast(`Pinned tuning for "${track.title}"`);
                }}
              >
                {currentTrackCfg ? "Update pin" : "Pin to track"}
              </Chip>
              {currentTrackCfg && (
                <Chip
                  onClick={() => {
                    saveTrackAudioConfig(track.id, null);
                    engine.apply(settings.get());
                    toast("Cleared per-track tuning");
                  }}
                >
                  Clear
                </Chip>
              )}
            </div>
          </div>
        )}
      </div>

      {/* Advanced DSP & 10-Band Equalizer Section */}
      <div className="sound-panel secondary-sound">
        <div className="sound-panel-head">
          <span className="sound-icon">
            <Icon n="bolt" s={22} />
          </span>
          <div>
            <span className="eyebrow">PRECISION AUDIO ENGINE</span>
            <h2>Advanced DSP &amp; 10-band EQ</h2>
          </div>
          <button
            type="button"
            className="outline-button"
            aria-expanded={advancedOpen}
            onClick={() => setAdvancedOpen(!advancedOpen)}
          >
            {advancedOpen ? "Hide advanced" : "Open advanced"} <Icon n={advancedOpen ? "up" : "down"} s={15} />
          </button>
        </div>

        {!advancedOpen ? (
          <p>
            Fine-tune 10 parametric bands with AutoEQ import, live frequency response curve, 8D HRTF spatial orbit,
            crossfeed, gapless crossfade, and 432Hz / semitone pitch modes.
          </p>
        ) : (
          <div className="mt-5 space-y-6 border-t border-line pt-5">
            <div>
              <div className="mb-2 flex flex-wrap items-center justify-between gap-2">
                <b className="text-xs">10-Band Parametric Equalizer &amp; Live Response</b>
                <div className="flex items-center gap-2">
                  <Chip active={autoEqOpen} onClick={() => setAutoEqOpen(!autoEqOpen)}>
                    Import AutoEQ
                  </Chip>
                  <Chip active={s.eqOn} onClick={() => set({ eqOn: !s.eqOn })}>
                    {s.eqOn ? "EQ Active" : "EQ Bypassed"}
                  </Chip>
                </div>
              </div>
              {autoEqOpen && (
                <div className="mb-3 rounded-md border border-line bg-surface2 p-3">
                  <p className="mb-2 text-[11px] text-muted">
                    Paste an AutoEQ / EqualizerAPO parametric preset (<code>Preamp: -4.2 dB</code>, <code>Filter 1: ON PK Fc 105 Hz Gain 4.5 dB Q 0.71</code>, or <code>GraphicEQ: ...</code>):
                  </p>
                  <textarea
                    value={autoEqText}
                    onChange={(e) => setAutoEqText(e.target.value)}
                    rows={4}
                    placeholder={"Preamp: -3.5 dB\nFilter 1: ON LSC Fc 105 Hz Gain 4.5 dB Q 0.70\nFilter 2: ON PK Fc 1200 Hz Gain -2.0 dB Q 1.20\nFilter 3: ON HSC Fc 8000 Hz Gain 2.5 dB Q 0.70"}
                    className="w-full rounded border border-line bg-bg p-2 font-mono text-[11px] text-fg outline-none focus:border-accent"
                  />
                  <div className="mt-2 flex justify-end gap-2">
                    <Chip onClick={() => setAutoEqOpen(false)}>Cancel</Chip>
                    <Chip
                      active
                      onClick={() => {
                        const parsed = parseAutoEqText(autoEqText);
                        if (!parsed) {
                          toast("No valid AutoEQ / Parametric filters found");
                          return;
                        }
                        set({
                          eq: parsed.eq,
                          preampDb: parsed.preampDb,
                          bassDb: parsed.bassDb,
                          midsDb: parsed.midsDb,
                          trebleDb: parsed.trebleDb,
                          eqOn: true,
                        });
                        setAutoEqOpen(false);
                        toast("AutoEQ curve imported");
                      }}
                    >
                      Apply AutoEQ
                    </Chip>
                  </div>
                </div>
              )}
              <ResponseCurve />
              <div className="mt-4 flex justify-between gap-1 overflow-x-auto pb-2">
                {EQ_FREQS.map((f, i) => (
                  <div key={f} className="flex flex-col items-center gap-1.5">
                    <span className="font-mono text-[10px] tabular-nums text-muted">
                      {s.eq[i] > 0 ? "+" : ""}
                      {s.eq[i].toFixed(1)}
                    </span>
                    <Slider vertical min={-12} max={12} step={0.5} value={s.eq[i]} onChange={(v) => setBand(i, v)} />
                    <span className="text-[10px] font-bold text-muted">{fq(f)}</span>
                  </div>
                ))}
              </div>
              <div className="mt-3 grid gap-4 sm:grid-cols-2">
                <Slider
                  label="Pre-amp headroom"
                  min={-15}
                  max={6}
                  step={0.5}
                  value={s.preampDb}
                  onChange={(v) => set({ preampDb: v })}
                />
                <Toggle
                  on={s.autoPreamp}
                  onChange={(v) => set({ autoPreamp: v })}
                  label="Auto headroom"
                  hint="Lowers pre-amp by largest EQ boost to eliminate digital clipping"
                />
              </div>
            </div>

            <div className="sound-divider" />

            <div className="grid gap-6 md:grid-cols-2">
              <div className="space-y-3">
                <b className="block text-xs">Spatial Stage &amp; 8D Orbit</b>
                <div className="flex flex-wrap gap-1.5">
                  {(
                    [
                      ["normal", "Stereo"],
                      ["mono", "Mono"],
                      ["vocal", "Vocal focus"],
                      ["karaoke", "Karaoke (no vocals)"],
                    ] as const
                  ).map(([k, l]) => (
                    <Chip key={k} active={s.stereoMode === k} onClick={() => set({ stereoMode: k })}>
                      {l}
                    </Chip>
                  ))}
                </div>
                <Slider
                  label="Stereo width"
                  min={0}
                  max={2.5}
                  step={0.05}
                  value={s.width}
                  onChange={(v) => set({ width: v, stereoMode: "normal" })}
                  fmt={(v) => `${Math.round(v * 100)}%`}
                />
                <Slider
                  label="Headphone crossfeed"
                  min={0}
                  max={1}
                  step={0.05}
                  value={s.crossfeed}
                  onChange={(v) => set({ crossfeed: v })}
                  fmt={(v) => `${Math.round(v * 100)}%`}
                />
                <Slider
                  label="Room ambience"
                  min={0}
                  max={1}
                  step={0.05}
                  value={s.room}
                  onChange={(v) => set({ room: v })}
                  fmt={(v) => `${Math.round(v * 100)}%`}
                />
                <Toggle
                  on={s.orbit}
                  onChange={(v) => set({ orbit: v })}
                  label="8D HRTF Orbit"
                  hint="Audio-rate oscillators pan sound around your head (started only when enabled)"
                />
                {s.orbit && (
                  <Slider
                    label="Orbit speed"
                    min={0.03}
                    max={0.6}
                    step={0.01}
                    value={s.orbitSpeed}
                    onChange={(v) => set({ orbitSpeed: v })}
                    fmt={(v) => `${(1 / v).toFixed(1)}s / rev`}
                  />
                )}
              </div>

              <div className="space-y-3">
                <b className="block text-xs">Transitions, ReplayGain &amp; Speed/Pitch</b>
                <Toggle
                  on={s.gapless}
                  onChange={(v) => set({ gapless: v })}
                  label="Gapless dual-deck playback"
                  hint="Pre-buffers next track on deck B for zero-gap transitions"
                />
                <Slider
                  label="Crossfade duration"
                  min={0}
                  max={12}
                  step={0.5}
                  value={s.crossfade}
                  onChange={(v) => set({ crossfade: v })}
                  fmt={(v) => (v ? `${v}s` : "Off")}
                />
                <Slider
                  label="Play / pause smooth fade"
                  min={0}
                  max={600}
                  step={20}
                  value={s.playPauseFadeMs}
                  onChange={(v) => set({ playPauseFadeMs: v })}
                  fmt={(v) => (v ? `${v} ms` : "Instant")}
                />
                <Slider
                  label="ReplayGain pre-amp"
                  min={-6}
                  max={6}
                  step={0.5}
                  value={s.rgPreamp}
                  onChange={(v) => set({ rgPreamp: v })}
                />
                <Slider
                  label="Playback speed"
                  min={0.5}
                  max={2}
                  step={0.01}
                  value={s.speed}
                  onChange={(v) => set({ speed: v })}
                  fmt={(v) => `${v.toFixed(2)}×`}
                />
                <div className="flex flex-wrap items-center gap-1.5 pt-1">
                  <Chip active={Math.abs(s.speed - 432 / 440) < 0.002 && !s.keepPitch} onClick={() => set({ speed: Number((432 / 440).toFixed(4)), keepPitch: false })}>
                    432Hz Natural
                  </Chip>
                  <Chip active={s.speed === 0.85 && !s.keepPitch} onClick={() => set({ speed: 0.85, keepPitch: false, room: Math.max(s.room, 0.25) })}>
                    Slowed + Reverb
                  </Chip>
                  <Chip active={s.speed === 1 && s.keepPitch} onClick={() => set({ speed: 1, keepPitch: true })}>
                    1.0× Normal
                  </Chip>
                  <Chip active={s.speed === 1.25 && !s.keepPitch} onClick={() => set({ speed: 1.25, keepPitch: false })}>
                    Nightcore
                  </Chip>
                  <Chip
                    onClick={() => {
                      const nextSt = Math.max(-12, semitones - 1);
                      set({ speed: Number(Math.pow(2, nextSt / 12).toFixed(4)), keepPitch: false });
                    }}
                  >
                    -1 Semitone
                  </Chip>
                  <Chip
                    onClick={() => {
                      const nextSt = Math.min(12, semitones + 1);
                      set({ speed: Number(Math.pow(2, nextSt / 12).toFixed(4)), keepPitch: false });
                    }}
                  >
                    +1 Semitone
                  </Chip>
                </div>
              </div>
            </div>
          </div>
        )}
      </div>

      {/* Sonora Visual Analyzer Card */}
      <div className="sound-panel secondary-sound">
        <div className="sound-panel-head">
          <span className="sound-icon">
            <Icon n="wave" s={23} />
          </span>
          <div>
            <span className="eyebrow">EXPLORE THE DETAILS</span>
            <h2>Visual analyzer</h2>
          </div>
        </div>
        <p>
          Inspect full-track FFT spectrograms (including hidden spectrogram art), live spectrum, oscilloscope, and
          stereo vectorscope.
        </p>
        <button type="button" className="outline-button" onClick={onOpenVisual}>
          Open spectrogram <Icon n="right" s={16} />
        </button>
      </div>

      {/* Sonora Output Profile Card wired to B's devices.ts */}
      <div className="sound-panel secondary-sound">
        <div className="sound-panel-head">
          <span className="sound-icon">
            <Icon n="headphones" s={23} />
          </span>
          <div>
            <span className="eyebrow">PERSONAL LISTENING</span>
            <h2>Output profile</h2>
          </div>
        </div>
        <p>
          Save this EQ &amp; spatial setup for your selected output device. Profiles apply automatically when a bound
          headset connects.
        </p>
        <div className="output-row">
          <select
            value={outputId}
            onChange={(e) => {
              const id = e.target.value;
              engine.setOutput(id);
              toast("Audio output switched");
            }}
            aria-label="Audio output"
          >
            <option value="default">System default output</option>
            {outs
              .filter((d) => d.id && d.id !== "default")
              .map((d, i) => (
                <option value={d.id} key={d.id}>
                  {d.label || `Audio output ${i + 1}`}
                </option>
              ))}
          </select>
          <input
            value={profileName}
            onChange={(e) => setProfileName(e.target.value)}
            placeholder="Profile name (e.g. Buds Pro)"
            aria-label="Profile name"
            className="rounded border border-line bg-surface2 px-3 py-1.5 text-xs text-fg outline-none focus:border-accent"
          />
          <button
            type="button"
            className="accent-button"
            onClick={() => {
              const name = profileName.trim() || `Output ${s.profiles.length + 1}`;
              const activeDev = outs.find((o) => o.id === outputId);
              saveProfile(name, activeDev?.key);
              setProfileName("");
              toast(`Saved profile "${name}"`);
            }}
          >
            Save profile
          </button>
        </div>

        {s.profiles.length > 0 && (
          <div className="mt-4 space-y-2">
            {s.profiles.map((p) => (
              <div
                key={p.id}
                className="flex items-center justify-between gap-2 rounded-md border border-line bg-surface2 px-3 py-2 text-xs"
              >
                <span className="font-bold text-fg">{p.name}</span>
                <div className="flex items-center gap-2">
                  <Chip
                    onClick={() => {
                      applyProfile(p);
                      toast(`Applied "${p.name}"`);
                    }}
                  >
                    Apply
                  </Chip>
                  <button
                    type="button"
                    className="text-muted hover:text-fg"
                    aria-label={`Delete profile ${p.name}`}
                    onClick={() => set({ profiles: s.profiles.filter((x) => x.id !== p.id) })}
                  >
                    <Icon n="x" s={14} />
                  </button>
                </div>
              </div>
            ))}
          </div>
        )}

        {!named && (
          <div className="mt-3">
            <button type="button" className="outline-button text-xs" onClick={requestDeviceNames}>
              Unlock device names (requests audio permission once)
            </button>
          </div>
        )}
      </div>
    </div>
  );
}
