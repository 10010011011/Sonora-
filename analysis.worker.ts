/// <reference lib="webworker" />
// Off-main-thread analysis. The maths lives in analysis-core.ts (shared with the main-thread fallback).
import { computeAnalysis } from "./analysis-core";

interface Req {
  id: number;
  channels: Float32Array[]; // raw channel buffers transferred from AudioBuffer
  sr: number;
  fftSize: number;
  cols: number;
  spectro: boolean;
}

self.onmessage = (e: MessageEvent<Req>) => {
  const { id, channels, sr, fftSize, cols, spectro } = e.data;
  const r = computeAnalysis(channels, sr, fftSize, cols, spectro);
  (self as unknown as Worker).postMessage({ id, ...r }, r.spec ? [r.peaks.buffer, r.spec.buffer] : [r.peaks.buffer]);
};
