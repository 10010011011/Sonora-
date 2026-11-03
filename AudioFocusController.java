package app.sonora.player;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import androidx.core.content.ContextCompat;

import com.getcapacitor.JSObject;

/**
 * Android does not grant a WebView audio focus on its own, so playback driven by the web engine must ask
 * for it here and react when other apps take it. Also watches for headphone / Bluetooth disconnects
 * ("becoming noisy"), which is delivered without any Bluetooth permission.
 */
final class AudioFocusController implements AudioManager.OnAudioFocusChangeListener {
    private static AudioFocusController instance;

    static synchronized AudioFocusController get(Context context) {
        if (instance == null) instance = new AudioFocusController(context.getApplicationContext());
        return instance;
    }

    private final Context app;
    private final AudioManager audio;
    private final Handler main = new Handler(Looper.getMainLooper());
    private AudioFocusRequest request;
    private boolean hasFocus;
    private boolean transientLoss;
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
        this.audio = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
    }

    /** Idempotent. Returns whether we hold (or already held) focus. */
    synchronized boolean request() {
        if (hasFocus) { transientLoss = false; return true; }
        int result;
        if (Build.VERSION.SDK_INT >= 26) {
            AudioAttributes attrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build();
            request = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attrs)
                .setOnAudioFocusChangeListener(this, main)
                .setWillPauseWhenDucked(false) // we duck in the Web Audio graph ourselves
                .setAcceptsDelayedFocusGain(false)
                .build();
            result = audio.requestAudioFocus(request);
        } else {
            result = audio.requestAudioFocus(this, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
        }
        hasFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        if (hasFocus) { transientLoss = false; registerNoisy(); }
        return hasFocus;
    }

    /** Called when JS reports it stopped playing. A transient loss keeps focus so we hear the GAIN. */
    synchronized void onPlaybackStopped() {
        if (transientLoss) return;
        abandon();
    }

    private void abandon() {
        if (!hasFocus && request == null) return;
        if (Build.VERSION.SDK_INT >= 26 && request != null) audio.abandonAudioFocusRequest(request);
        else audio.abandonAudioFocus(this);
        request = null;
        hasFocus = false;
        transientLoss = false;
        unregisterNoisy();
    }

    @Override
    public synchronized void onAudioFocusChange(int change) {
        JSObject event = new JSObject();
        switch (change) {
            case AudioManager.AUDIOFOCUS_LOSS:
                abandon();
                SonoraPlayerPlugin.emit("audioFocus", event.put("type", "loss"));
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                transientLoss = true;
                SonoraPlayerPlugin.emit("audioFocus", event.put("type", "lossTransient"));
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                SonoraPlayerPlugin.emit("audioFocus", event.put("type", "duck"));
                break;
            case AudioManager.AUDIOFOCUS_GAIN:
                transientLoss = false;
                SonoraPlayerPlugin.emit("audioFocus", event.put("type", "gain"));
                break;
            default:
                break;
        }
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
