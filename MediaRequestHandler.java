package app.sonora.player;

import android.content.ContentResolver;
import android.content.res.AssetFileDescriptor;
import android.net.Uri;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Serves library files to the WebView at https://localhost/_sonora_media_/&lt;key&gt; (and covers, thumbnails,
 * lyrics) with real HTTP Range support, so {@code <audio>} can seek without the audio ever passing through JS.
 * Copied audio is read from the app's own files; audio that stays in a granted folder is read through its
 * persisted SAF URI. Only validated hash keys are reachable, never arbitrary paths.
 */
final class MediaRequestHandler {
    static final String MEDIA = "/_sonora_media_/";
    static final String COVER = "/_sonora_cover_/";
    static final String THUMB = "/_sonora_thumb_/";
    static final String LYRICS = "/_sonora_lyrics_/";

    private final LibraryStore store;
    private final ContentResolver resolver;

    MediaRequestHandler(android.content.Context context, LibraryStore store) {
        this.store = store;
        this.resolver = context.getApplicationContext().getContentResolver();
    }

    /** Opens the byte stream for a resource starting at {@code start}; knows its total length. */
    private interface Source {
        long length() throws IOException;
        InputStream open(long start, long length) throws IOException;
    }

    WebResourceResponse handle(WebResourceRequest request) {
        Uri uri = request.getUrl();
        String path = uri.getPath();
        if (path == null || !"localhost".equals(uri.getHost())) return null;
        boolean media = path.startsWith(MEDIA), cover = path.startsWith(COVER), thumb = path.startsWith(THUMB),
            lyrics = path.startsWith(LYRICS);
        if (!media && !cover && !thumb && !lyrics) return null;
        String method = request.getMethod();
        if (!"GET".equals(method) && !"HEAD".equals(method)) return null;

        try {
            Source source; String mime; String encoding = null; boolean immutable = false;
            if (media) {
                LibraryStore.MediaSource ms = store.mediaSource(path.substring(MEDIA.length()));
                if (ms == null) return status(404, "Not Found");
                mime = ms.mime;
                source = ms.file != null ? fileSource(ms.file) : uriSource(ms.uri, ms.size);
            } else {
                File file;
                if (cover) { file = store.coverFile(path.substring(COVER.length())); mime = "image/jpeg"; immutable = true; }
                else if (thumb) { file = store.thumbFile(path.substring(THUMB.length())); mime = "image/jpeg"; immutable = true; }
                else { file = store.lyricsFile(path.substring(LYRICS.length())); mime = "text/plain"; encoding = "utf-8"; }
                if (file == null || !file.isFile()) return status(404, "Not Found");
                source = fileSource(file);
            }
            return respond(source, mime, encoding, request, immutable);
        } catch (IOException | RuntimeException e) {
            return status(500, "Internal Error");
        }
    }

    private WebResourceResponse respond(Source source, String mime, String encoding, WebResourceRequest request, boolean immutable)
            throws IOException {
        long length = source.length();
        Map<String, String> headers = new HashMap<>();
        headers.put("Accept-Ranges", "bytes");
        headers.put("Access-Control-Allow-Origin", "*");
        headers.put("Cache-Control", immutable ? "public, max-age=31536000, immutable" : "no-cache");

        SonoraUtil.Range range = SonoraUtil.parseRange(header(request.getRequestHeaders(), "Range"), length);
        if (range == SonoraUtil.UNSATISFIABLE) {
            headers.put("Content-Range", "bytes */" + length);
            return new WebResourceResponse(mime, encoding, 416, "Range Not Satisfiable", headers,
                new ByteArrayInputStream(new byte[0]));
        }

        long start = 0, end = length - 1;
        int code = 200;
        String reason = "OK";
        if (range != null) {
            start = range.start;
            end = range.end;
            code = 206;
            reason = "Partial Content";
            headers.put("Content-Range", "bytes " + start + "-" + end + "/" + length);
        }
        long contentLength = length == 0 ? 0 : end - start + 1;
        headers.put("Content-Length", String.valueOf(contentLength));

        InputStream body = "HEAD".equals(request.getMethod())
            ? new ByteArrayInputStream(new byte[0])
            : source.open(start, contentLength);
        return new WebResourceResponse(mime, encoding, code, reason, headers, body);
    }

    private static Source fileSource(final File file) {
        return new Source() {
            @Override public long length() { return file.length(); }
            @Override public InputStream open(long start, long length) throws IOException {
                FileInputStream in = new FileInputStream(file);
                try {
                    if (start > 0) in.getChannel().position(start);
                } catch (IOException e) {
                    in.close();
                    throw e;
                }
                return new BoundedInputStream(in, length);
            }
        };
    }

    /** A granted-folder file. The size from the index is trusted when the provider does not report one. */
    private Source uriSource(final Uri uri, final long indexedSize) {
        return new Source() {
            @Override public long length() throws IOException {
                try (AssetFileDescriptor afd = resolver.openAssetFileDescriptor(uri, "r")) {
                    if (afd != null && afd.getLength() >= 0) return afd.getLength();
                } catch (RuntimeException ignored) { }
                if (indexedSize >= 0) return indexedSize;
                throw new IOException("Unknown length");
            }
            @Override public InputStream open(long start, long length) throws IOException {
                AssetFileDescriptor afd = resolver.openAssetFileDescriptor(uri, "r");
                if (afd == null) throw new IOException("Could not open file");
                FileInputStream in = afd.createInputStream(); // closing it closes the descriptor
                try {
                    long skip = afd.getStartOffset() + start;
                    if (skip > 0) {
                        try {
                            in.getChannel().position(skip);
                        } catch (IOException notSeekable) {
                            long left = skip;
                            while (left > 0) { long s = in.skip(left); if (s <= 0) throw notSeekable; left -= s; }
                        }
                    }
                } catch (IOException e) {
                    in.close();
                    throw e;
                }
                return new BoundedInputStream(in, length);
            }
        };
    }

    private static String header(Map<String, String> headers, String name) {
        if (headers == null) return null;
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(name)) return e.getValue();
        }
        return null;
    }

    private static WebResourceResponse status(int code, String reason) {
        return new WebResourceResponse("text/plain", "utf-8", code, reason, new HashMap<String, String>(),
            new ByteArrayInputStream(new byte[0]));
    }

    /** Stops reading after {@code limit} bytes so a ranged response never overruns its Content-Length. */
    private static final class BoundedInputStream extends FilterInputStream {
        private long remaining;

        BoundedInputStream(InputStream in, long limit) { super(in); this.remaining = limit; }

        @Override public int read() throws IOException {
            if (remaining <= 0) return -1;
            int b = super.read();
            if (b >= 0) remaining--;
            return b;
        }

        @Override public int read(byte[] buf, int off, int len) throws IOException {
            if (remaining <= 0) return -1;
            int n = super.read(buf, off, (int) Math.min(len, remaining));
            if (n > 0) remaining -= n;
            return n;
        }

        @Override public long skip(long n) throws IOException {
            long s = super.skip(Math.min(n, remaining));
            if (s > 0) remaining -= s;
            return s;
        }

        @Override public int available() throws IOException {
            return (int) Math.min(super.available(), remaining);
        }

        @Override public boolean markSupported() { return false; }
    }
}
