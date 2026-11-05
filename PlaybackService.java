package app.sonora.player;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;

import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;

import com.getcapacitor.JSObject;

import java.io.File;

/**
 * Foreground media-playback service for "path A": audio is still produced by the web engine, and this
 * service keeps the process alive, owns the MediaSession (lock screen, headset and Bluetooth buttons)
 * and shows the media notification. Every transport command is forwarded to JS, which stays the single
 * source of truth for playback.
 */
public class PlaybackService extends Service {
    /** Immutable-by-convention snapshot pushed from JS. */
    static final class Snapshot {
        boolean playing;
        String title = "";
        String artist = "";
        String album = "";
        String coverName = "";
        long durationMs;
        long positionMs;
        float speed = 1f;
        boolean hasPrev = true;
        boolean hasNext = true;
    }

    static final String CHANNEL_ID = "sonora_playback";
    static final int NOTIFICATION_ID = 4201;
    static final String ACTION_PLAY_PAUSE = "app.sonora.player.PLAY_PAUSE"; // legacy toggle action
    static final String ACTION_PLAY = "app.sonora.player.PLAY";
    static final String ACTION_PAUSE = "app.sonora.player.PAUSE";
    static final String ACTION_NEXT = "app.sonora.player.NEXT";
    static final String ACTION_PREV = "app.sonora.player.PREV";
    static final String ACTION_STOP = "app.sonora.player.STOP";
    private static final long WAKE_TIMEOUT_MS = 10L * 60 * 60 * 1000;
    /**
     * A paused session stays a foreground service for this long, so Android keeps the process (and the WebView
     * that owns playback) alive and the notification buttons keep working. After it, the notification is removed
     * instead of being left behind as a "zombie" whose buttons do nothing.
     */
    private static final long IDLE_STOP_MS = 30L * 60 * 1000;
    private static final int COVER_MAX_PX = 512;

    private static volatile PlaybackService instance;
    private static volatile Snapshot latest = new Snapshot();

    static boolean isPlaying() { return latest.playing; }
    /** The paused MediaSession still needs the WebView alive to receive notification play/skip commands. */
    static boolean isSessionActive() { return instance != null; }

    /** Entry point used by the plugin. Starts the service only when playback actually begins. */
    static void apply(Context context, Snapshot snapshot) {
        latest = snapshot;
        PlaybackService svc = instance;
        if (svc != null && !svc.stopping) {
            svc.main.post(svc::refresh);
        } else if (snapshot.playing) {
            try {
                ContextCompat.startForegroundService(context, new Intent(context, PlaybackService.class));
            } catch (RuntimeException e) {
                // e.g. ForegroundServiceStartNotAllowedException: keep playing in-app, without a service.
                SonoraPlayerPlugin.emit("serviceError", new JSObject().put("message", String.valueOf(e.getMessage())));
            }
        }
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private MediaSessionCompat session;
    private PowerManager.WakeLock wakeLock;
    private boolean everForeground;
    /** Set once the session is being torn down, so a late refresh cannot bring the notification back. */
    private volatile boolean stopping;
    private final Runnable idleStop = () -> { if (!latest.playing) stopAndRemove(); };
    private Bitmap cover;
    private String coverName = "";

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        createChannel();
        session = new MediaSessionCompat(this, "Sonora");
        session.setCallback(new MediaSessionCompat.Callback() {
            @Override public void onPlay() { command("play", -1); }
            @Override public void onPause() { command("pause", -1); }
            @Override public void onSkipToNext() { command("next", -1); }
            @Override public void onSkipToPrevious() { command("previous", -1); }
            @Override public void onStop() { command("stop", -1); stopAndRemove(); }
            @Override public void onSeekTo(long pos) { command("seekTo", pos); }
            @Override public void onFastForward() { command("seekBy", 10_000); }
            @Override public void onRewind() { command("seekBy", -10_000); }
        });
        Intent open = getPackageManager().getLaunchIntentForPackage(getPackageName());
        if (open != null) {
            session.setSessionActivity(PendingIntent.getActivity(
                this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        }
        session.setActive(true);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_PLAY_PAUSE.equals(action)) {
            command(latest.playing ? "pause" : "play", -1);
            return START_NOT_STICKY;
        }
        if (ACTION_PLAY.equals(action)) { command("play", -1); return START_NOT_STICKY; }
        if (ACTION_PAUSE.equals(action)) { command("pause", -1); return START_NOT_STICKY; }
        if (ACTION_NEXT.equals(action)) { command("next", -1); return START_NOT_STICKY; }
        if (ACTION_PREV.equals(action)) { command("previous", -1); return START_NOT_STICKY; }
        if (ACTION_STOP.equals(action)) { command("stop", -1); stopAndRemove(); return START_NOT_STICKY; }
        refresh(); // A plain start must satisfy startForegroundService's foreground deadline.
        return START_NOT_STICKY; // JS state is gone if we are killed, so never restart blindly
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // Swiping the app away destroys the WebView, which is what actually plays the audio. Whatever the state, the
        // notification would be left with controls that reach nothing, so end the session cleanly.
        stopAndRemove();
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        if (instance == this) instance = null;
        releaseWake();
        if (session != null) { session.setActive(false); session.release(); }
        super.onDestroy();
    }

    /**
     * Relays a transport command to JS. When JS is gone (WebView destroyed, process restarted) the notification is
     * stale and its buttons can no longer work, so it is removed instead of lingering. Commands are never queued:
     * a retained "next" replayed minutes later, when the app is reopened, looks like a random skip.
     */
    private void command(String action, long positionMs) {
        JSObject event = new JSObject().put("action", action);
        if (positionMs >= 0 || "seekBy".equals(action)) event.put("positionMs", positionMs);
        if (!SonoraPlayerPlugin.emit("mediaCommand", event, false) && !"stop".equals(action)) stopAndRemove();
    }

    private void stopAndRemove() {
        stopping = true;
        main.removeCallbacks(idleStop);
        releaseWake();
        AudioFocusController.get(this).onPlaybackStopped();
        latest = new Snapshot();
        try {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } catch (RuntimeException ignored) { }
        try {
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).cancel(NOTIFICATION_ID);
        } catch (RuntimeException ignored) { }
        everForeground = false;
        stopSelf();
    }

    void refresh() {
        if (stopping) return;
        Snapshot s = latest;
        loadCover(s.coverName);

        MediaMetadataCompat.Builder md = new MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, s.title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, s.artist)
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, s.album)
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, Math.max(0, s.durationMs));
        if (cover != null) md.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, cover);
        session.setMetadata(md.build());

        long actions = PlaybackStateCompat.ACTION_PLAY | PlaybackStateCompat.ACTION_PAUSE
            | PlaybackStateCompat.ACTION_PLAY_PAUSE | PlaybackStateCompat.ACTION_STOP
            | PlaybackStateCompat.ACTION_SEEK_TO | PlaybackStateCompat.ACTION_FAST_FORWARD
            | PlaybackStateCompat.ACTION_REWIND;
        if (s.hasNext) actions |= PlaybackStateCompat.ACTION_SKIP_TO_NEXT;
        if (s.hasPrev) actions |= PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS;
        session.setPlaybackState(new PlaybackStateCompat.Builder()
            .setActions(actions)
            .setState(s.playing ? PlaybackStateCompat.STATE_PLAYING : PlaybackStateCompat.STATE_PAUSED,
                Math.max(0, s.positionMs), s.playing ? s.speed : 0f, SystemClock.elapsedRealtime())
            .build());

        Notification n = buildNotification(s);
        // Stay a foreground service while paused too (bounded by IDLE_STOP_MS): a detached notification outlives the
        // process Android is free to kill, and its buttons then reach nothing.
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            everForeground = true;
        } catch (RuntimeException e) {
            SonoraPlayerPlugin.emit("serviceError", new JSObject().put("message", String.valueOf(e.getMessage())));
            if (everForeground) {
                try {
                    ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(NOTIFICATION_ID, n);
                } catch (RuntimeException ignored) { }
            }
        }
        main.removeCallbacks(idleStop);
        if (s.playing) {
            acquireWake();
        } else {
            releaseWake();
            main.postDelayed(idleStop, IDLE_STOP_MS);
        }
    }

    private Notification buildNotification(Snapshot s) {
        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_sonora)
            .setContentTitle(s.title.isEmpty() ? "Sonora" : s.title)
            .setContentText(s.artist)
            .setSubText(s.album)
            .setLargeIcon(cover)
            .setContentIntent(session.getController().getSessionActivity())
            .setDeleteIntent(servicePending(ACTION_STOP, 4))
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setOngoing(s.playing)
            .addAction(R.drawable.ic_sonora_prev, "Previous", servicePending(ACTION_PREV, 1))
            .addAction(s.playing ? R.drawable.ic_sonora_pause : R.drawable.ic_sonora_play,
                s.playing ? "Pause" : "Play", servicePending(s.playing ? ACTION_PAUSE : ACTION_PLAY, 2))
            .addAction(R.drawable.ic_sonora_next, "Next", servicePending(ACTION_NEXT, 3))
            .addAction(R.drawable.ic_sonora_close, "Close", servicePending(ACTION_STOP, 5))
            .setStyle(new androidx.media.app.NotificationCompat.MediaStyle()
                .setMediaSession(session.getSessionToken())
                .setShowActionsInCompactView(0, 1, 2));
        return b.build();
    }

    private PendingIntent servicePending(String action, int requestCode) {
        Intent i = new Intent(this, PlaybackService.class).setAction(action);
        return PendingIntent.getService(this, requestCode, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private void loadCover(String name) {
        if (name == null) name = "";
        if (name.equals(coverName)) return;
        coverName = name;
        cover = null;
        if (name.isEmpty()) return;
        File f = LibraryStore.get(this).coverFile(name);
        if (f == null || !f.isFile()) return;
        try {
            // Decode at notification size, not full size: a 3000 px cover is ~36 MB of bitmap otherwise.
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getAbsolutePath(), bounds);
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = SonoraUtil.sampleSizeFor(bounds.outWidth, bounds.outHeight, COVER_MAX_PX);
            cover = BitmapFactory.decodeFile(f.getAbsolutePath(), opts);
        } catch (RuntimeException | OutOfMemoryError e) {
            cover = null;
        }
    }

    private void acquireWake() {
        if (wakeLock == null) {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Sonora:playback");
            wakeLock.setReferenceCounted(false);
        }
        wakeLock.acquire(WAKE_TIMEOUT_MS);
    }

    private void releaseWake() {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "Playback", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("Now playing controls");
        ch.setShowBadge(false);
        ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(ch);
    }
}
