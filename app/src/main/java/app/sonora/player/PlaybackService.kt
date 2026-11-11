package app.sonora.player

import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.media3.common.Player
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

class SonoraApp : Application() {
    override fun onCreate() {
        super.onCreate()
        S.init(this)
        Engine.init()
    }
}

/**
 * Lock-screen / notification / headset-button controls. Media3 keeps this a foreground service while music plays
 * and posts the media notification (previous, play/pause, next, plus Sonora's Close button).
 *
 * The engine lives here and in the Application — never in an Activity — so playback survives the app
 * being swiped away. When a crossfade promotes the standby player, the session follows it.
 */
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private val handler = Handler(Looper.getMainLooper())
    private val closeCmd = SessionCommand("app.sonora.player.CLOSE", Bundle.EMPTY)

    /** A paused session is kept for 30 minutes, then the service lets go so Android never shows a dead notification. */
    private val idleStop = Runnable {
        if (!Engine.isPlayingAny()) {
            Diag.log("service", "idle stop")
            stopSelf()
        }
    }

    private val idleWatch = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            handler.removeCallbacks(idleStop)
            if (!Engine.isPlayingAny()) handler.postDelayed(idleStop, 30L * 60 * 1000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        S.init(this)
        setMediaNotificationProvider(androidx.media3.session.DefaultMediaNotificationProvider.Builder(this).build().apply { setSmallIcon(R.drawable.ic_stat_sonora) })
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val close = CommandButton.Builder().setDisplayName("Close").setIconResId(R.drawable.ic_sonora_close).setSessionCommand(closeCmd).build()
        session = MediaSession.Builder(this, Engine.mainRouted())
            .setSessionActivity(open)
            .setCustomLayout(listOf(close))
            .setCallback(object : MediaSession.Callback {
                override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult =
                    MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                        .setAvailableSessionCommands(MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon().add(closeCmd).build())
                        .build()

                override fun onCustomCommand(session: MediaSession, controller: MediaSession.ControllerInfo, customCommand: SessionCommand, args: Bundle): ListenableFuture<SessionResult> {
                    if (customCommand.customAction == closeCmd.customAction) {
                        Engine.pause(true)
                        Engine.saveResume()
                        stopSelf()
                    }
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
            })
            .build()
        // setPlayer must run on the players' looper (main).
        Engine.sessionSetter = { p -> handler.post { runCatching { session?.setPlayer(p) } } }
        Engine.forEachPlayer { it.addListener(idleWatch) }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    /** Swiping the app away keeps music playing; if nothing is playing the notification and service go away with it. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!Engine.isPlayingAny()) {
            Engine.pause(true)
            Engine.saveResume()
            S.flush()
            stopSelf()
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(idleStop)
        Engine.forEachPlayer { runCatching { it.removeListener(idleWatch) } }
        session?.release()
        session = null
        Engine.release()
        super.onDestroy()
    }
}
