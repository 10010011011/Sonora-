package app.sonora.player

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.common.Tracks
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * Playback engine: two ExoPlayers (a main one and a standby for true overlap crossfade), the play
 * queue, and every playback rule (native gapless, repeat / repeat-N, sleep timer, A-B loop, fades,
 * ReplayGain, classified error recovery).
 *
 * The engine is owned by the Application and the PlaybackService — never by an Activity — so music
 * keeps playing after the app is swiped away. The whole queue is loaded as one playlist, so
 * gapless transitions are native; Sonora's own queue stays the source of truth and the player's
 * playlist is synced to it after every change.
 */
object Engine {
    val dspA = SonoraDsp()
    val dspB = SonoraDsp()
    private var playerA: ExoPlayer? = null
    private var playerB: ExoPlayer? = null
    private var mainIsA = true
    private var routA: Player? = null
    private var routB: Player? = null

    /** The DSP of the player currently heard (meters, visualiser tap). */
    val dsp: SonoraDsp get() = if (mainIsA) dspA else dspB
    private fun dspOf(p: ExoPlayer) = if (p === playerA) dspA else dspB

    private fun main(): ExoPlayer {
        if (mainIsA) {
            if (playerA == null) playerA = build(dspA)
            return playerA!!
        }
        if (playerB == null) playerB = build(dspB)
        return playerB!!
    }

    private fun side(): ExoPlayer {
        if (!mainIsA) {
            if (playerA == null) playerA = build(dspA)
            return playerA!!
        }
        if (playerB == null) playerB = build(dspB)
        return playerB!!
    }

    private fun mainOrNull(): ExoPlayer? = if (mainIsA) playerA else playerB
    private fun sideOrNull(): ExoPlayer? = if (mainIsA) playerB else playerA
    fun forEachPlayer(f: (ExoPlayer) -> Unit) {
        playerA?.let(f)
        playerB?.let(f)
    }

    /** Compatibility: the main player. New code should use mainRouted() / curPlayer(). */
    val exo: ExoPlayer get() = main()

    /** The media-session player, with Next / Previous / play / pause routed through Sonora's queue. */
    fun mainRouted(): Player {
        return if (mainIsA) {
            routA ?: makeRouted(main()).also { routA = it }
        } else {
            routB ?: makeRouted(main()).also { routB = it }
        }
    }

    /** Installed by the PlaybackService; called (on the main thread) whenever the main player changes. */
    var sessionSetter: ((Player) -> Unit)? = null

    fun isPlayingAny(): Boolean = playerA?.isPlaying == true || playerB?.isPlaying == true

    private data class Overlap(val outId: String, val inId: String, val totalSec: Double, var t0ms: Long)
    private var overlap: Overlap? = null
    private var pauseAtMs = 0L
    private var playlistIds: List<String> = emptyList()
    private var playlistDirty = false

    private var fade = 1f
    private var sleepG = 1f
    private var duck = 1f
    private var pausing = false
    private var fadeJob: Job? = null
    private var ticker: Job? = null
    private var analyzeJob: Job? = null
    private var counted: String? = null
    private var lastResumeSave = 0L
    private val retried = HashSet<String>()
    private var loadSeq = 0
    private var failures = 0
    private var lastSkipAt = 0L
    private var pausedByNoisy = false
    private var holdSpeed = false
    private var autoStarted = false
    private var pendingStart: Double? = null
    private var lastModes = ""
    private var noisyOn: Boolean? = null
    private var knownOuts: Set<String>? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    /** The UI throttles the clock to 4 Hz while nothing that needs it is on screen. */
    @Volatile var uiVisible = true

    /** Position of the song currently heard (the incoming one during an overlap). */
    val time: Double get() {
        val p = (if (overlap != null) sideOrNull() else mainOrNull())?.takeIf { it.mediaItemCount > 0 }
        return p?.currentPosition?.div(1000.0) ?: S.clock.value
    }

    private fun q() = S.queue.value
    private fun st() = S.settings.value

    fun init() {
        S.scope.launch { S.settings.collect { apply() } }
        S.scope.launch { S.user.collect { apply() } }
        S.scope.launch {
            S.loaded.collect { if (it) prime() }
        }
        val am = S.app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.registerAudioDeviceCallback(object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>?) = devicesChanged()
            override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>?) = devicesChanged()
        }, null)
        devicesChanged()
    }

    private fun build(dsp: SonoraDsp): ExoPlayer {
        val ctx = S.app
        val rf = object : DefaultRenderersFactory(ctx) {
            override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink =
                DefaultAudioSink.Builder(context).setAudioProcessors(arrayOf<AudioProcessor>(dsp)).build()

            override fun buildAudioRenderers(
                context: Context, extensionRendererMode: Int, mediaCodecSelector: MediaCodecSelector, enableDecoderFallback: Boolean,
                audioSink: AudioSink, eventHandler: Handler, eventListener: AudioRendererEventListener, out: ArrayList<Renderer>,
            ) {
                super.buildAudioRenderers(context, extensionRendererMode, mediaCodecSelector, enableDecoderFallback, audioSink, eventHandler, eventListener, out)
                // After the MediaCodec renderer: hardware decoding stays preferred, FFmpeg is the fallback.
                Ffmpeg.createRenderer(dsp)?.let { out.add(it) }
            }
        }
        val step = (st().seekStepSec * 1000).toLong().coerceAtLeast(1000)
        val p = ExoPlayer.Builder(ctx, rf)
            // Focus is managed once for the app (see below): two players must not steal it from each other.
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), false)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .setSeekBackIncrementMs(step)
            .setSeekForwardIncrementMs(step)
            .build()
        p.setSeekParameters(SeekParameters.EXACT)
        p.addListener(makeListener(p))
        noisyOn = null
        apply()
        return p
    }

    private fun makeRouted(p: ExoPlayer): Player = object : ForwardingPlayer(p) {
        private val extra = intArrayOf(
            Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_PREVIOUS,
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
        )

        override fun getAvailableCommands(): Player.Commands = super.getAvailableCommands().buildUpon().addAll(*extra).build()
        override fun isCommandAvailable(command: Int) = command in extra || super.isCommandAvailable(command)
        override fun hasNextMediaItem() = true
        override fun hasPreviousMediaItem() = true
        override fun seekToNext() = Engine.next()
        override fun seekToNextMediaItem() = Engine.next()
        override fun seekToPrevious() = Engine.prev()
        override fun seekToPreviousMediaItem() = Engine.prev()
        override fun play() = Engine.play()
        override fun pause() = Engine.pause()
        override fun setPlayWhenReady(playWhenReady: Boolean) = if (playWhenReady) Engine.play() else Engine.pause()
        override fun stop() = Engine.pause(true)
        override fun setVolume(volume: Float) = Engine.setVolume(volume.toDouble())
        override fun getVolume(): Float = S.settings.value.volume.toFloat()

        // Headset rewind / fast-forward follow the Seek step setting as soon as it changes.
        override fun getSeekBackIncrement() = (S.settings.value.seekStepSec * 1000).toLong()
        override fun getSeekForwardIncrement() = (S.settings.value.seekStepSec * 1000).toLong()
        override fun seekBack() = Engine.seekBy(-S.settings.value.seekStepSec)
        override fun seekForward() = Engine.seekBy(S.settings.value.seekStepSec)
    }

    /* ------------------------------------------------------------------ queue (rules live in QueueOps) */

    private fun edit(f: (QueueState) -> QueueState) {
        S.queue.update(f)
        ensurePlaylist()
    }

    /** Replace the queue with `ids` and start playing at `start`. */
    fun playIds(ids: List<String>, start: Int = 0, forceShuffle: Boolean? = null) {
        if (ids.isEmpty()) return
        val shuffle = forceShuffle ?: st().shuffle
        if (forceShuffle != null && st().shuffle != forceShuffle) S.set { it.copy(shuffle = forceShuffle) }
        S.queue.value = QueueOps.start(ids, start, shuffle)
        playAt(q().qpos)
    }

    /** Plays every song once in random order. Only the queue is shuffled; the list it came from is never reordered. */
    fun playShuffled(ids: List<String>) {
        if (ids.isNotEmpty()) playIds(ids, Random.nextInt(ids.size), true)
    }

    fun playFromList(ids: List<String>, id: String) {
        val pos = ids.indexOf(id)
        if (pos >= 0) playIds(ids, pos)
    }

    /** Play a track right now without disturbing the rest of the queue (open-with). */
    fun playNow(id: String) {
        val (next, at) = QueueOps.insertNow(q(), id)
        S.queue.value = next
        playAt(at)
    }

    fun playNext(id: String, sequential: Boolean = false) {
        if (q().qpos !in q().queue.indices) return playIds(listOf(id))
        edit { QueueOps.playNext(it, id, sequential) }
    }

    fun addToQueue(id: String) {
        if (q().qpos !in q().queue.indices) return playIds(listOf(id))
        edit { QueueOps.add(it, id) }
    }

    fun enqueueMany(ids: List<String>) = ids.forEach { addToQueue(it) }
    fun removeFromQueue(pos: Int) = edit { QueueOps.remove(it, pos) }
    fun moveInQueue(from: Int, to: Int) = edit { QueueOps.move(it, from, to) }
    fun clearUpNext() = edit { QueueOps.clearUpNext(it) }

    /** Shuffle on: shuffles the queue around the current song. Off: puts the queue back in its original order. */
    fun setShuffle(on: Boolean) {
        S.set { it.copy(shuffle = on) }
        edit { QueueOps.setShuffle(it, on) }
    }

    /* ------------------------------------------------------------------ playlist sync */

    private fun item(t: Track): MediaItem {
        val b = MediaItem.Builder().setMediaId(t.id).setUri(t.toUri())
        mimeForFile(t.fileName, t.mime)?.let { b.setMimeType(it) }
        b.setMediaMetadata(
            MediaMetadata.Builder().setTitle(t.title).setArtist(t.artist).setAlbumTitle(t.album)
                .setArtworkUri(t.cover?.let { Uri.fromFile(File(it)) }).build(),
        )
        return b.build()
    }

    /** Where playback goes after `from`. `manual` = the user pressed Next (ignores repeat-one / repeat-N). */
    private fun nextPos(from: Int, manual: Boolean): Int =
        QueueOps.nextPos(q().queue.size, from, st().repeat, st().repeat == "one" || S.player.value.repeatRemaining > 0, manual)

    private fun sleepStopsAfterCurrent(): Boolean {
        val ps = S.player.value
        return ps.sleepEndOfTrack || ps.sleepTracksRemaining == 1
    }

    /** The track an overlap would fade into (none when repeating, or when the sleep timer stops after this song). */
    private fun upcoming(): Track? {
        if (sleepStopsAfterCurrent()) return null
        val np = nextPos(q().qpos, false)
        if (np < 0 || np == q().qpos) return null
        return S.track(q().queue.getOrNull(np))
    }

    /**
     * Keeps the main player's playlist on Sonora's queue. Prefers non-disruptive tail edits while
     * the same song plays; rebuilds (preserving the song and position) when the order changed.
     * Deferred while an overlap is in flight; the handoff applies it.
     */
    fun ensurePlaylist(startMs: Long? = null) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { ensurePlaylist(startMs) }
            return
        }
        if (overlap != null) {
            playlistDirty = true
            return
        }
        val m = mainOrNull() ?: return
        val ids = q().queue
        if (ids.isEmpty()) {
            if (m.mediaItemCount > 0) m.clearMediaItems()
            playlistIds = emptyList()
            return
        }
        val cur = q().qpos.coerceIn(ids.indices)
        val curId = ids[cur]
        // Gapless off (or stopping after this song): hold only the current song so it ends cleanly.
        val want = if (st().gapless && !sleepStopsAfterCurrent()) ids else listOf(curId)
        if (want == playlistIds && m.mediaItemCount == want.size && m.currentMediaItem?.mediaId == curId) {
            syncRepeatMode()
            return
        }
        if (m.mediaItemCount > 0 && m.currentMediaItem?.mediaId == curId && playlistIds.isNotEmpty()) {
            val haveIdx = m.currentMediaItemIndex
            val haveTail = m.mediaItemCount - haveIdx - 1
            if (haveTail > 0) m.removeMediaItems(haveIdx + 1, m.mediaItemCount)
            val prefixOk = playlistIds.take(playlistIds.indexOf(curId) + 1) == want.take(want.indexOf(curId) + 1)
            if (prefixOk) {
                want.drop(want.indexOf(curId) + 1).forEach { id -> S.track(id)?.let { m.addMediaItem(item(it)) } }
                playlistIds = want
                syncRepeatMode()
                return
            }
        }
        val items = want.mapNotNull { id -> S.track(id)?.let { item(it) } }
        if (items.isEmpty()) {
            m.clearMediaItems()
            playlistIds = emptyList()
            return
        }
        val startIndex = max(0, want.indexOf(curId))
        val posMs = startMs ?: if (m.mediaItemCount > 0 && m.currentMediaItem?.mediaId == curId) m.currentPosition else 0L
        m.setMediaItems(items, startIndex, posMs)
        m.prepare()
        playlistIds = want
        syncRepeatMode()
    }

    /** Backwards-compatible alias (imports and sheets call this after changing the queue). */
    fun requeue() = ensurePlaylist()

    private fun syncRepeatMode() {
        val m = mainOrNull() ?: return
        if (overlap != null) {
            // The outgoing song must end; the incoming side never loops.
            m.repeatMode = Player.REPEAT_MODE_OFF
            sideOrNull()?.repeatMode = Player.REPEAT_MODE_OFF
            return
        }
        val multi = m.mediaItemCount > 1
        m.repeatMode = when {
            st().repeat == "one" || S.player.value.repeatRemaining > 0 -> Player.REPEAT_MODE_ONE
            st().repeat == "all" && multi -> Player.REPEAT_MODE_ALL
            else -> Player.REPEAT_MODE_OFF
        }
        sideOrNull()?.repeatMode = Player.REPEAT_MODE_OFF
    }

    /* ------------------------------------------------------------------ transport */

    /** Restores the last song at launch: playlist loaded and positioned, buffered, but paused. */
    private fun prime() {
        val s = q()
        val t = S.track(s.queue.getOrNull(s.qpos)) ?: return
        if (playlistIds.isNotEmpty()) return
        main() // create now so the first Play is instant
        val start = S.startPositionFor(t, true)
        S.clock.value = start
        S.player.update { it.copy(trackId = t.id, duration = t.duration, abA = null, abB = null, repeatRemaining = max(0, st().repeatCountTarget - 1)) }
        ensurePlaylist((start * 1000).toLong())
        mainOrNull()?.pause()
        pendingStart = null
        apply()
    }

    /** A missing file or an unsupported format fails before the player is even involved. */
    private fun preflight(t: Track): PlaybackFailure? {
        if (supportOf(t.fileName) is Support.Unsupported) return PlaybackFailure.UNSUPPORTED
        if (t.storage != "tree" && !File(t.uri).isFile) return PlaybackFailure.MISSING
        return null
    }

    fun playAt(pos: Int, auto: Boolean = false, play: Boolean = true) {
        if (overlap != null) dropIncomingForJump()
        val id = q().queue.getOrNull(pos) ?: return
        val t = S.track(id) ?: return
        preflight(t)?.let { kind -> failFast(t, kind, pos); return }
        S.queue.update { it.copy(qpos = pos, insertCursor = 0) }
        val start = if (auto) 0.0 else pendingStart?.takeIf { S.player.value.trackId == t.id } ?: S.startPositionFor(t, false)
        pendingStart = null
        autoStarted = auto
        main()
        started(t)
        ensurePlaylist((start * 1000).toLong())
        val m = main()
        m.seekTo(max(0, playlistIds.indexOf(t.id)), (start * 1000).toLong())
        S.clock.value = start
        if (play) {
            if (!requestFocus()) {
                S.toast("Another app is using audio — try again")
                m.pause()
                return
            }
            pausedByNoisy = false
            fadeIn()
            m.play()
        } else m.pause()
    }

    /** A pre-flight failure: named message, advance to the next song, stop after 3 in a row. Never silent. */
    private fun failFast(t: Track, kind: PlaybackFailure, pos: Int) {
        failures++
        val label = (supportOf(t.fileName) as? Support.Unsupported)?.label
        S.toast(if (label != null) unsupportedMessage(t.title, label) else failureMessage(kind, t.title, extOf(t.fileName).uppercase()))
        Diag.log("play-blocked", "$kind ${t.fileName}")
        if (failures >= 3) {
            failures = 0
            mainOrNull()?.pause()
            S.toast("Several songs in a row failed to play, so playback stopped")
            return
        }
        val np = nextPos(pos, true)
        if (np >= 0 && np != pos) playAt(np, auto = true) else mainOrNull()?.pause()
    }

    /** Bookkeeping when a track becomes current. */
    private fun started(t: Track) {
        S.player.update {
            it.copy(
                trackId = t.id, duration = t.duration, abA = null, abB = null,
                repeatRemaining = max(0, st().repeatCountTarget - 1),
            )
        }
        counted = null
        loadSeq++
        if (retried.size > 100) retried.clear()
        apply()
        syncRepeatMode()
        analyze(t)
        Diag.log("track", t.fileName)
    }

    /**
     * One background pass over a song the first time it plays: its real waveform for the seek bar (cached on disk),
     * and, when the file has no ReplayGain tag, its loudness (the gain then ramps in smoothly).
     */
    private fun analyze(t: Track) {
        analyzeJob?.cancel()
        analyzeJob = S.scope.launch {
            val file = File(S.waveDir, t.id)
            val cached = withContext(Dispatchers.IO) { runCatching { file.takeIf { it.length() == WAVE_BARS.toLong() }?.readBytes() }.getOrNull() }
            if (cached != null) S.wave.value = t.id to FloatArray(WAVE_BARS) { (cached[it].toInt() and 0xff) / 255f }
            val s = st()
            val needGain = s.rgMode != "off" && s.rgAnalyze && t.rgTrack == null && !t.analyzed
            val needWave = cached == null && s.waveformSeekbar && t.duration > 1 && t.duration <= 1200
            if (!needGain && !needWave) return@launch
            delay(1500) // let playback settle first; skipped songs are never analysed
            val job = coroutineContext[Job]
            val r = withContext(Dispatchers.Default) {
                Analyzer.analyze(S.app, t, 1024, 0, if (needWave) 1200.0 else 360.0, if (needWave) WAVE_BARS else 0) { job?.isActive == false }
            } ?: return@launch
            if (needGain) {
                S.patchTrack(t.id) { it.copy(rgTrack = r.gainDb ?: it.rgTrack, analyzed = true) }
                apply()
            }
            val peaks = r.peaks ?: return@launch
            S.wave.value = t.id to peaks
            withContext(Dispatchers.IO) { runCatching { file.writeBytes(ByteArray(WAVE_BARS) { (peaks[it] * 255).toInt().coerceIn(0, 255).toByte() }) } }
        }
    }

    const val WAVE_BARS = 72

    private fun makeListener(me: ExoPlayer) = object : Player.Listener {
        private fun isMain() = me === mainOrNull()

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (!isMain()) return
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) onAutoAdvance()
            else if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) onRepeatLoop()
        }

        override fun onPlaybackStateChanged(state: Int) {
            if (!isMain()) return
            if (state == Player.STATE_ENDED) onEnded()
        }

        override fun onEvents(p: Player, events: Player.Events) {
            if (isMain()) sync()
        }

        /** A seek (or any jump) updates the clock at once, so the time bar never freezes behind the song. */
        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
            if (isMain() && me.mediaItemCount > 0) S.clock.value = me.currentPosition / 1000.0
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (!isMain() || playWhenReady) return
            if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY) {
                pausedByNoisy = true
                S.toast("Audio output disconnected — paused")
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            if (isMain()) handleError(me, error) else sideError(error)
        }

        override fun onTracksChanged(tracks: Tracks) = enrich(me, tracks)
    }

    private fun sync() {
        val p = (if (overlap != null) sideOrNull() else mainOrNull()) ?: return
        if (p.mediaItemCount == 0) return
        val playing = p.playWhenReady && p.playbackState != Player.STATE_IDLE && p.playbackState != Player.STATE_ENDED && !pausing
        val d = p.duration.takeIf { it != C.TIME_UNSET && it > 0 }?.let { it / 1000.0 }
        S.player.update { if (it.playing == playing && (d == null || abs(it.duration - d) < 0.01)) it else it.copy(playing = playing, duration = d ?: it.duration) }
        if (d != null) {
            val id = p.currentMediaItem?.mediaId
            val t = S.track(id)
            if (t != null && abs(t.duration - d) > 0.5) S.patchTrack(t.id) { it.copy(duration = d) }
        }
        if (isPlayingAny()) startTicker() else {
            tick()
            saveResume()
        }
    }

    /** The player moved to the next playlist item with no gap. The queue follows the player. */
    private fun onAutoAdvance() {
        val m = main()
        val id = m.currentMediaItem?.mediaId
        val pos = q().queue.indexOf(id)
        val t = S.track(id)
        if (pos < 0 || t == null) {
            // The queue changed under the playlist: re-sync, then continue from the queue.
            Diag.log("stale-transition", id ?: "null")
            ensurePlaylist()
            val np = nextPos(q().qpos, true)
            if (np >= 0) playAt(np, auto = true) else m.pause()
            return
        }
        S.player.update { if (it.sleepTracksRemaining > 1) it.copy(sleepTracksRemaining = it.sleepTracksRemaining - 1) else it }
        S.queue.update { it.copy(qpos = pos, insertCursor = 0) }
        S.clock.value = 0.0
        started(t)
        ensurePlaylist() // trims the tail when the sleep timer now says stop-after-current
    }

    /** A repeat-one / repeat-N loop point (native, no gap). */
    private fun onRepeatLoop() {
        val ps = S.player.value
        if (ps.sleepEndOfTrack || ps.sleepTracksRemaining == 1) {
            S.player.update { it.copy(sleepEndOfTrack = false, sleepTracksRemaining = 0) }
            S.toast("Sleep timer: playback stopped")
            pause(true)
            return
        }
        S.player.update {
            it.copy(
                sleepTracksRemaining = if (it.sleepTracksRemaining > 1) it.sleepTracksRemaining - 1 else it.sleepTracksRemaining,
                repeatRemaining = if (st().repeat == "one") it.repeatRemaining else max(0, it.repeatRemaining - 1),
            )
        }
        loadSeq++
        counted = null
        S.clock.value = 0.0
        syncRepeatMode() // exhausted repeat-N falls back to off/all, so the next end advances
    }

    /**
     * The playlist truly ended: an overlap just finished its outgoing song, the sleep timer stops
     * here, gapless is off and the next song starts with a small gap, or the queue is over.
     */
    private fun onEnded() {
        if (overlap != null) {
            finalizeOverlap()
            return
        }
        val ps = S.player.value
        if (ps.sleepEndOfTrack || ps.sleepTracksRemaining == 1) {
            S.player.update { it.copy(sleepEndOfTrack = false, sleepTracksRemaining = 0) }
            S.toast("Sleep timer: playback stopped")
            val np = nextPos(q().qpos, false)
            if (np >= 0) playAt(np, auto = true, play = false) else mainOrNull()?.pause()
            return
        }
        val np = nextPos(q().qpos, false)
        if (np >= 0) playAt(np, auto = true)
        else if (q().queue.isNotEmpty()) playAt(0, auto = true, play = false)
    }

    /**
     * Classified error recovery: retry once, in place (same song, just before the failure point),
     * then a specific message naming the song and format, and a stop after 3 failures in a row.
     * Nothing ever skips silently.
     */
    private fun handleError(me: ExoPlayer, e: PlaybackException) {
        val id = me.currentMediaItem?.mediaId
        val t = S.track(id)
        Diag.log("player-error", "${e.errorCodeName} (${e.errorCode}) ${t?.fileName}")
        var kind = classifyFailure(e.errorCode)
        if (t != null) {
            if (supportOf(t.fileName) is Support.Unsupported) kind = PlaybackFailure.UNSUPPORTED
            else if (t.storage != "tree" && !File(t.uri).isFile) kind = PlaybackFailure.MISSING
        }
        if (t != null && failureRetryable(kind) && retried.add("$id#$loadSeq")) {
            me.seekTo(me.currentMediaItemIndex, max(0L, me.currentPosition - 400))
            me.prepare()
            me.play()
            return
        }
        failures++
        val label = t?.let { (supportOf(it.fileName) as? Support.Unsupported)?.label }
        S.toast(if (label != null && t != null) unsupportedMessage(t.title, label) else failureMessage(kind, t?.title.orEmpty(), extOf(t?.fileName.orEmpty()).uppercase()))
        if (failures >= 3) {
            failures = 0
            me.pause()
            S.toast("Several songs in a row failed to play, so playback stopped")
            return
        }
        val np = nextPos(q().qpos, true)
        if (np >= 0 && np != q().qpos) playAt(np, auto = true) else me.pause()
    }

    /** The incoming player failed mid-overlap: drop it and let the outgoing song finish. No toast here — the main player reaches the failing file next and says exactly what's wrong. */
    private fun sideError(e: PlaybackException) {
        val ov = overlap ?: return
        val sd = sideOrNull() ?: return
        Diag.log("xfade-side-error", "${e.errorCodeName} ${ov.inId}")
        sd.stop()
        sd.clearMediaItems()
        overlap = null
        val outPos = q().queue.indexOf(ov.outId)
        S.queue.update { it.copy(qpos = max(0, outPos)) }
        val out = S.track(ov.outId)
        S.player.update { it.copy(trackId = ov.outId, duration = out?.duration ?: it.duration) }
        ensurePlaylist()
        syncRepeatMode()
        applyVolumes()
    }

    private fun enrich(me: ExoPlayer, tracks: Tracks) {
        val id = me.currentMediaItem?.mediaId ?: return
        val t = S.track(id) ?: return
        val ex = Tags.Extras()
        for (g in tracks.groups) for (i in 0 until g.length) g.getTrackFormat(i).metadata?.let { Tags.read(it, ex) }
        if ((ex.rgTrack != null && t.rgTrack == null) || (ex.rgAlbum != null && t.rgAlbum == null) || (ex.lyrics != null && t.embeddedLyrics == null)) {
            S.patchTrack(id) { it.copy(rgTrack = it.rgTrack ?: ex.rgTrack, rgAlbum = it.rgAlbum ?: ex.rgAlbum, embeddedLyrics = it.embeddedLyrics ?: ex.lyrics) }
            apply()
        }
    }

    /* ------------------------------------------------------------------ true overlap crossfade */

    /**
     * Starts a real overlap: the next song begins on the standby player while the current one is
     * still playing, and the two are heard together with equal-power ramps. The new song becomes
     * current the moment it starts (position, display, queue); the outgoing one finishes underneath.
     */
    private fun maybeStartOverlap(pos: Double, dur: Double) {
        if (overlap != null) return
        val cf = st().crossfade
        if (cf <= 0) return
        val m = mainOrNull() ?: return
        if (!m.isPlaying) return
        val ps = S.player.value
        if (ps.abA != null && ps.abB != null) return // an A-B loop owns the ending
        if (st().repeat == "one" || ps.repeatRemaining > 0) return
        if (sleepStopsAfterCurrent()) return
        if (dur <= 0) return
        val left = dur - pos
        if (left > cf || left < 0.5) return
        if (dur < cf + 8) return // too short to overlap: native gapless handles it
        val next = upcoming() ?: return
        if (next.duration < cf + 8) return
        if (preflight(next) != null) return // let the normal path report it
        startOverlap(next, cf)
    }

    private fun startOverlap(next: Track, cf: Double) {
        val m = main()
        val sd = side()
        val curId = S.player.value.trackId ?: return
        val np = q().queue.indexOf(next.id)
        if (np < 0) return
        // The outgoing song keeps only itself, so it ends cleanly instead of auto-advancing.
        if (m.mediaItemCount > m.currentMediaItemIndex + 1) m.removeMediaItems(m.currentMediaItemIndex + 1, m.mediaItemCount)
        m.repeatMode = Player.REPEAT_MODE_OFF
        sd.repeatMode = Player.REPEAT_MODE_OFF
        playlistIds = listOf(curId)
        sd.setMediaItems(listOf(item(next)), 0, 0)
        sd.prepare()
        sd.play()
        overlap = Overlap(curId, next.id, cf, System.currentTimeMillis())
        S.player.update { if (it.sleepTracksRemaining > 1) it.copy(sleepTracksRemaining = it.sleepTracksRemaining - 1) else it }
        S.queue.update { it.copy(qpos = np, insertCursor = 0) }
        S.clock.value = 0.0
        started(next)
        applyVolumes()
        Diag.log("xfade", "overlap ${next.fileName} (${cf}s)")
    }

    /** The outgoing song ended under the overlap: the incoming player becomes the main one. */
    private fun finalizeOverlap() {
        val ov = overlap ?: return
        overlap = null
        mainIsA = !mainIsA
        playlistIds = listOf(ov.inId)
        if (playlistDirty) {
            playlistDirty = false
        }
        ensurePlaylist()
        syncRepeatMode()
        applyVolumes()
        sessionSetter?.invoke(mainRouted())
        Diag.log("xfade", "promoted ${ov.inId.take(8)}")
        if (main().playbackState == Player.STATE_ENDED) onEnded() // paranoia: incoming ended mid-overlap
    }

    /** Jumping elsewhere mid-overlap: the incoming song is discarded, the outgoing one is kept for the jump. */
    private fun dropIncomingForJump() {
        overlap ?: return
        overlap = null
        sideOrNull()?.let {
            it.stop()
            it.clearMediaItems()
        }
        playlistDirty = false
        applyVolumes()
    }

    /** Manual Next/Previous mid-overlap: the outgoing song stops now and the incoming one carries on. */
    private fun promoteForManualNav() {
        val ov = overlap ?: return
        mainOrNull()?.let {
            it.pause()
            it.stop()
            it.clearMediaItems()
        }
        mainIsA = !mainIsA
        overlap = null
        playlistIds = listOf(ov.inId)
        if (playlistDirty) playlistDirty = false
        ensurePlaylist()
        syncRepeatMode()
        applyVolumes()
        sessionSetter?.invoke(mainRouted())
    }

    /* ------------------------------------------------------------------ transport */

    fun play() {
        if (!S.loaded.value) {
            // A headset or lock-screen Play can arrive before the saved library has loaded.
            S.scope.launch {
                S.loaded.first { it }
                play()
            }
            return
        }
        val m = main()
        ensurePlaylist()
        if (m.mediaItemCount == 0) {
            val s = q()
            if (s.queue.isNotEmpty()) playAt(max(0, s.qpos))
            return
        }
        if (!requestFocus()) {
            S.toast("Another app is using audio — try again")
            return
        }
        if (m.playbackState == Player.STATE_IDLE) m.prepare()
        if (m.playbackState == Player.STATE_ENDED) m.seekTo(m.currentMediaItemIndex, 0)
        pausedByNoisy = false
        fadeIn()
        m.play()
        val ov = overlap
        if (ov != null) {
            if (pauseAtMs > 0) ov.t0ms += System.currentTimeMillis() - pauseAtMs
            pauseAtMs = 0
            side().play()
        }
    }

    private fun fadeIn() {
        fadeJob?.cancel()
        pausing = false
        val ms = st().playPauseFadeMs
        if (ms <= 0 || autoStarted && isPlayingAny()) {
            fade = 1f
            applyVolumes()
            return
        }
        fade = 0f
        applyVolumes()
        fadeJob = S.scope.launch {
            val t0 = System.nanoTime()
            while (fade < 1f) {
                delay(16)
                fade = min(1f, ((System.nanoTime() - t0) / 1e6 / ms).toFloat())
                applyVolumes()
            }
        }
    }

    /** Pauses with a short volume ramp (Settings > Smooth play / pause fade) so there is no click. */
    fun pause(immediate: Boolean = false) {
        if (overlap != null && pauseAtMs == 0L) pauseAtMs = System.currentTimeMillis()
        val ms = st().playPauseFadeMs
        fadeJob?.cancel()
        if (immediate || ms <= 0 || !isPlayingAny()) {
            pausing = false
            forEachPlayer { it.pause() }
            fade = 1f
            applyVolumes()
            abandonFocus()
            return
        }
        pausing = true
        sync()
        fadeJob = S.scope.launch {
            val t0 = System.nanoTime()
            val from = fade
            while (fade > 0f) {
                delay(16)
                fade = max(0f, from * (1 - ((System.nanoTime() - t0) / 1e6 / ms).toFloat()))
                applyVolumes()
            }
            forEachPlayer { it.pause() }
            pausing = false
            fade = 1f
            applyVolumes()
            sync()
            abandonFocus()
        }
    }

    fun toggle() = if (S.player.value.playing) pause() else play()

    private fun debounced(): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastSkipAt < 350) return true
        lastSkipAt = now
        return false
    }

    fun next() {
        if (debounced()) return
        promoteForManualNav()
        val np = nextPos(q().qpos, true)
        if (np >= 0) playAt(np)
    }

    fun prev() {
        if (debounced()) return
        if (time > 3) return seek(0.0)
        promoteForManualNav()
        val s = q()
        if (s.queue.isEmpty()) return
        playAt(QueueOps.prevPos(s.queue.size, s.qpos, st().repeat))
    }

    fun seek(t: Double) {
        val d = S.player.value.duration
        val to = if (d > 0) t.coerceIn(0.0, max(0.0, d - 0.2)) else max(0.0, t)
        S.clock.value = to
        val p = (if (overlap != null) sideOrNull() else mainOrNull())?.takeIf { it.mediaItemCount > 0 }
        if (p == null) pendingStart = to else p.seekTo((to * 1000).toLong())
    }

    fun seekBy(delta: Double) = seek(time + delta)

    /** Hold the cover in Now Playing for 1.5x speed; release to restore. */
    fun holdSpeed(on: Boolean) {
        if (holdSpeed == on) return
        holdSpeed = on
        apply()
    }

    fun setVolume(v: Double) {
        S.set { it.copy(volume = v.coerceIn(0.0, 1.0), muted = false) }
        if (v <= 0.0 && st().pauseOnVolume0 && S.player.value.playing) {
            pause()
            S.toast("Volume at 0 — paused")
        }
    }

    fun setSleep(minutes: Int = 0, endOfTrack: Boolean = false, tracks: Int = 0) {
        sleepG = 1f
        S.player.update { it.copy(sleepAt = if (minutes > 0) System.currentTimeMillis() + minutes * 60000L else null, sleepEndOfTrack = endOfTrack, sleepTracksRemaining = tracks) }
        applyVolumes()
        ensurePlaylist()
        syncRepeatMode()
    }

    private fun applyVolumes() {
        val s = st()
        val base = ((if (s.muted) 0.0 else s.volume) * fade * sleepG * duck).toFloat().coerceIn(0f, 1f)
        val m = mainOrNull()
        val sd = sideOrNull()
        val ov = overlap
        if (ov == null) {
            m?.volume = base
            if (sd != null && sd.playbackState != Player.STATE_IDLE) sd.volume = base
            return
        }
        val (og, ig) = crossfadeGains((System.currentTimeMillis() - ov.t0ms) / 1000.0, ov.totalSec)
        m?.volume = base * og // main is the outgoing song during an overlap
        sd?.volume = base * ig
    }

    /* ------------------------------------------------------------------ audio focus (managed once for the app) */

    private var focusGranted = false
    private var focusRequest26: android.media.AudioFocusRequest? = null
    private fun audioManager() = S.app.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                duck = 1f
                if (isPlayingAny()) {
                    pause()
                    S.toast("Another app is using audio — paused")
                } else applyVolumes()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                duck = 0.2f
                applyVolumes()
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                duck = 1f
                applyVolumes()
            }
        }
    }

    private fun requestFocus(): Boolean {
        if (focusGranted) return true
        val am = audioManager()
        val r = if (Build.VERSION.SDK_INT >= 26) {
            val req = android.media.AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC).build(),
                )
                .setOnAudioFocusChangeListener(focusListener).build()
            focusRequest26 = req
            am.requestAudioFocus(req)
        } else {
            @Suppress("DEPRECATION") am.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
        }
        focusGranted = r == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        return focusGranted
    }

    private fun abandonFocus() {
        if (!focusGranted) return
        focusGranted = false
        val am = audioManager()
        if (Build.VERSION.SDK_INT >= 26) focusRequest26?.let { am.abandonAudioFocusRequest(it) }
        else @Suppress("DEPRECATION") am.abandonAudioFocus(focusListener)
        focusRequest26 = null
    }

    /** ReplayGain to apply for a track, dB (0 when off or unknown). */
    private fun rgFor(t: Track?): Double {
        val s = st()
        if (t == null || s.rgMode == "off") return 0.0
        val v = (if (s.rgMode == "album") t.rgAlbum ?: t.rgTrack else t.rgTrack) ?: return 0.0
        return (v + s.rgPreamp).coerceIn(-20.0, 12.0)
    }

    /** Pushes settings (plus the current track's pinned tuning, if any) to both DSP chains and players. */
    fun apply() {
        val s = st()
        val id = S.player.value.trackId
        val cfg = id?.let { S.user.value.trackConfigs[it] }
        val speed = if (holdSpeed) 1.5 else (cfg?.speed ?: s.speed).coerceIn(0.25, 4.0)
        val keep = if (holdSpeed) true else cfg?.keepPitch ?: s.keepPitch
        val eq = cfg?.midsDb?.let { applyMidsToEq(s.eq, it) } ?: s.eq
        val base = DspParams(
            s.eqOn, eq, cfg?.bassDb ?: s.bassDb, cfg?.trebleDb ?: s.trebleDb, s.preampDb, s.autoPreamp, cfg?.boostDb ?: s.boostDb, s.limiter,
            s.stereoMode, s.width, s.crossfeed, s.orbit, s.orbitSpeed, s.room, 0.0,
        )
        // During an overlap each song keeps its own ReplayGain.
        val curId = overlap?.inId ?: id
        mainOrNull()?.let { dspOf(it).params = base.copy(rgDb = rgFor(S.track(if (overlap != null) overlap!!.outId else curId))) }
        sideOrNull()?.let { dspOf(it).params = base.copy(rgDb = rgFor(S.track(overlap?.inId ?: curId))) }
        val rg = rgFor(S.track(id))
        if (S.player.value.rgApplied != rg) S.player.update { it.copy(rgApplied = rg) }
        val pp = PlaybackParameters(speed.toFloat(), if (keep) 1f else speed.toFloat())
        forEachPlayer { if (it.playbackParameters != pp) it.playbackParameters = pp }
        if (noisyOn != s.pauseOnDisconnect) {
            noisyOn = s.pauseOnDisconnect
            forEachPlayer { it.setHandleAudioBecomingNoisy(s.pauseOnDisconnect) }
        }
        applyVolumes()
        val modes = "${s.gapless}|${s.repeat}|${s.repeatCountTarget}"
        if (modes != lastModes) {
            lastModes = modes
            ensurePlaylist()
            syncRepeatMode()
        }
    }

    /* ------------------------------------------------------------------ clock: A-B loop, sleep, overlap, play counts, resume */

    private fun startTicker() {
        if (ticker?.isActive == true) return
        ticker = S.scope.launch {
            while (isPlayingAny()) {
                tick()
                delay(if (uiVisible) 50 else 250)
            }
        }
    }

    private fun tick() {
        val p = (if (overlap != null) sideOrNull() else mainOrNull()) ?: return
        if (p.mediaItemCount == 0) return
        val pos = p.currentPosition / 1000.0
        val ps = S.player.value
        val dur = ps.duration
        S.clock.value = pos
        val a = ps.abA
        val b = ps.abB
        if (a != null && b != null && pos >= b) p.seekTo((a * 1000).toLong())
        val now = System.currentTimeMillis()
        var g = 1f
        ps.sleepAt?.let { sleepAt ->
            val left = sleepAt - now
            if (left <= 0) {
                S.player.update { it.copy(sleepAt = null) }
                pause(true)
                S.toast("Sleep timer: playback stopped")
            } else if (left < 20000) g = left / 20000f // 20 s fade-out
        }
        val sleepChanged = g != sleepG
        sleepG = g
        if (overlap != null || sleepChanged) applyVolumes() // overlap ramps move every tick
        if (pos > 2 && p.isPlaying) failures = 0
        // A play counts once the song has been heard for 30 s (or 80% of a shorter one).
        val id = ps.trackId
        if (pos < 2) counted = null
        if (id != null && counted != id && p.isPlaying && pos >= min(30.0, if (dur > 0) dur * 0.8 else 30.0)) {
            counted = id
            S.patchTrack(id) { it.copy(playCount = it.playCount + 1, lastPlayedAt = now) }
        }
        if (now - lastResumeSave >= 5000 && p.isPlaying) saveResume()
        val red = dsp.reductionDb.toDouble()
        val clip = now - dsp.clipAt < 1200
        val m = S.meter.value
        if (abs(m.limiting - red) > 0.1 || m.clipping != clip) S.meter.value = Meter(red, clip)
        if (overlap == null) maybeStartOverlap(pos, dur)
    }

    fun saveResume() {
        val id = S.player.value.trackId ?: return
        lastResumeSave = System.currentTimeMillis()
        S.saveResume(id, time, S.player.value.duration)
    }

    /** Called when the PlaybackService is destroyed. Players are rebuilt on next use. */
    fun release() {
        pendingStart = time
        saveResume()
        ticker?.cancel()
        abandonFocus()
        sessionSetter = null
        overlap = null
        playlistIds = emptyList()
        playlistDirty = false
        forEachPlayer { it.release() }
        playerA = null
        playerB = null
        routA = null
        routB = null
        S.player.update { it.copy(playing = false) }
    }

    /* ------------------------------------------------------------------ visualiser tap */

    fun setTapOn(on: Boolean) {
        dspA.tapOn = on
        dspB.tapOn = on
    }

    private val mixL = FloatArray(4096)
    private val mixR = FloatArray(4096)

    /** Mixed output tap: every DSP that wrote recently (both during an overlap). */
    fun mixedSnapshot(n: Int, left: FloatArray, right: FloatArray) {
        val m = min(n, 4096)
        val now = System.currentTimeMillis()
        var any = false
        for (d in arrayOf(dspA, dspB)) {
            if (now - d.lastWriteMs > 600) continue
            if (!any) {
                d.snapshot(m, left, right)
                any = true
            } else {
                d.snapshot(m, mixL, mixR)
                for (i in 0 until m) {
                    left[i] = (left[i] + mixL[i]).coerceIn(-1f, 1f)
                    right[i] = (right[i] + mixR[i]).coerceIn(-1f, 1f)
                }
            }
        }
        if (!any) {
            left.fill(0f, 0, m)
            right.fill(0f, 0, m)
        }
    }

    /* ------------------------------------------------------------------ outputs & sound profiles */

    private val HEADSET_TYPES = setOf(
        AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        22 /* USB headset */, AudioDeviceInfo.TYPE_USB_DEVICE, 26 /* BLE headset */, 23 /* hearing aid */,
    )

    fun namesHidden(): Boolean = Build.VERSION.SDK_INT >= 31 && S.app.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED

    fun devicesChanged() {
        val am = S.app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val outs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).filter { it.type in HEADSET_TYPES }.map {
            val wired = it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET || it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES
            val name = if (wired) "Wired headphones" else it.productName?.toString()?.trim().orEmpty().ifEmpty { "Audio output device" }
            OutDev(name, name, name, true)
        }.distinctBy { it.key }
        val keys = outs.map { it.key }.toSet()
        val prev = knownOuts
        knownOuts = keys
        S.outs.value = outs
        S.player.update { it.copy(outputLabel = outs.firstOrNull()?.label.orEmpty()) }
        if (prev == null) return
        val s = st()
        for (o in outs.filter { it.key !in prev }) {
            val prof = s.deviceMap[o.key]?.let { pid -> s.profiles.firstOrNull { it.id == pid } }
            if (prof != null) {
                applyProfile(prof)
                S.toast("${o.label} — profile \"${prof.name}\" applied")
            } else S.toast("${o.label} connected")
            if (pausedByNoisy && s.autoResume) {
                pausedByNoisy = false
                play()
            }
        }
    }

    fun applyProfile(p: SoundProfile) = S.set {
        it.copy(eq = p.eq, preampDb = p.preampDb, bassDb = p.bassDb, midsDb = p.midsDb, trebleDb = p.trebleDb, width = p.width, crossfeed = p.crossfeed, eqOn = true)
    }

    /** Saves the current EQ + stage as a profile, optionally bound to an output so it applies when that headset connects. */
    fun saveProfile(name: String, bindKey: String?) {
        val s = st()
        val p = SoundProfile(uid(), name, s.eq, s.preampDb, s.bassDb, s.midsDb, s.trebleDb, s.width, s.crossfeed)
        S.set { it.copy(profiles = it.profiles + p, deviceMap = if (bindKey != null) it.deviceMap + (bindKey to p.id) else it.deviceMap) }
    }
}
