import { useEffect, useRef, useState } from "react";
import { useStore, lyricsStore, clock, settings, player, toast, type Lyrics, type Track } from "./store";
import { loadLyricsFor } from "./library";
import { activeIndex } from "./lyrics";
import { LyricsScroller, LyricText } from "./LyricsScroller";
import { engine, STARTER_BARS } from "./engine";
import { useCurrent } from "./Player";
import { Icon, Btn, Cover } from "./ui";

export const MAPS: Record<string, number[][]> = {
  miku: [
    [6, 12, 14],
    [12, 58, 64],
    [22, 126, 132],
    [57, 197, 187],
    [225, 40, 133],
    [255, 235, 248],
  ],
  teto: [
    [14, 6, 9],
    [62, 16, 26],
    [138, 28, 48],
    [232, 69, 90],
    [233, 181, 111],
    [255, 244, 224],
  ],
  neru: [
    [12, 11, 6],
    [58, 48, 12],
    [142, 108, 16],
    [255, 138, 31],
    [255, 214, 10],
    [255, 252, 225],
  ],
  magma: [
    [0, 0, 4],
    [40, 11, 84],
    [101, 21, 110],
    [159, 42, 99],
    [212, 72, 66],
    [245, 125, 21],
    [250, 193, 39],
    [252, 253, 191],
  ],
  viridis: [
    [68, 1, 84],
    [72, 40, 120],
    [62, 74, 137],
    [49, 104, 142],
    [38, 130, 142],
    [31, 158, 137],
    [53, 183, 121],
    [109, 205, 89],
    [180, 222, 44],
    [253, 231, 37],
  ],
  gray: [
    [0, 0, 0],
    [255, 255, 255],
  ],
};

const lutCache = new Map<string, Uint8Array>();
export function lut(name: string) {
  let l = lutCache.get(name);
  if (l) return l;
  const st = MAPS[name] || MAPS.miku;
  l = new Uint8Array(256 * 3);
  for (let i = 0; i < 256; i++) {
    const x = (i / 255) * (st.length - 1);
    const a = Math.floor(x),
      b = Math.min(st.length - 1, a + 1),
      f = x - a;
    for (let c = 0; c < 3; c++) l[i * 3 + c] = st[a][c] + (st[b][c] - st[a][c]) * f;
  }
  lutCache.set(name, l);
  return l;
}

export function themeColors() {
  const c = getComputedStyle(document.documentElement);
  return {
    a: c.getPropertyValue("--accent").trim() || "#cbef79",
    b: c.getPropertyValue("--accent2").trim() || "#39c5bb",
    bg: c.getPropertyValue("--panel").trim() || "#171b1c",
    line: c.getPropertyValue("--border").trim() || "#2a3030",
  };
}

export type Draw = (g: CanvasRenderingContext2D, W: number, H: number, col: ReturnType<typeof themeColors>) => void;

export function LiveCanvas({ draw, className = "" }: { draw: Draw; className?: string }) {
  const ref = useRef<HTMLCanvasElement>(null);
  const theme = useStore(settings, (s) => s.theme);
  const playing = useStore(player, (s) => s.playing);
  const drawRef = useRef(draw);
  drawRef.current = draw;

  useEffect(() => {
    const c = ref.current;
    if (!c) return;
    const g = c.getContext("2d")!;
    const col = themeColors();
    let raf = 0,
      idle = 0;
    const loop = () => {
      const W = c.clientWidth * devicePixelRatio,
        H = c.clientHeight * devicePixelRatio;
      if (c.width !== W || c.height !== H) {
        c.width = W;
        c.height = H;
      }
      if (engine.ctx && !document.hidden) drawRef.current(g, W, H, col);
      else if (!engine.ctx) {
        g.clearRect(0, 0, W, H);
        g.strokeStyle = col.line;
        g.beginPath();
        g.moveTo(0, H / 2);
        g.lineTo(W, H / 2);
        g.stroke();
      }
      idle = playing ? 0 : idle + 1;
      if (idle < 70) raf = requestAnimationFrame(loop);
    };
    raf = requestAnimationFrame(loop);
    return () => cancelAnimationFrame(raf);
  }, [theme, playing]);

  return <canvas ref={ref} className={className} />;
}

let fbufShared: Uint8Array<ArrayBuffer> | null = null;
const peaksArrShared = new Float32Array(128);

export const drawSpectrum =
  (bars = 72): Draw =>
  (g, W, H, col) => {
    const an = engine.analyser;
    if (!an) return;
    if (!fbufShared || fbufShared.length !== an.frequencyBinCount) {
      fbufShared = new Uint8Array(an.frequencyBinCount);
    }
    an.getByteFrequencyData(fbufShared);
    g.clearRect(0, 0, W, H);
    const ny = engine.ctx!.sampleRate / 2;
    const bw = W / bars;
    const grad = g.createLinearGradient(0, H, 0, 0);
    grad.addColorStop(0, col.a);
    grad.addColorStop(1, col.b);
    g.fillStyle = grad;
    for (let i = 0; i < bars; i++) {
      const f0 = 30 * Math.pow(18000 / 30, i / bars),
        f1 = 30 * Math.pow(18000 / 30, (i + 1) / bars);
      const b0 = Math.floor((f0 / ny) * fbufShared.length),
        b1 = Math.max(b0 + 1, Math.floor((f1 / ny) * fbufShared.length));
      let m = 0;
      for (let b = b0; b < b1; b++) m = Math.max(m, fbufShared[b]);
      const v = (m / 255) ** 1.6;
      const h = v * H * 0.95;
      g.fillRect(i * bw + 1, H - h, bw - 2, h);
      peaksArrShared[i] = Math.max(peaksArrShared[i] - H * 0.006, h);
      g.fillRect(i * bw + 1, H - peaksArrShared[i] - 3, bw - 2, 2);
    }
  };

export function useLyrics(t?: Track) {
  const l = useStore(lyricsStore, (s) => (t ? s.map[t.id] : undefined));
  useEffect(() => {
    if (t) loadLyricsFor(t);
  }, [t]);
  return l;
}

export function RightLyricsPanel({ onOpenStudio }: { onOpenStudio: () => void }) {
  const track = useCurrent();

  return (
    <aside className="lyrics-panel">
      <div className="lyrics-top">
        <div>
          <span className="eyebrow">THE WORDS BEHIND THE MUSIC</span>
          <h2>Live lyrics<span>.</span></h2>
        </div>
        <button type="button" title="Open lyrics studio" aria-label="Open lyrics studio" onClick={onOpenStudio}>
          <Icon n="more" s={21} />
        </button>
      </div>

      <div className="lyrics-track">
        <Cover src={track?.cover} size={42} className="mini-cover" alt={track?.title || ""} />
        <div>
          <b>{track?.title || "No track"}</b>
          <span>{track?.artist || "Sonora"}</span>
        </div>
        <Icon n="audio-lines" s={19} className="lyric-track-icon" />
      </div>

      <LyricsScroller className="lyrics-body" onOpenStudio={onOpenStudio} />

      <div className="lyrics-bottom">
        <button type="button" onClick={onOpenStudio}>
          <span className="lyrics-edit-icon"><Icon n="mic" s={18} /></span>
          <span><b>Make the words yours</b><small>Import, edit &amp; sync lyrics</small></span>
          <Icon n="right" s={17} />
        </button>
      </div>
    </aside>
  );
}

export function Calibrate({ onClose }: { onClose: () => void }) {
  const [phase, setPhase] = useState<"idle" | "run" | "done">("idle");
  const [count, setCount] = useState(0);
  const [result, setResult] = useState<number | null>(null);
  const beats = useRef<number[]>([]);
  const taps = useRef<number[]>([]);
  const BEAT = 0.6,
    N = 16,
    COUNTIN = 4;

  const start = async () => {
    engine.pause();
    const ctx = engine.ensure();
    await ctx.resume();
    const t0 = ctx.currentTime + 0.4;
    beats.current = [];
    taps.current = [];
    for (let i = 0; i < N; i++) {
      const bt = t0 + i * BEAT;
      beats.current.push(bt);
      const o = ctx.createOscillator(),
        g = ctx.createGain();
      o.frequency.value = i % 4 === 0 ? 1600 : 1000;
      g.gain.setValueAtTime(0.0001, bt);
      g.gain.exponentialRampToValueAtTime(0.5, bt + 0.002);
      g.gain.exponentialRampToValueAtTime(0.0001, bt + 0.05);
      o.connect(g).connect(ctx.destination);
      o.start(bt);
      o.stop(bt + 0.06);
    }
    setPhase("run");
    setCount(0);
    setTimeout(() => {
      const d = taps.current.sort((a, b) => a - b);
      if (d.length < 4) {
        setResult(null);
        setPhase("done");
        return;
      }
      const med = d[Math.floor(d.length / 2)];
      setResult(Math.max(0, Math.min(400, Math.round(med * 1000))));
      setPhase("done");
    }, (N * BEAT + 0.9) * 1000);
  };

  const tap = () => {
    if (phase !== "run" || !engine.ctx) return;
    const now = engine.ctx.currentTime;
    const b = beats.current.reduce((p, c) => (Math.abs(c - now) < Math.abs(p - now) ? c : p), Infinity);
    const bi = beats.current.indexOf(b);
    const d = now - b;
    if (bi >= COUNTIN && Math.abs(d) < BEAT / 2) taps.current.push(d);
    setCount((c) => c + 1);
  };

  return (
    <div className="modal-backdrop" onMouseDown={(e) => e.target === e.currentTarget && onClose()}>
      <div className="settings-modal max-w-sm p-6 text-center" onClick={(e) => e.stopPropagation()}>
        <div className="modal-head px-0 pt-0">
          <div className="text-left">
            <span className="eyebrow">PRECISION TIMING</span>
            <h2>Tap-latency calibration</h2>
          </div>
          <button type="button" onClick={onClose} aria-label="Close">
            <Icon n="x" s={20} />
          </button>
        </div>
        <p className="my-4 text-xs leading-relaxed text-muted">
          You will hear 16 clicks. Let the first 4 pass, then tap the pad on each click. We measure your reaction +
          audio output delay and subtract it from every tap-sync stamp.
        </p>
        <button
          type="button"
          onPointerDown={(e) => {
            e.preventDefault();
            phase === "run" ? tap() : phase === "idle" && start();
          }}
          className="mx-auto my-2 flex h-32 w-32 select-none items-center justify-center rounded-full bg-accent text-lg font-extrabold text-onaccent transition active:scale-95"
        >
          {phase === "idle" ? "START" : phase === "run" ? `TAP ${count}` : result != null ? `${result} ms` : "RETRY"}
        </button>
        {phase === "done" && (
          <div className="mt-3 flex justify-center gap-2">
            <Btn
              onClick={() => {
                setPhase("idle");
                setResult(null);
              }}
            >
              Again
            </Btn>
            {result != null && (
              <Btn
                primary
                onClick={() => {
                  settings.set({ tapLatency: result });
                  toast(`Tap latency set to ${result} ms`);
                  onClose();
                }}
              >
                Use {result} ms
              </Btn>
            )}
          </div>
        )}
      </div>
    </div>
  );
}
