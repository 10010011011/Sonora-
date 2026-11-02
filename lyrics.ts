import type { LyricLine, Lyrics, LyricWord } from "./store";

const TAG = /\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?\]/g;
const INLINE_TIME = /<((?:\d{1,2}:)?\d{1,2}:\d{2}(?:[.,]\d{1,3})?)>/g;

function toSec(m: string, s: string, f?: string) {
  const frac = f ? parseInt(f, 10) / Math.pow(10, f.length) : 0;
  return parseInt(m, 10) * 60 + parseInt(s, 10) + frac;
}

export function parseCueTime(value: string): number | null {
  const match = value.trim().match(/^(?:(\d+):)?(\d{1,2}):(\d{2})(?:[.,](\d{1,3}))?$/);
  if (!match) return null;
  return (match[1] ? parseInt(match[1], 10) * 3600 : 0)
    + parseInt(match[2], 10) * 60
    + parseInt(match[3], 10)
    + (match[4] ? parseInt(match[4], 10) / Math.pow(10, match[4].length) : 0);
}

function cleanCueMarkup(value: string, trim = true) {
  const cleaned = value
    .replace(/<\/?(?:c|v|lang|b|i|u)(?:\s+[^>]*)?>/gi, "")
    .replace(/<[^>]+>/g, "")
    .replace(/&nbsp;/gi, " ");
  return trim ? cleaned.trim() : cleaned;
}

/**
 * Enhanced LRC marks the beginning of each spoken word with <mm:ss.xx>.
 * WebVTT karaoke cues use the same idea with <hh:mm:ss.mmm>.
 * Text between markers belongs to the preceding marker.
 */
function parseInlineWords(value: string): LyricWord[] | undefined {
  const words: LyricWord[] = [];
  let current: LyricWord | undefined;
  let prefix = "";
  let cursor = 0;
  INLINE_TIME.lastIndex = 0;

  let match: RegExpExecArray | null;
  while ((match = INLINE_TIME.exec(value))) {
    const between = value.slice(cursor, match.index);
    if (current) current.text += between;
    else prefix += between;

    const time = parseCueTime(match[1]);
    if (time !== null) {
      if (current) words.push(current);
      current = { t: time, text: "" };
    } else if (current) {
      current.text += match[0];
    } else {
      prefix += match[0];
    }
    cursor = INLINE_TIME.lastIndex;
  }

  if (!current) return undefined;
  current.text += value.slice(cursor);
  current.text = prefix + current.text;
  words.push(current);

  const cleaned = words
    .map(word => ({ ...word, text: cleanCueMarkup(word.text, false) }))
    .filter(word => word.text.trim().length > 0);
  if (!cleaned.length) return undefined;

  // Keep inter-word spaces (they are usually trailing text on the previous token).
  cleaned[0].text = cleaned[0].text.replace(/^\s+/, "");
  cleaned[cleaned.length - 1].text = cleaned[cleaned.length - 1].text.replace(/\s+$/, "");
  return cleaned;
}

function sortLines(lines: LyricLine[]) {
  if (lines.some(line => line.t == null)) return lines;
  return [...lines].sort((a, b) => (a.t ?? 0) - (b.t ?? 0));
}

function shiftWords(words: LyricWord[] | undefined, delta: number) {
  if (!words || !delta) return words;
  return words.map(word => ({ ...word, t: Math.max(0, word.t + delta) }));
}

export function parseLyrics(input: string, source = "text"): Lyrics {
  const text = input.replace(/\r\n?/g, "\n").replace(/^\uFEFF/, "");
  const lines: LyricLine[] = [];

  // SRT / WebVTT. WebVTT's inline timestamp tags are retained as word timings.
  if (/-->/.test(text)) {
    for (const block of text.split(/\n\s*\n/)) {
      const rows = block.split("\n").filter(row => row.trim() && row.trim() !== "WEBVTT");
      const timeIndex = rows.findIndex(row => row.includes("-->"));
      if (timeIndex < 0) continue;
      const cueTime = parseCueTime(rows[timeIndex].split("-->")[0]);
      const rawText = rows.slice(timeIndex + 1).join(" ");
      const words = parseInlineWords(rawText);
      const lineText = words ? words.map(word => word.text).join("").trim() : cleanCueMarkup(rawText);
      if (lineText) lines.push({ t: cueTime, text: lineText, ...(words ? { words } : {}) });
    }
    return { lines: sortLines(lines), offset: 0, source };
  }

  // LRC, including repeated line timestamps, [offset:], and enhanced word tags.
  let offset = 0;
  let timed = 0;
  for (const raw of text.split("\n")) {
    const offsetTag = raw.match(/^\s*\[offset:\s*([+-]?\d+)\s*\]/i);
    if (offsetTag) {
      offset = parseInt(offsetTag[1], 10) / 1000;
      continue;
    }
    if (/^\s*\[[a-z]+:.*\]\s*$/i.test(raw)) continue;

    const lineTimes: number[] = [];
    TAG.lastIndex = 0;
    let match: RegExpExecArray | null;
    let lastEnd = 0;
    while ((match = TAG.exec(raw))) {
      if (match.index !== lastEnd && raw.slice(lastEnd, match.index).trim()) break;
      lineTimes.push(toSec(match[1], match[2], match[3]));
      lastEnd = match.index + match[0].length;
    }

    const payload = raw.slice(lastEnd);
    const words = parseInlineWords(payload);
    const lineText = words
      ? words.map(word => word.text).join("").trim()
      : cleanCueMarkup(payload.replace(/<\d{1,3}:\d{1,2}(?:[.:]\d{1,3})?>/g, ""));

    if (!lineText) continue;

    if (lineTimes.length) {
      timed++;
      for (const time of lineTimes) {
        // Repeated LRC line tags denote repeated performances of the same lyric.
        const delta = words?.length && lineTimes.length > 1 ? time - lineTimes[0] : 0;
        lines.push({ t: time, text: lineText, ...(words ? { words: shiftWords(words, delta) } : {}) });
      }
    } else if (words?.length) {
      timed++;
      lines.push({ t: words[0].t, text: lineText, words });
    } else {
      lines.push({ t: null, text: lineText });
    }
  }

  const result = timed ? sortLines(lines) : lines;
  if (offset) {
    for (const line of result) {
      if (line.t != null) line.t = Math.max(0, line.t - offset);
      if (line.words) line.words = line.words.map(word => ({ ...word, t: Math.max(0, word.t - offset) }));
    }
  }
  return { lines: result, offset: 0, source };
}

export function stamp(seconds: number) {
  const value = Math.max(0, seconds);
  const minutes = Math.floor(value / 60);
  const remainder = value - minutes * 60;
  return String(minutes).padStart(2, "0") + ":" + remainder.toFixed(2).padStart(5, "0");
}

export function toLRC(lyrics: Lyrics, meta?: { ti?: string; ar?: string }) {
  const output: string[] = [];
  if (meta?.ti) output.push("[ti:" + meta.ti + "]");
  if (meta?.ar) output.push("[ar:" + meta.ar + "]");
  for (const line of lyrics.lines) {
    const body = line.words?.length
      ? line.words.map(word => "<" + stamp(word.t + lyrics.offset) + ">" + word.text).join("")
      : line.text;
    output.push(line.t == null ? body : "[" + stamp(line.t + lyrics.offset) + "]" + body);
  }
  return output.join("\n");
}

export function bakeOffset(lyrics: Lyrics): Lyrics {
  return {
    ...lyrics,
    offset: 0,
    lines: lyrics.lines.map(line => ({
      ...line,
      t: line.t == null ? null : Math.max(0, line.t + lyrics.offset),
      words: shiftWords(line.words, lyrics.offset),
    })),
  };
}

export function shiftFrom(lyrics: Lyrics, fromIndex: number, delta: number): Lyrics {
  return {
    ...lyrics,
    lines: lyrics.lines.map((line, index) => {
      if (index < fromIndex) return line;
      return {
        ...line,
        t: line.t == null ? null : Math.max(0, line.t + delta),
        words: shiftWords(line.words, delta),
      };
    }),
  };
}

/**
 * Finds the active lyric line index for `time`. Uses binary search when all lines are timed and monotonic,
 * falling back to linear scan when untimed lines are mixed in.
 */
export function activeIndex(lyrics: Lyrics | undefined, time: number) {
  if (!lyrics || !lyrics.lines.length) return -1;
  const lines = lyrics.lines;
  const target = time + 0.05 - lyrics.offset;

  let canBinary = true;
  for (let i = 0; i < lines.length; i++) {
    if (lines[i].t == null || (i > 0 && lines[i].t! < lines[i - 1].t!)) {
      canBinary = false;
      break;
    }
  }

  if (canBinary) {
    let lo = 0;
    let hi = lines.length - 1;
    let ans = -1;
    while (lo <= hi) {
      const mid = (lo + hi) >> 1;
      if ((lines[mid].t as number) <= target) {
        ans = mid;
        lo = mid + 1;
      } else {
        hi = mid - 1;
      }
    }
    return ans;
  }

  let index = -1;
  for (let i = 0; i < lines.length; i++) {
    const t = lines[i].t;
    if (t == null) continue;
    if (t <= target) index = i;
    else break;
  }
  return index;
}

export function activeWordIndex(line: LyricLine | undefined, time: number, offset = 0) {
  if (!line?.words?.length) return -1;
  let index = -1;
  for (let i = 0; i < line.words.length; i++) {
    if (line.words[i].t + offset <= time + 0.035) index = i;
    else break;
  }
  return index;
}

/**
 * Standard LRC timestamps identify lines, not individual words. When enhanced
 * word timestamps are absent, estimate a readable karaoke progression inside
 * the line's time window. Enhanced LRC/WebVTT timings always take precedence.
 * These are visual estimates, not claimed phoneme/word recognition timings.
 */
export function estimateWordTimings(
  lyrics: Lyrics,
  lineIndex: number,
  trackDuration = 0
): LyricWord[] | undefined {
  const line = lyrics.lines[lineIndex];
  if (!line || line.t == null || !line.text.trim()) return line?.words;
  if (line.words?.length) return line.words;

  const tokens = line.text.match(/\S+\s*/gu) ?? [];
  if (!tokens.length) return undefined;

  const start = line.t;
  let nextStart: number | undefined;
  for (let i = lineIndex + 1; i < lyrics.lines.length; i++) {
    const candidate = lyrics.lines[i].t;
    if (candidate != null && candidate > start + 0.03) {
      nextStart = candidate;
      break;
    }
  }

  let available = nextStart != null
    ? nextStart - start
    : trackDuration > start
      ? trackDuration - start
      : 0;

  if (available <= 0) {
    const gaps: number[] = [];
    let previous: number | undefined;
    for (let i = 0; i < lineIndex; i++) {
      const time = lyrics.lines[i].t;
      if (time == null) continue;
      if (previous != null && time > previous) gaps.push(time - previous);
      previous = time;
    }
    const recent = gaps.slice(-5).sort((a, b) => a - b);
    available = recent.length
      ? recent[Math.floor(recent.length / 2)]
      : Math.max(2.2, tokens.length * 0.42);
  }

  // Avoid stretching the last line across a long instrumental outro.
  const speechWindow = Math.min(available, Math.max(2.2, tokens.length * 0.72));
  const span = Math.max(0.12, speechWindow * 0.9);
  const weights = tokens.map((token) => {
    const count = Array.from(token.trim().replace(/[^\p{L}\p{N}]/gu, "")).length;
    return Math.max(1, count);
  });
  const total = weights.reduce((sum, weight) => sum + weight, 0);

  let elapsed = 0;
  return tokens.map((text, index) => {
    const word = { t: start + (elapsed / total) * span, text };
    elapsed += weights[index];
    return word;
  });
}
