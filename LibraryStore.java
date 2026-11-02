package app.sonora.player;

import android.content.Context;
import android.net.Uri;
import android.util.AtomicFile;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Metadata index for the on-device library. Two kinds of record share one shape:
 * <ul>
 *   <li>copy: the audio lives in files/sonora/media (picked files, legacy library);</li>
 *   <li>tree: the audio stays where the user keeps it and is read through a persisted SAF tree URI.</li>
 * </ul>
 * Track ids are opaque strings. Records created before stable ids keep their old "name|size|mtime" id so
 * likes and lyrics keyed by it survive; new records get a UUID. {@code fp} (size + SHA-1 of the first and
 * last 64 KB) is what recognises the same audio under a new name. The index is rewritten atomically and only
 * when something changed. Audio bytes are only ever streamed natively.
 */
final class LibraryStore {
    private static final Pattern KEY = Pattern.compile("^[0-9a-f]{20}$");
    private static final Pattern COVER = Pattern.compile("^[0-9a-f]{16}\\.jpg$");
    private static final Pattern LYRIC_FILE = Pattern.compile("^[0-9a-f]{20}\\.[a-z0-9]{1,6}$");
    /** Fields JavaScript is allowed to change after import. Everything else is native-owned. */
    private static final String[] PATCHABLE = {
        "rgTrack", "rgAlbum", "embeddedLyrics", "tagsDone", "title", "artist", "album", "edited",
        "playCount", "lastPlayedAt"
    };

    /** What the WebView request handler needs to stream one track. */
    static final class MediaSource {
        final File file; final Uri uri; final String mime; final long size;
        MediaSource(File file, Uri uri, String mime, long size) { this.file = file; this.uri = uri; this.mime = mime; this.size = size; }
    }

    private static LibraryStore instance;

    static synchronized LibraryStore get(Context context) {
        if (instance == null) instance = new LibraryStore(context.getApplicationContext());
        return instance;
    }

    final File mediaDir;
    final File coverDir;
    final File thumbDir;
    final File lyricsDir;
    private final AtomicFile indexFile;
    private final AtomicFile stateFile;
    private final AtomicFile userFile;
    private final AtomicFile treesFile;
    private final LinkedHashMap<String, JSONObject> byId = new LinkedHashMap<>();
    private final HashMap<String, JSONObject> byKey = new HashMap<>();
    private final HashMap<String, JSONObject> byFp = new HashMap<>();
    private final HashMap<String, JSONObject> bySrc = new HashMap<>();
    private final Object lock = new Object();
    private JSONArray trees = new JSONArray();
    private boolean dirty;

    private LibraryStore(Context context) {
        File root = new File(context.getFilesDir(), "sonora");
        mediaDir = new File(root, "media");
        coverDir = new File(root, "covers");
        thumbDir = new File(root, "thumbs");
        lyricsDir = new File(root, "lyrics");
        mediaDir.mkdirs();
        coverDir.mkdirs();
        thumbDir.mkdirs();
        lyricsDir.mkdirs();
        indexFile = new AtomicFile(new File(root, "library.json"));
        stateFile = new AtomicFile(new File(root, "state.json"));
        userFile = new AtomicFile(new File(root, "userdata.json"));
        treesFile = new AtomicFile(new File(root, "trees.json"));
        load();
    }

    private static boolean isTree(JSONObject rec) { return "tree".equals(rec.optString("storage", "copy")); }

    private void load() {
        synchronized (lock) {
            try {
                if (treesFile.getBaseFile().exists()) {
                    trees = new JSONArray(new String(treesFile.readFully(), StandardCharsets.UTF_8));
                }
            } catch (IOException | JSONException e) {
                trees = new JSONArray();
            }
            try {
                if (!indexFile.getBaseFile().exists()) return;
                JSONArray arr = new JSONArray(new String(indexFile.readFully(), StandardCharsets.UTF_8));
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject rec = arr.optJSONObject(i);
                    if (rec == null) continue;
                    String id = rec.optString("id", "");
                    String key = rec.optString("key", "");
                    if (id.isEmpty() || !KEY.matcher(key).matches()) continue;
                    if (!isTree(rec) && !new File(mediaDir, rec.optString("file", "")).isFile()) {
                        dirty = true; // audio file vanished: drop the ghost entry
                        continue;
                    }
                    index(rec);
                }
                if (dirty) commit();
            } catch (IOException | JSONException e) {
                // A corrupt index must not brick the app; start empty. Audio files stay on disk.
                byId.clear(); byKey.clear(); byFp.clear(); bySrc.clear();
            }
        }
    }

    /** Source identities ("name|size|mtime") a record answers to: its own legacy id plus every one it was matched under. */
    private static List<String> srcIdsOf(JSONObject rec) {
        List<String> out = new ArrayList<>();
        String id = rec.optString("id", "");
        if (id.indexOf('|') > 0) out.add(id);
        JSONArray arr = rec.optJSONArray("srcIds");
        if (arr != null) for (int i = 0; i < arr.length(); i++) {
            String s = arr.optString(i, "");
            if (!s.isEmpty() && !out.contains(s)) out.add(s);
        }
        return out;
    }

    private void index(JSONObject rec) {
        byId.put(rec.optString("id"), rec);
        byKey.put(rec.optString("key"), rec);
        String fp = rec.optString("fp", "");
        if (!fp.isEmpty() && !byFp.containsKey(fp)) byFp.put(fp, rec); // first record owns the fingerprint
        for (String s : srcIdsOf(rec)) bySrc.put(s, rec);
    }

    private void unindex(JSONObject rec) {
        byId.remove(rec.optString("id"));
        byKey.remove(rec.optString("key"));
        String fp = rec.optString("fp", "");
        if (!fp.isEmpty() && byFp.get(fp) == rec) byFp.remove(fp);
        for (String s : srcIdsOf(rec)) if (bySrc.get(s) == rec) bySrc.remove(s);
    }

    boolean has(String id) {
        synchronized (lock) { return byId.containsKey(id); }
    }

    JSONObject find(String id) {
        synchronized (lock) { return byId.get(id); }
    }

    JSONObject findBySrc(String srcId) {
        synchronized (lock) { return bySrc.get(srcId); }
    }

    JSONObject findByFp(String fp) {
        if (fp == null) return null;
        synchronized (lock) { return byFp.get(fp); }
    }

    /** Add or replace a record in memory. Call commit() to persist. */
    void put(JSONObject rec) throws JSONException {
        synchronized (lock) {
            JSONObject old = byId.get(rec.getString("id"));
            if (old != null) unindex(old);
            index(rec);
            dirty = true;
        }
    }

    /**
     * The same audio turned up under a different name or place: keep the record (and so its id, likes, lyrics,
     * ReplayGain, tag edits, play count) and just learn the new source identity and file name.
     */
    void rematch(JSONObject rec, String srcId, String fileName, String path, long size, long lastModified) throws JSONException {
        synchronized (lock) {
            unindex(rec);
            JSONArray ids = rec.optJSONArray("srcIds");
            if (ids == null) {
                ids = new JSONArray();
                for (String s : srcIdsOf(rec)) ids.put(s);
                rec.put("srcIds", ids);
            }
            boolean known = false;
            for (int i = 0; i < ids.length(); i++) if (srcId.equals(ids.optString(i))) known = true;
            if (!known) ids.put(srcId);
            rec.put("fileName", fileName);
            rec.put("path", path);
            rec.put("size", size);
            rec.put("lastModified", lastModified);
            index(rec);
            dirty = true;
        }
    }

    void markDirty() {
        synchronized (lock) { dirty = true; }
    }

    /** Persist the index if (and only if) it changed. Never touches audio files. */
    void commit() {
        synchronized (lock) {
            if (!dirty) return;
            FileOutputStream out = null;
            try {
                JSONArray arr = new JSONArray();
                for (JSONObject rec : byId.values()) arr.put(rec);
                out = indexFile.startWrite();
                out.write(arr.toString().getBytes(StandardCharsets.UTF_8));
                indexFile.finishWrite(out);
                dirty = false;
            } catch (IOException e) {
                if (out != null) indexFile.failWrite(out);
            }
        }
    }

    String listJson() {
        synchronized (lock) {
            JSONArray arr = new JSONArray();
            for (JSONObject rec : byId.values()) arr.put(rec);
            return arr.toString();
        }
    }

    int count() {
        synchronized (lock) { return byId.size(); }
    }

    long totalBytes() {
        synchronized (lock) {
            long sum = 0;
            for (JSONObject rec : byId.values()) if (!isTree(rec)) sum += Math.max(0, rec.optLong("size", 0));
            return sum;
        }
    }

    /** Records whose file name (without extension) matches, for lyric sidecar pairing. */
    List<JSONObject> findByBaseKey(String baseKey) {
        synchronized (lock) {
            List<JSONObject> out = new ArrayList<>();
            for (JSONObject rec : byId.values()) {
                if (SonoraUtil.baseKey(rec.optString("fileName", "")).equals(baseKey)) out.add(rec);
            }
            return out;
        }
    }

    /** Same as {@link #findByBaseKey} but restricted to one folder of one tree (relative directory path). */
    List<JSONObject> findInTree(String treeUri, String dir, String baseKey) {
        synchronized (lock) {
            List<JSONObject> out = new ArrayList<>();
            for (JSONObject rec : byId.values()) {
                if (!isTree(rec) || !treeUri.equals(rec.optString("treeUri"))) continue;
                if (!SonoraUtil.parentDir(rec.optString("path", "")).equals(dir)) continue;
                if (SonoraUtil.baseKey(rec.optString("fileName", "")).equals(baseKey)) out.add(rec);
            }
            return out;
        }
    }

    List<JSONObject> treeRecords(String treeUri) {
        synchronized (lock) {
            List<JSONObject> out = new ArrayList<>();
            for (JSONObject rec : byId.values()) if (isTree(rec) && treeUri.equals(rec.optString("treeUri"))) out.add(rec);
            return out;
        }
    }

    void patch(JSONArray patches) {
        synchronized (lock) {
            for (int i = 0; i < patches.length(); i++) {
                JSONObject p = patches.optJSONObject(i);
                if (p == null) continue;
                JSONObject rec = byId.get(p.optString("id", ""));
                if (rec == null) continue;
                for (String field : PATCHABLE) {
                    if (!p.has(field)) continue;
                    try {
                        if (p.isNull(field)) rec.remove(field); else rec.put(field, p.get(field));
                        dirty = true;
                    } catch (JSONException ignored) { }
                }
            }
        }
        commit();
    }

    /** Drops records. Copied audio is deleted; audio that stays in the user's own folder is never touched. */
    void remove(JSONArray ids) {
        Set<String> list = new HashSet<>();
        for (int i = 0; i < ids.length(); i++) list.add(ids.optString(i, ""));
        removeIds(list);
    }

    void removeIds(Set<String> ids) {
        Set<String> coverCandidates = new HashSet<>();
        synchronized (lock) {
            for (String id : ids) {
                JSONObject rec = byId.get(id);
                if (rec == null) continue;
                unindex(rec);
                String file = rec.optString("file", "");
                if (!isTree(rec) && !file.isEmpty()) new File(mediaDir, file).delete();
                String sidecar = rec.optString("sidecarFile", "");
                if (!sidecar.isEmpty() && LYRIC_FILE.matcher(sidecar).matches()) new File(lyricsDir, sidecar).delete();
                String cover = rec.optString("coverName", "");
                if (!cover.isEmpty()) coverCandidates.add(cover);
                dirty = true;
            }
            // Covers are content-addressed and shared between tracks of an album: only drop unreferenced ones.
            for (JSONObject rec : byId.values()) coverCandidates.remove(rec.optString("coverName", ""));
            for (String name : coverCandidates) {
                if (!COVER.matcher(name).matches()) continue;
                new File(coverDir, name).delete();
                new File(thumbDir, name).delete();
            }
        }
        commit();
    }

    /** Records with copied audio and no fingerprint yet (libraries created before stable ids). */
    List<JSONObject> withoutFingerprint() {
        synchronized (lock) {
            List<JSONObject> out = new ArrayList<>();
            for (JSONObject rec : byId.values()) {
                if (!isTree(rec) && rec.optString("fp", "").isEmpty()) out.add(rec);
            }
            return out;
        }
    }

    /** Sets a fingerprint on an existing record (backfill). A second record with the same bytes stays separate. */
    void setFingerprint(JSONObject rec, String fp) throws JSONException {
        synchronized (lock) {
            rec.put("fp", fp);
            if (!byFp.containsKey(fp)) byFp.put(fp, rec);
            dirty = true;
        }
    }

    /** Distinct cover names that have no thumbnail yet. */
    List<String> coversWithoutThumb() {
        synchronized (lock) {
            Set<String> seen = new HashSet<>();
            List<String> out = new ArrayList<>();
            for (JSONObject rec : byId.values()) {
                String c = rec.optString("coverName", "");
                if (c.isEmpty() || !seen.add(c)) continue;
                if (!new File(thumbDir, c).isFile() && new File(coverDir, c).isFile()) out.add(c);
            }
            return out;
        }
    }

    /* ---------- lookups used by the WebView request interceptor ---------- */

    MediaSource mediaSource(String key) {
        if (!KEY.matcher(key).matches()) return null;
        synchronized (lock) {
            JSONObject rec = byKey.get(key);
            if (rec == null) return null;
            String mime = rec.optString("mime", "application/octet-stream");
            long size = rec.optLong("size", -1);
            if (isTree(rec)) {
                String uri = rec.optString("uri", "");
                return uri.isEmpty() ? null : new MediaSource(null, Uri.parse(uri), mime, size);
            }
            return new MediaSource(new File(mediaDir, rec.optString("file", "")), null, mime, size);
        }
    }

    File coverFile(String name) {
        return COVER.matcher(name).matches() ? new File(coverDir, name) : null;
    }

    /** Thumbnail, falling back to the full cover while the thumbnail is still being generated. */
    File thumbFile(String name) {
        if (!COVER.matcher(name).matches()) return null;
        File t = new File(thumbDir, name);
        return t.isFile() ? t : new File(coverDir, name);
    }

    File lyricsFile(String name) {
        return LYRIC_FILE.matcher(name).matches() ? new File(lyricsDir, name) : null;
    }

    /* ---------- folders (SAF trees) ---------- */

    String treesJson() {
        synchronized (lock) { return trees.toString(); }
    }

    JSONObject findTree(String uri) {
        synchronized (lock) {
            for (int i = 0; i < trees.length(); i++) {
                JSONObject t = trees.optJSONObject(i);
                if (t != null && uri.equals(t.optString("uri"))) return t;
            }
            return null;
        }
    }

    List<JSONObject> allTrees() {
        synchronized (lock) {
            List<JSONObject> out = new ArrayList<>();
            for (int i = 0; i < trees.length(); i++) {
                JSONObject t = trees.optJSONObject(i);
                if (t != null) out.add(t);
            }
            return out;
        }
    }

    void putTree(JSONObject tree) throws JSONException {
        synchronized (lock) {
            JSONObject existing = findTree(tree.getString("uri"));
            if (existing == null) trees.put(tree);
            else for (java.util.Iterator<String> it = tree.keys(); it.hasNext(); ) { String k = it.next(); existing.put(k, tree.get(k)); }
            saveTrees();
        }
    }

    void removeTree(String uri) {
        synchronized (lock) {
            JSONArray next = new JSONArray();
            for (int i = 0; i < trees.length(); i++) {
                JSONObject t = trees.optJSONObject(i);
                if (t != null && !uri.equals(t.optString("uri"))) next.put(t);
            }
            trees = next;
            saveTrees();
        }
    }

    private void saveTrees() {
        FileOutputStream out = null;
        try {
            out = treesFile.startWrite();
            out.write(trees.toString().getBytes(StandardCharsets.UTF_8));
            treesFile.finishWrite(out);
        } catch (IOException e) {
            if (out != null) treesFile.failWrite(out);
        }
    }

    /* ---------- app state blob (settings, lyric edits, queue) and user data (playlists, bookmarks, resume) ---------- */

    void saveState(String json) throws IOException { write(stateFile, json); }

    String loadState() { return read(stateFile); }

    void saveUserData(String json) throws IOException { write(userFile, json); }

    String loadUserData() { return read(userFile); }

    private static void write(AtomicFile file, String json) throws IOException {
        FileOutputStream out = null;
        try {
            out = file.startWrite();
            out.write(json.getBytes(StandardCharsets.UTF_8));
            file.finishWrite(out);
        } catch (IOException e) {
            if (out != null) file.failWrite(out);
            throw e;
        }
    }

    private static String read(AtomicFile file) {
        try {
            return file.getBaseFile().exists()
                ? new String(file.readFully(), StandardCharsets.UTF_8)
                : null;
        } catch (IOException e) {
            return null;
        }
    }
}
