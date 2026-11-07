package app.sonora.player.audio

import android.app.PendingIntent
import android.content.Intent
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.mp3.Mp3Extractor
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import app.sonora.player.Graph
import app.sonora.player.MainActivity
import app.sonora.player.R
import app.sonora.player.data.effectiveSettings
import app.sonora.player.library.SonoraUtil
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * THE playback engine. ExoPlayer lives here (not in the UI), so playback, notification, lock screen, Bluetooth
 * controls, A-B loop, sleep timer, repeat-N, ReplayGain, and crossfade (Level 1 + Level 2 dual-player overlap)
 * keep working when the screen is off or the activity is gone.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    companion object {
        const val ACTION_CLOSE_SESSION = "app.sonora.player.CLOSE_SESSION"
        private const val IDLE_STOP_MS = 30L * 60L * 1000L // 30 min paused notification cleanup
        private const val MAX_FAIL_STREAK = 3
    }

    private var session: MediaSession? = null
    private lateinit var playerA: ExoPlayer
    private lateinit var playerB: ExoPlayer
    private val dspB = DspParamsHolder()
    private var activeIsA = true
    private val activePlayer: ExoPlayer get() = if (activeIsA) playerA else playerB
    private val standbyPlayer: ExoPlayer get() = if (activeIsA) playerB else playerA

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var fadeJob: Job? = null
    private var crossfadeJob: Job? = null
    private var idleStopJob: Job? = null
    private var fadeMultiplier = 1f
    private var sleepMultiplier = 1f
    private var crossfadeActive = false
    private var level2Available = true

    // Error & retry tracking (PLAN 6.2 & FIXES.md)
    private val retriedMediaIds = HashSet<String>()
    private var failStreak = 0
    private var lastKnownPositionMs = 0L

    // Tracking state (tracking.ts: save resume every 5s, play count at min(30s, 80% dur))
    private var lastResumeSaveMs = 0L
    private var countedTrackId: String? = null
    private var lastTickRealtimeMs = 0L

    private fun buildExoPlayer(dspHolder: DspParamsHolder, handleAudioFocus: Boolean): ExoPlayer {
        val extractors = DefaultExtractorsFactory()
            .setConstantBitrateSeekingEnabled(true)
            .setMp3ExtractorFlags(Mp3Extractor.FLAG_ENABLE_INDEX_SEEKING)

        return ExoPlayer.Builder(this, SonoraRenderersFactory(this, dspHolder))
            .setMediaSourceFactory(DefaultMediaSourceFactory(this, extractors))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                handleAudioFocus,
            )
            .setHandleAudioBecomingNoisy(handleAudioFocus)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
            .apply { setSeekParameters(SeekParameters.EXACT) }
    }

    override fun onCreate() {
        super.onCreate()
        playerA = buildExoPlayer(Graph.dsp, handleAudioFocus = true)
        playerB = buildExoPlayer(dspB, handleAudioFocus = false)

        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        session = MediaSession.Builder(this, makeForwardingPlayer(activePlayer))
            .setSessionActivity(openApp)
            .build()

        val provider = DefaultMediaNotificationProvider.Builder(this).build()
        provider.setSmallIcon(R.drawable.ic_stat_sonora)
        setMediaNotificationProvider(provider)

        val playerListener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) {
                    failStreak = 0
                    idleStopJob?.cancel()
                } else {
                    saveCurrentResumeNow()
                    scheduleIdleStop()
                }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val p = activePlayer
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) {
                    val rem = Graph.controller.state.value.repeatRemaining
                    if (rem > 0 && Graph.settings.value.repeat != "one") {
                        val nextRem = rem - 1
                        Graph.controller.setRepeatRemaining(nextRem)
                        if (nextRem <= 0) {
                            syncRepeatMode(p)
                        }
                    }
                    return
                }

                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                    if (Graph.controller.checkAndConsumeSleepOnTrackEnd()) {
                        p.pause()
                        return
                    }
                }

                onActiveTrackChanged(mediaItem)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    val ui = Graph.controller.state.value
                    val abA = ui.abA
                    if (abA != null) {
                        activePlayer.seekTo((abA * 1000.0).toLong())
                        activePlayer.play()
                        return
                    }
                    if (Graph.controller.checkAndConsumeSleepOnTrackEnd()) {
                        activePlayer.pause()
                    }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                handlePlaybackError(error)
            }
        }

        playerA.addListener(playerListener)
        playerB.addListener(playerListener)

        var prevVol = Graph.settings.value.volume
        scope.launch {
            Graph.settings.flow.collect { s ->
                if (s.pauseOnVolume0 && prevVol > 0.0 && s.volume == 0.0 && activePlayer.isPlaying) {
                    fadePause()
                }
                prevVol = s.volume
                applyOutputVolume()
                activePlayer.setHandleAudioBecomingNoisy(s.pauseOnDisconnect)
                syncRepeatMode(activePlayer)
                applyTrackDspAndSpeed(activePlayer.currentMediaItem?.mediaId)
            }
        }

        startEngineTicker()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CLOSE_SESSION) {
            activePlayer.pause()
            standbyPlayer.pause()
            saveCurrentResumeNow()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        return super.onStartCommand(intent, flags, startId)
    }

    private fun makeForwardingPlayer(p: ExoPlayer): SonoraForwardingPlayer =
        SonoraForwardingPlayer(
            player = p,
            onPlayRequested = { fadePlay() },
            onPauseRequested = { fadePause() },
        )

    private fun baseVolume(): Float {
        val s = Graph.settings.value
        return if (s.muted) 0f else (s.volume * s.volume).toFloat().coerceIn(0f, 1f)
    }

    private fun applyOutputVolume() {
        if (!crossfadeActive) {
            activePlayer.volume = (baseVolume() * fadeMultiplier * sleepMultiplier).coerceIn(0f, 1f)
        }
    }

    private fun fadePlay() {
        fadeJob?.cancel()
        val p = activePlayer
        val fadeMs = Graph.settings.value.playPauseFadeMs.toLong()
        if (fadeMs <= 20L) {
            fadeMultiplier = 1f
            applyOutputVolume()
            if (p.playbackState == Player.STATE_IDLE) p.prepare()
            p.play()
            return
        }
        fadeMultiplier = 0.01f
        applyOutputVolume()
        if (p.playbackState == Player.STATE_IDLE) p.prepare()
        p.play()
        fadeJob = scope.launch {
            val steps = max(4, (fadeMs / 20L).toInt())
            val stepMs = fadeMs / steps
            for (i in 1..steps) {
                fadeMultiplier = i.toFloat() / steps
                applyOutputVolume()
                delay(stepMs)
            }
            fadeMultiplier = 1f
            applyOutputVolume()
        }
    }

    private fun fadePause() {
        fadeJob?.cancel()
        val p = activePlayer
        val fadeMs = Graph.settings.value.playPauseFadeMs.toLong()
        if (!p.isPlaying || fadeMs <= 20L) {
            p.pause()
            fadeMultiplier = 1f
            applyOutputVolume()
            return
        }
        fadeJob = scope.launch {
            val steps = max(4, (fadeMs / 20L).toInt())
            val stepMs = fadeMs / steps
            for (i in (steps - 1) downTo 0) {
                fadeMultiplier = max(0.001f, i.toFloat() / steps)
                applyOutputVolume()
                delay(stepMs)
            }
            p.pause()
            fadeMultiplier = 1f
            applyOutputVolume()
        }
    }

    private fun syncRepeatMode(p: ExoPlayer) {
        val s = Graph.settings.value
        val rem = Graph.controller.state.value.repeatRemaining
        p.repeatMode = when {
            s.repeat == "one" || rem > 0 -> Player.REPEAT_MODE_ONE
            s.repeat == "all" -> Player.REPEAT_MODE_ALL
            else -> Player.REPEAT_MODE_OFF
        }
    }

    private fun onActiveTrackChanged(mediaItem: MediaItem?) {
        val trackId = mediaItem?.mediaId ?: ""
        countedTrackId = null
        val s = Graph.settings.value
        val targetRepeats = max(0, s.repeatCountTarget.toInt() - 1)
        Graph.controller.onServiceTrackTransition(trackId, targetRepeats)
        syncRepeatMode(activePlayer)
        applyTrackDspAndSpeed(trackId)
    }

    private fun applyTrackDspAndSpeed(trackId: String?) {
        val s = Graph.settings.value
        val id = trackId ?: activePlayer.currentMediaItem?.mediaId ?: ""
        val track = Graph.repo.tracks.value.firstOrNull { it.id == id }
        val cfg = if (id.isNotEmpty()) Graph.repo.getTrackAudioConfig(id) else null
        val eff = effectiveSettings(s, cfg)

        val rgDb = if (track != null) TrackAnalyzer.rgFor(track, s.rgMode, s.rgPreamp) else 0.0
        Graph.controller.setRgApplied(rgDb)
        val params = DspParams.from(s, rgDb, cfg)
        Graph.dsp.p = params
        dspB.p = params

        if (!Graph.controller.state.value.holdSpeedActive) {
            val speed = eff.speed.toFloat().coerceIn(0.25f, 3.0f)
            val pitch = if (eff.keepPitch) 1f else speed
            activePlayer.playbackParameters = PlaybackParameters(speed, pitch)
        }

        if (track != null && s.rgMode != "off" && s.rgAnalyze &&
            track.rgTrack == null && TrackAnalyzer.getAnalyzedRgDb(track.id) == null
        ) {
            scope.launch {
                runCatching {
                    TrackAnalyzer.analyze(this@PlaybackService, Graph.repo, track, 512, 180, spectro = false, loudnessOnly = true)
                    if (activePlayer.currentMediaItem?.mediaId == track.id) {
                        val updatedRg = TrackAnalyzer.rgFor(track, Graph.settings.value.rgMode, Graph.settings.value.rgPreamp)
                        Graph.controller.setRgApplied(updatedRg)
                        Graph.dsp.p = DspParams.from(Graph.settings.value, updatedRg, cfg)
                    }
                }
            }
        }
    }

    private fun handlePlaybackError(error: PlaybackException) {
        val p = activePlayer
        val item = p.currentMediaItem
        val trackId = item?.mediaId ?: ""
        val track = Graph.repo.tracks.value.firstOrNull { it.id == trackId }
        val title = track?.title?.ifEmpty { track.fileName } ?: item?.mediaMetadata?.title?.toString() ?: "track"
        val ext = SonoraUtil.extOf(track?.fileName).uppercase()

        Diagnostics.log("media-error", "$title (${error.errorCodeName}): ${error.message}")

        if (SonoraUtil.isTierCUnsupported(track?.fileName, track?.mime)) {
            Graph.toast("Can't play “$title” ($ext): format not supported")
            advanceAfterFailure(p)
            return
        }

        val isIoError = error.errorCode in 2000..2999
        val retryKey = "${p.currentMediaItemIndex}:$trackId"
        if (isIoError && !retriedMediaIds.contains(retryKey)) {
            if (retriedMediaIds.size > 50) retriedMediaIds.clear()
            retriedMediaIds.add(retryKey)
            val resumePos = max(0L, lastKnownPositionMs - 200L)
            Diagnostics.log("media-error-retry", "$title at ${resumePos}ms")
            scope.launch {
                delay(250L)
                p.prepare()
                if (resumePos > 0L) p.seekTo(resumePos)
                p.play()
            }
            return
        }

        Graph.toast("Could not play $title (${error.errorCodeName})")
        advanceAfterFailure(p)
    }

    private fun advanceAfterFailure(p: ExoPlayer) {
        if (++failStreak >= MAX_FAIL_STREAK) {
            failStreak = 0
            Diagnostics.log("media-error-stop", "3 consecutive failures")
            Graph.toast("Several songs in a row failed to play, so playback stopped")
            p.pause()
            return
        }
        scope.launch {
            delay(350L)
            if (p.hasNextMediaItem()) {
                p.seekToNextMediaItem()
                p.prepare()
                p.play()
            } else {
                p.pause()
            }
        }
    }

    private fun startEngineTicker() {
        scope.launch {
            while (isActive) {
                val p = activePlayer
                val nowRealtime = SystemClock.elapsedRealtime()
                if (lastTickRealtimeMs > 0L && p.isPlaying) {
                    Diagnostics.recordTick(nowRealtime - lastTickRealtimeMs, hidden = false)
                }
                lastTickRealtimeMs = nowRealtime

                if (p.mediaItemCount > 0) {
                    val posMs = p.currentPosition.coerceAtLeast(0L)
                    val durMs = p.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L
                    lastKnownPositionMs = posMs
                    val posSec = posMs / 1000.0
                    val durSec = durMs / 1000.0
                    val ui = Graph.controller.state.value
                    val abA = ui.abA
                    val abB = ui.abB

                    // 1. A-B loop check
                    if (abA != null && abB != null && posSec >= abB) {
                        p.seekTo((abA * 1000.0).toLong())
                    }

                    // 2. Sleep timer check (with 20s fade-out)
                    val sleepAt = ui.sleepAt
                    if (sleepAt != null) {
                        val remMs = sleepAt - System.currentTimeMillis()
                        if (remMs <= 0L) {
                            sleepMultiplier = 1f
                            Graph.controller.clearSleepTimer()
                            p.pause()
                            applyOutputVolume()
                            Graph.toast("Sleep timer finished")
                        } else {
                            sleepMultiplier = (remMs / 20000f).coerceIn(0f, 1f)
                            applyOutputVolume()
                        }
                    } else if (sleepMultiplier != 1f) {
                        sleepMultiplier = 1f
                        applyOutputVolume()
                    }

                    // 3. Limiter reduction & clipping telemetry
                    val proc = (if (activeIsA) Graph.dsp else dspB).processor
                    if (proc != null) {
                        Graph.controller.updateLimiterTelemetry(proc.limitingDb, proc.clipping)
                    }

                    // 4. Play-count & periodic resume position tracking (tracking.ts)
                    if (p.isPlaying) {
                        val trackId = p.currentMediaItem?.mediaId ?: ""
                        if (posSec < 2.0) countedTrackId = null
                        val threshold = min(30.0, if (durSec > 0.0) durSec * 0.8 else 30.0)
                        if (trackId.isNotEmpty() && countedTrackId != trackId && posSec >= threshold) {
                            countedTrackId = trackId
                            Graph.repo.recordPlayCount(trackId)
                        }
                        if (trackId.isNotEmpty() && nowRealtime - lastResumeSaveMs >= 5000L) {
                            lastResumeSaveMs = nowRealtime
                            Graph.repo.saveResume(trackId, posSec, durSec, Graph.settings.value.resumeMinSec)
                        }
                    }

                    // 5. Crossfade (Level 2 dual-player true overlap with automatic Level 1 fallback)
                    val cfSec = Graph.settings.value.crossfade
                    if (cfSec > 0.0 && p.isPlaying && !crossfadeActive && durMs > 4000L &&
                        Graph.settings.value.repeat != "one" && ui.repeatRemaining <= 0 && abB == null
                    ) {
                        val remSec = (durMs - posMs) / 1000.0 / p.playbackParameters.speed.coerceAtLeast(0.25f)
                        if (remSec <= cfSec && remSec > 0.25 && p.hasNextMediaItem()) {
                            triggerCrossfade(remSec.coerceAtMost(cfSec))
                        }
                    }
                }
                delay(100L)
            }
        }
    }

    private fun triggerCrossfade(durationSec: Double) {
        val nextIndex = activePlayer.nextMediaItemIndex
        if (nextIndex == C.INDEX_UNSET) return

        if (level2Available) {
            try {
                val outPlayer = activePlayer
                val inPlayer = standbyPlayer
                val items = (0 until outPlayer.mediaItemCount).map { outPlayer.getMediaItemAt(it) }
                inPlayer.setMediaItems(items, nextIndex, 0L)
                inPlayer.prepare()
                inPlayer.volume = 0f
                inPlayer.play()

                crossfadeActive = true
                crossfadeJob?.cancel()
                crossfadeJob = scope.launch {
                    val totalMs = (durationSec * 1000.0).toLong().coerceAtLeast(400L)
                    val stepMs = 50L
                    val steps = max(4, (totalMs / stepMs).toInt())
                    val baseVol = baseVolume()
                    for (i in 1..steps) {
                        val progress = i.toDouble() / steps
                        val gainOut = cos(progress * PI * 0.5).toFloat()
                        val gainIn = sin(progress * PI * 0.5).toFloat()
                        outPlayer.volume = (baseVol * gainOut * sleepMultiplier).coerceIn(0f, 1f)
                        inPlayer.volume = (baseVol * gainIn * sleepMultiplier).coerceIn(0f, 1f)
                        delay(stepMs)
                    }
                    outPlayer.pause()
                    activeIsA = !activeIsA
                    session?.player = makeForwardingPlayer(activePlayer)
                    crossfadeActive = false
                    applyOutputVolume()
                    onActiveTrackChanged(activePlayer.currentMediaItem)
                }
                return
            } catch (e: Exception) {
                level2Available = false
                Diagnostics.log("crossfade-l2-fallback", e.message ?: "falling back to level 1")
            }
        }

        crossfadeActive = true
        crossfadeJob?.cancel()
        crossfadeJob = scope.launch {
            val halfMs = (durationSec * 500.0).toLong().coerceAtLeast(250L)
            val steps = max(4, (halfMs / 40L).toInt())
            val stepMs = halfMs / steps
            val baseVol = baseVolume()
            for (i in (steps - 1) downTo 0) {
                activePlayer.volume = (baseVol * (i.toFloat() / steps) * sleepMultiplier).coerceIn(0f, 1f)
                delay(stepMs)
            }
            if (activePlayer.hasNextMediaItem()) {
                activePlayer.seekToNextMediaItem()
            }
            for (i in 1..steps) {
                activePlayer.volume = (baseVol * (i.toFloat() / steps) * sleepMultiplier).coerceIn(0f, 1f)
                delay(stepMs)
            }
            crossfadeActive = false
            applyOutputVolume()
        }
    }

    private fun saveCurrentResumeNow() {
        val p = activePlayer
        val id = p.currentMediaItem?.mediaId ?: return
        val posSec = p.currentPosition.coerceAtLeast(0L) / 1000.0
        val durSec = (p.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L) / 1000.0
        Graph.repo.saveResume(id, posSec, durSec, Graph.settings.value.resumeMinSec)
    }

    private fun scheduleIdleStop() {
        idleStopJob?.cancel()
        idleStopJob = scope.launch {
            delay(IDLE_STOP_MS)
            if (!activePlayer.isPlaying) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = session?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) {
            saveCurrentResumeNow()
            stopSelf()
        }
    }

    override fun onDestroy() {
        saveCurrentResumeNow()
        scope.cancel()
        session?.run {
            player.release()
            release()
        }
        runCatching { playerA.release() }
        runCatching { playerB.release() }
        session = null
        super.onDestroy()
    }
}
