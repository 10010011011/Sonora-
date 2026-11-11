package app.sonora.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Upgrading from Sonora 1.2 (the Capacitor build) and backup restore, using files shaped like the ones that build wrote. */
class PersistTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun legacyRoot(): File {
        val root = tmp.newFolder("sonora")
        for (d in listOf("media", "covers", "thumbs", "lyrics")) File(root, d).mkdirs()
        File(root, "media/k1.mp3").writeText("audio")
        File(root, "covers/c1.jpg").writeText("img")
        File(root, "thumbs/c1.jpg").writeText("img")
        File(root, "lyrics/k1.lrc").writeText("[00:01.00]hi")
        File(root, "library.json").writeText(
            """[
            {"id":"t1","key":"k1.mp3","file":"k1.mp3","fileName":"World is Mine.mp3","path":"World is Mine.mp3","mime":"audio/mpeg","size":5,"lastModified":1700000000000,
             "title":"World is Mine","artist":"ryo feat. Hatsune Miku","album":"supercell","duration":251.4,"coverName":"c1.jpg","sidecarFile":"k1.lrc","sidecarName":"World is Mine.lrc",
             "fp":"5:abc","storage":"copy","addedAt":1700000000001,"playCount":7,"lastPlayedAt":1700000009999,"rgTrack":-7.25,"edited":true,"tagsDone":true,"srcIds":["x"]},
            {"id":"t2","key":"gone.mp3","file":"gone.mp3","fileName":"gone.mp3","size":1,"storage":"copy"},
            {"id":"t3","key":"doc3","fileName":"untagged.flac","size":9,"storage":"tree","uri":"content://docs/tree/primary%3AMusic/document/primary%3AMusic%2Funtagged.flac","treeUri":"content://docs/tree/primary%3AMusic","path":"Music/untagged.flac"},
            {"id":"t1","file":"k1.mp3","fileName":"duplicate id"},
            "not an object"
            ]""",
        )
        return root
    }

    @Test
    fun readsTheOldLibraryInPlace() {
        val root = legacyRoot()
        val tracks = Legacy.readTracks(root)
        assertEquals(listOf("t1", "t3"), tracks.map { it.id }) // the record whose file is gone and the duplicate are skipped
        val t = tracks[0]
        assertEquals(File(root, "media/k1.mp3").absolutePath, t.uri)
        assertEquals("World is Mine", t.title)
        assertEquals("ryo feat. Hatsune Miku", t.artist)
        assertEquals(251.4, t.duration, 1e-9)
        assertEquals(File(root, "covers/c1.jpg").absolutePath, t.cover)
        assertEquals(File(root, "thumbs/c1.jpg").absolutePath, t.thumb)
        assertEquals(File(root, "lyrics/k1.lrc").absolutePath, t.sidecar)
        assertEquals("5:abc", t.fp)
        assertEquals(7, t.playCount)
        assertEquals(-7.25, t.rgTrack!!, 1e-9)
        assertTrue(t.edited)
        assertEquals("copy", t.storage)
        val folder = tracks[1]
        assertEquals("tree", folder.storage)
        assertTrue(folder.uri.startsWith("content://"))
        assertEquals("content://docs/tree/primary%3AMusic", folder.treeUri)
        assertEquals("untagged", folder.title) // no tags: file name without its extension
        assertEquals("Unknown artist", folder.artist)
        assertEquals("Local audio", folder.album)
        assertNull(folder.cover)
    }

    @Test
    fun aDamagedOrMissingIndexIsAnEmptyLibraryNotACrash() {
        val root = tmp.newFolder("broken")
        assertEquals(emptyList<Track>(), Legacy.readTracks(root))
        File(root, "library.json").writeText("{ this is not json")
        assertEquals(emptyList<Track>(), Legacy.readTracks(root))
        File(root, "library.json").writeText("{\"an\":\"object\"}")
        assertEquals(emptyList<Track>(), Legacy.readTracks(root))
    }

    @Test
    fun readsTheOldSettingsLyricsAndQueue() {
        val f = tmp.newFile("state.json")
        f.writeText(
            """{"version":2,"settings":{"theme":"miku","themeIcon":true,"volume":0.8,"eq":[0,1.5,0,0,0,0,0,0,0,-2],"playPauseFadeMs":220,"crossfade":4,"speed":1,
            "widgets":[{"id":"now","on":false}],"showRoomWidgets":false,"floatingMini":false,"likedIds":["t1"],"repeat":"one","repeatCountTarget":2,"fftSize":4096,
            "profiles":[{"id":"p1","name":"Buds","eq":[0,0,0,0,0,0,0,0,0,0],"preampDb":-2,"bassDb":3,"midsDb":0,"trebleDb":1,"width":1,"crossfeed":0.2}],"deviceMap":{"Buds":"p1"},
            "uiMode":"simple","resumeMinSec":1200,"librarySort":"manual"},
            "lyrics":{"t1":{"lines":[{"t":1,"text":"hi","words":[{"t":1,"text":"hi"}]},{"t":null,"text":"untimed"}],"offset":-0.5,"source":"x.lrc"}},
            "queueIds":["t3","t1"],"currentTrackId":"t1"}""",
        )
        val st = Legacy.readJson(f, StateFile.serializer())!!
        assertEquals("miku", st.settings.theme)
        assertTrue(st.settings.themeIcon)
        assertEquals(1.5, st.settings.eq[1], 1e-9)
        assertEquals(4.0, st.settings.crossfade, 1e-9)
        assertEquals(listOf("t1"), st.settings.likedIds)
        assertEquals(2, st.settings.repeatCountTarget)
        assertEquals("Buds", st.settings.profiles[0].name)
        assertEquals("p1", st.settings.deviceMap["Buds"])
        assertEquals("simple", st.settings.uiMode)
        val lyr = st.lyrics.getValue("t1")
        assertEquals(-0.5, lyr.offset, 1e-9)
        assertEquals(1, lyr.lines[0].words!!.size)
        assertNull(lyr.lines[1].t)
        assertEquals(listOf("t3", "t1"), st.queueIds)
        assertEquals("t1", st.currentTrackId)
    }

    @Test
    fun readsTheOldPlaylistsBookmarksAndResume() {
        val f = tmp.newFile("userdata.json")
        f.writeText(
            """{"version":1,"playlists":[{"id":"pl","name":"Vocaloid","trackIds":["t1","t3"],"createdAt":1,"updatedAt":2}],
            "bookmarks":[{"id":"b","trackId":"t1","t":61.5,"label":"chorus","createdAt":3}],
            "resume":{"last":{"trackId":"t1","ms":42000},"byTrack":{"t3":900000}},"trackConfigs":{"t1":{"speed":1.25,"bassDb":3}},"libraryOrder":["t3","t1"]}""",
        )
        val u = Legacy.readJson(f, UserData.serializer())!!
        assertEquals(listOf("t1", "t3"), u.playlists[0].trackIds)
        assertEquals(61.5, u.bookmarks[0].t, 1e-9)
        assertEquals(42000L, u.resume.last!!.ms)
        assertEquals(900000L, u.resume.byTrack["t3"])
        assertEquals(1.25, u.trackConfigs.getValue("t1").speed!!, 1e-9)
        assertNull(u.trackConfigs.getValue("t1").trebleDb)
        assertEquals(listOf("t3", "t1"), u.libraryOrder)
        val empty = tmp.newFile("empty.json")
        empty.writeText("{\"version\":1,\"playlists\":[],\"bookmarks\":[],\"resume\":{\"last\":null,\"byTrack\":{}}}")
        assertNull(Legacy.readJson(empty, UserData.serializer())!!.resume.last)
    }

    @Test
    fun backupRestoresOntoAReinstalledLibrary() {
        val old = listOf(
            Track(id = "old1", fileName = "a.mp3", size = 10, fp = "10:aa", title = "Edited title", artist = "A", album = "X", playCount = 9, lastPlayedAt = 99, addedAt = 5, edited = true),
            Track(id = "old2", fileName = "b.mp3", size = 20, title = "B", playCount = 1),
            Track(id = "old3", fileName = "missing.mp3", size = 30, title = "Not here"),
        )
        val user = UserData(
            playlists = listOf(Playlist("pl", "Mix", listOf("old1", "old2", "old3"))), bookmarks = listOf(Bookmark("bm", "old1", 12.0, "drop")),
            resume = ResumeState(ResumeLast("old2", 5000), mapOf("old1" to 60000L)), trackConfigs = mapOf("old1" to TrackAudioConfig(speed = 1.1)), libraryOrder = listOf("old2", "old1"),
        )
        val text = Backup.build(old, Settings(theme = "teto", likedIds = listOf("old1", "old3")), user, mapOf("old1" to Lyrics(listOf(LyricLine(1.0, "from backup")))), 123)

        // The same two files imported again on a new install: new ids, one matched by fingerprint, one by name + size.
        val now = listOf(
            Track(id = "new1", fileName = "renamed.mp3", size = 10, fp = "10:aa", title = "Tag title", artist = "A", album = "X", playCount = 2, addedAt = 50),
            Track(id = "new2", fileName = "b.mp3", size = 20, title = "B", playCount = 4),
        )
        val local = UserData(playlists = listOf(Playlist("mine", "Made here", listOf("new2"))))
        val r = Backup.restore(text, now, Settings(likedIds = listOf("new2")), local, mapOf("new2" to Lyrics(listOf(LyricLine(null, "edited here")))))

        assertEquals(RestoreSummary(2, 1, 1), r.summary)
        assertEquals("Edited title", r.tracks[0].title)
        assertEquals(9, r.tracks[0].playCount)
        assertEquals(5L, r.tracks[0].addedAt)
        assertEquals(4, r.tracks[1].playCount) // the higher count wins
        assertEquals("teto", r.settings.theme)
        assertEquals(listOf("new2", "new1", "old3"), r.settings.likedIds)
        assertEquals(listOf("mine", "pl"), r.user.playlists.map { it.id }) // local playlists are kept
        assertEquals(listOf("new1", "new2", "old3"), r.user.playlists[1].trackIds)
        assertEquals("new1", r.user.bookmarks[0].trackId)
        assertEquals("new2", r.user.resume.last!!.trackId)
        assertEquals(60000L, r.user.resume.byTrack["new1"])
        assertNotNull(r.user.trackConfigs["new1"])
        assertEquals("from backup", r.lyrics.getValue("new1").lines[0].text)
        assertEquals("edited here", r.lyrics.getValue("new2").lines[0].text)
    }

    @Test
    fun aFileThatIsNotABackupIsRefused() {
        for (bad in listOf("hello", "[]", "{\"app\":\"other\",\"tracks\":[]}", "{\"app\":\"sonora\"}", "{\"app\":\"sonora\",\"tracks\":\"nope\"}")) {
            try {
                Backup.restore(bad, emptyList(), Settings(), UserData(), emptyMap())
                fail("accepted: $bad")
            } catch (e: IllegalArgumentException) {
                assertTrue(e.message!!.isNotEmpty())
            }
        }
    }

    @Test
    fun mergingNeverDropsWhatIsOnThisDevice() {
        val local = UserData(playlists = listOf(Playlist("a", "Local")), resume = ResumeState(ResumeLast("x", 1), mapOf("x" to 5L)), libraryOrder = listOf("x"))
        val incoming = UserData(playlists = listOf(Playlist("a", "Older copy"), Playlist("b", "Other")), resume = ResumeState(ResumeLast("y", 2), mapOf("x" to 9L, "y" to 7L)), libraryOrder = listOf("y", "x"))
        val m = mergeUserData(local, incoming)
        assertEquals(listOf("Local", "Other"), m.playlists.map { it.name })
        assertEquals("x", m.resume.last!!.trackId)
        assertEquals(mapOf("x" to 5L, "y" to 7L), m.resume.byTrack)
        assertEquals(listOf("x", "y"), m.libraryOrder)
    }
}
