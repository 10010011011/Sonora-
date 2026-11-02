import { useEffect, useRef, useState } from "react";
import { useStore, settings, clock, fmt } from "./store";
import { engine, type FullAnalysis } from "./engine";
import { useCurrent } from "./Player";
import { LiveCanvas, drawSpectrum, lut, MAPS, themeColors, type Draw } from "./SharedAudioViews";
import { Chip, Slider } from "./ui";

let fbuf: Uint8Array<ArrayBuffer> | null = null;
let tbuf: Float32Array<ArrayBuffer> | null = null,
  lbuf: Float32Array<ArrayBuffer> | null = null,
  rbuf: Float32Array<ArrayBuffer> | null = null;

const drawScope: Draw = (g, W, H, col) => {
  const an = engine.analyser;
  if (!an) return;
  if (!tbuf || tbuf.length !== an.fftSize) tbuf = new Float32Array(an.fftSize);
  an.getFloatTimeDomainData(tbuf);
  g.clearRect(0, 0, W, H);
  g.strokeStyle = col.line;
  g.beginPath();
  g.moveTo(0, H / 2);
  g.lineTo(W, H / 2);
  g.stroke();
  let s = 0;
  for (let i = 1; i < tbuf.length / 2; i++)
    if (tbuf[i - 1] < 0 && tbuf[i] >= 0) {
      s = i;
      break;
    }
  const n = Math.min(1024, tbuf.length - s);
  g.strokeStyle = col.a;
  g.lineWidth = 2 * devicePixelRatio;
  g.beginPath();
  for (let i = 0; i < n; i++) (i ? g.lineTo : g.moveTo).call(g, (i / n) * W, H / 2 - tbuf[s + i] * H * 0.45);
  g.stroke();
};

const drawVector: Draw = (g, W, H, col) => {
  if (!engine.aL) return;
  const n = engine.aL.fftSize;
  if (!lbuf || lbuf.length !== n) {
    lbuf = new Float32Array(n);
    rbuf = new Float32Array(n);
  }
  engine.aL.getFloatTimeDomainData(lbuf);
  engine.aR.getFloatTimeDomainData(rbuf!);
  g.globalAlpha = 0.25;
  g.fillStyle = col.bg;
  g.fillRect(0, 0, W, H);
  g.globalAlpha = 1;
  const cx = W / 2,
    cy = H / 2,
    r = Math.min(W, H) * 0.45;
  g.strokeStyle = col.line;
  g.beginPath();
  g.moveTo(cx, cy - r);
  g.lineTo(cx, cy + r);
  g.moveTo(cx - r, cy);
  g.lineTo(cx + r, cy);
  g.stroke();
  g.fillStyle = col.a;
  let corr = 0,
    el = 0,
    er = 0;
  for (let i = 0; i < n; i += 2) {
    const L = lbuf[i],
      R = rbuf![i];
    corr += L * R;
    el += L * L;
    er += R * R;
    g.fillRect(
      cx + ((L - R) / Math.SQRT2) * r,
      cy - ((L + R) / Math.SQRT2) * r,
      1.6 * devicePixelRatio,
      1.6 * devicePixelRatio
    );
  }
  const c = corr / Math.sqrt(el * er + 1e-12);
  g.fillStyle = col.b;
  g.font = `${11 * devicePixelRatio}px monospace`;
  g.fillText(
    `correlation ${c.toFixed(2)}  (Mid vertical · Side horizontal)`,
    8 * devicePixelRatio,
    H - 8 * devicePixelRatio
  );
};

const drawLiveSpec =
  (map: string): Draw =>
  (g, W, H) => {
    const an = engine.analyser;
    if (!an) return;
    if (!fbuf || fbuf.length !== an.frequencyBinCount) fbuf = new Uint8Array(an.frequencyBinCount);
    an.getByteFrequencyData(fbuf);
    const step = Math.max(1, Math.round(2 * devicePixelRatio));
    g.drawImage(g.canvas, -step, 0);
    const L = lut(map);
    const img = g.createImageData(step, H);
    const nb = fbuf.length;
    for (let y = 0; y < H; y++) {
      const fr = 1 - y / H;
      const b = Math.min(nb - 1, Math.floor(Math.pow(nb, fr)));
      const v = fbuf[b];
      for (let x = 0; x < step; x++) {
        const o = (y * step + x) * 4;
        img.data[o] = L[v * 3];
        img.data[o + 1] = L[v * 3 + 1];
        img.data[o + 2] = L[v * 3 + 2];
        img.data[o + 3] = 255;
      }
    }
    g.putImageData(img, W - step, 0);
  };

function FullTrack({ kind }: { kind: "wave" | "spec" }) {
  const t = useCurrent();
  const s = useStore(settings, (x) => x);
  const [res, setRes] = useState<FullAnalysis | null>(null);
  const [busy, setBusy] = useState(false);
  const [floor, setFloor] = useState(-95);
  const [hover, setHover] = useState<string>("");
  const ref = useRef<HTMLCanvasElement>(null);
  const time = useStore(clock, (x) => x.t);
  const cols = Math.max(1000, Math.min(3000, Math.round((t?.duration ?? 240) * 8)));

  useEffect(() => {
    if (!t) return;
    let alive = true;
    setBusy(true);
    engine
      .analyze(t, kind === "spec" ? s.fftSize : 1024, cols, kind === "spec")
      .then((r) => alive && setRes(r))
      .catch(() => alive && setRes(null))
      .finally(() => alive && setBusy(false));
    return () => {
      alive = false;
    };
  }, [t, s.fftSize, kind]);

  useEffect(() => {
    const c = ref.current;
    if (!c || !res) return;
    const g = c.getContext("2d")!;
    const col = themeColors();
    if (kind === "wave") {
      const W = (c.width = c.clientWidth * devicePixelRatio),
        H = (c.height = c.clientHeight * devicePixelRatio);
      g.clearRect(0, 0, W, H);
      const grad = g.createLinearGradient(0, 0, 0, H);
      grad.addColorStop(0, col.a);
      grad.addColorStop(0.5, col.b);
      grad.addColorStop(1, col.a);
      g.fillStyle = grad;
      for (let x = 0; x < W; x++) {
        const ci = Math.floor((x / W) * res.cols);
        const mn = res.peaks[ci * 2],
          mx = res.peaks[ci * 2 + 1];
        g.fillRect(x, H / 2 - mx * H * 0.48, 1, Math.max(1, (mx - mn) * H * 0.48));
      }
      return;
    }
    if (!res.spec) return;
    const H = 360;
    c.width = res.cols;
    c.height = H;
    const img = g.createImageData(res.cols, H);
    const L = lut(s.colormap);
    const ny = res.sr / 2;
    const lo = ((floor + 110) / 110) * 255;
    const scale = 255 / Math.max(1, 255 - lo);
    const rowBin = new Uint32Array(H),
      rowW = new Float32Array(H);
    for (let y = 0; y < H; y++) {
      const fr = 1 - y / (H - 1);
      const f = s.specLog ? 20 * Math.pow(ny / 20, fr) : fr * ny;
      const fb = Math.min(res.bins - 1.001, (f / ny) * res.bins);
      rowBin[y] = Math.floor(fb);
      rowW[y] = fb - rowBin[y];
    }
    const spec = res.spec;
    for (let x = 0; x < res.cols; x++) {
      const off = x * res.bins;
      for (let y = 0; y < H; y++) {
        const b0 = off + rowBin[y];
        let v = (spec[b0] * (1 - rowW[y]) + spec[b0 + 1] * rowW[y] - lo) * scale;
        v = v < 0 ? 0 : v > 255 ? 255 : v | 0;
        const o = (y * res.cols + x) * 4;
        img.data[o] = L[v * 3];
        img.data[o + 1] = L[v * 3 + 1];
        img.data[o + 2] = L[v * 3 + 2];
        img.data[o + 3] = 255;
      }
    }
    g.putImageData(img, 0, 0);
  }, [res, s.colormap, s.specLog, floor, kind, s.theme]);

  if (!t) return <div className="p-8 text-center text-xs text-muted">Select a track first.</div>;
  const dur = res?.duration || t.duration || 20;

  const onMove = (e: React.PointerEvent) => {
    if (!res) return;
    const r = e.currentTarget.getBoundingClientRect();
    const fx = (e.clientX - r.left) / r.width,
      fy = 1 - (e.clientY - r.top) / r.height;
    const ny = res.sr / 2;
    const f = s.specLog ? 20 * Math.pow(ny / 20, fy) : fy * ny;
    setHover(`${fmt(fx * dur)}${kind === "spec" ? ` · ${f >= 1000 ? (f / 1000).toFixed(2) + " kHz" : Math.round(f) + " Hz"}` : ""}`);
  };

  return (
    <div className="flex flex-col gap-3">
      {kind === "spec" && (
        <div className="flex flex-wrap items-center gap-2">
          {Object.keys(MAPS).map((m) => (
            <Chip key={m} active={s.colormap === m} onClick={() => settings.set({ colormap: m as any })}>
              {m}
            </Chip>
          ))}
          <span className="mx-1 h-4 w-px bg-line" />
          <Chip active={!s.specLog} onClick={() => settings.set({ specLog: false })}>
            Linear Hz
          </Chip>
          <Chip active={s.specLog} onClick={() => settings.set({ specLog: true })}>
            Log Hz
          </Chip>
          <span className="mx-1 h-4 w-px bg-line" />
          {[1024, 2048, 4096].map((n) => (
            <Chip key={n} active={s.fftSize === n} onClick={() => settings.set({ fftSize: n })}>
              FFT {n}
            </Chip>
          ))}
          <div className="ml-auto w-44">
            <Slider label="Floor" min={-110} max={-45} step={1} value={floor} onChange={setFloor} fmt={(v) => `${v} dB`} />
          </div>
        </div>
      )}
      <div
        className="relative h-72 w-full overflow-hidden rounded-lg border border-line bg-black/50"
        onPointerMove={onMove}
        onPointerLeave={() => setHover("")}
        onClick={(e) => {
          const r = e.currentTarget.getBoundingClientRect();
          engine.seek(((e.clientX - r.left) / r.width) * dur);
        }}
      >
        <canvas ref={ref} className="h-full w-full cursor-crosshair" />
        <div
          className="pointer-events-none absolute inset-y-0 w-0.5 bg-white/90 shadow-[0_0_8px_white]"
          style={{ left: `${Math.min(100, (time / dur) * 100)}%` }}
        />
        {busy && (
          <div className="absolute inset-0 flex items-center justify-center bg-black/50 text-xs font-bold text-white">
            Analysing…
          </div>
        )}
        {hover && (
          <div className="pointer-events-none absolute left-2 top-2 rounded bg-black/75 px-2 py-0.5 font-mono text-[11px] text-white">
            {hover}
          </div>
        )}
        {kind === "spec" && res && (
          <div className="pointer-events-none absolute right-2 top-2 rounded bg-black/65 px-2 py-0.5 font-mono text-[10px] text-white/80">
            {(res.sr / 2000).toFixed(1)} kHz · {res.bins} bins · {res.cols} frames
          </div>
        )}
      </div>
      {res && (
        <p className="text-[11px] text-muted tabular-nums">
          Integrated ≈ {res.lufs.toFixed(1)} LUFS · Peak {(20 * Math.log10(res.peak + 1e-9)).toFixed(1)} dBFS · ReplayGain ≈{" "}
          {(-18 - res.lufs).toFixed(1)} dB
        </p>
      )}
    </div>
  );
}

const TABS = [
  ["spec", "Spectrogram (Full FFT)"],
  ["wave", "Full Waveform"],
  ["spectrum", "Live Spectrum"],
  ["scope", "Oscilloscope"],
  ["vector", "Stereo Vectorscope"],
  ["live", "Live Spectrogram"],
] as const;

export default function Visual({ onBack }: { onBack?: () => void }) {
  const [tab, setTab] = useState<(typeof TABS)[number][0]>("spec");
  const map = useStore(settings, (s) => s.colormap);
  const track = useCurrent();

  return (
    <div className="main-content inner-content">
      <div className="flex items-center justify-between gap-3">
        <div className="section-kicker">
          <span /> FREQUENCY &amp; PHASE LAB
        </div>
        {onBack && (
          <button type="button" className="outline-button" onClick={onBack}>
            Back to Sound lab
          </button>
        )}
      </div>
      <h1>
        Visual <em>analyzer.</em>
      </h1>
      <p className="subtitle">
        <strong className="text-fg">{track?.title || "No track playing"}</strong>
      </p>

      <div className="sound-panel">
        <div className="mb-4 flex flex-wrap gap-2">
          {TABS.map(([k, l]) => (
            <Chip key={k} active={tab === k} onClick={() => setTab(k)}>
              {l}
            </Chip>
          ))}
        </div>

        <div className="min-h-[300px]">
          {tab === "spectrum" && (
            <LiveCanvas draw={drawSpectrum(96)} className="h-72 w-full rounded-lg border border-line bg-surface" />
          )}
          {tab === "scope" && (
            <LiveCanvas draw={drawScope} className="h-72 w-full rounded-lg border border-line bg-surface" />
          )}
          {tab === "vector" && (
            <LiveCanvas
              draw={drawVector}
              className="mx-auto aspect-square h-72 max-w-full rounded-lg border border-line bg-surface"
            />
          )}
          {tab === "live" && (
            <LiveCanvas key={map} draw={drawLiveSpec(map)} className="h-72 w-full rounded-lg border border-line bg-black" />
          )}
          {tab === "wave" && <FullTrack kind="wave" />}
          {tab === "spec" && <FullTrack kind="spec" />}
        </div>
      </div>
    </div>
  );
}
