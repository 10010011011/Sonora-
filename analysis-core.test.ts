import test from "node:test";
import assert from "node:assert/strict";
import { computeAnalysis } from "./analysis-core";

const SR = 44100;

test("spectrogram: 1 kHz sine lands in the right bin at the right level", () => {
  const N = SR * 5;
  const x = new Float32Array(N);
  for (let i = 0; i < N; i++) x[i] = 0.5 * Math.sin((2 * Math.PI * 1000 * i) / SR);
  const r = computeAnalysis([x], SR, 2048, 200, true);
  const bins = r.bins;
  const col = r.spec!.subarray(100 * bins, 101 * bins);
  let best = 0;
  for (let b = 1; b < bins; b++) if (col[b] > col[best]) best = b;
  assert.equal(best, Math.round(1000 / (SR / 2048)));
  const expected = ((20 * Math.log10(0.5) + 110) / 110) * 255; // -6.02 dBFS
  assert.ok(Math.abs(col[best] - expected) <= 6, `level ${col[best]} vs ${expected.toFixed(1)}`);
});

test("spectrogram: short burst between frame centres is still visible (peak-hold)", () => {
  const N = SR * 30;
  const x = new Float32Array(N);
  const at = Math.floor(100.4 * (N / 200)); // inside column 100, away from its nominal centre
  for (let i = 0; i < 1500; i++) x[at + i] = 0.5 * Math.sin((2 * Math.PI * 3000 * i) / SR);
  const r = computeAnalysis([x], SR, 1024, 200, true);
  const bin = Math.round(3000 / (SR / 1024));
  let hit = 0;
  for (let c = 99; c <= 101; c++) hit = Math.max(hit, r.spec![c * r.bins + bin]);
  assert.ok(hit > 150, `burst level ${hit}`);
  assert.ok(r.spec![20 * r.bins + bin] < 5, "silence stays dark");
});

test("peaks and loudness stay sane", () => {
  const N = SR * 3;
  const x = new Float32Array(N);
  for (let i = 0; i < N; i++) x[i] = 0.25 * Math.sin((2 * Math.PI * 440 * i) / SR);
  const r = computeAnalysis([x], SR, 1024, 100, false);
  assert.equal(r.spec, null);
  assert.ok(Math.abs(r.peak - 0.25) < 0.01);
  assert.ok(r.lufs < -10 && r.lufs > -40);
});
