package app.sonora.player;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.StatFs;
import android.provider.DocumentsContract;
import android.util.Base64;

import androidx.activity.result.ActivityResult;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.ActivityCallback;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Bridge between the web app and the native library, media session and audio focus.
 * Events: mediaCommand, audioFocus, becomingNoisy, backButton, importProgress, serviceError, openIntent.
 */
@CapacitorPlugin(
    name = "SonoraPlayer",
    permissions = { @Permission(alias = "notifications", strings = { Manifest.permission.POST_NOTIFICATIONS }) }
)
public class SonoraPlayerPlugin extends Plugin {
    private static final int MAX_TEXT_BYTES = 32 * 1024 * 1024;

    private static volatile SonoraPlayerPlugin instance;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    /** Background upkeep (fingerprint backfill, thumbnails) must never delay a library load. */
    private final ExecutorService upkeep = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "sonora-upkeep");
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });
    private LibraryStore store;
    private volatile LibraryImporter importer;
    private final Map<String, MigrationJob> migrations = new HashMap<>();

    private static final class MigrationJob {
        File tmp; OutputStream out; String name, mime, id; long size, lastModified;
    }

    @Override
    public void load() {
        instance = this;
        store = LibraryStore.get(getContext());
        upkeep.execute(this::runUpkeep);
    }

    @Override
    protected void handleOnDestroy() {
        if (instance == this) instance = null;
        super.handleOnDestroy();
    }

    /** Events are retained until JS attaches a listener; dropped if the WebView is not alive. */
    static void emit(String event, JSObject data) {
        SonoraPlayerPlugin p = instance;
        if (p != null) p.notifyListeners(event, data, true);
    }

    /** Libraries created before stable ids have no fingerprints, and older covers no thumbnails. */
    private void runUpkeep() {
        try {
            for (JSONObject rec : store.withoutFingerprint()) {
                String fp = LibraryImporter.fingerprintFile(new File(store.mediaDir, rec.optString("file", "")));
                if (fp != null) store.setFingerprint(rec, fp);
            }
            store.commit();
        } catch (JSONException | RuntimeException ignored) { }
        for (String cover : store.coversWithoutThumb()) LibraryImporter.makeThumb(store, cover);
    }

    /* ------------------------------------------------------------------ importing picked files */

    @PluginMethod
    public void pickAndImport(PluginCall call) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[] {
            "audio/*", "text/*", "application/x-subrip", "application/octet-stream"
        });
        startActivityForResult(call, intent, "importPicked");
    }

    @ActivityCallback
    public void importPicked(PluginCall call, ActivityResult result) {
        if (call == null) return;
        Intent data = result.getData();
        if (result.getResultCode() != Activity.RESULT_OK || data == null) {
            call.reject("Import cancelled", "CANCELLED");
            return;
        }
        final List<Uri> uris = new ArrayList<>();
        ClipData clip = data.getClipData();
        if (clip != null) {
            for (int i = 0; i < clip.getItemCount(); i++) uris.add(clip.getItemAt(i).getUri());
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        if (uris.isEmpty()) { call.reject("No files were selected", "CANCELLED"); return; }
        for (Uri u : uris) keepAccess(u); // lets "retry failed" still read them after a process restart
        runImport(call, uris);
    }

    /** Re-run an import for URIs that failed earlier (the picker's access is kept while the process lives). */
    @PluginMethod
    public void retryImport(PluginCall call) {
        JSArray arr = call.getArray("uris");
        List<Uri> uris = new ArrayList<>();
        if (arr != null) for (int i = 0; i < arr.length(); i++) {
            String s = arr.optString(i, "");
            if (!s.isEmpty()) uris.add(Uri.parse(s));
        }
        if (uris.isEmpty()) { call.reject("Nothing to retry", "CANCELLED"); return; }
        runImport(call, uris);
    }

    private void keepAccess(Uri uri) {
        try {
            getContext().getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (RuntimeException ignored) { /* not every provider offers persistable grants */ }
    }

    private void runImport(PluginCall call, List<Uri> uris) {
        io.execute(() -> {
            try {
                LibraryImporter job = new LibraryImporter(getContext(), store);
                importer = job;
                call.resolve(new JSObject(job.run(uris, progressEmitter()).toString()));
            } catch (Exception e) {
                call.reject("Import failed: " + e.getMessage(), "IMPORT_FAILED", e);
            } finally {
                importer = null;
            }
        });
    }

    private LibraryImporter.Progress progressEmitter() {
        final long[] lastEmit = { 0 };
        return (done, total, name) -> {
            long now = System.currentTimeMillis();
            if (done != total && now - lastEmit[0] < 150) return;
            lastEmit[0] = now;
            notifyListeners("importProgress", new JSObject().put("done", done).put("total", total).put("name", name));
        };
    }

    @PluginMethod
    public void cancelImport(PluginCall call) {
        LibraryImporter job = importer;
        if (job != null) job.cancel();
        call.resolve();
    }

    /* ------------------------------------------------------------------ folders indexed in place */

    @PluginMethod
    public void pickFolder(PluginCall call) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(call, intent, "folderPicked");
    }

    @ActivityCallback
    public void folderPicked(PluginCall call, ActivityResult result) {
        if (call == null) return;
        Intent data = result.getData();
        if (result.getResultCode() != Activity.RESULT_OK || data == null || data.getData() == null) {
            call.reject("Folder cancelled", "CANCELLED");
            return;
        }
        final Uri tree = data.getData();
        try {
            getContext().getContentResolver().takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (RuntimeException e) {
            call.reject("Could not keep access to this folder", "PERMISSION_FAILED", e);
            return;
        }
        io.execute(() -> {
            try {
                JSONObject t = new JSONObject();
                t.put("uri", tree.toString());
                t.put("name", treeName(tree));
                t.put("addedAt", System.currentTimeMillis());
                store.putTree(t);
                call.resolve(new JSObject(scan(tree.toString()).toString()));
            } catch (Exception e) {
                call.reject("Folder scan failed: " + e.getMessage(), "SCAN_FAILED", e);
            } finally {
                importer = null;
            }
        });
    }

    @PluginMethod
    public void rescanFolders(PluginCall call) {
        io.execute(() -> {
            try {
                JSONObject total = null;
                for (JSONObject t : store.allTrees()) {
                    String uri = t.optString("uri");
                    if (!treeAccessible(uri)) continue; // access was revoked: keep the index, report it via listFolders
                    JSONObject one = scan(uri);
                    total = total == null ? one : merge(total, one);
                }
                call.resolve(new JSObject((total == null ? emptyResult() : total).toString()));
            } catch (Exception e) {
                call.reject("Rescan failed: " + e.getMessage(), "SCAN_FAILED", e);
            } finally {
                importer = null;
            }
        });
    }

    private JSONObject scan(String treeUri) throws JSONException {
        LibraryImporter job = new LibraryImporter(getContext(), store);
        importer = job;
        JSONObject out = job.scanTree(treeUri, progressEmitter());
        JSONObject t = store.findTree(treeUri);
        if (t != null) {
            t.put("lastScanAt", System.currentTimeMillis());
            t.put("count", store.treeRecords(treeUri).size());
            store.putTree(t);
        }
        return out;
    }

    @PluginMethod
    public void listFolders(PluginCall call) {
        io.execute(() -> {
            try {
                JSONArray arr = new JSONArray();
                for (JSONObject t : store.allTrees()) {
                    JSONObject o = new JSONObject(t.toString());
                    o.put("count", store.treeRecords(t.optString("uri")).size());
                    o.put("accessible", treeAccessible(t.optString("uri")));
                    arr.put(o);
                }
                call.resolve(new JSObject().put("folders", new JSArray(arr.toString())));
            } catch (JSONException e) {
                call.reject("Could not list folders: " + e.getMessage(), "LIST_FAILED", e);
            }
        });
    }

    @PluginMethod
    public void removeFolder(PluginCall call) {
        final String uri = call.getString("uri");
        if (uri == null) { call.reject("uri is required"); return; }
        io.execute(() -> {
            java.util.Set<String> ids = new java.util.HashSet<>();
            for (JSONObject rec : store.treeRecords(uri)) ids.add(rec.optString("id"));
            store.removeIds(ids); // index entries only: the user's audio files are never touched
            store.removeTree(uri);
            try {
                getContext().getContentResolver().releasePersistableUriPermission(Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (RuntimeException ignored) { }
            call.resolve(new JSObject().put("removed", ids.size()));
        });
    }

    /** Named apart from Plugin.hasPermission(String), which is public and checks Android runtime permissions. */
    private boolean treeAccessible(String uri) {
        for (android.content.UriPermission p : getContext().getContentResolver().getPersistedUriPermissions()) {
            if (p.isReadPermission() && uri.equals(p.getUri().toString())) return true;
        }
        return false;
    }

    private String treeName(Uri tree) {
        try {
            Uri doc = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
            try (Cursor c = getContext().getContentResolver().query(doc,
                    new String[] { DocumentsContract.Document.COLUMN_DISPLAY_NAME }, null, null, null)) {
                if (c != null && c.moveToFirst() && !c.isNull(0)) return c.getString(0);
            }
        } catch (RuntimeException ignored) { }
        return "Folder";
    }

    private static JSONObject emptyResult() throws JSONException {
        return new JSONObject().put("added", new JSONArray()).put("updated", new JSONArray()).put("rematched", new JSONArray())
            .put("failed", new JSONArray()).put("skipped", 0).put("unmatchedLyrics", 0).put("removed", 0)
            .put("cancelled", false).put("complete", true);
    }

    /** Sums two import results (arrays are concatenated, counters added, flags and-ed/or-ed). */
    private static JSONObject merge(JSONObject a, JSONObject b) throws JSONException {
        for (Iterator<String> it = b.keys(); it.hasNext(); ) {
            String k = it.next();
            Object v = b.get(k);
            if (v instanceof JSONArray) {
                JSONArray dst = a.optJSONArray(k);
                if (dst == null) { dst = new JSONArray(); a.put(k, dst); }
                JSONArray src = (JSONArray) v;
                for (int i = 0; i < src.length(); i++) dst.put(src.get(i));
            } else if (v instanceof Integer) {
                a.put(k, a.optInt(k, 0) + (Integer) v);
            } else if (v instanceof Boolean) {
                boolean x = (Boolean) v;
                a.put(k, "complete".equals(k) ? a.optBoolean(k, true) && x : a.optBoolean(k, false) || x);
            }
        }
        return a;
    }

    /* ------------------------------------------------------------------ "Open with Sonora" */

    @PluginMethod
    public void consumeOpenIntent(PluginCall call) {
        final Uri uri = MainActivity.pendingOpenUri;
        MainActivity.pendingOpenUri = null;
        if (uri == null) { call.resolve(new JSObject().put("record", JSONObject.NULL)); return; }
        io.execute(() -> {
            try {
                JSONObject rec = new LibraryImporter(getContext(), store).importOne(uri);
                JSObject out = new JSObject();
                out.put("record", rec == null ? JSONObject.NULL : new JSObject(rec.toString()));
                call.resolve(out);
            } catch (Exception e) {
                call.reject("Could not open this file: " + e.getMessage(), "OPEN_FAILED", e);
            }
        });
    }

    /* ------------------------------------------------------------------ library records */

    @PluginMethod
    public void loadLibrary(PluginCall call) {
        io.execute(() -> {
            try {
                JSObject out = new JSObject();
                out.put("tracks", new JSArray(store.listJson()));
                call.resolve(out);
            } catch (JSONException e) {
                call.reject("Could not read library: " + e.getMessage(), "LIBRARY_READ_FAILED", e);
            }
        });
    }

    @PluginMethod
    public void patchTracks(PluginCall call) {
        JSArray patches = call.getArray("patches");
        if (patches == null) { call.resolve(); return; }
        io.execute(() -> { store.patch(patches); call.resolve(); });
    }

    @PluginMethod
    public void removeTracks(PluginCall call) {
        JSArray ids = call.getArray("ids");
        if (ids == null) { call.resolve(); return; }
        io.execute(() -> { store.remove(ids); call.resolve(); });
    }

    @PluginMethod
    public void getStats(PluginCall call) {
        io.execute(() -> {
            JSObject out = new JSObject();
            out.put("tracks", store.count());
            out.put("bytes", store.totalBytes());
            out.put("freeBytes", new StatFs(store.mediaDir.getAbsolutePath()).getAvailableBytes());
            call.resolve(out);
        });
    }

    /* ------------------------------------------------------------------ app state + user data */

    @PluginMethod
    public void saveState(PluginCall call) {
        String json = call.getString("json");
        if (json == null) { call.reject("json is required"); return; }
        io.execute(() -> {
            try { store.saveState(json); call.resolve(); }
            catch (Exception e) { call.reject("Could not save state: " + e.getMessage(), "STATE_WRITE_FAILED", e); }
        });
    }

    @PluginMethod
    public void loadState(PluginCall call) {
        io.execute(() -> {
            JSObject out = new JSObject();
            String json = store.loadState();
            out.put("json", json == null ? JSONObject.NULL : json);
            call.resolve(out);
        });
    }

    /** Playlists, bookmarks and resume positions: small, written often, kept apart from the big state blob. */
    @PluginMethod
    public void saveUserData(PluginCall call) {
        String json = call.getString("json");
        if (json == null) { call.reject("json is required"); return; }
        io.execute(() -> {
            try { store.saveUserData(json); call.resolve(); }
            catch (Exception e) { call.reject("Could not save: " + e.getMessage(), "STATE_WRITE_FAILED", e); }
        });
    }

    @PluginMethod
    public void loadUserData(PluginCall call) {
        io.execute(() -> {
            JSObject out = new JSObject();
            String json = store.loadUserData();
            out.put("json", json == null ? JSONObject.NULL : json);
            call.resolve(out);
        });
    }

    /* ------------------------------------------------------------------ backup file (metadata only) */

    @PluginMethod
    public void saveTextFile(PluginCall call) {
        if (call.getString("text") == null) { call.reject("text is required"); return; }
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/json");
        intent.putExtra(Intent.EXTRA_TITLE, call.getString("name", "sonora-backup.json"));
        startActivityForResult(call, intent, "textFileCreated");
    }

    @ActivityCallback
    public void textFileCreated(PluginCall call, ActivityResult result) {
        if (call == null) return;
        Intent data = result.getData();
        if (result.getResultCode() != Activity.RESULT_OK || data == null || data.getData() == null) {
            call.reject("Cancelled", "CANCELLED");
            return;
        }
        final Uri uri = data.getData();
        final String text = call.getString("text", "");
        io.execute(() -> {
            try (OutputStream out = getContext().getContentResolver().openOutputStream(uri, "wt")) {
                if (out == null) throw new IOException("Could not open the file");
                out.write(text.getBytes(StandardCharsets.UTF_8));
                call.resolve();
            } catch (IOException | RuntimeException e) {
                call.reject("Could not save: " + e.getMessage(), "WRITE_FAILED", e);
            }
        });
    }

    @PluginMethod
    public void pickTextFile(PluginCall call) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        startActivityForResult(call, intent, "textFilePicked");
    }

    @ActivityCallback
    public void textFilePicked(PluginCall call, ActivityResult result) {
        if (call == null) return;
        Intent data = result.getData();
        if (result.getResultCode() != Activity.RESULT_OK || data == null || data.getData() == null) {
            call.reject("Cancelled", "CANCELLED");
            return;
        }
        final Uri uri = data.getData();
        io.execute(() -> {
            try (InputStream in = getContext().getContentResolver().openInputStream(uri)) {
                if (in == null) throw new IOException("Could not open the file");
                java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
                byte[] chunk = new byte[64 * 1024];
                int n;
                while ((n = in.read(chunk)) != -1) {
                    buf.write(chunk, 0, n);
                    if (buf.size() > MAX_TEXT_BYTES) throw new IOException("File is too large for a backup");
                }
                call.resolve(new JSObject().put("text", new String(buf.toByteArray(), StandardCharsets.UTF_8)));
            } catch (IOException | RuntimeException e) {
                call.reject("Could not read: " + e.getMessage(), "READ_FAILED", e);
            }
        });
    }

    /* ------------------------------------------------------------------ one-time migration of the pre-native library */

    /** Moves audio the old build kept in app-private storage into the new library (names, sizes, mtimes kept). */
    @PluginMethod
    public void scanLegacyFiles(PluginCall call) {
        io.execute(() -> {
            try {
                Context ctx = getContext();
                List<File> roots = new ArrayList<>();
                roots.add(new File(ctx.getFilesDir(), "sonora-library"));
                File ext = ctx.getExternalFilesDir(null);
                if (ext != null) roots.add(new File(ext, "sonora-library"));

                LibraryImporter job = new LibraryImporter(ctx, store);
                JSONArray added = new JSONArray(), failed = new JSONArray();
                List<Uri> lyricUris = new ArrayList<>();
                List<File> files = new ArrayList<>();
                for (File root : roots) if (root.isDirectory()) collect(root, files, 0);
                int skipped = 0;
                for (File f : files) {
                    String name = f.getName();
                    if (SonoraUtil.isLyricName(name)) { lyricUris.add(Uri.fromFile(f)); continue; }
                    if (!SonoraUtil.isAudioName(name)) continue;
                    try {
                        JSONObject rec = job.adopt(f, name, f.length(), f.lastModified(),
                            SonoraUtil.mimeForExt(SonoraUtil.extOf(name)), SonoraUtil.idFor(name, f.length(), f.lastModified()));
                        if (rec != null) added.put(rec); else skipped++;
                    } catch (IOException | JSONException | RuntimeException e) {
                        failed.put(new JSONObject().put("name", name).put("reason", String.valueOf(e.getMessage())));
                    }
                }
                store.commit();
                JSONArray updated = new JSONArray();
                if (!lyricUris.isEmpty() && added.length() > 0) {
                    JSONObject r = job.run(lyricUris, (d, t, n) -> { });
                    updated = r.optJSONArray("updated");
                    for (Uri u : lyricUris) new File(u.getPath()).delete();
                }
                for (File root : roots) prune(root);
                call.resolve(new JSObject(new JSONObject().put("added", added).put("updated", updated == null ? new JSONArray() : updated)
                    .put("skipped", skipped).put("failed", failed).toString()));
            } catch (Exception e) {
                call.reject("Legacy scan failed: " + e.getMessage(), "MIGRATION_FAILED", e);
            }
        });
    }

    private static void collect(File dir, List<File> out, int depth) {
        File[] kids = dir.listFiles();
        if (kids == null || depth > 6) return;
        for (File k : kids) {
            if (k.isDirectory()) collect(k, out, depth + 1); else out.add(k);
        }
    }

    /** Removes now-empty legacy directories; files that could not be adopted are left alone. */
    private static void prune(File dir) {
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (File k : kids) if (k.isDirectory()) prune(k);
        dir.delete(); // only succeeds when empty
    }

    /** Legacy IndexedDB blobs arrive in chunks so no whole file is ever held in the JS heap. */
    @PluginMethod
    public void migrationBegin(PluginCall call) {
        final String id = call.getString("id");
        final String name = call.getString("name");
        if (id == null || name == null) { call.reject("id and name are required"); return; }
        io.execute(() -> {
            try {
                if (store.findBySrc(id) != null) { call.resolve(new JSObject().put("skip", true)); return; }
                MigrationJob job = new MigrationJob();
                job.id = id; job.name = name;
                job.size = (long) (double) call.getDouble("size", 0.0);
                job.lastModified = (long) (double) call.getDouble("lastModified", 0.0);
                job.mime = call.getString("mime", "");
                job.tmp = new File(store.mediaDir, SonoraUtil.sha1Hex(id + "#migrate", 20) + ".part");
                job.out = new FileOutputStream(job.tmp);
                String token = UUID.randomUUID().toString();
                synchronized (migrations) { migrations.put(token, job); }
                call.resolve(new JSObject().put("token", token));
            } catch (Exception e) {
                call.reject("Could not start migration: " + e.getMessage(), "MIGRATION_FAILED", e);
            }
        });
    }

    @PluginMethod
    public void migrationChunk(PluginCall call) {
        final String token = call.getString("token");
        final String data = call.getString("data");
        io.execute(() -> {
            MigrationJob job;
            synchronized (migrations) { job = token == null ? null : migrations.get(token); }
            if (job == null || data == null) { call.reject("Unknown migration", "MIGRATION_FAILED"); return; }
            try {
                job.out.write(Base64.decode(data, Base64.DEFAULT));
                call.resolve();
            } catch (IOException | IllegalArgumentException e) {
                abort(token);
                call.reject("Could not write chunk: " + e.getMessage(), "MIGRATION_FAILED", e);
            }
        });
    }

    @PluginMethod
    public void migrationEnd(PluginCall call) {
        final String token = call.getString("token");
        io.execute(() -> {
            MigrationJob job;
            synchronized (migrations) { job = token == null ? null : migrations.remove(token); }
            if (job == null) { call.reject("Unknown migration", "MIGRATION_FAILED"); return; }
            try {
                job.out.close();
                long size = job.size > 0 ? job.size : job.tmp.length();
                String mime = job.mime != null && job.mime.startsWith("audio/") ? job.mime
                    : SonoraUtil.mimeForExt(SonoraUtil.extOf(job.name));
                JSONObject rec = new LibraryImporter(getContext(), store).adopt(job.tmp, job.name, size, job.lastModified, mime, job.id);
                store.commit();
                JSObject out = new JSObject();
                out.put("record", rec == null ? JSONObject.NULL : new JSObject(rec.toString()));
                call.resolve(out);
            } catch (Exception e) {
                job.tmp.delete();
                call.reject("Could not finish migration: " + e.getMessage(), "MIGRATION_FAILED", e);
            }
        });
    }

    @PluginMethod
    public void migrationAbort(PluginCall call) {
        abort(call.getString("token"));
        call.resolve();
    }

    private void abort(String token) {
        MigrationJob job;
        synchronized (migrations) { job = token == null ? null : migrations.remove(token); }
        if (job == null) return;
        try { job.out.close(); } catch (IOException ignored) { }
        job.tmp.delete();
    }

    /* ------------------------------------------------------------------ media session / focus */

    @PluginMethod
    public void updateSession(PluginCall call) {
        PlaybackService.Snapshot s = new PlaybackService.Snapshot();
        s.playing = Boolean.TRUE.equals(call.getBoolean("playing", false));
        s.title = call.getString("title", "");
        s.artist = call.getString("artist", "");
        s.album = call.getString("album", "");
        s.coverName = call.getString("coverName", "");
        s.durationMs = (long) (double) call.getDouble("durationMs", 0.0);
        s.positionMs = (long) (double) call.getDouble("positionMs", 0.0);
        s.speed = (float) (double) call.getDouble("speed", 1.0);
        s.hasPrev = !Boolean.FALSE.equals(call.getBoolean("hasPrev", true));
        s.hasNext = !Boolean.FALSE.equals(call.getBoolean("hasNext", true));
        if (!s.playing) AudioFocusController.get(getContext()).onPlaybackStopped();
        PlaybackService.apply(getContext(), s);
        call.resolve();
    }

    @PluginMethod
    public void requestFocus(PluginCall call) {
        boolean granted = AudioFocusController.get(getContext()).request();
        call.resolve(new JSObject().put("granted", granted));
    }

    @PluginMethod
    public void requestNotificationPermission(PluginCall call) {
        if (Build.VERSION.SDK_INT < 33 || getPermissionState("notifications") == PermissionState.GRANTED) {
            call.resolve(new JSObject().put("granted", true));
            return;
        }
        requestPermissionForAlias("notifications", call, "notificationsResult");
    }

    @PermissionCallback
    public void notificationsResult(PluginCall call) {
        call.resolve(new JSObject().put("granted", getPermissionState("notifications") == PermissionState.GRANTED));
    }

    /* ------------------------------------------------------------------ back button */

    @PluginMethod
    public void setBackHandled(PluginCall call) {
        MainActivity.backHandledByJs = Boolean.TRUE.equals(call.getBoolean("enabled", false));
        call.resolve();
    }

    @PluginMethod
    public void moveToBackground(PluginCall call) {
        getActivity().runOnUiThread(() -> getActivity().moveTaskToBack(true));
        call.resolve();
    }
}
