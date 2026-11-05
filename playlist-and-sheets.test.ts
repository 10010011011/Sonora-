import test from "node:test";
import assert from "node:assert/strict";
import { createPlaylist, addToPlaylist, userdata, mergeUserData, normalizeUserData, removeFromPlaylist } from "./userdata";
import { sheets, openSongPicker, openPlaylistPicker, openTrackMenu, closeSheets } from "./sheets";

test("a new playlist starts empty and songs can be added to it", () => {
  const id = createPlaylist("Road trip");
  const get = () => userdata.get().playlists.find((p) => p.id === id)!;
  assert.deepEqual(get().trackIds, []);
  assert.equal(addToPlaylist(id, ["a", "b", "c"]), 3);
  assert.deepEqual(get().trackIds, ["a", "b", "c"]);
});

test("adding skips duplicates and reports only what was new", () => {
  const id = createPlaylist("Dupes", ["x"]);
  assert.equal(addToPlaylist(id, ["x", "y", "y", "z"]), 2);
  assert.deepEqual(userdata.get().playlists.find((p) => p.id === id)!.trackIds, ["x", "y", "z"]);
  assert.equal(addToPlaylist(id, ["x", "y"]), 0);
});

test("adding to a playlist that no longer exists does nothing and does not throw", () => {
  assert.equal(addToPlaylist("missing", ["a"]), 0);
});

test("removing then re-adding works", () => {
  const id = createPlaylist("Re", ["a", "b"]);
  removeFromPlaylist(id, "a");
  assert.equal(addToPlaylist(id, ["a"]), 1);
  assert.deepEqual(userdata.get().playlists.find((p) => p.id === id)!.trackIds, ["b", "a"]);
});

test("merging the on-disk copy keeps playlists edited before it loaded", () => {
  const local = normalizeUserData({ playlists: [{ id: "new", name: "Fresh", trackIds: ["t1"], createdAt: 1, updatedAt: 1 }] });
  const disk = normalizeUserData({ playlists: [{ id: "old", name: "Saved", trackIds: ["t2"], createdAt: 1, updatedAt: 1 }] });
  const merged = mergeUserData(local, disk);
  assert.deepEqual(merged.playlists.map((p) => p.id).sort(), ["new", "old"]);
});

test("damaged user data normalises to something safe", () => {
  const n = normalizeUserData({ playlists: [null, { id: 5 }, { id: "ok", name: "n", trackIds: [], createdAt: 0, updatedAt: 0 }], bookmarks: "x" });
  assert.deepEqual(n.playlists.map((p) => p.id), ["ok"]);
  assert.deepEqual(n.bookmarks, []);
});

test("the song picker opens for one playlist and closes every other sheet", () => {
  openTrackMenu(3);
  assert.ok(sheets.get().menu);
  openSongPicker("pl1");
  assert.equal(sheets.get().songPicker, "pl1");
  assert.equal(sheets.get().menu, null);
  openPlaylistPicker(["a"]);
  assert.equal(sheets.get().songPicker, null);
  closeSheets();
  assert.equal(sheets.get().picker, null);
});
