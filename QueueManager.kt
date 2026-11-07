package app.sonora.player.audio

import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * Pure queue state & operations ported line-by-line from queue.ts and shuffle.ts.
 * Queue elements are indices into the current library track list.
 */
data class QueueState(
    val queue: List<Int> = emptyList(),
    val orderBase: List<Int>? = null,
    val qpos: Int = -1,
    val insertCursor: Int = 0,
)

object QueueManager {

    /** Random order of a queue, keeping [keepFirst] (the playing track) in front (mirrors shuffle.ts). */
    fun shuffled(q: List<Int>, keepFirst: Int, random: Random = Random.Default): List<Int> {
        val a = q.filter { it != keepFirst }.toMutableList()
        for (i in a.lastIndex downTo 1) {
            val j = random.nextInt(i + 1)
            val tmp = a[i]
            a[i] = a[j]
            a[j] = tmp
        }
        return if (keepFirst >= 0 && q.contains(keepFirst)) listOf(keepFirst) + a else a
    }

    /** Replace the queue with [indices] and start playing at [start] (a position in [indices]). */
    fun buildQueueForPlayIndices(
        indices: List<Int>,
        start: Int = 0,
        shuffle: Boolean = false,
        random: Random = Random.Default,
    ): QueueState {
        if (indices.isEmpty()) return QueueState()
        val clampedStart = max(0, min(start, indices.lastIndex))
        val startTrack = indices[clampedStart]
        val q = if (shuffle) shuffled(indices, startTrack, random) else indices.toList()
        return QueueState(
            queue = q,
            orderBase = indices.toList(),
            qpos = q.indexOf(startTrack).coerceAtLeast(0),
            insertCursor = 0,
        )
    }

    /** Play a track right now without disturbing the rest of the queue (open-with, search hits). */
    fun playNowState(state: QueueState, trackIdx: Int): QueueState {
        val existingPos = state.queue.indexOf(trackIdx)
        if (existingPos >= 0) return state.copy(qpos = existingPos, insertCursor = 0)
        val next = state.queue.toMutableList()
        val insertAt = (state.qpos + 1).coerceIn(0, next.size)
        next.add(insertAt, trackIdx)
        return state.copy(queue = next, qpos = insertAt, insertCursor = 0)
    }

    /**
     * Moves (or inserts) a track so it plays right after the current one (or after the previously
     * "Play next" inserted track when queuing several songs in sequence).
     */
    fun playNextState(state: QueueState, trackIdx: Int, sequential: Boolean = false): QueueState {
        if (state.queue.isEmpty() || state.qpos < 0) {
            return buildQueueForPlayIndices(listOf(trackIdx), 0, false)
        }
        if (state.queue.getOrNull(state.qpos) == trackIdx) return state
        val next = state.queue.toMutableList()
        var pos = state.qpos
        var cursor = if (sequential) max(0, min(state.insertCursor, next.size - 1 - pos)) else 0
        val at = next.indexOf(trackIdx)
        if (at >= 0) {
            next.removeAt(at)
            if (at < pos) pos--
            else if (at <= pos + cursor) cursor = max(0, cursor - 1)
        }
        val target = min(next.size, pos + 1 + cursor)
        next.add(target, trackIdx)
        return state.copy(
            queue = next,
            qpos = pos,
            insertCursor = if (sequential) cursor + 1 else 1,
        )
    }

    fun addToQueueState(state: QueueState, trackIdx: Int): QueueState {
        if (state.queue.isEmpty() || state.qpos < 0) {
            return buildQueueForPlayIndices(listOf(trackIdx), 0, false)
        }
        if (state.queue.getOrNull(state.qpos) == trackIdx) return state
        val next = state.queue.toMutableList()
        var pos = state.qpos
        val at = next.indexOf(trackIdx)
        if (at >= 0) {
            next.removeAt(at)
            if (at < pos) pos--
        }
        next.add(trackIdx)
        return state.copy(queue = next, qpos = pos)
    }

    /** The current track cannot be removed (it is playing). */
    fun removeFromQueueState(state: QueueState, pos: Int): QueueState {
        if (pos < 0 || pos >= state.queue.size || pos == state.qpos) return state
        val next = state.queue.toMutableList()
        next.removeAt(pos)
        val nextPos = if (pos < state.qpos) state.qpos - 1 else state.qpos
        val nextCursor = if (pos > state.qpos && pos <= state.qpos + state.insertCursor) {
            max(0, state.insertCursor - 1)
        } else {
            state.insertCursor
        }
        return state.copy(queue = next, qpos = nextPos, insertCursor = nextCursor)
    }

    fun moveInQueueState(state: QueueState, from: Int, to: Int): QueueState {
        if (from == to || from < 0 || to < 0 || from >= state.queue.size || to >= state.queue.size) return state
        val next = state.queue.toMutableList()
        val item = next.removeAt(from)
        next.add(to, item)
        val pos = when {
            from == state.qpos -> to
            from < state.qpos && to >= state.qpos -> state.qpos - 1
            from > state.qpos && to <= state.qpos -> state.qpos + 1
            else -> state.qpos
        }
        return state.copy(queue = next, qpos = pos, insertCursor = 0)
    }

    /** Drops everything after the current track. */
    fun clearUpNextState(state: QueueState): QueueState {
        if (state.qpos < 0 || state.qpos >= state.queue.size - 1) return state
        return state.copy(
            queue = state.queue.subList(0, state.qpos + 1).toList(),
            insertCursor = 0,
        )
    }

    /** Shuffles or un-shuffles the current queue while preserving the current track (library.ts setShuffle). */
    fun setShuffleState(state: QueueState, on: Boolean, random: Random = Random.Default): QueueState {
        if (state.queue.isEmpty()) return state
        val cur = state.queue.getOrNull(state.qpos) ?: -1
        val nq = if (on) {
            shuffled(state.queue, cur, random)
        } else {
            val inQueue = state.queue.toSet()
            val base = (state.orderBase ?: inQueue.sorted()).filter { inQueue.contains(it) }
            val have = base.toSet()
            base + state.queue.filter { !have.contains(it) }
        }
        return state.copy(
            queue = nq,
            qpos = if (cur >= 0) nq.indexOf(cur) else -1,
            insertCursor = 0,
        )
    }

    /** True only for the same transport action repeated inside the 350 ms debounce window (engineGuards.ts). */
    fun isDuplicateSkip(
        action: String,
        lastAction: String,
        lastAtMs: Long,
        nowMs: Long,
        windowMs: Long = 350L,
    ): Boolean {
        val elapsed = nowMs - lastAtMs
        return action == lastAction && elapsed in 0 until windowMs
    }
}
