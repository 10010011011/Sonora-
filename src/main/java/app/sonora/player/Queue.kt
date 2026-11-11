package app.sonora.player

import kotlin.math.max
import kotlin.math.min

/**
 * Every queue rule as a pure function (QueueState in, QueueState out), so the rules are unit-tested
 * without a player. Port of queue.ts; tracks are identified by id rather than library index.
 * Each edit keeps `qpos` pointing at the same song.
 */
object QueueOps {
    /** A fresh queue from `ids`, starting at position `start`; shuffled around the starting song when asked. */
    fun start(ids: List<String>, start: Int, shuffle: Boolean): QueueState {
        if (ids.isEmpty()) return QueueState()
        val startId = ids[start.coerceIn(0, ids.size - 1)]
        val queue = if (shuffle) shuffledKeeping(ids, startId) else ids
        return QueueState(queue, ids, queue.indexOf(startId), 0)
    }

    /** Inserts `id` right after the current song (for "play this now"). Returns the new state and where the song sits. */
    fun insertNow(s: QueueState, id: String): Pair<QueueState, Int> {
        val existing = s.queue.indexOf(id)
        if (existing >= 0) return s to existing
        val at = (s.qpos + 1).coerceIn(0, s.queue.size)
        return s.copy(queue = s.queue.toMutableList().apply { add(at, id) }, insertCursor = 0) to at
    }

    /**
     * Moves (or inserts) a song so it plays right after the current one, or, when `sequential`,
     * after the songs already queued with "Play next" so several stay in the order they were chosen.
     */
    fun playNext(s: QueueState, id: String, sequential: Boolean): QueueState {
        if (s.qpos !in s.queue.indices || s.queue[s.qpos] == id) return s
        val next = s.queue.toMutableList()
        var pos = s.qpos
        var cursor = if (sequential) s.insertCursor.coerceIn(0, max(0, next.size - 1 - pos)) else 0
        val at = next.indexOf(id)
        if (at >= 0) {
            next.removeAt(at)
            if (at < pos) pos-- else if (at <= pos + cursor) cursor = max(0, cursor - 1)
        }
        next.add(min(next.size, pos + 1 + cursor), id)
        return s.copy(queue = next, qpos = pos, insertCursor = if (sequential) cursor + 1 else 1)
    }

    /** Moves (or adds) a song to the end of the queue. */
    fun add(s: QueueState, id: String): QueueState {
        if (s.qpos !in s.queue.indices || s.queue[s.qpos] == id) return s
        val next = s.queue.toMutableList()
        var pos = s.qpos
        val at = next.indexOf(id)
        if (at >= 0) {
            next.removeAt(at)
            if (at < pos) pos--
        }
        next.add(id)
        return s.copy(queue = next, qpos = pos)
    }

    /** The current song cannot be removed (it is playing). */
    fun remove(s: QueueState, pos: Int): QueueState {
        if (pos !in s.queue.indices || pos == s.qpos) return s
        val cursor = if (pos > s.qpos && pos <= s.qpos + s.insertCursor) max(0, s.insertCursor - 1) else s.insertCursor
        return s.copy(queue = s.queue.toMutableList().apply { removeAt(pos) }, qpos = if (pos < s.qpos) s.qpos - 1 else s.qpos, insertCursor = cursor)
    }

    fun move(s: QueueState, from: Int, to: Int): QueueState {
        if (from == to || from !in s.queue.indices || to !in s.queue.indices) return s
        val pos = when {
            from == s.qpos -> to
            from < s.qpos && to >= s.qpos -> s.qpos - 1
            from > s.qpos && to <= s.qpos -> s.qpos + 1
            else -> s.qpos
        }
        return s.copy(queue = s.queue.toMutableList().apply { add(to, removeAt(from)) }, qpos = pos, insertCursor = 0)
    }

    /** Drops everything after the current song. */
    fun clearUpNext(s: QueueState): QueueState =
        if (s.qpos < 0 || s.qpos >= s.queue.size - 1) s else s.copy(queue = s.queue.take(s.qpos + 1), insertCursor = 0)

    /** On: shuffles around the current song and remembers the order. Off: puts the queue back in that order. */
    fun setShuffle(s: QueueState, on: Boolean): QueueState {
        if (s.queue.isEmpty()) return s
        val cur = s.queue.getOrNull(s.qpos)
        if (on) {
            val shuffled = shuffledKeeping(s.queue, cur)
            return s.copy(queue = shuffled, orderBase = s.orderBase ?: s.queue, qpos = max(0, shuffled.indexOf(cur)), insertCursor = 0)
        }
        val have = s.queue.toHashSet()
        val ordered = (s.orderBase ?: s.queue).filter { it in have }
        val inBase = ordered.toHashSet()
        val restored = ordered + s.queue.filter { it !in inBase } // songs queued while shuffled keep their place at the end
        return s.copy(queue = restored, orderBase = null, qpos = max(0, restored.indexOf(cur)), insertCursor = 0)
    }

    /** Songs removed from the library leave the queue; the current position stays on the same song where it can. */
    fun without(s: QueueState, ids: Set<String>): QueueState {
        if (ids.isEmpty()) return s
        val cur = s.queue.getOrNull(s.qpos)
        val next = s.queue.filter { it !in ids }
        val pos = if (next.isEmpty()) -1 else next.indexOf(cur).let { if (it >= 0) it else s.qpos.coerceIn(0, next.size - 1) }
        return s.copy(queue = next, orderBase = s.orderBase?.filter { it !in ids }, qpos = pos, insertCursor = 0)
    }

    /** Newly imported songs join the end of an unshuffled, unscoped queue (or become the queue when there is none). */
    fun appendNew(s: QueueState, ids: List<String>): QueueState = when {
        ids.isEmpty() -> s
        s.queue.isEmpty() -> QueueState(ids, null, 0, 0)
        s.orderBase == null -> s.copy(queue = s.queue + ids)
        else -> s
    }

    /**
     * Where playback goes after position `from`, or -1 to stop.
     * `repeatSame` = repeat-one, or repeat-N with plays left. `manual` = the user pressed Next, which always moves on.
     */
    fun nextPos(size: Int, from: Int, repeat: String, repeatSame: Boolean, manual: Boolean): Int {
        if (size == 0) return -1
        if (!manual && repeatSame) return from.coerceIn(0, size - 1)
        return if (from + 1 < size) from + 1 else if (repeat != "off") 0 else -1
    }

    /** Previous: back one song, wrapping to the end only when repeat-all is on. */
    fun prevPos(size: Int, from: Int, repeat: String): Int = if (size == 0) -1 else if (from > 0) from - 1 else if (repeat == "all") size - 1 else 0
}
