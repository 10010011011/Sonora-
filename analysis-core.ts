// Shared analysis core: mono peaks, STFT spectrogram and BS.1770-style loudness.
// Imported by analysis.worker.ts and by the main-thread fallback in engine.ts, so both always agree.

export interface AnalysisResult {
  peaks: Float32Array<ArrayBuffer>; // min/max pairs per column
  spec: Uint8Array<ArrayBuffer> | null; // cols * bins, 0..255 maps -110..0 dBFS
  lufs: number;
  peak: number;
  bins: number;
  cols: number;
}

const tables = new Map<number, { cos: Float32Array; sin: Float32Array; rev: Uint32Array; win: Float32Array }>();

function getTables(n: number) {
  let t = tables.get(n);
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
  tables.set(n, t);
  return t;
}

function fft(re: Float32Array, im: Float32Array, n: number) {
  const { cos, sin, rev } = getTables(n);
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

export function computeAnalysis(
  channels: Float32Array[],
  sr: number,
  fftSize: number,
  cols: number,
  spectro: boolean,
): AnalysisResult {
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

  // Waveform peaks (min/max per column)
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

  // Loudness: 400 ms blocks, 75% overlap, high-shelf pre-emphasis + absolute/relative gating (BS.1770 approximation)
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

  // STFT spectrogram. A column covers `seg` samples, which is usually far more than one FFT window, so up to
  // 4 windows are spread across the column and the strongest power per bin is kept (peak-hold). Transients and
  // thin lines between "frame centres" are no longer skipped.
  let spec: Uint8Array<ArrayBuffer> | null = null;
  if (spectro) {
    const n = fftSize,
      bins = n / 2;
    const { win } = getTables(n);
    spec = new Uint8Array(cols * bins);
    const re = new Float32Array(n),
      im = new Float32Array(n),
      pow = new Float32Array(bins);
    const norm = 4 / n; // Hann coherent gain 0.5: amplitude = 2|X| / (n * 0.5)
    const norm2 = norm * norm;
    const sub = Math.max(1, Math.min(4, Math.floor(seg / n)));
    for (let c = 0; c < cols; c++) {
      pow.fill(0);
      for (let j = 0; j < sub; j++) {
        const center = Math.floor(c * seg + ((j + 0.5) * seg) / sub);
        const start = center - (n >> 1);
        for (let i = 0; i < n; i++) {
          const k = start + i;
          re[i] = k >= 0 && k < N ? data[k] * win[i] : 0;
          im[i] = 0;
        }
        fft(re, im, n);
        for (let b = 0; b < bins; b++) {
          const p = re[b] * re[b] + im[b] * im[b];
          if (p > pow[b]) pow[b] = p;
        }
      }
      const off = c * bins;
      for (let b = 0; b < bins; b++) {
        const db = 10 * Math.log10(pow[b] * norm2 + 1e-18);
        const v = ((db + 110) / 110) * 255;
        spec[off + b] = v < 0 ? 0 : v > 255 ? 255 : v;
      }
    }
  }

  return { peaks, spec, lufs, peak: maxPeak, bins: fftSize / 2, cols };
}
