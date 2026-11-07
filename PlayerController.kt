package app.sonora.player.audio

import android.content.ComponentName
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import app.sonora.player.Graph
import app.sonora.player.data.Store
import app.sonora.player.data.TrackRecord
import app.sonora.player.data.effectiveSettings
import app.sonora.player.data.fmtSec
import app.sonora.player.library.UserDataLogic
import kotlin.math.abs
import kotlin.math.max
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class PlayerUiState(
    val connected: Boolean = false,
    val isPlaying: Boolean = false,
    val playbackState: Int = Player.STATE_IDLE,
    val mediaId: String = "",
    val title: String = "",
    val artist: String = "",
    val index: Int = -1,
    val count: Int = 0,
    val durationMs: Long = 0L,
    val speed: Float = 1f,
    val error: String? = null,
    val abA: Double? = null,
    val abB: Double? = null,
    val sleepAt: Long? = null,
    val sleepEndOfTrack: Boolean = false,
    val sleepTracksRemaining: Int = 0,
    val repeatRemaining: Int = 0,
    val limitingDb: Double = 0.0,
    val clipping: Boolean = false,
    val rgApplied: Double = 0.0,
    val holdSpeedActive: Boolean = false,
)

/**
 * UI-side handle to the playback service (Media3 MediaController). Everything the UI needs is a StateFlow.
 * Commands issued before the connection is ready are queued, never dropped.
 * Seeking rule: the UI seeks ONCE on release (see SeekBar). Never call seekTo in a loop while dragging.
 */
class PlayerController(private val context: Context) {

    private var controller: MediaController? = null
    private var connecting = false
    private val pending = ArrayList<(MediaController) -> Unit>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var ticker: Job? = null

    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    private val _position = MutableStateFlow(0L)
    val positionMs: StateFlow<Long> = _position.asStateFlow()

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()

    val queueState = Store(QueueState())

    private fun addLog(line: String) {
        _log.update { (it + line).takeLast(12) }
    }

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            refresh(player)
        }

        override fun onPlayerError(error: PlaybackException) {
            val msg = "${error.errorCodeName}: ${error.message}"
            addLog("ERROR $msg")
            _state.update { it.copy(error = msg) }
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            addLog("jump reason=$reason ${oldPosition.positionMs}->${newPosition.positionMs}")
        }
    }

    fun connect() {
        if (controller != null || connecting) return
        connecting = true
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener(
            {
                try {
                    val c = future.get()
                    controller = c
                    c.addListener(listener)
                    refresh(c)
                    pending.forEach { it(c) }
                    pending.clear()
                    startTicker()
                } catch (e: Exception) {
                    addLog("connect failed: ${e.message}")
                }
                connecting = false
            },
            ContextCompat.getMainExecutor(context),
        )
    }

    private fun withController(block: (MediaController) -> Unit) {
        val c = controller
        if (c != null) block(c) else { pending.add(block); connect() }
    }

    private fun refresh(p: Player) {
        val d = p.duration
        val idx = p.currentMediaItemIndex
        val q = queueState.value
        if (idx >= 0 && idx < q.queue.size && idx != q.qpos) {
            queueState.set { it.copy(qpos = idx, insertCursor = 0) }
        }
        _state.update {
            it.copy(
                connected = true,
                isPlaying = p.isPlaying,
                playbackState = p.playbackState,
                mediaId = p.currentMediaItem?.mediaId ?: "",
                title = p.mediaMetadata.title?.toString() ?: "",
                artist = p.mediaMetadata.artist?.toString() ?: "",
                index = idx,
                count = p.mediaItemCount,
                durationMs = if (d == C.TIME_UNSET || d < 0) 0L else d,
                speed = p.playbackParameters.speed,
                error = if (p.playerError == null) null else it.error,
            )
        }
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive) {
                val c = controller
                if (c != null && c.mediaItemCount > 0) _position.value = c.currentPosition.coerceAtLeast(0L)
                delay(if (c?.isPlaying == true) 120L else 400L)
            }
        }
    }

    fun currentPositionNow(): Long = controller?.currentPosition ?: 0L

    fun currentTrack(): TrackRecord? {
        val tracks = Graph.repo.tracks.value
        if (tracks.isEmpty()) return null
        val mediaId = _state.value.mediaId
        if (mediaId.isNotEmpty()) {
            tracks.firstOrNull { it.id == mediaId }?.let { return it }
        }
        val q = queueState.value
        val idx = q.queue.getOrNull(q.qpos)
        return if (idx != null) tracks.getOrNull(idx) else tracks.firstOrNull()
    }

    /* ---------------- Service Callbacks ---------------- */

    fun onServiceTrackTransition(trackId: String, initialRepeatRemaining: Int) {
        _state.update {
            it.copy(
                mediaId = trackId,
                abA = null,
                abB = null,
                repeatRemaining = initialRepeatRemaining,
            )
        }
    }

    fun setRepeatRemaining(rem: Int) {
        _state.update { it.copy(repeatRemaining = max(0, rem)) }
    }

    fun setRgApplied(rgDb: Double) {
        _state.update { it.copy(rgApplied = rgDb) }
    }

    fun updateLimiterTelemetry(limitingDb: Double, clipping: Boolean) {
        val cur = _state.value
        if (abs(cur.limitingDb - limitingDb) > 0.25 || cur.clipping != clipping) {
            _state.update { it.copy(limitingDb = limitingDb, clipping = clipping) }
        }
    }

    fun checkAndConsumeSleepOnTrackEnd(): Boolean {
        val cur = _state.value
        if (cur.sleepEndOfTrack) {
            _state.update { it.copy(sleepEndOfTrack = false) }
            Graph.toast("Sleep timer: stopped at end of track")
            return true
        }
        if (cur.sleepTracksRemaining > 0) {
            val rem = cur.sleepTracksRemaining - 1
            _state.update { it.copy(sleepTracksRemaining = rem) }
            if (rem <= 0) {
                Graph.toast("Sleep timer: track count reached")
                return true
            }
        }
        return false
    }

    fun clearSleepTimer() {
        _state.update { it.copy(sleepAt = null, sleepEndOfTrack = false, sleepTracksRemaining = 0) }
    }

    fun configureSleepTimer(optionValue: Int) {
        when {
            optionValue == -1 -> _state.update {
                it.copy(sleepEndOfTrack = true, sleepAt = null, sleepTracksRemaining = 0)
            }
            optionValue < -100 -> _state.update {
                it.copy(sleepEndOfTrack = false, sleepAt = null, sleepTracksRemaining = abs(optionValue + 100))
            }
            optionValue > 0 -> _state.update {
                it.copy(sleepEndOfTrack = false, sleepTracksRemaining = 0, sleepAt = System.currentTimeMillis() + optionValue * 60_000L)
            }
            else -> clearSleepTimer()
        }
    }

    fun toggleAbLoop() {
        val posSec = (_position.value.coerceAtLeast(0L)) / 1000.0
        val cur = _state.value
        when {
            cur.abA == null -> {
                _state.update { it.copy(abA = posSec, abB = null) }
                Graph.toast("Loop A set at ${fmtSec(posSec)}")
            }
            cur.abB == null -> {
                if (posSec <= cur.abA) {
                    Graph.toast("Set B after point A")
                } else {
                    _state.update { it.copy(abB = posSec) }
                    Graph.toast("A-B loop is active")
                }
            }
            else -> {
                _state.update { it.copy(abA = null, abB = null) }
                Graph.toast("A-B loop cleared")
            }
        }
    }

    fun startHoldSpeed() {
        if (!_state.value.isPlaying) return
        _state.update { it.copy(holdSpeedActive = true) }
        setSpeed(1.5f, keepPitch = Graph.settings.value.keepPitch)
        Graph.toast("1.5× speed (release to restore)")
    }

    fun endHoldSpeed() {
        if (!_state.value.holdSpeedActive) return
        _state.update { it.copy(holdSpeedActive = false) }
        val s = Graph.settings.value
        val cfg = currentTrack()?.let { Graph.repo.getTrackAudioConfig(it.id) }
        val eff = effectiveSettings(s, cfg)
        setSpeed(eff.speed.toFloat(), keepPitch = eff.keepPitch)
    }

    /* ---------------- Queue & Transport Operations (queue.ts) ---------------- */

    fun setQueue(items: List<MediaItem>, startIndex: Int, startMs: Long = 0L, play: Boolean = true) = withController {
        it.setMediaItems(items, startIndex.coerceIn(0, max(0, items.lastIndex)), startMs)
        it.prepare()
        if (play) it.play()
        _position.value = startMs
    }

    private fun syncQueueToPlayer(
        newState: QueueState,
        startTrackPlay: Boolean? = null,
        startKind: String = "play",
    ) {
        val prevState = queueState.value
        queueState.replace(newState)
        val tracks = Graph.repo.tracks.value
        if (newState.queue.isEmpty() || tracks.isEmpty()) return

        val items = newState.queue.mapNotNull { idx -> tracks.getOrNull(idx)?.let { Graph.repo.mediaItemFor(it) } }
        val targetPos = newState.qpos.coerceIn(0, max(0, items.lastIndex))
        val targetTrack = tracks.getOrNull(newState.queue.getOrNull(targetPos) ?: -1)

        if (startTrackPlay != null) {
            val startSec = if (targetTrack != null) {
                val curTrackId = _state.value.mediaId
                if (startKind == "play" && curTrackId == targetTrack.id) 0.0
                else UserDataLogic.startPositionFor(Graph.repo.userData.value, targetTrack, startKind, Graph.settings.value.resumeMinSec)
            } else 0.0
            val startMs = (startSec * 1000.0).toLong()
            setQueue(items, targetPos, startMs = startMs, play = startTrackPlay)
        } else {
            // Queue edited around the currently playing track: update ExoPlayer playlist without interrupting playback
            withController { c ->
                val curMs = c.currentPosition.coerceAtLeast(0L)
                val wasPlaying = c.isPlaying
                if (c.mediaItemCount == 0 || prevState.queue.getOrNull(prevState.qpos) != newState.queue.getOrNull(targetPos)) {
                    c.setMediaItems(items, targetPos, curMs)
                    c.prepare()
                    if (wasPlaying) c.play()
                } else {
                    c.setMediaItems(items, targetPos, curMs)
                }
            }
        }
    }

    fun restoreSavedQueue(savedQueueIds: List<String>?, savedCurrentTrackId: String?) {
        val tracks = Graph.repo.tracks.value
        if (tracks.isEmpty()) return
        val indexById = tracks.mapIndexed { idx, t -> t.id to idx }.toMap()
        val restored = savedQueueIds.orEmpty().mapNotNull { indexById[it] }.distinct()
        val used = restored.toSet()
        val fullQueue = restored + tracks.indices.filter { !used.contains(it) }
        val curIdx = savedCurrentTrackId?.let { indexById[it] }
        val qpos = if (curIdx != null) fullQueue.indexOf(curIdx).coerceAtLeast(0) else 0
        val qs = QueueState(queue = fullQueue, orderBase = null, qpos = qpos, insertCursor = 0)
        syncQueueToPlayer(qs, startTrackPlay = false, startKind = "prime")
    }

    fun onLibraryTracksChanged(newlyAdded: List<TrackRecord>) {
        val tracks = Graph.repo.tracks.value
        if (tracks.isEmpty()) {
            queueState.replace(QueueState())
            return
        }
        val q = queueState.value
        if (q.queue.isEmpty()) {
            val all = tracks.indices.toList()
            syncQueueToPlayer(QueueState(queue = all, orderBase = null, qpos = 0, insertCursor = 0), startTrackPlay = false, startKind = "prime")
            return
        }
        val prevCount = tracks.size - newlyAdded.size
        if (newlyAdded.isNotEmpty() && q.queue.size == prevCount) {
            val grown = q.queue + (prevCount until tracks.size)
            syncQueueToPlayer(q.copy(queue = grown, orderBase = null), startTrackPlay = null)
        }
    }

    fun playIndices(indices: List<Int>, start: Int = 0, forceShuffle: Boolean? = null) {
        if (indices.isEmpty()) return
        val shuffle = forceShuffle ?: Graph.settings.value.shuffle
        if (forceShuffle != null && Graph.settings.value.shuffle != forceShuffle) {
            Graph.settings.set { it.copy(shuffle = forceShuffle) }
        }
        val next = QueueManager.buildQueueForPlayIndices(indices, start, shuffle)
        syncQueueToPlayer(next, startTrackPlay = true, startKind = "play")
    }

    fun playShuffled(indices: List<Int>) {
        if (indices.isEmpty()) return
        playIndices(indices, Random.nextInt(indices.size), forceShuffle = true)
    }

    fun playFromList(list: List<Int>, trackIdx: Int) {
        val pos = list.indexOf(trackIdx)
        if (pos >= 0) playIndices(list, pos)
    }

    fun playNow(trackIdx: Int) {
        val q = queueState.value
        if (q.queue.isEmpty()) {
            playIndices(listOf(trackIdx), 0)
            return
        }
        val next = QueueManager.playNowState(q, trackIdx)
        syncQueueToPlayer(next, startTrackPlay = true, startKind = "play")
    }

    fun playAtQueuePosition(pos: Int) {
        val q = queueState.value
        if (pos !in q.queue.indices) return
        val next = q.copy(qpos = pos, insertCursor = 0)
        syncQueueToPlayer(next, startTrackPlay = true, startKind = "play")
    }

    fun playNext(trackIdx: Int, sequential: Boolean = false) {
        val q = queueState.value
        if (q.queue.isEmpty() || q.qpos < 0) {
            playIndices(listOf(trackIdx), 0)
            return
        }
        val next = QueueManager.playNextState(q, trackIdx, sequential)
        syncQueueToPlayer(next, startTrackPlay = null)
    }

    fun addToQueue(trackIdx: Int) {
        val q = queueState.value
        if (q.queue.isEmpty() || q.qpos < 0) {
            playIndices(listOf(trackIdx), 0)
            return
        }
        val next = QueueManager.addToQueueState(q, trackIdx)
        syncQueueToPlayer(next, startTrackPlay = null)
    }

    fun enqueueMany(indices: List<Int>) {
        var cur = queueState.value
        if (cur.queue.isEmpty() || cur.qpos < 0) {
            playIndices(indices, 0)
            return
        }
        for (idx in indices) {
            cur = QueueManager.addToQueueState(cur, idx)
        }
        syncQueueToPlayer(cur, startTrackPlay = null)
    }

    fun removeFromQueue(pos: Int) {
        val next = QueueManager.removeFromQueueState(queueState.value, pos)
        syncQueueToPlayer(next, startTrackPlay = null)
    }

    fun moveInQueue(from: Int, to: Int) {
        val next = QueueManager.moveInQueueState(queueState.value, from, to)
        syncQueueToPlayer(next, startTrackPlay = null)
    }

    fun clearUpNext() {
        val next = QueueManager.clearUpNextState(queueState.value)
        syncQueueToPlayer(next, startTrackPlay = null)
    }

    fun setShuffle(on: Boolean) {
        Graph.settings.set { it.copy(shuffle = on) }
        val next = QueueManager.setShuffleState(queueState.value, on)
        syncQueueToPlayer(next, startTrackPlay = null)
    }

    fun cycleRepeat() {
        val cur = Graph.settings.value.repeat
        val next = when (cur) {
            "off" -> "all"
            "all" -> "one"
            else -> "off"
        }
        Graph.settings.set { it.copy(repeat = next) }
    }

    fun play() = withController { it.play() }
    fun pause() = withController { it.pause() }
    fun toggle() = withController { if (it.isPlaying) it.pause() else it.play() }
    fun next() = withController { it.seekToNext() }
    fun prev() = withController { it.seekToPrevious() }

    fun seekTo(ms: Long) = withController {
        val target = ms.coerceAtLeast(0L)
        it.seekTo(target)
        _position.value = target
    }

    fun seekSeconds(sec: Double) = seekTo((sec * 1000.0).toLong().coerceAtLeast(0L))

    fun setSpeed(speed: Float, keepPitch: Boolean = true) = withController {
        it.setPlaybackParameters(PlaybackParameters(speed, if (keepPitch) 1f else speed))
    }
}
