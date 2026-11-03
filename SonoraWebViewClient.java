package app.sonora.player;

import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;

import com.getcapacitor.Bridge;
import com.getcapacitor.BridgeWebViewClient;

/** Capacitor's client, plus our own range-capable handler for library audio, covers and lyrics. */
public class SonoraWebViewClient extends BridgeWebViewClient {
    private final MediaRequestHandler media;

    public SonoraWebViewClient(Bridge bridge, MediaRequestHandler media) {
        super(bridge);
        this.media = media;
    }

    @Override
    public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
        WebResourceResponse mine = media.handle(request);
        return mine != null ? mine : super.shouldInterceptRequest(view, request);
    }
}
