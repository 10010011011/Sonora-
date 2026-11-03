import { settings, library, player, clock, toast, applyMidsToEq, type Settings, type Track, EQ_FREQS } from "./store";
import { kvGet, kvSet } from "./db";
import { diag, diagTick } from "./diag";
import { getTrackAudioConfig } from "./userdata";

const dB = (x: number) => Math.pow(10, x / 20);
const MAX_ANALYZE_BYTES = 45 * 1024 * 1024; // Skip full decode analysis for files > 45 MB
const MAX_ANALYZE_SECONDS = 10 * 60; // ...and for tracks longer than 10 minutes (decoded PCM is ~100 MB per 10 min)
/** Formats whose frames resync mid-stream, so a leading byte range still decodes (used for the ReplayGain pass). */
const WINDOWABLE = /\.(mp3|ogg|oga|opus|aac)$/i;

/** One mono copy (the analysis mixes down anyway); avoids holding per-channel duplicates of the decoded audio. */
function mixToMono(buf: AudioBuffer): Float32Array {
  const n = buf.length;
  const chs = buf.numberOfChannels;
  const out = new Float32Array(n);
  if (chs === 1) {
    out.set(buf.getChannelData(0));
    return out;
  }
  const inv = 1 / chs;
  for (let c = 0; c < chs; c++) {
    const ch = buf.getChannelData(c);
    for (let i = 0; i < n; i++) out[i] += ch[i] * inv;
  }
  return out;
}

export interface FullAnalysis {
  key: string;
  peaks: Float32Array;
  bars180: number[];
  spec: Uint8Array | null;
  bins: number;
  cols: number;
  sr: number;
  duration: number;
  lufs: number;
  peak: number;
}

export const STARTER_BARS = Array.from(
  { length: 180 },
  (_, i) => 0.14 + 0.52 * Math.abs(Math.sin(i * 2.31) * Math.cos(i * 0.19)) + 0.2 * Math.abs(Math.sin(i * 0.067))
);

/** Generates a deterministic 96-bar waveform signature for a track when full PCM analysis hasn't run yet. */
export function waveformSignatureForTrack(t?: Track | null, count = 96): number[] {
  if (!t) return STARTER_BARS.slice(0, count);
  const cached = waveformCache.get(t.id);
  if (cached && cached.length === count) return cached;
  let seed = 2166136261;
  const str = `${t.id}|${t.title}|${t.duration ?? 0}`;
  for (let i = 0; i < str.length; i++) {
    seed ^= str.charCodeAt(i);
    seed = Math.imul(seed, 16777619);
  }
  const bars: number[] = [];
  for (let i = 0; i < count; i++) {
    const pos = i / Math.max(1, count - 1);
    const env = Math.sin(pos * Math.PI); // natural intro/outro taper
    const h1 = Math.abs(Math.sin(i * 1.73 + (seed & 0xff) * 0.05));
    const h2 = Math.abs(Math.cos(i * 0.41 + ((seed >> 8) & 0xff) * 0.03));
    const h3 = Math.abs(Math.sin(i * 3.17 + ((seed >> 16) & 0xff) * 0.07));
    const val = Math.max(0.14, Math.min(0.96, (0.2 + 0.55 * (h1 * 0.5 + h2 * 0.3 + h3 * 0.2)) * (0.45 + 0.55 * env)));
    bars.push(val);
  }
  waveformCache.set(t.id, bars);
  return bars;
}

const waveformCache = new Map<string, number[]>();

// Fast FFT lookup tables shared with worker fallback
const fftTables = new Map<number, { cos: Float32Array; sin: Float32Array; rev: Uint32Array; win: Float32Array }>();
function getFftTables(n: number) {
  let t = fftTables.get(n);
  if (t) return t;
  const cos = new Float32Array(n / 2),
    sin = new Float32Array(n / 2),
    rev = new Uint32Array(n),
    win = new Float32Array(n);
  for (let i = 0; i < n / 2; i++) {
    cos[i] = Math.cos((2 * Math.PI * i) / n);
    sin[i] = -Math.sin((2 * Math.PI * i) / n);
  }
  const bits = Math.log2(n);
  for (let i = 0; i < n; i++) {
    let r = 0;
    for (let b = 0; b < bits; b++) r |= ((i >> b) & 1) << (bits - 1 - b);
    rev[i] = r;
    win[i] = 0.5 - 0.5 * Math.cos((2 * Math.PI * i) / (n - 1));
  }
  t = { cos, sin, rev, win };
  fftTables.set(n, t);
  return t;
}

function runRadix2Fft(re: Float32Array, im: Float32Array, n: number) {
  const { cos, sin, rev } = getFftTables(n);
  for (let i = 0; i < n; i++) {
    const j = rev[i];
    if (j > i) {
      let tmp = re[i];
      re[i] = re[j];
      re[j] = tmp;
      tmp = im[i];
      im[i] = im[j];
      im[j] = tmp;
    }
  }
  for (let size = 2; size <= n; size <<= 1) {
    const half = size >> 1,
      step = n / size;
    for (let i = 0; i < n; i += size) {
      for (let j = 0, k = 0; j < half; j++, k += step) {
        const a = i + j,
          b = a + half;
        const tr = re[b] * cos[k] - im[b] * sin[k];
        const ti = re[b] * sin[k] + im[b] * cos[k];
        re[b] = re[a] - tr;
        im[b] = im[a] - ti;
        re[a] += tr;
        im[a] += ti;
      }
    }
  }
}

function computeAnalysisSync(
  channels: Float32Array[],
  sr: number,
  fftSize: number,
  cols: number,
  spectro: boolean
) {
  const chs = channels.length;
  const N = channels[0]?.length ?? 0;
  let data: Float32Array;
  if (chs === 1) {
    data = channels[0];
  } else {
    data = new Float32Array(N);
    const inv = 1 / Math.max(1, chs);
    for (let c = 0; c < chs; c++) {
      const ch = channels[c];
      for (let i = 0; i < N; i++) data[i] += ch[i] * inv;
    }
  }

  const peaks = new Float32Array(cols * 2);
  const seg = N / cols;
  let maxPeak = 0;
  for (let c = 0; c < cols; c++) {
    let mn = 1,
      mx = -1;
    const s = Math.floor(c * seg),
      en = Math.min(N, Math.floor((c + 1) * seg));
    for (let i = s; i < en; i++) {
      const v = data[i];
      if (v < mn) mn = v;
      if (v > mx) mx = v;
    }
    if (en <= s) {
      mn = 0;
      mx = 0;
    }
    peaks[c * 2] = mn;
    peaks[c * 2 + 1] = mx;
    maxPeak = Math.max(maxPeak, Math.abs(mn), Math.abs(mx));
  }

  const blk = Math.max(16, Math.floor(sr * 0.4)),
    hop = Math.max(4, Math.floor(blk / 4));
  const energies: number[] = [];
  let prev = 0;
  const emph = new Float32Array(N);
  for (let i = 0; i < N; i++) {
    const x = data[i];
    emph[i] = x + 0.6 * (x - prev);
    prev = x;
  }
  for (let s = 0; s + blk <= N; s += hop) {
    let acc = 0;
    for (let i = s; i < s + blk; i += 2) acc += emph[i] * emph[i];
    energies.push(acc / (blk / 2));
  }
  const L = (z: number) => -0.691 + 10 * Math.log10(z + 1e-12);
  let gated = energies.filter((z) => L(z) > -70);
  const rel = L(gated.reduce((a, b) => a + b, 0) / Math.max(1, gated.length)) - 10;
  gated = gated.filter((z) => L(z) > rel);
  const lufs = L(gated.reduce((a, b) => a + b, 0) / Math.max(1, gated.length));

  let spec: Uint8Array | null = null;
  if (spectro) {
    const n = fftSize,
      bins = n / 2;
    const { win } = getFftTables(n);
    spec = new Uint8Array(cols * bins);
    const re = new Float32Array(n),
      im = new Float32Array(n);
    const norm = 2 / (n * 0.5);
    // One column can span far more audio than one FFT window, so analyse several overlapping frames per
    // column and keep the loudest value per bin; transients and short notes stay visible.
    const sub = Math.max(1, Math.min(6, Math.ceil(seg / (n / 2))));
    const acc = new Uint8Array(bins);
    for (let c = 0; c < cols; c++) {
      acc.fill(0);
      for (let q = 0; q < sub; q++) {
        const center = Math.floor((c + (q + 0.5) / sub) * seg);
        const start = center - n / 2;
        for (let i = 0; i < n; i++) {
          const k = start + i;
          re[i] = k >= 0 && k < N ? data[k] * win[i] : 0;
          im[i] = 0;
        }
        runRadix2Fft(re, im, n);
        for (let b = 0; b < bins; b++) {
          const mag = Math.sqrt(re[b] * re[b] + im[b] * im[b]) * norm;
          const db = 20 * Math.log10(mag + 1e-9);
          const v = ((db + 110) / 110) * 255;
          const u = v < 0 ? 0 : v > 255 ? 255 : v;
          if (u > acc[b]) acc[b] = u;
        }
      }
      spec.set(acc, c * bins);
    }
  }

  return { peaks, spec, lufs, peak: maxPeak, bins: fftSize / 2, cols };
}

class Engine {
  ctx: AudioContext | null = null;
  els: HTMLAudioElement[];
  slotPos: (number | null)[] = [null, null];
  active = 0;

  private rg: GainNode[] = [];
  private fade: GainNode[] = [];
  private preamp!: GainNode;
  eq: BiquadFilterNode[] = [];
  bass!: BiquadFilterNode;
  treble!: BiquadFilterNode;

  // Spatial & Crossfeed stage (connected only when non-neutral)
  private split!: ChannelSplitterNode;
  private mx: GainNode[] = []; // LL, RL, LR, RR
  private cf: GainNode[] = [];
  private merger!: ChannelMergerNode;
  private spatialConnected: boolean | null = null;

  // Orbit 8D stage (oscillators created & started only when 8D is turned on)
  private orbitDry!: GainNode;
  private orbitWet!: GainNode;
  private panner!: PannerNode;
  private oscs: OscillatorNode[] | null = null;
  private orbitOn = false;
  private postOrbit!: GainNode;

  // Room ambience stage (connected only when room > 0)
  private roomDry!: GainNode;
  private roomWet!: GainNode;
  private conv: ConvolverNode | null = null;
  private roomOn = false;
  private postRoom!: GainNode;

  private boost!: GainNode;
  private limiter!: DynamicsCompressorNode;
  private limiterOn: boolean | null = null;
  private volume!: GainNode;
  private sleepGain!: GainNode;

  analyser!: AnalyserNode;
  aL!: AnalyserNode;
  aR!: AnalyserNode;

  private tickT: number | undefined;
  private gapT: number | undefined;
  private pauseFadeT: number | undefined;
  private crossfading = false;
  private suspendT: number | undefined;

  private worker: Worker | null = null;
  private workerFailed = false;
  private wid = 0;
  private wres = new Map<number, (v: any) => void>();
  private lastAnalysis: FullAnalysis | null = null;
  private tdBuf: Float32Array<ArrayBuffer> | null = null;
  private lastClockEmit = 0;

  /** Platform hooks. The Android bridge sets beforePlay to request audio focus. */
  hooks: {
    beforePlay?: () => Promise<boolean>;
    /** Where to start a track, in seconds. "prime" = restore/queue load without playing; "play" = the user picked it. */
    startAt?: (t: Track, kind: "prime" | "play") => number;
  } = {};
  private listeners = new Set<() => void>();
  private duckFactor = 1;
  private lastSpeed = 1;
  private lastTickAt = 0;
  private switchMarkAt = 0;

  /** Subscribe to transport changes (play state, track, duration, seek, speed). Returns an unsubscribe. */
  onChange(cb: () => void) {
    this.listeners.add(cb);
    return () => {
      this.listeners.delete(cb);
    };
  }

  private emit() {
    this.listeners.forEach((l) => l());
  }

  /** Lower output while another app briefly needs the speaker (audio focus "duck"). */
  setDuck(on: boolean) {
    this.duckFactor = on ? 0.25 : 1;
    this.apply(settings.get());
  }

  private focusDenied() {
    toast("Another app is using audio — try again");
  }

  constructor() {
    this.els = [new Audio(), new Audio()];
    this.els.forEach((el, i) => {
      el.preload = "auto";
      el.addEventListener("ended", () => i === this.active && this.onEnded());
      el.addEventListener("loadedmetadata", () => {
        const pos = this.slotPos[i];
        const t = pos != null ? this.trackAt(pos) : undefined;
        if (t && isFinite(el.duration) && el.duration > 0) t.duration = el.duration;
        if (i === this.active && isFinite(el.duration) && el.duration > 0) {
          player.set({ duration: el.duration });
          this.emit();
        }
      });
      el.addEventListener("timeupdate", () => i === this.active && this.checkSleep());
      el.addEventListener("playing", () => {
        if (i !== this.active || !this.switchMarkAt) return;
        diag("switch-latency", { ms: Math.round(performance.now() - this.switchMarkAt), hidden: document.hidden });
        this.switchMarkAt = 0;
      });
      el.addEventListener("play", () => i === this.active && this.onPlayState(true));
      el.addEventListener("pause", () => i === this.active && !this.crossfading && !el.ended && this.onPlayState(false));
      el.addEventListener("error", () => {
        if (i === this.active && el.src) toast("Cannot decode this audio file — skipping");
        if (i === this.active && el.src) setTimeout(() => this.next(), 400);
      });
    });
    this.applyElementSettings(settings.get());
    let prevVol = settings.get().volume;
    settings.sub(() => {
      const s = settings.get();
      if (s.pauseOnVolume0 && prevVol > 0 && s.volume === 0 && player.get().playing) {
        this.pause(true);
      }
      prevVol = s.volume;
      this.apply(s);
    });
    this.setupMediaSession();
  }

  private trackAt(pos: number): Track | undefined {
    const { tracks, queue } = library.get();
    return tracks[queue[pos]];
  }

  /* ---------------- Graph ---------------- */
  ensure() {
    if (this.ctx) return this.ctx;
    const ctx = new AudioContext({ latencyHint: "playback" });
    this.ctx = ctx;
    const g = (v = 1) => {
      const n = ctx.createGain();
      n.gain.value = v;
      return n;
    };

    this.preamp = g();
    this.preamp.channelCount = 2;
    this.preamp.channelCountMode = "explicit";
    this.preamp.channelInterpretation = "speakers";

    this.els.forEach((el, i) => {
      const src = ctx.createMediaElementSource(el);
      this.rg[i] = g();
      this.fade[i] = g(i === this.active ? 1 : 0);
      src.connect(this.rg[i]).connect(this.fade[i]).connect(this.preamp);
    });

    let node: AudioNode = this.preamp;
    this.eq = EQ_FREQS.map((f, i) => {
      const b = ctx.createBiquadFilter();
      b.type = i === 0 ? "lowshelf" : i === EQ_FREQS.length - 1 ? "highshelf" : "peaking";
      b.frequency.value = f;
      b.Q.value = 1.1;
      node.connect(b);
      node = b;
      return b;
    });
    this.bass = ctx.createBiquadFilter();
    this.bass.type = "lowshelf";
    this.bass.frequency.value = 100;
    this.treble = ctx.createBiquadFilter();
    this.treble.type = "highshelf";
    this.treble.frequency.value = 8000;
    node.connect(this.bass).connect(this.treble);

    // Mid/side stereo matrix + crossfeed (connected on demand)
    this.split = ctx.createChannelSplitter(2);
    this.merger = ctx.createChannelMerger(2);
    this.mx = [g(1), g(0), g(0), g(1)];
    this.split.connect(this.mx[0], 0);
    this.mx[0].connect(this.merger, 0, 0);
    this.split.connect(this.mx[1], 1);
    this.mx[1].connect(this.merger, 0, 0);
    this.split.connect(this.mx[2], 0);
    this.mx[2].connect(this.merger, 0, 1);
    this.split.connect(this.mx[3], 1);
    this.mx[3].connect(this.merger, 0, 1);

    [0, 1].forEach((ch) => {
      const lp = ctx.createBiquadFilter();
      lp.type = "lowpass";
      lp.frequency.value = 700;
      const d = ctx.createDelay(0.01);
      d.delayTime.value = 0.0003;
      const cg = g(0);
      this.split.connect(lp, ch);
      lp.connect(d).connect(cg).connect(this.merger, 0, 1 - ch);
      this.cf.push(cg);
    });

    // Orbit (8D) — HRTF panner (oscillators created lazily only when 8D is turned on)
    this.orbitDry = g(1);
    this.orbitWet = g(0);
    this.postOrbit = g();
    this.panner = ctx.createPanner();
    this.panner.panningModel = "HRTF";
    this.panner.distanceModel = "inverse";
    this.panner.rolloffFactor = 0;
    this.panner.positionX.value = 0;
    this.panner.positionZ.value = 0;

    this.orbitDry.connect(this.postOrbit);
    this.panner.connect(this.orbitWet).connect(this.postOrbit);

    // Room
    this.roomDry = g(1);
    this.roomWet = g(0);
    this.postRoom = g();
    this.postOrbit.connect(this.roomDry).connect(this.postRoom);
    this.roomWet.connect(this.postRoom);

    // Boost -> limiter -> volume -> sleep -> out
    this.boost = g();
    this.postRoom.connect(this.boost);
    this.limiter = ctx.createDynamicsCompressor();
    this.limiter.threshold.value = -1.5;
    this.limiter.knee.value = 0;
    this.limiter.ratio.value = 20;
    this.limiter.attack.value = 0.002;
    this.limiter.release.value = 0.12;
    this.volume = g();
    this.limiter.connect(this.volume);
    this.sleepGain = g();
    this.volume.connect(this.sleepGain);

    this.analyser = ctx.createAnalyser();
    this.analyser.fftSize = 2048;
    this.analyser.smoothingTimeConstant = 0.78;
    this.sleepGain.connect(this.analyser).connect(ctx.destination);

    const s2 = ctx.createChannelSplitter(2);
    this.aL = ctx.createAnalyser();
    this.aR = ctx.createAnalyser();
    this.aL.fftSize = this.aR.fftSize = 2048;
    this.sleepGain.connect(s2);
    s2.connect(this.aL, 0);
    s2.connect(this.aR, 1);

    this.apply(settings.get(), true);
    return ctx;
  }

  private ensureOrbitOscillators(ctx: AudioContext, speed: number) {
    if (this.oscs) return;
    const ox = ctx.createOscillator();
    const oz = ctx.createOscillator();
    oz.setPeriodicWave(ctx.createPeriodicWave(new Float32Array([0, 1]), new Float32Array([0, 0])));
    ox.frequency.value = speed;
    oz.frequency.value = speed;
    const rx = ctx.createGain();
    rx.gain.value = 1.2;
    const rz = ctx.createGain();
    rz.gain.value = 1.2;
    ox.connect(rx).connect(this.panner.positionX);
    oz.connect(rz).connect(this.panner.positionZ);
    ox.start();
    oz.start();
    this.oscs = [ox, oz];
  }

  private makeIR(ctx: AudioContext) {
    const len = Math.floor(ctx.sampleRate * 2.2);
    const b = ctx.createBuffer(2, len, ctx.sampleRate);
    for (let c = 0; c < 2; c++) {
      const d = b.getChannelData(c);
      for (let i = 0; i < len; i++) d[i] = (Math.random() * 2 - 1) * Math.pow(1 - i / len, 3.2) * 0.6;
    }
    const cv = ctx.createConvolver();
    cv.buffer = b;
    return cv;
  }

  private effectiveSettings(s: Settings): Settings {
    const curId = player.get().trackId;
    const cfg = curId ? getTrackAudioConfig(curId) : undefined;
    if (!cfg) return s;
    const mids = cfg.midsDb ?? s.midsDb;
    return {
      ...s,
      speed: cfg.speed ?? s.speed,
      keepPitch: cfg.keepPitch ?? s.keepPitch,
      bassDb: cfg.bassDb ?? s.bassDb,
      midsDb: mids,
      trebleDb: cfg.trebleDb ?? s.trebleDb,
      boostDb: cfg.boostDb ?? s.boostDb,
      eq: cfg.midsDb !== undefined ? applyMidsToEq(s.eq, mids) : s.eq,
    };
  }

  private applyElementSettings(raw: Settings) {
    const s = this.effectiveSettings(raw);
    this.els.forEach((el) => {
      el.playbackRate = s.speed;
      el.preservesPitch = s.keepPitch;
      (el as any).webkitPreservesPitch = s.keepPitch;
      el.muted = s.muted;
    });
  }

  apply(raw: Settings, force = false) {
    const s = this.effectiveSettings(raw);
    this.applyElementSettings(raw);
    if (s.speed !== this.lastSpeed) {
      this.lastSpeed = s.speed;
      this.emit();
    }
    const ctx = this.ctx;
    if (!ctx) return;
    const t = ctx.currentTime;
    const set = (p: AudioParam, v: number) => (force ? (p.value = v) : p.setTargetAtTime(v, t, 0.025));

    const eqv = s.eqOn ? s.eq : s.eq.map(() => 0);
    const maxBoost = s.eqOn ? Math.max(0, ...s.eq, s.bassDb, s.trebleDb) : 0;
    set(this.preamp.gain, dB(s.preampDb - (s.autoPreamp ? maxBoost : 0)));
    this.eq.forEach((f, i) => set(f.gain, eqv[i]));
    set(this.bass.gain, s.eqOn ? s.bassDb : 0);
    set(this.treble.gain, s.eqOn ? s.trebleDb : 0);

    // Connect spatial & crossfeed stages ONLY when non-neutral
    const wantSpatial = s.stereoMode !== "normal" || Math.abs(s.width - 1) > 0.005 || s.crossfeed > 0.005;
    if (wantSpatial !== this.spatialConnected) {
      this.spatialConnected = wantSpatial;
      try {
        this.treble.disconnect();
      } catch {}
      try {
        this.merger.disconnect();
      } catch {}
      if (wantSpatial) {
        this.treble.connect(this.split);
        this.merger.connect(this.orbitDry);
        if (this.orbitOn) this.merger.connect(this.panner);
      } else {
        this.treble.connect(this.orbitDry);
        if (this.orbitOn) this.treble.connect(this.panner);
      }
    }

    if (wantSpatial) {
      let LL = 1,
        RL = 0,
        LR = 0,
        RR = 1;
      if (s.stereoMode === "karaoke") {
        LL = 0.7;
        RL = -0.7;
        LR = 0.7;
        RR = -0.7;
      } else {
        const m = 1;
        const sd = s.stereoMode === "mono" ? 0 : s.stereoMode === "vocal" ? 0.25 : s.width;
        LL = RR = (m + sd) / 2;
        RL = LR = (m - sd) / 2;
      }
      [LL, RL, LR, RR].forEach((v, i) => set(this.mx[i].gain, v));
      this.cf.forEach((c) => set(c.gain, s.crossfeed * 0.45));
    }

    // Orbit 8D — start oscillators & connect panner ONLY when 8D is on
    if (s.orbit !== this.orbitOn) {
      this.orbitOn = s.orbit;
      const srcNode = this.spatialConnected ? this.merger : this.treble;
      if (s.orbit) {
        this.ensureOrbitOscillators(ctx, s.orbitSpeed);
        srcNode.connect(this.panner);
      } else {
        try {
          srcNode.disconnect(this.panner);
        } catch {}
      }
    }
    set(this.orbitWet.gain, s.orbit ? 1 : 0);
    set(this.orbitDry.gain, s.orbit ? 0.2 : 1);
    if (this.oscs && s.orbit) {
      this.oscs.forEach((o) => set(o.frequency, s.orbitSpeed));
    }

    // Room ambience — connect convolver only when room > 0
    const wantRoom = s.room > 0.001;
    if (wantRoom !== this.roomOn) {
      this.roomOn = wantRoom;
      if (wantRoom) {
        if (!this.conv) {
          this.conv = this.makeIR(ctx);
          this.conv.connect(this.roomWet);
        }
        this.postOrbit.connect(this.conv);
      } else if (this.conv) {
        try {
          this.postOrbit.disconnect(this.conv);
        } catch {}
      }
    }
    set(this.roomWet.gain, s.room * 0.55);
    set(this.roomDry.gain, 1 - s.room * 0.3);

    set(this.boost.gain, dB(s.boostDb));
    if (s.limiter !== this.limiterOn) {
      try {
        this.boost.disconnect();
      } catch {}
      this.boost.connect(s.limiter ? this.limiter : this.volume);
      this.limiterOn = s.limiter;
    }
    const vol = s.muted ? 0 : s.volume * s.volume * this.duckFactor;
    set(this.volume.gain, vol);

    this.els.forEach((_, i) => {
      const pos = this.slotPos[i];
      const tr = pos != null ? this.trackAt(pos) : undefined;
      if (tr) set(this.rg[i].gain, dB(this.rgFor(tr)));
    });
  }

  /* ---------------- ReplayGain ---------------- */
  private analyzedRG = new Map<string, number>();

  rgFor(t: Track) {
    const s = settings.get();
    if (s.rgMode === "off") return 0;
    const a = this.analyzedRG.get(t.id);
    const v = s.rgMode === "album" ? t.rgAlbum ?? t.rgTrack ?? a : t.rgTrack ?? a;
    return v == null ? 0 : Math.max(-20, Math.min(12, v + s.rgPreamp));
  }

  private async ensureRG(t: Track) {
    const s = settings.get();
    if (s.rgMode === "off" || t.rgTrack != null || this.analyzedRG.has(t.id) || !s.rgAnalyze) return this.refreshRG();
    const cached = await kvGet<number>("rg:" + t.id);
    if (cached != null) this.analyzedRG.set(t.id, cached);
    else {
      try {
        const r = await this.analyze(t, 512, 180, false, { loudnessOnly: true });
        const gain = Math.max(-15, Math.min(10, -18 - r.lufs));
        if (isFinite(gain)) {
          this.analyzedRG.set(t.id, gain);
          kvSet("rg:" + t.id, gain);
        }
      } catch {}
    }
    this.refreshRG();
  }

  private refreshRG() {
    const pos = this.slotPos[this.active];
    const t = pos != null ? this.trackAt(pos) : undefined;
    player.set({ rgApplied: t ? this.rgFor(t) : 0 });
    this.apply(settings.get());
  }

  /* ---------------- Analysis ---------------- */
  private getWorker(): Worker | null {
    if (this.workerFailed) return null;
    if (!this.worker) {
      try {
        this.worker = new Worker(new URL("./analysis.worker.ts", import.meta.url), { type: "module" });
        this.worker.onmessage = (e) => {
          const r = this.wres.get(e.data.id);
          this.wres.delete(e.data.id);
          r?.(e.data);
        };
        this.worker.onerror = () => {
          this.workerFailed = true;
          this.worker = null;
          // Anyone waiting on the dead worker falls back to computing on this thread.
          this.wres.forEach((resolve) => resolve(null));
          this.wres.clear();
        };
      } catch {
        this.workerFailed = true;
        return null;
      }
    }
    return this.worker;
  }

  /**
   * Decodes and analyses a track. Bounded: tracks over 10 minutes or 45 MB are refused, except that the
   * ReplayGain pass (`loudnessOnly`) may analyse the first part of a frame-resyncable file (mp3/ogg/opus/aac).
   * Only called on demand (Visual page, ReplayGain on first play), never when a library loads.
   */
  async analyze(
    t: Track,
    fftSize = 1024,
    cols = 180,
    spectro = false,
    opts: { loudnessOnly?: boolean } = {},
  ): Promise<FullAnalysis> {
    const key = `${t.id}|${fftSize}|${cols}|${spectro}|${opts.loudnessOnly ? "lufs" : "full"}`;
    if (this.lastAnalysis?.key === key) return this.lastAnalysis;

    const tooLong = (t.duration ?? 0) > MAX_ANALYZE_SECONDS;
    const tooBig = t.size > MAX_ANALYZE_BYTES;
    let range: string | undefined;
    if (tooLong || tooBig) {
      if (!opts.loudnessOnly || !WINDOWABLE.test(t.fileName)) {
        throw new Error(tooLong ? "Track is longer than 10 minutes — analysis skipped" : "File too large for full decode analysis");
      }
      const share = tooLong ? MAX_ANALYZE_SECONDS / (t.duration as number) : 1;
      const bytes = Math.max(1, Math.min(MAX_ANALYZE_BYTES, Math.ceil(t.size * share)));
      range = `bytes=0-${bytes - 1}`;
    }

    const targetSr = spectro ? 44100 : 22050;
    const decodeCtx =
      typeof OfflineAudioContext !== "undefined"
        ? new OfflineAudioContext(1, 1, targetSr)
        : this.ensure();

    const resp = await fetch(t.url, range ? { headers: { Range: range } } : undefined);
    if (!resp.ok) throw new Error(`Could not read audio for analysis (HTTP ${resp.status})`);
    const buf = await decodeCtx.decodeAudioData(await resp.arrayBuffer());

    let res: any = null;
    const w = this.getWorker();
    if (w) {
      const id = ++this.wid;
      const mono = mixToMono(buf); // the only copy; transferred, not cloned
      res = await Promise.race([
        new Promise((r) => {
          this.wres.set(id, r);
          w.postMessage({ id, channels: [mono], sr: buf.sampleRate, fftSize, cols, spectro }, [mono.buffer]);
        }),
        // Generous: a long track legitimately takes seconds. Only a dead worker should fall through.
        new Promise((r) => setTimeout(() => r(null), 60000)),
      ]);
      this.wres.delete(id);
    }
    if (!res) {
      res = computeAnalysisSync([mixToMono(buf)], buf.sampleRate, fftSize, cols, spectro);
    }

    const rawBars: number[] = [];
    let maxBar = 0;
    const barCount = 180;
    for (let i = 0; i < barCount; i++) {
      const ci = Math.min(cols - 1, Math.floor((i / barCount) * cols));
      const mn = Math.abs(res.peaks[ci * 2] || 0);
      const mx = Math.abs(res.peaks[ci * 2 + 1] || 0);
      const pk = Math.max(mn, mx);
      rawBars.push(pk);
      if (pk > maxBar) maxBar = pk;
    }
    const scale = maxBar || 1;
    const bars180 = rawBars.map((p) => 0.08 + 0.88 * (p / scale));
    // Cache 96-bar version for the Now Playing waveform seekbar
    const bars96 = Array.from({ length: 96 }, (_, i) => bars180[Math.min(179, Math.floor((i / 96) * 180))]);
    waveformCache.set(t.id, bars96);

    const out: FullAnalysis = {
      key,
      peaks: res.peaks,
      bars180,
      spec: res.spec,
      bins: res.bins,
      cols,
      sr: buf.sampleRate,
      duration: buf.duration,
      lufs: res.lufs,
      peak: res.peak,
    };
    this.lastAnalysis = out;
    if (!this.analyzedRG.has(t.id) && isFinite(res.lufs)) {
      const gain = Math.max(-15, Math.min(10, -18 - res.lufs));
      this.analyzedRG.set(t.id, gain);
      kvSet("rg:" + t.id, gain);
    }
    return out;
  }

  /* ---------------- Transport ---------------- */
  get el() {
    return this.els[this.active];
  }

  private loadSlot(slot: number, pos: number) {
    const t = this.trackAt(pos);
    if (!t) return;
    const el = this.els[slot];
    el.src = t.url; // streamed by the browser / native media server, never copied into JS
    el.loop = false;
    this.slotPos[slot] = pos;
    this.applyElementSettings(settings.get());
    if (this.ctx) this.rg[slot].gain.value = dB(this.rgFor(t));
  }

  primeAt(pos: number) {
    if (pos < 0 || !this.trackAt(pos)) return;
    this.loadSlot(this.active, pos);
    library.set({ qpos: pos, insertCursor: 0 });
    const t = this.trackAt(pos);
    const start = t ? this.hooks.startAt?.(t, "prime") ?? 0 : 0;
    if (start > 0) this.el.currentTime = start; // restore position without starting playback
    player.set({
      trackId: t?.id ?? null,
      abA: null,
      abB: null,
      duration: t?.duration ?? 20,
      repeatRemaining: Math.max(0, (settings.get().repeatCountTarget || 1) - 1),
    });
    clock.set({ t: start > 0 ? start : 0 });
    this.updateMediaMeta(t);
    this.preloadNext();
    this.emit();
  }

  async playAt(pos: number, auto = false) {
    if (pos < 0 || !this.trackAt(pos)) return;
    // Automatic advance keeps the focus we already hold; user-initiated starts must (re)acquire it.
    if (!auto && this.hooks.beforePlay && !(await this.hooks.beforePlay())) return this.focusDenied();
    clearTimeout(this.pauseFadeT);
    this.switchMarkAt = performance.now();
    const ctx = this.ensure();
    this.cancelFade();
    this.els[1 - this.active].pause();
    const target = this.trackAt(pos);
    // Picking a different track resumes it where you left it (long tracks only); picking the current one restarts it.
    const resumeAt = !auto && target && player.get().trackId !== target.id ? this.hooks.startAt?.(target, "play") ?? 0 : 0;
    this.loadSlot(this.active, pos);
    if (resumeAt > 0) this.el.currentTime = resumeAt;
    const fadeSec = !auto && settings.get().playPauseFadeMs > 0 ? settings.get().playPauseFadeMs / 1000 : 0;
    const g = this.fade[this.active].gain;
    g.cancelScheduledValues(0);
    if (fadeSec > 0.02) {
      const now = ctx.currentTime;
      g.setValueAtTime(0.0001, now);
      g.linearRampToValueAtTime(1, now + fadeSec);
    } else {
      g.value = 1;
    }
    library.set({ qpos: pos, insertCursor: 0 });
    this.afterTrackChange();
    try {
      await ctx.resume();
      await this.el.play();
    } catch {}
  }

  private checkSleepTrackEnd(): boolean {
    const ps = player.get();
    if (ps.sleepEndOfTrack) {
      this.pause(true);
      player.set({ sleepEndOfTrack: false });
      toast("Sleep timer: stopped at end of track");
      return true;
    }
    if (ps.sleepTracksRemaining > 0) {
      const rem = ps.sleepTracksRemaining - 1;
      player.set({ sleepTracksRemaining: rem });
      if (rem <= 0) {
        this.pause(true);
        toast("Sleep timer: track count reached");
        return true;
      }
    }
    return false;
  }

  private startOther(fadeSec: number) {
    const o = 1 - this.active;
    const old = this.active;
    const pos = this.slotPos[o];
    if (pos == null || !this.ctx) return false;
    clearTimeout(this.gapT);
    this.gapT = undefined;
    if (settings.get().repeat === "one") return false;
    if (player.get().repeatRemaining > 0) return false;
    if (this.checkSleepTrackEnd()) return true;

    const t = this.ctx.currentTime;
    const eo = this.els[o];
    eo.currentTime = 0;
    this.switchMarkAt = performance.now();
    diag("track-switch", {
      via: fadeSec > 0.05 ? "crossfade" : "gapless",
      remainingMs: Math.round(((this.els[old].duration || 0) - this.els[old].currentTime) * 1000),
      hidden: document.hidden,
    });

    if (fadeSec > 0.05) {
      this.crossfading = true;
      this.fade[o].gain.cancelScheduledValues(t);
      this.fade[o].gain.setValueAtTime(0.0001, t);
      this.fade[o].gain.linearRampToValueAtTime(1, t + fadeSec);
      this.fade[old].gain.cancelScheduledValues(t);
      this.fade[old].gain.setValueAtTime(this.fade[old].gain.value, t);
      this.fade[old].gain.linearRampToValueAtTime(0, t + fadeSec);
      window.setTimeout(() => {
        this.els[old].pause();
        this.crossfading = false;
        this.preloadNext();
      }, fadeSec * 1000 + 60);
    } else {
      this.fade[o].gain.cancelScheduledValues(t);
      this.fade[o].gain.value = 1;
      this.fade[old].gain.value = 0;
      this.els[old].pause();
    }
    eo.play().catch(() => {});
    this.active = o;
    library.set({ qpos: pos, insertCursor: 0 });
    this.afterTrackChange();
    return true;
  }

  private cancelFade() {
    if (!this.crossfading || !this.ctx) return;
    this.crossfading = false;
    this.fade.forEach((f) => f.gain.cancelScheduledValues(0));
    this.fade[1 - this.active].gain.value = 0;
  }

  private afterTrackChange() {
    const t = this.trackAt(library.get().qpos);
    player.set({
      trackId: t?.id ?? null,
      abA: null,
      abB: null,
      duration: t?.duration ?? (isFinite(this.el.duration) ? this.el.duration : 20),
      repeatRemaining: Math.max(0, (settings.get().repeatCountTarget || 1) - 1),
    });
    clock.set({ t: 0 });
    clearTimeout(this.gapT);
    this.gapT = undefined;
    this.el.loop = settings.get().repeat === "one";
    this.updateMediaMeta(t);
    this.apply(settings.get());
    if (t) this.ensureRG(t);
    this.preloadNext();
    this.emit();
  }

  nextPos(from: number, manual: boolean) {
    const { queue } = library.get();
    const s = settings.get();
    if (!queue.length) return -1;
    if (s.repeat === "one" && !manual) return from;
    if (from + 1 < queue.length) return from + 1;
    return s.repeat === "all" || manual ? 0 : -1;
  }

  private preloadNext() {
    if (this.crossfading) return;
    const n = this.nextPos(library.get().qpos, true);
    const o = 1 - this.active;
    if (n < 0 || n === library.get().qpos) {
      this.slotPos[o] = null;
      return;
    }
    if (this.slotPos[o] === n && this.els[o].src) return;
    this.els[o].pause();
    this.loadSlot(o, n);
    if (this.ctx) this.fade[o].gain.value = 0;
  }

  private onEnded() {
    if (this.crossfading) return;
    const ps = player.get();
    if (ps.abA != null) {
      this.seek(ps.abA);
      this.play();
      return;
    }
    // Repeat N times before moving on
    if (ps.repeatRemaining > 0 && settings.get().repeat !== "one") {
      player.set({ repeatRemaining: ps.repeatRemaining - 1 });
      this.seek(0);
      void this.play();
      return;
    }
    const s = settings.get();
    // The natural end fires "pause" then "ended"; the pause is ignored so playing stays true across a
    // track change (the foreground service must not be demoted between songs). Report real stops here.
    if (this.checkSleepTrackEnd()) {
      this.onPlayState(false);
      return;
    }
    const n = this.nextPos(library.get().qpos, false);
    if (n < 0 || (s.repeat === "off" && n === 0 && library.get().qpos === library.get().queue.length - 1)) {
      this.onPlayState(false);
      return;
    }
    diag("track-ended-fallback", { hidden: document.hidden, preloaded: this.slotPos[1 - this.active] === n });
    if (this.slotPos[1 - this.active] === n) this.startOther(0);
    else this.playAt(n, true);
  }

  private onPlayState(p: boolean) {
    player.set({ playing: p });
    if ("mediaSession" in navigator) navigator.mediaSession.playbackState = p ? "playing" : "paused";
    clearInterval(this.tickT);
    clearTimeout(this.suspendT);
    if (p) {
      this.ctx?.resume();
      this.tickT = window.setInterval(() => this.tick(), 50);
    } else {
      this.suspendT = window.setTimeout(
        () => this.ctx?.state === "running" && !player.get().playing && this.ctx.suspend(),
        20000
      );
    }
    this.lastTickAt = 0;
    this.emit();
  }

  private tick() {
    const el = this.el;
    const t = el.currentTime;
    const ps = player.get();
    const s = settings.get();
    const nowMs = performance.now();
    if (this.lastTickAt) diagTick(nowMs - this.lastTickAt, document.hidden);
    this.lastTickAt = nowMs;

    const minInterval = ps.lyricsVisible ? 45 : 95;
    if (nowMs - this.lastClockEmit >= minInterval && Math.abs(t - clock.get().t) > 0.025) {
      this.lastClockEmit = nowMs;
      clock.set({ t });
    }

    if (ps.abA != null && ps.abB != null && t >= ps.abB) el.currentTime = ps.abA;

    this.checkSleep();

    if (this.ctx) {
      const red = s.limiter ? this.limiter.reduction : 0;
      let clip = false;
      if (!s.limiter && s.boostDb > 0) {
        if (!this.tdBuf) this.tdBuf = new Float32Array(this.analyser.fftSize);
        this.analyser.getFloatTimeDomainData(this.tdBuf);
        for (let i = 0; i < this.tdBuf.length; i += 4)
          if (Math.abs(this.tdBuf[i]) >= 0.999) {
            clip = true;
            break;
          }
      }
      if (Math.abs(red - ps.limiting) > 0.3 || clip !== ps.clipping) player.set({ limiting: red, clipping: clip });
    }

    const dur = el.duration;
    if (!dur || el.paused || s.repeat === "one" || ps.repeatRemaining > 0 || ps.abB != null || this.crossfading) return;
    const rem = (dur - t) / (el.playbackRate || 1);
    const o = 1 - this.active;
    if (this.slotPos[o] == null) return;
    if (s.crossfade > 0 && rem <= s.crossfade && rem > 0.2) this.startOther(rem);
    else if (s.gapless && s.crossfade === 0 && rem < 0.15 && this.gapT == null) {
      this.gapT = window.setTimeout(() => this.startOther(0), Math.max(0, rem * 1000 - 12));
    }
  }

  /** Sleep timer. Also driven by "timeupdate" so it still fires if the 50 ms interval is throttled. */
  private checkSleep() {
    const ps = player.get();
    if (!ps.sleepAt) return;
    const rem = ps.sleepAt - Date.now();
    if (this.ctx) this.sleepGain.gain.value = Math.max(0, Math.min(1, rem / 20000));
    if (rem <= 0) {
      diag("sleep-fired", { overrunMs: Math.round(-rem), hidden: document.hidden });
      this.pause(true);
      player.set({ sleepAt: null });
      setTimeout(() => this.ctx && (this.sleepGain.gain.value = 1), 300);
      toast("Sleep timer finished");
    }
  }

  async play() {
    const { qpos, queue } = library.get();
    if (qpos < 0) return queue.length ? this.playAt(0) : undefined;
    if (!this.el.src) return this.playAt(qpos);
    if (this.hooks.beforePlay && !(await this.hooks.beforePlay())) return this.focusDenied();
    clearTimeout(this.pauseFadeT);
    const ctx = this.ensure();
    await ctx.resume();
    const fadeSec = settings.get().playPauseFadeMs / 1000;
    const g = this.fade[this.active]?.gain;
    if (g) {
      g.cancelScheduledValues(0);
      if (fadeSec > 0.02) {
        const now = ctx.currentTime;
        g.setValueAtTime(0.0001, now);
        g.linearRampToValueAtTime(1, now + fadeSec);
      } else {
        g.value = 1;
      }
    }
    this.el.play().catch(() => {});
  }

  pause(immediate = false) {
    clearTimeout(this.pauseFadeT);
    this.cancelFade();
    const fadeSec = !immediate && this.ctx && player.get().playing ? settings.get().playPauseFadeMs / 1000 : 0;
    const g = this.fade[this.active]?.gain;
    if (fadeSec > 0.02 && this.ctx && g) {
      const now = this.ctx.currentTime;
      g.cancelScheduledValues(now);
      g.setValueAtTime(Math.max(0.0001, g.value), now);
      g.linearRampToValueAtTime(0.0001, now + fadeSec);
      player.set({ playing: false });
      this.pauseFadeT = window.setTimeout(() => {
        this.els.forEach((e) => e.pause());
        if (this.fade[this.active]) this.fade[this.active].gain.value = 1;
      }, fadeSec * 1000);
    } else {
      this.els.forEach((e) => e.pause());
    }
  }

  toggle() {
    player.get().playing ? this.pause() : this.play();
  }

  next() {
    const n = this.nextPos(library.get().qpos, true);
    if (n < 0) return;
    if (!player.get().playing) void this.hooks.beforePlay?.();
    if (this.slotPos[1 - this.active] === n && this.ctx && settings.get().repeat !== "one" && !this.crossfading) {
      this.startOther(0);
      if (!player.get().playing) this.ctx.resume();
    } else this.playAt(n);
  }

  prev() {
    if (this.el.currentTime > 3) return this.seek(0);
    const { qpos, queue } = library.get();
    if (!queue.length) return;
    this.playAt(qpos > 0 ? qpos - 1 : queue.length - 1);
  }

  seek(t: number) {
    const el = this.el;
    if (!el.src) {
      const { qpos } = library.get();
      if (qpos >= 0) this.primeAt(qpos);
    }
    if (!el.src) return;
    const dur = this.el.duration || player.get().duration || t;
    this.el.currentTime = Math.max(0, Math.min(t, dur - 0.05));
    clock.set({ t: this.el.currentTime });
    clearTimeout(this.gapT);
    clearTimeout(this.suspendT);
    this.gapT = undefined;
    this.emit();
  }

  get time() {
    return this.el.currentTime;
  }

  setLoopFlag() {
    this.el.loop = settings.get().repeat === "one";
  }

  /** Re-publish the current track's title/artist/cover (after a tag edit). */
  refreshMeta() {
    this.updateMediaMeta(this.trackAt(library.get().qpos));
    this.emit();
  }

  requeue() {
    this.slotPos[1 - this.active] = null;
    this.preloadNext();
  }

  async setOutput(deviceId: string) {
    const ctx = this.ensure();
    if (ctx.setSinkId) {
      try {
        await ctx.setSinkId(deviceId === "default" ? "" : deviceId);
        player.set({ outputId: deviceId });
        return true;
      } catch {}
    }
    return false;
  }

  private setupMediaSession() {
    if (typeof navigator === "undefined" || !("mediaSession" in navigator)) return;
    const ms = navigator.mediaSession;
    const h: [MediaSessionAction, MediaSessionActionHandler][] = [
      ["play", () => this.play()],
      ["pause", () => this.pause()],
      ["nexttrack", () => this.next()],
      ["previoustrack", () => this.prev()],
      ["seekto", (d) => d.seekTime != null && this.seek(d.seekTime)],
      ["seekbackward", () => this.seek(this.time - (settings.get().seekStepSec || 10))],
      ["seekforward", () => this.seek(this.time + (settings.get().seekStepSec || 10))],
    ];
    h.forEach(([a, f]) => {
      try {
        ms.setActionHandler(a, f);
      } catch {}
    });
  }

  private updateMediaMeta(t?: Track) {
    if (typeof navigator === "undefined" || !("mediaSession" in navigator) || !t) return;
    try {
      navigator.mediaSession.metadata = new MediaMetadata({
        title: t.title,
        artist: t.artist,
        album: t.album,
        artwork: t.cover ? [{ src: t.cover, sizes: "512x512" }] : [],
      });
    } catch {}
  }
}

export const engine = new Engine();
