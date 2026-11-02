package app.sonora.player;

import java.security.MessageDigest;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Pure helpers with no Android dependencies, so they can be unit-tested on a plain JVM. */
final class SonoraUtil {
    private SonoraUtil() {}

    static final Pattern AUDIO = Pattern.compile(
        "\\.(mp3|flac|wav|ogg|oga|opus|m4a|aac|webm|weba|mp4|wma|aiff?|alac)$", Pattern.CASE_INSENSITIVE);
    static final Pattern LYRICS = Pattern.compile("\\.(lrc|srt|vtt|txt)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern ARTIST_TITLE = Pattern.compile("^(.+?)\\s+-\\s+(.+)$");

    static boolean isAudioName(String name) { return name != null && AUDIO.matcher(name).find(); }
    static boolean isLyricName(String name) { return name != null && LYRICS.matcher(name).find(); }

    /** Same identity scheme the JS side has always used, so likes / lyrics / ReplayGain keys survive. */
    static String idFor(String name, long size, long lastModified) {
        return name + "|" + size + "|" + lastModified;
    }


    /* ---------- stable ids + content fingerprint (A1) ---------- */

    /** Bytes hashed from each end of a file. */
    static final int FP_BYTES = 64 * 1024;

    /** New opaque track id. Existing records keep whatever id they already have (likes/lyrics are keyed by it). */
    static String newId() { return UUID.randomUUID().toString(); }

    /** Number of bytes read from the head and from the tail of a file of {@code size} bytes. */
    static int fpSpan(long size) { return (int) Math.min(FP_BYTES, Math.max(0, size)); }

    /** "size:sha1(head||tail)". Same bytes in, same fingerprint out, whatever the file is called. */
    static String fingerprint(long size, byte[] head, byte[] tail) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            md.update(head);
            md.update(tail);
            return size + ":" + hex(md.digest(), 20);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Directory part of a "a/b/c.mp3" style path; empty when the file sits at the root. */
    static String parentDir(String path) {
        if (path == null) return "";
        int i = path.lastIndexOf('/');
        return i <= 0 ? "" : path.substring(0, i);
    }

    /** Lower-cased file name without its extension; used to pair lyric sidecars with audio. */
    static String baseKey(String name) {
        if (name == null) return "";
        return name.replaceFirst("\\.[^./]+$", "").toLowerCase(Locale.ROOT);
    }

    static String extOf(String name) {
        if (name == null) return "bin";
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return "bin";
        String ext = name.substring(dot + 1).toLowerCase(Locale.ROOT);
        return ext.matches("[a-z0-9]{1,6}") ? ext : "bin";
    }

    static String mimeForExt(String ext) {
        switch (ext) {
            case "mp3": return "audio/mpeg";
            case "flac": return "audio/flac";
            case "wav": return "audio/wav";
            case "ogg": case "oga": case "opus": return "audio/ogg";
            case "m4a": case "alac": case "mp4": return "audio/mp4";
            case "aac": return "audio/aac";
            case "webm": case "weba": return "audio/webm";
            case "aif": case "aiff": return "audio/aiff";
            case "wma": return "audio/x-ms-wma";
            case "lrc": case "txt": return "text/plain";
            case "vtt": return "text/vtt";
            case "srt": return "application/x-subrip";
            default: return "application/octet-stream";
        }
    }

    /** @return {artist, title}; artist may be empty. Mirrors titleFromName() in tags.ts. */
    static String[] titleFromName(String name) {
        String base = (name == null ? "" : name).replaceFirst("\\.[^.]+$", "").replace('_', ' ');
        Matcher m = ARTIST_TITLE.matcher(base);
        if (m.matches()) {
            return new String[] { m.group(1).replaceFirst("^\\d+[.\\s-]*", "").trim(), m.group(2).trim() };
        }
        String t = base.replaceFirst("^\\d+[.\\s-]+", "").trim();
        return new String[] { "", t.isEmpty() ? base : t };
    }

    static String sha1Hex(String value, int chars) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            return hex(md.digest(value.getBytes("UTF-8")), chars);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static String sha1Hex(byte[] value, int chars) {
        try {
            return hex(MessageDigest.getInstance("SHA-1").digest(value), chars);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String hex(byte[] bytes, int chars) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.substring(0, Math.min(chars, sb.length()));
    }

    /** Natural ordering: "Track 2" before "Track 10". */
    static int naturalCompare(String a, String b) {
        int i = 0, j = 0;
        while (i < a.length() && j < b.length()) {
            char x = a.charAt(i), y = b.charAt(j);
            if (Character.isDigit(x) && Character.isDigit(y)) {
                int si = i, sj = j;
                while (i < a.length() && Character.isDigit(a.charAt(i))) i++;
                while (j < b.length() && Character.isDigit(b.charAt(j))) j++;
                String na = a.substring(si, i).replaceFirst("^0+(?=.)", "");
                String nb = b.substring(sj, j).replaceFirst("^0+(?=.)", "");
                if (na.length() != nb.length()) return na.length() - nb.length();
                int c = na.compareTo(nb);
                if (c != 0) return c;
            } else {
                int c = Character.compare(Character.toLowerCase(x), Character.toLowerCase(y));
                if (c != 0) return c;
                i++; j++;
            }
        }
        return (a.length() - i) - (b.length() - j);
    }

    /** Inclusive byte range. */
    static final class Range {
        final long start, end;
        Range(long start, long end) { this.start = start; this.end = end; }
    }

    static final Range UNSATISFIABLE = new Range(-1, -1);

    /**
     * Parses an HTTP Range header against a resource of {@code length} bytes.
     * @return null to serve the whole resource, UNSATISFIABLE for HTTP 416, otherwise the range to serve.
     */
    static Range parseRange(String header, long length) {
        if (header == null) return null;
        String h = header.trim().toLowerCase(Locale.ROOT);
        if (!h.startsWith("bytes=")) return null;
        String spec = h.substring(6).trim();
        int comma = spec.indexOf(',');
        if (comma >= 0) spec = spec.substring(0, comma).trim(); // only the first range is served
        int dash = spec.indexOf('-');
        if (dash < 0) return null;
        String a = spec.substring(0, dash).trim();
        String b = spec.substring(dash + 1).trim();
        try {
            long start, end;
            if (a.isEmpty()) {
                if (b.isEmpty()) return null;
                long n = Long.parseLong(b);
                if (n <= 0) return UNSATISFIABLE;
                start = Math.max(0, length - n);
                end = length - 1;
            } else {
                start = Long.parseLong(a);
                end = b.isEmpty() ? length - 1 : Long.parseLong(b);
                if (end >= length) end = length - 1;
            }
            if (length <= 0 || start < 0 || start >= length || end < start) return UNSATISFIABLE;
            return new Range(start, end);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
