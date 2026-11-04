package app.sonora.player;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.WebView;

import androidx.activity.OnBackPressedCallback;

import com.getcapacitor.BridgeActivity;
import com.getcapacitor.JSObject;

public class MainActivity extends BridgeActivity {
    /** Kept in sync by JS: true while it has an overlay or sub-screen that Back should close first. */
    static volatile boolean backHandledByJs = false;

    /** Audio file the user opened with "Open with Sonora"; JS pulls it with consumeOpenIntent(). */
    static volatile Uri pendingOpenUri;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        // Only a fresh launch may take the "open with" file. After process death the system hands the original
        // intent back, so it is replaced first. Must run before super.onCreate(): BridgeActivity.load() also feeds
        // getIntent() to onNewIntent(), and that path would otherwise capture it again.
        if (savedInstanceState == null) captureViewIntent(getIntent());
        else setIntent(new Intent(Intent.ACTION_MAIN));

        registerPlugin(SonoraPlayerPlugin.class);
        super.onCreate(savedInstanceState);

        // Serve library audio, covers, thumbnails and lyrics (with HTTP Range) straight from native files.
        getBridge().setWebViewClient(new SonoraWebViewClient(getBridge(), new MediaRequestHandler(this, LibraryStore.get(this))));

        // Predictive-back safe: handled through the dispatcher, not onBackPressed().
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                if (backHandledByJs) {
                    SonoraPlayerPlugin.emit("backButton", new JSObject());
                } else {
                    // Root screen: leave the app running (music keeps playing) instead of destroying the WebView.
                    moveTaskToBack(true);
                }
            }
        });
    }

    // Public on purpose: widening is always legal, whatever visibility the Capacitor version declares.
    @Override public void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (captureViewIntent(intent)) SonoraPlayerPlugin.emit("openIntent", new JSObject());
    }

    private static boolean captureViewIntent(Intent intent) {
        if (intent == null || !Intent.ACTION_VIEW.equals(intent.getAction()) || intent.getData() == null) return false;
        pendingOpenUri = intent.getData();
        intent.setAction(Intent.ACTION_MAIN); // consumed
        return true;
    }

    // Public on purpose: BridgeActivity declares these public, so overrides may not narrow them.
    @Override public void onPause() { super.onPause(); keepWebViewRunningWhilePlaying(); }
    @Override public void onStop() { super.onStop(); keepWebViewRunningWhilePlaying(); }

    /** The WebView owns transport state; keep it responsive while the MediaSession notification is active. */
    private void keepWebViewRunningWhilePlaying() {
        if (!PlaybackService.isSessionActive() || getBridge() == null) return;
        WebView webView = getBridge().getWebView();
        if (webView == null) return;
        webView.onResume();
        webView.resumeTimers();
    }
}
