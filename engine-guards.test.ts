import test from "node:test";
import assert from "node:assert/strict";
import { isDuplicateSkip, matchesExpectedPreloadedDeck } from "./engineGuards";

test("same next/previous action is debounced only inside 350 ms", () => {
  assert.equal(isDuplicateSkip("next", "next", 1000, 1349), true);
  assert.equal(isDuplicateSkip("next", "next", 1000, 1350), false);
  assert.equal(isDuplicateSkip("prev", "prev", 1000, 1349), true);
});

test("different transport actions are not mistaken for duplicate dispatches", () => {
  assert.equal(isDuplicateSkip("prev", "next", 1000, 1100), false);
  assert.equal(isDuplicateSkip("next", "prev", 1000, 1100), false);
});

test("clock rollback cannot accidentally suppress a skip", () => {
  assert.equal(isDuplicateSkip("next", "next", 2000, 1900), false);
});

test("preloaded deck must match both queue position and track identity", () => {
  assert.equal(matchesExpectedPreloadedDeck(2, 2, "track-c", "track-c"), true);
  assert.equal(matchesExpectedPreloadedDeck(2, 2, "track-b", "track-c"), false);
  assert.equal(matchesExpectedPreloadedDeck(1, 2, "track-c", "track-c"), false);
  assert.equal(matchesExpectedPreloadedDeck(2, 2, null, "track-c"), false);
  assert.equal(matchesExpectedPreloadedDeck(2, 2, "track-c", undefined), false);
});
