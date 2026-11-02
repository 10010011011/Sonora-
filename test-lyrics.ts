import assert from "node:assert/strict";
import { activeWordIndex, estimateWordTimings, parseLyrics, toLRC } from "./lyrics";

const enhanced = parseLyrics(
  "[00:00.00]<00:00.00>Hello <00:00.55>there",
  "enhanced-test.lrc"
).lines[0];

assert.equal(enhanced.text, "Hello there");
assert.equal(enhanced.words?.length, 2, "enhanced LRC must retain word timestamps");
assert.equal(enhanced.words?.[0].text, "Hello ");
assert.equal(activeWordIndex(enhanced, 0.3), 0);
assert.equal(activeWordIndex(enhanced, 0.8), 1);

const classic = parseLyrics(
  "[00:01.00]I love this song\n[00:04.00]Second line",
  "standard-test.lrc"
);
assert.equal(classic.lines[0].words, undefined, "ordinary LRC must not pretend it contains exact word timing");

const estimated = estimateWordTimings(classic, 0, 15);
assert.equal(estimated?.length, 4, "ordinary LRC should receive estimated word progression");
const estimatedLine = { ...classic.lines[0], words: estimated };
assert.equal(activeWordIndex(estimatedLine, 1.05), 0);
assert.equal(activeWordIndex(estimatedLine, 1.5), 1);

const roundTrip = parseLyrics(toLRC({ lines: [enhanced], offset: 0 }));
assert.equal(roundTrip.lines[0].words?.length, 2, "LRC export/import must preserve enhanced word timings");

console.log("Lyrics tests passed: enhanced timestamps, standard-LRC estimates, active-word selection, and round-trip export.");
