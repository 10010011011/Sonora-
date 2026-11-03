package app.sonora.player;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;

import androidx.core.content.ContextCompat;

import com.getcapacitor.JSObject;

/**
 * Watches for headphone / Bluetooth disconnects ("becoming noisy"), which is delivered without any Bluetooth
 * permission.
 *
 * This class must NOT request audio focus. Chromium's WebView already takes AUDIOFOCUS_GAIN whenever an
 * {@code <audio>} element plays, and pauses or resumes that element itself when focus is lost or regained. A second
 * request from this app evicts the WebView's (or is evicted by it); the AUDIOFOCUS_LOSS that followed paused
 * playback less than a second after it started.
 */
final class AudioFocusController {
    private static AudioFocusController instance;

    static synchronized AudioFocusController get(Context context) {
        if (instance == null) instance = new AudioFocusController(context.getApplicationContext());
        return instance;
    }

    private final Context app;
    private boolean noisyRegistered;

    private final BroadcastReceiver noisy = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(intent.getAction())) {
                SonoraPlayerPlugin.emit("becomingNoisy", new JSObject());
            }
        }
    };

    private AudioFocusController(Context app) {
        this.app = app;
    }

    /** Idempotent. Playback is starting: watch for output disconnects. Focus is owned by the WebView, so always true. */
    synchronized boolean request() {
        registerNoisy();
        return true;
    }

    /** Called when JS reports it stopped playing. */
    synchronized void onPlaybackStopped() {
        unregisterNoisy();
    }

    private void registerNoisy() {
        if (noisyRegistered) return;
        // Targeting Android 14+ requires an explicit export flag for runtime receivers. The broadcast is a system
        // one, so the receiver stays private; the compat call applies the flag on every API level.
        ContextCompat.registerReceiver(app, noisy, new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            ContextCompat.RECEIVER_NOT_EXPORTED);
        noisyRegistered = true;
    }

    private void unregisterNoisy() {
        if (!noisyRegistered) return;
        try { app.unregisterReceiver(noisy); } catch (IllegalArgumentException ignored) { }
        noisyRegistered = false;
    }
}
