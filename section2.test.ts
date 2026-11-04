import test from "node:test";
import assert from "node:assert/strict";
import { legacyItemFrom } from "./migrateLegacy";
import { normalizeUserData, remapUserData, mergeUserData, startPositionFor, saveResume, userdata, playlistToM3U, importM3UPlaylist } from "./userdata";
import { library, settings, type Track } from "./store";
import { shuffled } from "./shuffle";

const t = (id: string, duration = 200): Track => ({ id, title: id, artist: "a", album: "b", fileName: `${id}.mp3`, path: `music/${id}.mp3`, size: 1, duration } as unknown as Track);

test("legacy IndexedDB records are recognised by shape, not by schema", () => {
  const blob = new Blob([new Uint8Array(10)], { type: "audio/mpeg" });
  const a = legacyItemFrom("k", { name: "a.mp3", lastModified: 5, file: blob });
  assert.equal(a?.id, "a.mp3|10|5");
  assert.equal(legacyItemFrom("k", blob)?.name, "k.mp3"); // bare blob under a string key, mime says audio
  assert.equal(legacyItemFrom("k", { name: "notes.txt", file: new Blob(["x"], { type: "text/plain" }) }), null);
  assert.equal(legacyItemFrom("k", { name: "empty.mp3", file: new Blob([]) }), null);
  assert.equal(legacyItemFrom("k", { id: "x.mp3|10|5", name: "x.mp3", data: blob })?.id, "x.mp3|10|5"); // stored legacy id wins
});

test("user data is normalised, remapped to new ids and merged without losing local data", () => {
  assert.deepEqual(normalizeUserData(null).playlists, []);
  const incoming = normalizeUserData({
    playlists: [{ id: "p", name: "P", trackIds: ["old1", "old2", "old1"], createdAt: 1, updatedAt: 1 }],
    bookmarks: [{ id: "b", trackId: "old1", t: 3, label: "", createdAt: 1 }],
    resume: { last: { trackId: "old2", ms: 9000 }, byTrack: { old1: 1000 } },
    trackConfigs: { old1: { speed: 1.25 } },
  });
  const mapped = remapUserData(incoming, new Map([["old1", "new1"]]));
  assert.deepEqual(mapped.playlists[0].trackIds, ["new1", "old2"]);
  assert.equal(mapped.bookmarks[0].trackId, "new1");
  assert.equal(mapped.resume.byTrack.new1, 1000);
  assert.equal(mapped.trackConfigs?.new1?.speed, 1.25);
  const local = normalizeUserData({ playlists: [{ id: "q", name: "Q", trackIds: [], createdAt: 1, updatedAt: 1 }] });
  assert.deepEqual(mergeUserData(local, mapped).playlists.map((p) => p.id), ["q", "p"]);
});

test("M3U playlist export and import round-trip matches library tracks", () => {
  const tracks = [t("song1", 180), t("song2", 240)];
  const m3u = playlistToM3U({ id: "p1", name: "Favorites", trackIds: ["song1", "song2"], createdAt: 1, updatedAt: 1 }, tracks);
  assert.ok(m3u.includes("#PLAYLIST:Favorites"));
  const res = importM3UPlaylist(m3u, "fallback.m3u8", tracks);
  assert.equal(res.name, "Favorites");
  assert.equal(res.matched, 2);
});

test("resume: last track restores always, long tracks remember, finished tracks reset", () => {
  settings.set({ resumeMinSec: 600 });
  userdata.set({ version: 1, playlists: [], bookmarks: [], resume: { last: null, byTrack: {} }, trackConfigs: {}, libraryOrder: [] });
  saveResume("short", 42, 200);
  assert.equal(startPositionFor(t("short"), "prime"), 42);
  assert.equal(startPositionFor(t("short"), "play"), 0); // short tracks restart when picked
  saveResume("long", 900, 3600);
  assert.equal(startPositionFor(t("long", 3600), "play"), 900);
  saveResume("long", 3595, 3600); // within 10 s of the end = finished
  assert.equal(startPositionFor(t("long", 3600), "play"), 0);
});

test("shuffled keeps the playing track first and every index exactly once", () => {
  const out = shuffled([0, 1, 2, 3, 4, 5], 3);
  assert.equal(out[0], 3);
  assert.deepEqual([...out].sort(), [0, 1, 2, 3, 4, 5]);
});

test("queue operations keep qpos on the same track and support sequential playNext", async () => {
  // The engine builds <audio> elements at import time; a permissive stand-in is enough because only the queue is under test.
  const loose = () => new Proxy({} as Record<string, unknown>, { get: (o, k) => (k in o ? o[k as string] : () => loose()), set: (o, k, v) => ((o[k as string] = v), true) });
  Object.assign(globalThis, { Audio: class { constructor() { return loose(); } } });
  const { playNext, addToQueue, removeFromQueue, moveInQueue, clearUpNext } = await import("./queue");
  library.set({ tracks: [0, 1, 2, 3, 4].map((i) => t(`t${i}`)), queue: [0, 1, 2, 3, 4], qpos: 1, insertCursor: 0, orderBase: null });
  const cur = () => library.get().queue[library.get().qpos];
  playNext(4);
  assert.deepEqual(library.get().queue, [0, 1, 4, 2, 3]);
  assert.equal(cur(), 1);
  playNext(3, true); // sequential playNext inserts after 4
  assert.deepEqual(library.get().queue, [0, 1, 4, 3, 2]);
  addToQueue(0); // 0 is before the current track: moved to the end, qpos shifts back
  assert.deepEqual(library.get().queue, [1, 4, 3, 2, 0]);
  assert.equal(cur(), 1);
  moveInQueue(3, 0); // from after current to before it
  assert.equal(cur(), 1);
  removeFromQueue(library.get().qpos); // current cannot be removed
  assert.equal(library.get().queue.length, 5);
  clearUpNext();
  assert.equal(library.get().queue.length, library.get().qpos + 1);
});
