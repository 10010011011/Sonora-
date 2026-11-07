package app.sonora.player

import android.app.Application
import android.content.Context
import app.sonora.player.audio.DeviceManager
import app.sonora.player.audio.DspParams
import app.sonora.player.audio.DspParamsHolder
import app.sonora.player.audio.PlayerController
import app.sonora.player.data.AppState
import app.sonora.player.data.JsonFile
import app.sonora.player.data.Settings
import app.sonora.player.data.Store
import app.sonora.player.library.LibraryImporter
import app.sonora.player.library.LibraryRepo
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch

class SonoraApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Graph.init(this)
    }
}

/** Process-wide singletons shared by the UI and the playback service (same process). */
@OptIn(FlowPreview::class)
object Graph {
    lateinit var app: Context
        private set
    lateinit var root: File
        private set
    lateinit var repo: LibraryRepo
        private set
    lateinit var importer: LibraryImporter
        private set
    lateinit var devices: DeviceManager
        private set
    lateinit var controller: PlayerController
        private set

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    val dsp = DspParamsHolder()
    val settings = Store(Settings())
    val toastMessage = Store("")

    private lateinit var stateFile: JsonFile<AppState>
    private var lastState = AppState()
    private var toastJob: Job? = null

    fun toast(msg: String) {
        if (msg.isBlank()) return
        toastMessage.replace(msg)
        toastJob?.cancel()
        toastJob = mainScope.launch {
            delay(2800L)
            toastMessage.replace("")
        }
    }

    fun toggleLikeTrack(trackId: String): Boolean {
        var nowLiked = false
        settings.set { s ->
            val liked = s.likedIds.contains(trackId)
            nowLiked = !liked
            s.copy(likedIds = if (liked) s.likedIds.filter { it != trackId } else s.likedIds + trackId)
        }
        return nowLiked
    }

    fun init(context: Context) {
        app = context.applicationContext
        root = File(app.filesDir, "sonora").apply { mkdirs() }   // same folder the legacy app used
        stateFile = JsonFile(File(root, "state.json"), AppState.serializer()) { AppState() }
        lastState = stateFile.load()
        settings.replace(lastState.settings)
        dsp.p = DspParams.from(settings.value)

        repo = LibraryRepo(root)
        repo.loadAll(lastState.lyrics)

        controller = PlayerController(app)
        importer = LibraryImporter(
            context = app,
            repo = repo,
            scope = scope,
            onToast = ::toast,
            onLibraryChanged = { added -> controller.onLibraryTracksChanged(added) },
        )
        devices = DeviceManager(
            context = app,
            settingsStore = settings,
            onToast = ::toast,
            onAutoResume = { controller.play() },
        )
        devices.init()

        // Restore queue and current track from state.json
        controller.restoreSavedQueue(lastState.queueIds, lastState.currentTrackId)
        importer.startBackgroundUpkeep()

        scope.launch {
            settings.flow.collect { s ->
                val curTrack = controller.currentTrack()
                val cfg = curTrack?.let { repo.getTrackAudioConfig(it.id) }
                val rgDb = controller.state.value.rgApplied
                dsp.p = DspParams.from(s, rgDb, cfg)
            }
        }

        // Persist state.json (settings, lyrics, queueIds, currentTrackId) with 400ms debounce
        scope.launch {
            combine(
                settings.flow,
                repo.lyrics.flow,
                controller.queueState.flow,
                repo.tracks.flow,
            ) { s, lyr, q, tracks ->
                val qIds = q.queue.mapNotNull { tracks.getOrNull(it)?.id }
                val curId = tracks.getOrNull(q.queue.getOrNull(q.qpos) ?: -1)?.id
                AppState(
                    version = 2,
                    settings = s,
                    lyrics = lyr,
                    queueIds = qIds,
                    currentTrackId = curId,
                )
            }.debounce(400L).collect { newState ->
                lastState = newState
                runCatching { stateFile.save(newState) }
            }
        }
    }
}
