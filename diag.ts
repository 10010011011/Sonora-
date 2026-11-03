// Lightweight, in-memory diagnostics for the real-device checklist (background timers, gapless, sleep).
// Nothing is logged to the console or sent anywhere; Settings → Copy diagnostics exports the report.

interface DiagEvent {
  t: number;
  kind: string;
  data?: unknown;
}

const events: DiagEvent[] = [];
const MAX_EVENTS = 300;
const tick = { count: 0, hiddenCount: 0, maxGapVisibleMs: 0, maxGapHiddenMs: 0, over250HiddenMs: 0 };

export function diag(kind: string, data?: unknown) {
  events.push({ t: Date.now(), kind, data });
  if (events.length > MAX_EVENTS) events.shift();
}

/** Called by the engine's 50 ms clock with the gap since its previous tick. */
export function diagTick(gapMs: number, hidden: boolean) {
  tick.count++;
  if (hidden) {
    tick.hiddenCount++;
    tick.maxGapHiddenMs = Math.max(tick.maxGapHiddenMs, gapMs);
    if (gapMs > 250) tick.over250HiddenMs++;
  } else {
    tick.maxGapVisibleMs = Math.max(tick.maxGapVisibleMs, gapMs);
  }
}

export interface TimerVerdict {
  verdict: "keep-js" | "move-native" | "not-enough-data";
  reason: string;
}

/**
 * A7: should gapless scheduling move out of JavaScript into native (Media3, "path B")?
 * Decided from device data, not guessed. Needs at least ~10 minutes of screen-off playback.
 * Thresholds: JS timers throttled past 250 ms in more than 2% of hidden ticks, a hidden gap over 1 s, or any
 * gapless track change that fell back to the "ended" event are all signs the background timer cannot be trusted.
 */
export function timerVerdict(): TimerVerdict {
  const fallbacks = events.filter((e) => e.kind === "track-ended-fallback" && (e.data as { hidden?: boolean })?.hidden).length;
  const gapless = events.filter((e) => e.kind === "track-switch" && (e.data as { via?: string })?.via === "gapless").length;
  if (tick.hiddenCount < 12000) {
    return { verdict: "not-enough-data", reason: `${tick.hiddenCount} hidden ticks so far; need ~12000 (10 min screen off at 50 ms)` };
  }
  const share = tick.over250HiddenMs / tick.hiddenCount;
  if (tick.maxGapHiddenMs > 1000 || share > 0.02 || fallbacks > 0) {
    return {
      verdict: "move-native",
      reason: `hidden max gap ${Math.round(tick.maxGapHiddenMs)} ms, ${(share * 100).toFixed(1)}% of ticks over 250 ms, ${fallbacks} hidden fallback(s) vs ${gapless} gapless switch(es)`,
    };
  }
  return {
    verdict: "keep-js",
    reason: `hidden max gap ${Math.round(tick.maxGapHiddenMs)} ms, ${(share * 100).toFixed(2)}% of ticks over 250 ms, ${gapless} gapless switch(es), no hidden fallback`,
  };
}

export function diagReport(): string {
  return JSON.stringify(
    {
      generatedAt: new Date().toISOString(),
      userAgent: typeof navigator === "undefined" ? "" : navigator.userAgent,
      visibility: typeof document === "undefined" ? "" : document.visibilityState,
      tickStats: { ...tick, note: "gap = ms between consecutive 50 ms engine ticks while playing" },
      timerVerdict: timerVerdict(),
      events,
    },
    null,
    2,
  );
}
