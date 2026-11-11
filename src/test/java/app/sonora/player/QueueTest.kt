package app.sonora.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** The queue rules (port of the web app's queue tests). The current song must never change under an edit. */
class QueueTest {
    private val abcde = listOf("a", "b", "c", "d", "e")
    private fun q(ids: List<String>, pos: Int, cursor: Int = 0, base: List<String>? = null) = QueueState(ids, base, pos, cursor)
    private fun QueueState.current() = queue.getOrNull(qpos)

    @Test
    fun startInOrderAndShuffled() {
        val plain = QueueOps.start(abcde, 2, false)
        assertEquals(abcde, plain.queue)
        assertEquals("c", plain.current())
        val shuffled = QueueOps.start(abcde, 3, true)
        assertEquals("d", shuffled.queue[0])
        assertEquals(0, shuffled.qpos)
        assertEquals(abcde.toSet(), shuffled.queue.toSet())
        assertEquals(abcde, shuffled.orderBase)
        assertEquals(QueueState(), QueueOps.start(emptyList(), 0, false))
    }

    @Test
    fun playNextSeveralInARowStayInOrder() {
        var s = q(abcde, 0)
        s = QueueOps.playNext(s, "d", true)
        assertEquals(listOf("a", "d", "b", "c", "e"), s.queue)
        s = QueueOps.playNext(s, "e", true)
        assertEquals(listOf("a", "d", "e", "b", "c"), s.queue)
        assertEquals("a", s.current())
        // A plain "play next" jumps the line again.
        s = QueueOps.playNext(s, "c", false)
        assertEquals(listOf("a", "c", "d", "e", "b"), s.queue)
    }

    @Test
    fun playNextFromBeforeTheCurrentSongKeepsItCurrent() {
        val s = QueueOps.playNext(q(listOf("a", "b", "c"), 2), "a", false)
        assertEquals(listOf("b", "c", "a"), s.queue)
        assertEquals("c", s.current())
        val same = q(abcde, 1)
        assertSame(same, QueueOps.playNext(same, "b", false))
    }

    @Test
    fun addMovesToTheEnd() {
        val s = QueueOps.add(q(listOf("a", "b", "c"), 1), "a")
        assertEquals(listOf("b", "c", "a"), s.queue)
        assertEquals("b", s.current())
        assertEquals(listOf("a", "b", "c", "z"), QueueOps.add(q(listOf("a", "b", "c"), 0), "z").queue)
    }

    @Test
    fun removeNeverTakesTheCurrentSong() {
        val s = q(abcde, 2)
        assertSame(s, QueueOps.remove(s, 2))
        val before = QueueOps.remove(s, 0)
        assertEquals("c", before.current())
        assertEquals(1, before.qpos)
        val after = QueueOps.remove(s, 4)
        assertEquals("c", after.current())
        assertEquals(listOf("a", "b", "c", "d"), after.queue)
    }

    @Test
    fun moveKeepsTheCurrentSong() {
        val s = q(abcde, 2)
        assertEquals("c", QueueOps.move(s, 0, 4).current())
        assertEquals("c", QueueOps.move(s, 4, 0).current())
        val dragged = QueueOps.move(s, 2, 0)
        assertEquals(listOf("c", "a", "b", "d", "e"), dragged.queue)
        assertEquals("c", dragged.current())
        assertSame(s, QueueOps.move(s, 1, 9))
    }

    @Test
    fun clearUpNextDropsWhatFollows() {
        assertEquals(listOf("a", "b"), QueueOps.clearUpNext(q(abcde, 1)).queue)
        val last = q(abcde, 4)
        assertSame(last, QueueOps.clearUpNext(last))
    }

    @Test
    fun shuffleOffRestoresTheOriginalOrder() {
        val on = QueueOps.setShuffle(q(abcde, 2), true)
        assertEquals("c", on.current())
        assertEquals(abcde, on.orderBase)
        // A song queued while shuffled is not in the original list: it stays at the end.
        val withExtra = QueueOps.add(on, "z")
        val off = QueueOps.setShuffle(withExtra, false)
        assertEquals(abcde + "z", off.queue)
        assertEquals("c", off.current())
        assertNull(off.orderBase)
    }

    @Test
    fun removedSongsLeaveTheQueue() {
        val s = QueueOps.without(q(abcde, 2, base = abcde), setOf("a", "e"))
        assertEquals(listOf("b", "c", "d"), s.queue)
        assertEquals("c", s.current())
        assertEquals(listOf("b", "c", "d"), s.orderBase)
        val gone = QueueOps.without(q(abcde, 2), setOf("c"))
        assertEquals("d", gone.current())
        assertEquals(-1, QueueOps.without(q(listOf("a"), 0), setOf("a")).qpos)
    }

    @Test
    fun importedSongsJoinAnUnscopedQueue() {
        assertEquals(listOf("x", "y"), QueueOps.appendNew(QueueState(), listOf("x", "y")).queue)
        assertEquals(listOf("a", "x"), QueueOps.appendNew(q(listOf("a"), 0), listOf("x")).queue)
        // Playing a playlist or a shuffle: new imports do not barge in.
        assertEquals(listOf("a"), QueueOps.appendNew(q(listOf("a"), 0, base = listOf("a")), listOf("x")).queue)
    }

    @Test
    fun whereNextAndPreviousGo() {
        assertEquals(1, QueueOps.nextPos(3, 0, "off", repeatSame = false, manual = false))
        assertEquals(-1, QueueOps.nextPos(3, 2, "off", repeatSame = false, manual = false))
        assertEquals(0, QueueOps.nextPos(3, 2, "all", repeatSame = false, manual = false))
        // Repeat-one (or repeat-N with plays left) replays automatically, but pressing Next still moves on.
        assertEquals(1, QueueOps.nextPos(3, 1, "one", repeatSame = true, manual = false))
        assertEquals(2, QueueOps.nextPos(3, 1, "one", repeatSame = true, manual = true))
        assertEquals(-1, QueueOps.nextPos(0, 0, "all", repeatSame = false, manual = true))
        assertEquals(0, QueueOps.prevPos(3, 1, "off"))
        assertEquals(0, QueueOps.prevPos(3, 0, "off"))
        assertEquals(2, QueueOps.prevPos(3, 0, "all"))
    }
}
