package app.sonora.player.audio

import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi

/**
 * What the notification, lock screen and Bluetooth buttons talk to. Reproduces the old app's rules:
 *  - Next/Previous are always available when the queue is not empty.
 *  - Manual Next on the last track wraps to the first track (even with repeat off).
 *  - Previous restarts the track when more than 3 s in, otherwise goes to the previous track (wrapping).
 *  - Duplicate skip commands within 350 ms are debounced (FIXES.md 1.2.1 / engineGuards.ts).
 */
@OptIn(UnstableApi::class)
class SonoraForwardingPlayer(
    player: Player,
    private val onPlayRequested: (() -> Unit)? = null,
    private val onPauseRequested: (() -> Unit)? = null,
) : ForwardingPlayer(player) {

    private var lastSkipAction: String = ""
    private var lastSkipAtMs: Long = 0L

    override fun getAvailableCommands(): Player.Commands =
        super.getAvailableCommands().buildUpon()
            .add(Player.COMMAND_SEEK_TO_NEXT)
            .add(Player.COMMAND_SEEK_TO_PREVIOUS)
            .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
            .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
            .build()

    override fun isCommandAvailable(command: Int): Boolean = availableCommands.contains(command)

    override fun hasNextMediaItem(): Boolean = mediaItemCount > 0
    override fun hasPreviousMediaItem(): Boolean = mediaItemCount > 0

    override fun play() {
        if (onPlayRequested != null) onPlayRequested.invoke() else super.play()
    }

    override fun pause() {
        if (onPauseRequested != null) onPauseRequested.invoke() else super.pause()
    }

    override fun seekToNext() { goNext() }
    override fun seekToNextMediaItem() { goNext() }
    override fun seekToPrevious() { goPrev() }
    override fun seekToPreviousMediaItem() { goPrev() }

    private fun goNext() {
        if (mediaItemCount == 0) return
        val now = SystemClock.elapsedRealtime()
        if (QueueManager.isDuplicateSkip("next", lastSkipAction, lastSkipAtMs, now)) {
            Diagnostics.log("skip-debounced", "next")
            return
        }
        lastSkipAction = "next"
        lastSkipAtMs = now
        if (super.hasNextMediaItem()) super.seekToNextMediaItem() else super.seekToDefaultPosition(0)
    }

    private fun goPrev() {
        if (mediaItemCount == 0) return
        val now = SystemClock.elapsedRealtime()
        if (QueueManager.isDuplicateSkip("prev", lastSkipAction, lastSkipAtMs, now)) {
            Diagnostics.log("skip-debounced", "prev")
            return
        }
        lastSkipAction = "prev"
        lastSkipAtMs = now
        val idx = currentMediaItemIndex
        if (currentPosition > 3000L || mediaItemCount == 1) super.seekToDefaultPosition()
        else if (idx > 0) super.seekToDefaultPosition(idx - 1)
        else super.seekToDefaultPosition(mediaItemCount - 1)
    }
}
