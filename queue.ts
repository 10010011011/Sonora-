import { library, settings } from "./store";
import { engine } from "./engine";
import { shuffled } from "./shuffle";

/**
 * Queue operations. The queue is `library.queue`: library indices in play order. Every edit keeps `qpos`
 * pointing at the same track and refreshes the engine's preloaded "next" track.
 */

const commit = (queue: number[], qpos: number, insertCursor = 0) => {
  library.set({ queue, qpos, insertCursor });
  engine.requeue();
};

/** Replace the queue with `indices` and start playing at `start` (a position in `indices`). */
export function playIndices(indices: number[], start = 0, forceShuffle?: boolean) {
  if (!indices.length) return;
  const startTrack = indices[Math.max(0, Math.min(start, indices.length - 1))];
  const shuffle = forceShuffle ?? settings.get().shuffle;
  if (forceShuffle !== undefined && settings.get().shuffle !== forceShuffle) settings.set({ shuffle: forceShuffle });
  const queue = shuffle ? shuffled(indices, startTrack) : [...indices];
  library.set({ queue, orderBase: [...indices], qpos: queue.indexOf(startTrack), insertCursor: 0 });
  engine.requeue();
  void engine.playAt(library.get().qpos);
}

/** Play `trackIdx` as part of the list the user is looking at (so Next/Previous follow that list). */
export function playFromList(list: number[], trackIdx: number) {
  const pos = list.indexOf(trackIdx);
  if (pos >= 0) playIndices(list, pos);
}

/** Play a track right now without disturbing the rest of the queue (open-with, search hits). */
export function playNow(trackIdx: number) {
  const { queue, qpos } = library.get();
  const pos = queue.indexOf(trackIdx);
  if (pos >= 0) return void engine.playAt(pos);
  const next = [...queue];
  next.splice(qpos + 1, 0, trackIdx);
  commit(next, qpos, 0);
  void engine.playAt(qpos + 1);
}

/**
 * Moves (or inserts) a track so it plays right after the current one (or after the previously
 * "Play next" inserted track when queuing several songs in sequence).
 */
export function playNext(trackIdx: number, sequential = false) {
  const { queue, qpos, insertCursor } = library.get();
  if (!queue.length || qpos < 0) return playIndices([trackIdx]);
  if (queue[qpos] === trackIdx) return;
  const next = [...queue];
  let pos = qpos;
  let cursor = sequential ? Math.max(0, Math.min(insertCursor, next.length - 1 - pos)) : 0;
  const at = next.indexOf(trackIdx);
  if (at >= 0) {
    next.splice(at, 1);
    if (at < pos) pos--;
    else if (at <= pos + cursor) cursor = Math.max(0, cursor - 1);
  }
  const target = Math.min(next.length, pos + 1 + cursor);
  next.splice(target, 0, trackIdx);
  commit(next, pos, sequential ? cursor + 1 : 1);
}

export function addToQueue(trackIdx: number) {
  const { queue, qpos, insertCursor } = library.get();
  if (!queue.length || qpos < 0) return playIndices([trackIdx]);
  if (queue[qpos] === trackIdx) return;
  const next = [...queue];
  let pos = qpos;
  const at = next.indexOf(trackIdx);
  if (at >= 0) {
    next.splice(at, 1);
    if (at < pos) pos--;
  }
  next.push(trackIdx);
  commit(next, pos, insertCursor);
}

/** The current track cannot be removed (it is playing). */
export function removeFromQueue(pos: number) {
  const { queue, qpos, insertCursor } = library.get();
  if (pos < 0 || pos >= queue.length || pos === qpos) return;
  const next = [...queue];
  next.splice(pos, 1);
  const nextPos = pos < qpos ? qpos - 1 : qpos;
  const nextCursor = pos > qpos && pos <= qpos + insertCursor ? Math.max(0, insertCursor - 1) : insertCursor;
  commit(next, nextPos, nextCursor);
}

export function moveInQueue(from: number, to: number) {
  const { queue, qpos } = library.get();
  if (from === to || from < 0 || to < 0 || from >= queue.length || to >= queue.length) return;
  const next = [...queue];
  const [x] = next.splice(from, 1);
  next.splice(to, 0, x);
  let pos = qpos;
  if (from === qpos) pos = to;
  else if (from < qpos && to >= qpos) pos = qpos - 1;
  else if (from > qpos && to <= qpos) pos = qpos + 1;
  commit(next, pos, 0);
}

/** Drops everything after the current track. */
export function clearUpNext() {
  const { queue, qpos } = library.get();
  if (qpos < 0 || qpos >= queue.length - 1) return;
  commit(queue.slice(0, qpos + 1), qpos, 0);
}

export function enqueueMany(indices: number[]) {
  indices.forEach(addToQueue);
}
