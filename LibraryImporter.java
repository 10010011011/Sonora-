package app.sonora.player;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.os.StatFs;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Brings audio into the library without ever routing audio bytes through JavaScript.
 * <ul>
 *   <li>{@link #run}: picked files. Only audio that is not already known is copied.</li>
 *   <li>{@link #scanTree}: a folder the user granted. Audio stays in place and is indexed; a second scan reads no audio.</li>
 *   <li>{@link #adopt}: a file that is already on disk (legacy migration).</li>
 * </ul>
 * "Known" means: same name/size/mtime, or the same content fingerprint under a new name (a rematch: the record,
 * its id and everything keyed by it are kept).
 */
final class LibraryImporter {
    interface Progress { void onProgress(int done, int total, String name); }

    private static final long SAFETY_MARGIN_BYTES = 64L * 1024 * 1024;
    private static final int MAX_COVER_PX = 600;
    private static final int THUMB_PX = 128;

    private static final class Entry {
        final Uri uri; final String name; final long size; final long lastModified; final String mime;
        String rel; // tree scans: path relative to the tree root
        Entry(Uri uri, String name, long size, long lastModified, String mime) {
            this.uri = uri; this.name = name; this.size = size; this.lastModified = lastModified; this.mime = mime;
        }
    }

    private static final class Result {
        final JSONArray added = new JSONArray();
        final JSONArray rematched = new JSONArray();
        final JSONArray updated = new JSONArray();
        final JSONArray failed = new JSONArray();
        int skipped, unmatched, removed;
        boolean outOfSpace;
    }

    private final Context context;
    private final ContentResolver resolver;
    private final LibraryStore store;
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private JSONObject lastRecord;

    LibraryImporter(Context context, LibraryStore store) {
        this.context = context.getApplicationContext();
        this.resolver = this.context.getContentResolver();
        this.store = store;
    }

    void cancel() { cancelled.set(true); }

    /* ================================================================== picked files */

    JSONObject run(List<Uri> uris, Progress progress) throws JSONException {
        cancelled.set(false);
        Result r = new Result();
        List<Entry> audio = new ArrayList<>();
        List<Entry> lyrics = new ArrayList<>();

        for (Uri uri : uris) {
            Entry e = describe(uri);
            if (e == null || e.name == null) { fail(r.failed, String.valueOf(uri), uri, "Could not read file details"); continue; }
            if (SonoraUtil.isAudioName(e.name) || (e.mime != null && e.mime.startsWith("audio/"))) audio.add(e);
            else if (SonoraUtil.isLyricName(e.name)) lyrics.add(e);
            else fail(r.failed, e.name, uri, "Unsupported file type");
        }
        audio.sort((a, b) -> SonoraUtil.naturalCompare(a.name, b.name));

        int total = audio.size() + lyrics.size();
        int done = 0, sinceCommit = 0;
        for (Entry e : audio) {
            if (cancelled.get() || r.outOfSpace) break;
            progress.onProgress(done, total, e.name);
            try {
                if (ingestPicked(e, r) && ++sinceCommit >= 25) { store.commit(); sinceCommit = 0; }
            } catch (IOException ex) {
                String msg = String.valueOf(ex.getMessage());
                if (msg.contains("ENOSPC") || msg.toLowerCase().contains("no space")) {
                    fail(r.failed, e.name, e.uri, "Not enough free storage");
                    r.outOfSpace = true;
                } else {
                    fail(r.failed, e.name, e.uri, "Could not copy: " + msg);
                }
            } catch (RuntimeException ex) {
                fail(r.failed, e.name, e.uri, "Could not import: " + ex.getMessage());
            }
            done++;
        }

        Set<String> touched = new HashSet<>();
        for (Entry l : lyrics) {
            if (cancelled.get()) break;
            progress.onProgress(done, total, l.name);
            try {
                List<JSONObject> matches = store.findByBaseKey(SonoraUtil.baseKey(l.name));
                if (matches.isEmpty()) { r.unmatched++; done++; continue; }
                for (JSONObject track : matches) attachSidecar(l, track, r, touched);
            } catch (IOException | RuntimeException ex) {
                fail(r.failed, l.name, l.uri, "Could not attach lyrics: " + ex.getMessage());
            }
            done++;
        }

        store.commit();
        progress.onProgress(total, total, "");
        return result(r);
    }

    /** Imports exactly one file (the "open with" path). Returns the library record for it, or null. */
    JSONObject importOne(Uri uri) throws JSONException, IOException {
        cancelled.set(false);
        Entry e = describe(uri);
        if (e == null || e.name == null) return null;
        Result r = new Result();
        lastRecord = null;
        ingestPicked(e, r);
        JSONObject rec = lastRecord;
        if (rec == null && r.added.length() > 0) rec = r.added.optJSONObject(0);
        if (rec == null && r.rematched.length() > 0) rec = r.rematched.optJSONObject(0);
        store.commit();
        return rec;
    }

    /** @return true when the index changed. */
    private boolean ingestPicked(Entry e, Result r) throws IOException, JSONException {
        String srcId = SonoraUtil.idFor(e.name, e.size, e.lastModified);
        JSONObject known = e.size > 0 ? store.findBySrc(srcId) : null;
        if (known != null) { r.skipped++; lastRecord = known; return false; }

        String fp = e.size > 0 ? fingerprintUri(e.uri, e.size) : null;
        if (fp != null && absorb(fp, srcId, e, r)) return true;

        if (e.size > 0 && freeBytes() < e.size + SAFETY_MARGIN_BYTES) {
            fail(r.failed, e.name, e.uri, "Not enough free storage");
            r.outOfSpace = true;
            return false;
        }
        String tmpKey = SonoraUtil.sha1Hex(srcId + "#" + System.nanoTime(), 20);
        File tmp = new File(store.mediaDir, tmpKey + ".part");
        long copied = copy(e.uri, tmp);
        long size = e.size > 0 ? e.size : copied;
        if (e.size <= 0) {
            srcId = SonoraUtil.idFor(e.name, size, e.lastModified);
            JSONObject again = store.findBySrc(srcId);
            if (again != null) { tmp.delete(); r.skipped++; lastRecord = again; return false; }
        }
        if (fp == null) {
            fp = fingerprintFile(tmp);
            if (fp != null && absorb(fp, srcId, new Entry(e.uri, e.name, size, e.lastModified, e.mime), r)) {
                tmp.delete();
                return true;
            }
        }
        String id = SonoraUtil.newId();
        String key = SonoraUtil.sha1Hex(id, 20);
        String ext = SonoraUtil.extOf(e.name);
        File dest = new File(store.mediaDir, key + "." + ext);
        if (dest.exists()) dest.delete();
        if (!tmp.renameTo(dest)) { tmp.delete(); throw new IOException("Could not finalize copy"); }

        JSONObject rec = buildRecord(id, key, srcId, fp, e, size, dest, null);
        store.put(rec);
        r.added.put(rec);
        lastRecord = rec;
        return true;
    }

    /**
     * Same audio already in the library? Copied records learn the new name (rematch); a record that lives in a
     * granted folder just counts as "already in library". @return true when no copy is needed.
     */
    private boolean absorb(String fp, String srcId, Entry e, Result r) throws JSONException {
        JSONObject same = store.findByFp(fp);
        if (same == null) return false;
        lastRecord = same;
        if ("tree".equals(same.optString("storage", "copy"))) { r.skipped++; return true; }
        store.rematch(same, srcId, e.name, e.name, e.size, e.lastModified);
        r.rematched.put(same);
        return true;
    }

    private void attachSidecar(Entry l, JSONObject track, Result r, Set<String> touched) throws IOException, JSONException {
        String trackId = track.getString("id");
        String ext = SonoraUtil.extOf(l.name);
        String file = SonoraUtil.sha1Hex(trackId, 20) + "." + ext;
        boolean same = file.equals(track.optString("sidecarFile"))
            && track.optLong("sidecarSize", -1) == l.size
            && track.optLong("sidecarLastModified", -1) == l.lastModified;
        if (same) return;
        copy(l.uri, new File(store.lyricsDir, file));
        String old = track.optString("sidecarFile", "");
        if (!old.isEmpty() && !old.equals(file)) new File(store.lyricsDir, old).delete();
        track.put("sidecarFile", file);
        track.put("sidecarName", l.name);
        track.put("sidecarSize", l.size);
        track.put("sidecarLastModified", l.lastModified);
        store.markDirty();
        boolean isNew = false;
        for (int i = 0; i < r.added.length(); i++) if (trackId.equals(r.added.optJSONObject(i).optString("id"))) isNew = true;
        if (!isNew && touched.add(trackId)) r.updated.put(track);
    }

    /* ================================================================== folders (SAF trees) */

    /** Walks a granted tree, indexing new audio in place and dropping records whose file is gone. */
    JSONObject scanTree(String treeUriString, Progress progress) throws JSONException {
        cancelled.set(false);
        Result r = new Result();
        Uri tree = Uri.parse(treeUriString);
        List<Entry> audio = new ArrayList<>();
        List<Entry> lyrics = new ArrayList<>();
        boolean walkOk = walk(tree, audio, lyrics, r);

        // Pass 1: everything already indexed under the same path+size+mtime is untouched (no audio is read).
        Set<String> seen = new HashSet<>();
        List<Entry> fresh = new ArrayList<>();
        for (Entry e : audio) {
            JSONObject rec = store.findBySrc(treeSrcId(treeUriString, e));
            if (rec != null) { seen.add(rec.optString("id")); r.skipped++; } else fresh.add(e);
        }

        // Pass 2: new files. A file whose bytes match a record that vanished from this tree was moved/renamed.
        int total = fresh.size() + lyrics.size();
        int done = 0, sinceCommit = 0;
        for (Entry e : fresh) {
            if (cancelled.get()) break;
            progress.onProgress(done, total, e.name);
            try {
                String srcId = treeSrcId(treeUriString, e);
                String fp = fingerprintUri(e.uri, e.size);
                JSONObject same = fp == null ? null : store.findByFp(fp);
                boolean sameIsTree = same != null && "tree".equals(same.optString("storage", "copy"));
                boolean sameTree = sameIsTree && treeUriString.equals(same.optString("treeUri"));
                if (sameTree && !seen.contains(same.optString("id"))) {
                    same.put("uri", e.uri.toString());
                    store.rematch(same, srcId, e.name, e.rel, e.size, e.lastModified);
                    seen.add(same.optString("id"));
                    r.rematched.put(same);
                } else if (same != null && !sameIsTree) {
                    r.skipped++; // already in the library as a copied file
                } else {
                    String id = SonoraUtil.newId();
                    JSONObject rec = buildRecord(id, SonoraUtil.sha1Hex(id, 20), srcId, fp, e, e.size, null, treeUriString);
                    store.put(rec);
                    seen.add(id);
                    r.added.put(rec);
                }
                if (++sinceCommit >= 50) { store.commit(); sinceCommit = 0; }
            } catch (RuntimeException ex) {
                fail(r.failed, e.name, e.uri, "Could not index: " + ex.getMessage());
            }
            done++;
        }

        // Records whose file disappeared. Only when the whole walk worked, so an unmounted card never wipes the index.
        boolean suspiciouslyEmpty = audio.isEmpty() && !store.treeRecords(treeUriString).isEmpty();
        if (walkOk && !cancelled.get() && !suspiciouslyEmpty) {
            Set<String> gone = new HashSet<>();
            for (JSONObject rec : store.treeRecords(treeUriString)) if (!seen.contains(rec.optString("id"))) gone.add(rec.optString("id"));
            if (!gone.isEmpty()) { store.removeIds(gone); r.removed = gone.size(); }
        } else if (suspiciouslyEmpty) {
            fail(r.failed, "Folder", tree, "No audio found (or the folder is unavailable); nothing was removed");
        }

        Set<String> touched = new HashSet<>();
        for (Entry l : lyrics) {
            if (cancelled.get()) break;
            progress.onProgress(done, total, l.name);
            try {
                String dir = SonoraUtil.parentDir(l.rel);
                for (JSONObject track : store.findInTree(treeUriString, dir, SonoraUtil.baseKey(l.name))) attachSidecar(l, track, r, touched);
            } catch (IOException | RuntimeException ex) {
                fail(r.failed, l.name, l.uri, "Could not attach lyrics: " + ex.getMessage());
            }
            done++;
        }

        store.commit();
        progress.onProgress(total, total, "");
        JSONObject out = result(r);
        out.put("removed", r.removed);
        out.put("complete", walkOk && !cancelled.get());
        return out;
    }

    private static String treeSrcId(String treeUri, Entry e) {
        return "t:" + SonoraUtil.sha1Hex(treeUri, 8) + ":" + e.rel + "|" + e.size + "|" + e.lastModified;
    }

    /** Lists every audio/lyric file under the tree with one cursor per directory (no per-file provider calls). */
    private boolean walk(Uri tree, List<Entry> audio, List<Entry> lyrics, Result r) {
        boolean ok = true;
        String rootId = DocumentsContract.getTreeDocumentId(tree);
        ArrayDeque<String[]> stack = new ArrayDeque<>();
        stack.push(new String[] { rootId, "" });
        String[] cols = {
            DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        };
        while (!stack.isEmpty() && !cancelled.get()) {
            String[] dir = stack.pop();
            Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, dir[0]);
            try (Cursor c = resolver.query(children, cols, null, null, null)) {
                if (c == null) { ok = false; continue; }
                while (c.moveToNext()) {
                    String docId = c.getString(0), name = c.getString(1), mime = c.getString(2);
                    if (docId == null || name == null) continue;
                    long size = c.isNull(3) ? -1 : c.getLong(3);
                    long modified = c.isNull(4) ? 0 : c.getLong(4);
                    String rel = dir[1].isEmpty() ? name : dir[1] + "/" + name;
                    if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                        if (!name.startsWith(".")) stack.push(new String[] { docId, rel });
                        continue;
                    }
                    Entry e = new Entry(DocumentsContract.buildDocumentUriUsingTree(tree, docId), name, size, modified, mime);
                    e.rel = rel;
                    if (SonoraUtil.isAudioName(name) || (mime != null && mime.startsWith("audio/"))) audio.add(e);
                    else if (SonoraUtil.isLyricName(name)) lyrics.add(e);
                }
            } catch (RuntimeException ex) {
                ok = false;
                fail(r.failed, dir[1].isEmpty() ? "Folder" : dir[1], tree, "Could not list folder: " + ex.getMessage());
            }
        }
        audio.sort((a, b) -> SonoraUtil.naturalCompare(a.rel, b.rel));
        return ok;
    }

    /* ================================================================== files already on disk (legacy) */

    /**
     * Takes an existing file into the library under {@code id} (the old "name|size|mtime" id, so likes and lyrics
     * keyed by it keep working). The file is moved when possible. Returns the record, or null when already known.
     */
    JSONObject adopt(File src, String name, long size, long lastModified, String mime, String id) throws IOException, JSONException {
        if (store.findBySrc(id) != null) { src.delete(); return null; }
        String fp = fingerprintFile(src);
        if (fp != null && store.findByFp(fp) != null) { src.delete(); return null; }
        String key = SonoraUtil.sha1Hex(id, 20);
        String ext = SonoraUtil.extOf(name);
        File dest = new File(store.mediaDir, key + "." + ext);
        if (dest.exists()) dest.delete();
        if (!src.renameTo(dest)) {
            copyFile(src, dest);
            src.delete();
        }
        Entry e = new Entry(Uri.fromFile(dest), name, size, lastModified, mime);
        JSONObject rec = buildRecord(id, key, id, fp, e, size, dest, null);
        store.put(rec);
        return rec;
    }

    /* ================================================================== helpers */

    private JSONObject result(Result r) throws JSONException {
        JSONObject out = new JSONObject();
        out.put("added", r.added);
        out.put("updated", r.updated);
        out.put("rematched", r.rematched);
        out.put("skipped", r.skipped);
        out.put("unmatchedLyrics", r.unmatched);
        out.put("failed", r.failed);
        out.put("cancelled", cancelled.get());
        return out;
    }

    private static void fail(JSONArray failed, String name, Uri uri, String reason) {
        try {
            JSONObject o = new JSONObject().put("name", name).put("reason", reason);
            if (uri != null) o.put("uri", uri.toString());
            failed.put(o);
        } catch (JSONException ignored) { }
    }

    private long freeBytes() {
        return new StatFs(store.mediaDir.getAbsolutePath()).getAvailableBytes();
    }

    private Entry describe(Uri uri) {
        String name = null;
        long size = -1, modified = 0;
        Cursor c = null;
        try {
            try {
                c = resolver.query(uri, new String[] {
                    OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED
                }, null, null, null);
            } catch (RuntimeException unsupportedColumn) {
                c = resolver.query(uri, new String[] { OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE }, null, null, null);
            }
            if (c != null && c.moveToFirst()) {
                int n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                int s = c.getColumnIndex(OpenableColumns.SIZE);
                int m = c.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED);
                if (n >= 0 && !c.isNull(n)) name = c.getString(n);
                if (s >= 0 && !c.isNull(s)) size = c.getLong(s);
                if (m >= 0 && !c.isNull(m)) modified = c.getLong(m);
            }
        } catch (RuntimeException ignored) {
            // fall through with whatever we have
        } finally {
            if (c != null) c.close();
        }
        if (name == null) name = uri.getLastPathSegment();
        return new Entry(uri, name, size, modified, resolver.getType(uri));
    }

    private long copy(Uri from, File to) throws IOException {
        long total = 0;
        try (InputStream in = resolver.openInputStream(from);
             OutputStream out = new FileOutputStream(to)) {
            if (in == null) throw new IOException("Could not open file");
            byte[] buffer = new byte[256 * 1024];
            int n;
            while ((n = in.read(buffer)) != -1) {
                out.write(buffer, 0, n);
                total += n;
            }
            out.flush();
        } catch (IOException | RuntimeException e) {
            to.delete();
            throw e instanceof IOException ? (IOException) e : new IOException(e.getMessage(), e);
        }
        return total;
    }

    private static void copyFile(File from, File to) throws IOException {
        try (InputStream in = new FileInputStream(from); OutputStream out = new FileOutputStream(to)) {
            byte[] buffer = new byte[256 * 1024];
            int n;
            while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
        } catch (IOException e) {
            to.delete();
            throw e;
        }
    }

    /* ---------- fingerprints: size + SHA-1 of the first and last 64 KB (reads at most 128 KB) ---------- */

    private String fingerprintUri(Uri uri, long knownSize) {
        if (knownSize <= 0) return null;
        try (ParcelFileDescriptor pfd = resolver.openFileDescriptor(uri, "r")) {
            if (pfd == null) return null;
            try (FileInputStream in = new ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.dup(pfd.getFileDescriptor()));
                 FileChannel ch = in.getChannel()) {
                return fingerprintChannel(ch, knownSize);
            }
        } catch (IOException | RuntimeException e) {
            return null; // provider gave us a non-seekable stream: the caller falls back to fingerprinting the copy
        }
    }

    static String fingerprintFile(File f) {
        try (RandomAccessFile raf = new RandomAccessFile(f, "r"); FileChannel ch = raf.getChannel()) {
            long size = ch.size();
            return size <= 0 ? null : fingerprintChannel(ch, size);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static String fingerprintChannel(FileChannel ch, long size) throws IOException {
        int span = SonoraUtil.fpSpan(size);
        byte[] head = readAt(ch, 0, span);
        byte[] tail = readAt(ch, size - span, span);
        return SonoraUtil.fingerprint(size, head, tail);
    }

    private static byte[] readAt(FileChannel ch, long pos, int len) throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(len);
        long p = pos;
        while (buf.hasRemaining()) {
            int n = ch.read(buf, p);
            if (n < 0) throw new IOException("Short read");
            p += n;
        }
        return buf.array();
    }

    /* ---------- records, covers, thumbnails ---------- */

    private JSONObject buildRecord(String id, String key, String srcId, String fp, Entry e, long size, File file, String treeUri)
            throws JSONException {
        String name = e.name;
        String[] fallback = SonoraUtil.titleFromName(name);
        String title = null, artist = null, album = null, coverName = null;
        double durationSec = -1;

        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            if (file != null) r.setDataSource(file.getAbsolutePath()); else r.setDataSource(context, e.uri);
            title = clean(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE));
            artist = clean(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST));
            if (artist == null) artist = clean(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST));
            album = clean(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM));
            String dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (dur != null) durationSec = Long.parseLong(dur) / 1000.0;
            byte[] pic = r.getEmbeddedPicture();
            if (pic != null && pic.length > 0) coverName = writeCover(pic);
        } catch (RuntimeException ignored) {
            // Unreadable tags are fine: the filename fallback below still gives a usable row.
        } finally {
            try { r.release(); } catch (Exception ignored) { }
        }

        JSONObject rec = new JSONObject();
        rec.put("id", id);
        rec.put("key", key);
        rec.put("fileName", name);
        String ext = SonoraUtil.extOf(name);
        String mime = e.mime != null && e.mime.startsWith("audio/") ? e.mime : SonoraUtil.mimeForExt(ext);
        rec.put("mime", mime);
        rec.put("size", size);
        rec.put("lastModified", e.lastModified);
        if (treeUri != null) {
            rec.put("storage", "tree");
            rec.put("uri", e.uri.toString());
            rec.put("treeUri", treeUri);
            rec.put("path", e.rel);
        } else {
            rec.put("file", file.getName());
            rec.put("path", name);
        }
        if (fp != null) rec.put("fp", fp);
        rec.put("srcIds", new JSONArray().put(srcId));
        String dirName = treeUri == null ? "" : SonoraUtil.parentDir(e.rel);
        if (!dirName.isEmpty()) dirName = dirName.substring(dirName.lastIndexOf('/') + 1);
        rec.put("title", title != null ? title : fallback[1]);
        rec.put("artist", artist != null ? artist : (fallback[0].isEmpty() ? "Unknown artist" : fallback[0]));
        rec.put("album", album != null ? album : (dirName.isEmpty() ? "Local audio" : dirName));
        if (durationSec > 0) rec.put("duration", durationSec);
        if (coverName != null) rec.put("coverName", coverName);
        rec.put("addedAt", System.currentTimeMillis());
        rec.put("tagsDone", false); // JS still reads ReplayGain + embedded lyrics lazily
        return rec;
    }

    private static String clean(String v) {
        if (v == null) return null;
        String t = v.trim();
        return t.isEmpty() ? null : t;
    }

    /** Down-scales embedded art to a small JPEG (plus a list thumbnail), content-addressed so albums share one file. */
    private String writeCover(byte[] bytes) {
        try {
            String name = SonoraUtil.sha1Hex(bytes, 16) + ".jpg";
            File out = new File(store.coverDir, name);
            if (out.isFile() && out.length() > 0) {
                makeThumb(store, name);
                return name;
            }

            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
            int sample = 1;
            while (Math.max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_COVER_PX) sample *= 2;
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            Bitmap bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, opts);
            if (bmp == null) return null;
            int max = Math.max(bmp.getWidth(), bmp.getHeight());
            if (max > MAX_COVER_PX) {
                float scale = MAX_COVER_PX / (float) max;
                Bitmap scaled = Bitmap.createScaledBitmap(bmp, Math.round(bmp.getWidth() * scale), Math.round(bmp.getHeight() * scale), true);
                if (scaled != bmp) bmp.recycle();
                bmp = scaled;
            }
            File tmp = new File(store.coverDir, name + ".part");
            try (FileOutputStream fos = new FileOutputStream(tmp)) {
                bmp.compress(Bitmap.CompressFormat.JPEG, 88, fos);
            }
            bmp.recycle();
            if (!tmp.renameTo(out)) return null;
            makeThumb(store, name);
            return name;
        } catch (IOException | RuntimeException | OutOfMemoryError e) {
            return null;
        }
    }

    /** 128 px thumbnail of an existing cover, for list rows. Safe to call repeatedly. */
    static void makeThumb(LibraryStore store, String name) {
        File out = new File(store.thumbDir, name);
        if (out.isFile() && out.length() > 0) return;
        File cover = new File(store.coverDir, name);
        if (!cover.isFile()) return;
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(cover.getAbsolutePath(), bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return;
            int sample = 1;
            while (Math.max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= THUMB_PX) sample *= 2;
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            Bitmap bmp = BitmapFactory.decodeFile(cover.getAbsolutePath(), opts);
            if (bmp == null) return;
            int max = Math.max(bmp.getWidth(), bmp.getHeight());
            if (max > THUMB_PX) {
                float scale = THUMB_PX / (float) max;
                Bitmap scaled = Bitmap.createScaledBitmap(bmp, Math.max(1, Math.round(bmp.getWidth() * scale)), Math.max(1, Math.round(bmp.getHeight() * scale)), true);
                if (scaled != bmp) bmp.recycle();
                bmp = scaled;
            }
            File tmp = new File(store.thumbDir, name + ".part");
            try (FileOutputStream fos = new FileOutputStream(tmp)) {
                bmp.compress(Bitmap.CompressFormat.JPEG, 82, fos);
            }
            bmp.recycle();
            if (!tmp.renameTo(out)) tmp.delete();
        } catch (IOException | RuntimeException | OutOfMemoryError ignored) {
            // The list falls back to the full cover.
        }
    }
}
