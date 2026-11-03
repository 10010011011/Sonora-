package app.sonora.player;

/** Plain-JVM checks: javac SonoraUtil.java SonoraUtilTest.java && java -cp . app.sonora.player.SonoraUtilTest */
public class SonoraUtilTest {
    static int failures = 0;
    static void eq(Object want, Object got, String label) {
        if (!String.valueOf(want).equals(String.valueOf(got))) { failures++; System.out.println("FAIL " + label + ": want=" + want + " got=" + got); }
    }
    static String r(SonoraUtil.Range x) { return x == null ? "whole" : x == SonoraUtil.UNSATISFIABLE ? "416" : x.start + "-" + x.end; }

    public static void main(String[] args) {
        eq("0-99", r(SonoraUtil.parseRange("bytes=0-99", 1000)), "closed");
        eq("500-999", r(SonoraUtil.parseRange("bytes=500-", 1000)), "open-ended");
        eq("900-999", r(SonoraUtil.parseRange("bytes=-100", 1000)), "suffix");
        eq("0-999", r(SonoraUtil.parseRange("bytes=0-5000", 1000)), "end clamped");
        eq("416", r(SonoraUtil.parseRange("bytes=1000-", 1000)), "start past end");
        eq("416", r(SonoraUtil.parseRange("bytes=5-2", 1000)), "inverted");
        eq("416", r(SonoraUtil.parseRange("bytes=0-", 0)), "empty file");
        eq("whole", r(SonoraUtil.parseRange(null, 1000)), "no header");
        eq("whole", r(SonoraUtil.parseRange("items=0-5", 1000)), "wrong unit");
        eq("whole", r(SonoraUtil.parseRange("bytes=abc-def", 1000)), "garbage");
        eq("0-9", r(SonoraUtil.parseRange("bytes=0-9, 20-29", 1000)), "multi range -> first");

        String[] lead = SonoraUtil.titleFromName("03 - Daft Punk - One More Time.mp3");
        eq("", lead[0], "leading track number is not an artist (matches tags.ts)");
        eq("Daft Punk - One More Time", lead[1], "remainder stays in title (matches tags.ts)");
        String[] t = SonoraUtil.titleFromName("Daft Punk - One More Time.flac");
        eq("Daft Punk", t[0], "artist"); eq("One More Time", t[1], "title");
        t = SonoraUtil.titleFromName("07 Intro_Song.mp3");
        eq("", t[0], "no artist"); eq("Intro Song", t[1], "numbered title");
        eq("song", SonoraUtil.baseKey("Song.MP3"), "baseKey");
        eq("flac", SonoraUtil.extOf("A.FLAC"), "ext"); eq("bin", SonoraUtil.extOf("noext"), "no ext");
        eq("true", SonoraUtil.isAudioName("x.OPUS"), "audio"); eq("false", SonoraUtil.isAudioName("x.jpg"), "not audio");
        eq("true", SonoraUtil.isLyricName("x.lrc"), "lyric");
        eq("audio/flac", SonoraUtil.playbackMime("audio/x-flac", "flac"), "normalise provider FLAC MIME");
        eq("audio/ogg", SonoraUtil.playbackMime("audio/opus", "opus"), "normalise Opus container MIME");
        eq("audio/mpeg", SonoraUtil.playbackMime("audio/x-mp3", "mp3"), "normalise MP3 MIME");
        eq("audio/acme", SonoraUtil.playbackMime("audio/acme", "unknown"), "preserve provider MIME for unknown extension");
        eq("a.mp3|12|34", SonoraUtil.idFor("a.mp3", 12, 34), "id");
        eq("a9993e364706816aba3e", SonoraUtil.sha1Hex("abc", 20), "sha1");
        eq("true", SonoraUtil.naturalCompare("Track 2", "Track 10") < 0, "natural");
        eq("true", SonoraUtil.naturalCompare("b", "A") > 0, "case-insens");

        byte[] h = new byte[100], tl = new byte[100];
        for (int i = 0; i < 100; i++) { h[i] = (byte) i; tl[i] = (byte) (200 - i); }
        String fp = SonoraUtil.fingerprint(5000, h, tl);
        eq(fp, SonoraUtil.fingerprint(5000, h.clone(), tl.clone()), "fingerprint is deterministic");
        eq("true", fp.startsWith("5000:") && fp.length() == 5 + 20, "fingerprint shape");
        eq("false", fp.equals(SonoraUtil.fingerprint(5001, h, tl)), "size is part of the fingerprint");
        byte[] tl2 = tl.clone(); tl2[99] ^= 1;
        eq("false", fp.equals(SonoraUtil.fingerprint(5000, h, tl2)), "tail bytes matter");
        eq("65536", SonoraUtil.fpSpan(10_000_000), "span capped"); eq("300", SonoraUtil.fpSpan(300), "span small file"); eq("0", SonoraUtil.fpSpan(-5), "span negative");
        eq("false", SonoraUtil.newId().equals(SonoraUtil.newId()), "ids are unique");
        eq("a/b", SonoraUtil.parentDir("a/b/c.mp3"), "parentDir"); eq("", SonoraUtil.parentDir("c.mp3"), "parentDir root");
        if (failures == 0) System.out.println("SonoraUtil: all checks passed"); else System.exit(1);
    }
}
