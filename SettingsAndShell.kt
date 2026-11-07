// Sonora Native — SettingsAndShell.kt
// Top-level SonoraShell (Header, 5 Bottom Tabs, MiniPlayer, SheetsHost, ActivityResultContracts)
// and SettingsModal (Themes, App Icon Aliases, Playback Toggles, Backup Export/Restore, Diagnostics).

package app.sonora.player.ui.screens

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import app.sonora.player.Graph
import app.sonora.player.audio.AppIconManager
import app.sonora.player.audio.Diagnostics
import app.sonora.player.data.Lyrics
import app.sonora.player.data.Playlist
import app.sonora.player.data.Settings
import app.sonora.player.library.LyricsParser
import app.sonora.player.library.UserDataLogic
import app.sonora.player.ui.*
import java.io.File
import kotlinx.coroutines.launch

@Composable
fun SonoraShell() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by Graph.settings.flow.collectAsState()
    val tracks by Graph.repo.tracks.flow.collectAsState()
    val ui by Graph.controller.state.collectAsState()
    val importProgress by Graph.importer.progress.flow.collectAsState()
    val toastMsg by Graph.toastMessage.flow.collectAsState()

    var activeTab by remember { mutableStateOf("home") }
    var librarySubview by remember { mutableStateOf("tracks") }
    var viewMode by remember { mutableStateOf("list") }

    val theme = remember(settings.theme) { SonoraThemes.byId(settings.theme) }
    val sheets = remember { SheetsStore() }
    var showSettingsModal by remember { mutableStateOf(false) }

    val lyricsStudioTrackId by sheets.showLyricsStudioForTrackId.collectAsState()
    var pendingCoverTrackId by remember { mutableStateOf<String?>(null) }
    var pendingLrcTrackId by remember { mutableStateOf<String?>(null) }
    var pendingExportM3uContent by remember { mutableStateOf<String?>(null) }

    // SAF & File Pickers
    val pickAudioLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isNotEmpty()) Graph.importer.importPickedUris(uris)
    }

    val pickFolderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { treeUri ->
        if (treeUri != null) Graph.importer.linkAndScanSafTree(treeUri)
    }

    val pickCoverLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri ->
        val tid = pendingCoverTrackId
        if (uri != null && tid != null) {
            scope.launch {
                val ok = Graph.repo.replaceCoverFromUri(context, tid, uri)
                Graph.toast(if (ok) "Updated cover art" else "Failed to decode cover image")
            }
        }
    }

    val pickLrcLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        val tid = pendingLrcTrackId
        if (uri != null && tid != null) {
            runCatching {
                val txt = context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                if (!txt.isNullOrBlank()) {
                    Graph.repo.saveLyrics(tid, LyricsParser.parseLyrics(txt, "imported"))
                    Graph.toast("Imported lyrics")
                }
            }
        }
    }

    val exportBackupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri != null) {
            runCatching {
                val jsonStr = Graph.repo.exportBackupJson(settings)
                context.contentResolver.openOutputStream(uri)?.use { it.write(jsonStr.toByteArray(Charsets.UTF_8)) }
                Graph.toast("Exported Sonora backup")
            }
        }
    }

    val importBackupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            runCatching {
                val raw = context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                if (!raw.isNullOrBlank()) {
                    val (restoredSettings, rematched) = Graph.repo.restoreBackupJson(raw)
                    Graph.settings.replace(restoredSettings)
                    Graph.toast("Restored backup ($rematched tracks matched)")
                }
            }.onFailure {
                Graph.toast("Backup restore failed")
            }
        }
    }

    val exportM3uLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("audio/x-mpegurl"),
    ) { uri ->
        val body = pendingExportM3uContent
        if (uri != null && body != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                Graph.toast("Exported M3U playlist")
            }
        }
    }

    val importM3uLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            runCatching {
                val raw = context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
                val (plName, matchedIds) = UserDataLogic.parseM3U(raw, "Imported.m3u8", tracks)
                val pl = Graph.repo.createPlaylist(plName)
                for (id in matchedIds) Graph.repo.addTrackToPlaylist(pl.id, id)
                Graph.toast("Imported $plName (${matchedIds.size} tracks)")
            }
        }
    }

    BackHandler(enabled = lyricsStudioTrackId != null || showSettingsModal || activeTab != "home") {
        when {
            lyricsStudioTrackId != null -> sheets.closeLyricsStudio()
            showSettingsModal -> showSettingsModal = false
            activeTab != "home" -> activeTab = "home"
        }
    }

    val currentTrack = remember(ui.mediaId, tracks) { Graph.controller.currentTrack() }

    CompositionLocalProvider(LocalTheme provides theme) {
        SonoraBackgroundBox(currentTrack = currentTrack, repo = Graph.repo) {
            if (lyricsStudioTrackId != null) {
                LyricsStudioScreen(
                    trackId = lyricsStudioTrackId!!,
                    repo = Graph.repo,
                    player = Graph.controller,
                    onImportLrcFile = { tid ->
                        pendingLrcTrackId = tid
                        pickLrcLauncher.launch(arrayOf("*/*"))
                    },
                    onClose = { sheets.closeLyricsStudio() },
                    onToast = { Graph.toast(it) },
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                        .navigationBarsPadding(),
                ) {
                    // Top App Bar
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(30.dp)
                                    .clip(CircleShape)
                                    .background(theme.accentSoft)
                                    .border(1.dp, theme.accent, CircleShape),
                            ) {
                                SonoraIcon(SonoraIcons.Disc, tint = theme.accent, size = 17.dp)
                            }
                            Spacer(Modifier.width(9.dp))
                            SonoraText(
                                text = "SONORA",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.ExtraBold,
                                fontFamily = SonoraFonts.Manrope,
                                letterSpacing = 1.6.sp,
                            )
                            Spacer(Modifier.width(8.dp))
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(999.dp))
                                    .background(theme.bgElevated)
                                    .border(1.dp, theme.border, RoundedCornerShape(999.dp))
                                    .clickable {
                                        val order = SonoraThemes.All.map { it.id }
                                        val nextTheme = order[(order.indexOf(theme.id) + 1) % order.size]
                                        Graph.settings.set { it.copy(theme = nextTheme) }
                                        if (settings.themeIcon) {
                                            AppIconManager.applyAppIcon(context, "auto", nextTheme)
                                        }
                                    }
                                    .padding(horizontal = 8.dp, vertical = 3.dp),
                            ) {
                                SonoraText(theme.name, color = theme.accent, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SonoraButton(
                                text = "Add",
                                icon = SonoraIcons.Plus,
                                compact = true,
                                onClick = { pickAudioLauncher.launch(arrayOf("audio/*", "*/*")) },
                            )
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(theme.bgElevated)
                                    .border(1.dp, theme.borderStrong, RoundedCornerShape(10.dp))
                                    .clickable { showSettingsModal = true },
                            ) {
                                SonoraIcon(SonoraIcons.Settings, tint = theme.text, size = 18.dp)
                            }
                        }
                    }

                    // Active Tab Body
                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        when (activeTab) {
                            "home" -> HomeScreen(
                                repo = Graph.repo,
                                player = Graph.controller,
                                sheets = sheets,
                                onPickAudioFiles = { pickAudioLauncher.launch(arrayOf("audio/*", "*/*")) },
                                onPickSafFolder = { pickFolderLauncher.launch(null) },
                                onNavigateToLibrary = { activeTab = "library" },
                            )
                            "library" -> LibraryScreen(
                                repo = Graph.repo,
                                importer = Graph.importer,
                                player = Graph.controller,
                                sheets = sheets,
                                subview = librarySubview,
                                sortBy = settings.librarySort,
                                viewMode = viewMode,
                                onSubviewChange = { librarySubview = it },
                                onSortByChange = { nextSort -> Graph.settings.set { it.copy(librarySort = nextSort) } },
                                onViewModeChange = { viewMode = it },
                                onPickAudioFiles = { pickAudioLauncher.launch(arrayOf("audio/*", "*/*")) },
                                onPickSafFolder = { pickFolderLauncher.launch(null) },
                                onExportM3U = { pl ->
                                    pendingExportM3uContent = UserDataLogic.playlistToM3U(pl, tracks)
                                    exportM3uLauncher.launch("${pl.name}.m3u8")
                                },
                                onImportM3U = { importM3uLauncher.launch(arrayOf("*/*")) },
                            )
                            "now" -> NowPlayingScreen(
                                repo = Graph.repo,
                                player = Graph.controller,
                                sheets = sheets,
                                settings = settings,
                                onUpdateSettings = { fn -> Graph.settings.set(fn) },
                                onToast = { Graph.toast(it) },
                            )
                            "sound" -> SoundScreen(
                                settings = settings,
                                deviceManager = Graph.devices,
                                player = Graph.controller,
                                sheets = sheets,
                                onUpdateSettings = { fn -> Graph.settings.set(fn) },
                                onToast = { Graph.toast(it) },
                            )
                            "visual" -> VisualScreen(
                                repo = Graph.repo,
                                player = Graph.controller,
                                settings = settings,
                                onUpdateSettings = { fn -> Graph.settings.set(fn) },
                            )
                        }
                    }

                    // Persistent MiniPlayerBar
                    if (currentTrack != null && activeTab != "now") {
                        MiniPlayerBar(
                            track = currentTrack,
                            repo = Graph.repo,
                            player = Graph.controller,
                            onOpenNowPlaying = { activeTab = "now" },
                        )
                    }

                    // 5-Tab Bottom Navigation Bar
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceAround,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(theme.bgSurface)
                            .border(1.dp, theme.border)
                            .padding(vertical = 8.dp),
                    ) {
                        val navItems = listOf(
                            Triple("home", "Home", SonoraIcons.Home),
                            Triple("library", "Library", SonoraIcons.Library),
                            Triple("now", "Playing", SonoraIcons.Disc),
                            Triple("sound", "Sound", SonoraIcons.Sliders),
                            Triple("visual", "Visual", SonoraIcons.Wave),
                        )
                        for ((id, label, icon) in navItems) {
                            val selected = activeTab == id
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable { activeTab = id }
                                    .padding(horizontal = 14.dp, vertical = 4.dp),
                            ) {
                                SonoraIcon(
                                    spec = icon,
                                    tint = if (selected) theme.accent else theme.textMuted,
                                    size = 20.dp,
                                )
                                Spacer(Modifier.height(2.dp))
                                SonoraText(
                                    text = label,
                                    color = if (selected) theme.accent else theme.textMuted,
                                    fontSize = 10.sp,
                                    fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.SemiBold,
                                    fontFamily = SonoraFonts.Manrope,
                                )
                            }
                        }
                    }
                }
            }

            // Sheets & Modals Overlay
            SheetsHost(
                sheets = sheets,
                repo = Graph.repo,
                player = Graph.controller,
                importProgress = importProgress,
                onPickCoverForTrack = { tid ->
                    pendingCoverTrackId = tid
                    pickCoverLauncher.launch("image/*")
                },
                onShareTrack = { trk ->
                    runCatching {
                        val uri = if (trk.storage == "copy" && trk.file.isNotEmpty()) {
                            val f = File(Graph.repo.mediaDir, trk.file)
                            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", f)
                        } else {
                            Graph.repo.resolveTrackUri(trk)
                        }
                        if (uri != null) {
                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "audio/*"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(shareIntent, "Share ${trk.title}"))
                        }
                    }.onFailure {
                        Graph.toast("Unable to share track")
                    }
                },
                onToast = { Graph.toast(it) },
            )

            // Settings Modal
            if (showSettingsModal) {
                SettingsModal(
                    settings = settings,
                    trackCount = tracks.size,
                    onUpdateSettings = { fn -> Graph.settings.set(fn) },
                    onExportBackup = { exportBackupLauncher.launch("sonora-backup.json") },
                    onRestoreBackup = { importBackupLauncher.launch(arrayOf("application/json", "*/*")) },
                    onDismiss = { showSettingsModal = false },
                    onToast = { Graph.toast(it) },
                )
            }

            // Floating Toast Pill
            if (toastMsg.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 96.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(theme.bgElevated)
                        .border(1.dp, theme.accent, RoundedCornerShape(999.dp))
                        .padding(horizontal = 16.dp, vertical = 9.dp),
                ) {
                    SonoraText(toastMsg, color = theme.text, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun SettingsModal(
    settings: Settings,
    trackCount: Int,
    onUpdateSettings: ((Settings) -> Settings) -> Unit,
    onExportBackup: () -> Unit,
    onRestoreBackup: () -> Unit,
    onDismiss: () -> Unit,
    onToast: (String) -> Unit,
) {
    val context = LocalContext.current
    val theme = LocalTheme.current
    val diagEvents by Diagnostics.events.flow.collectAsState()
    var showDiagPanel by remember { mutableStateOf(false) }

    BottomSheetOverlay(onDismiss = onDismiss) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 560.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                SonoraText("Settings & Studio Preferences", fontWeight = FontWeight.ExtraBold, fontSize = 18.sp, fontFamily = SonoraFonts.Manrope)
                SonoraButton("Done", compact = true, onClick = onDismiss)
            }

            // 1. Theme Selector
            SectionKicker("Visual Theme")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                for (t in SonoraThemes.All) {
                    SonoraChip(
                        label = t.name.substringBefore(" "),
                        selected = settings.theme == t.id,
                        onClick = {
                            onUpdateSettings { it.copy(theme = t.id) }
                            if (settings.themeIcon) {
                                AppIconManager.applyAppIcon(context, "auto", t.id)
                            }
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            // 2. App Icon Selector
            SectionKicker("Home Screen App Icon")
            ToggleSettingRow("Match Launcher Icon to Active Theme", settings.themeIcon) { on ->
                onUpdateSettings { it.copy(themeIcon = on) }
                AppIconManager.applyAppIcon(context, if (on) "auto" else "default", settings.theme)
                onToast(if (on) "Launcher icon synced to theme" else "Reset launcher icon to default")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                for (ic in listOf("default", "general", "miku", "teto", "neru")) {
                    SonoraChip(
                        label = ic.replaceFirstChar { it.uppercase() },
                        selected = false,
                        onClick = {
                            AppIconManager.applyAppIcon(context, ic, settings.theme)
                            onToast("Switched launcher icon to $ic")
                        },
                    )
                }
            }

            // 3. Playback Behavior Toggles
            SectionKicker("Playback & Hardware Integration")
            ToggleSettingRow("72-Bar Interactive Waveform Seekbar", settings.waveformSeekbar) { on ->
                onUpdateSettings { it.copy(waveformSeekbar = on) }
            }
            ToggleSettingRow("Auto-Pause When Media Volume Hits 0", settings.pauseOnVolume0) { on ->
                onUpdateSettings { it.copy(pauseOnVolume0 = on) }
            }
            ToggleSettingRow("Pause When Headphones Disconnect", settings.pauseOnDisconnect) { on ->
                onUpdateSettings { it.copy(pauseOnDisconnect = on) }
            }
            ToggleSettingRow("Auto-Resume When Headset Reconnects", settings.autoResume) { on ->
                onUpdateSettings { it.copy(autoResume = on) }
            }

            // 4. Backup & Restore
            SectionKicker("Portable Backup & Migration (.json)")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                SonoraButton("Export Backup", icon = SonoraIcons.Download, primary = true, onClick = onExportBackup, modifier = Modifier.weight(1f))
                SonoraButton("Restore Backup", icon = SonoraIcons.Upload, onClick = onRestoreBackup, modifier = Modifier.weight(1f))
            }

            // 5. Diagnostics Panel
            SectionKicker("Runtime Diagnostics (${diagEvents.size} events)")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                SonoraButton(if (showDiagPanel) "Hide Log" else "Show Log", compact = true, onClick = { showDiagPanel = !showDiagPanel })
                SonoraButton("Copy JSON Report", icon = SonoraIcons.Copy, compact = true, onClick = {
                    val ok = Diagnostics.copyReportToClipboard(context, trackCount, settings.theme)
                    onToast(if (ok) "Copied diagnostics JSON to clipboard" else "Copy failed")
                })
                SonoraButton("Clear", danger = true, compact = true, onClick = {
                    Diagnostics.clear()
                    onToast("Cleared diagnostics")
                })
            }
            if (showDiagPanel) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(theme.bg)
                        .border(1.dp, theme.border, RoundedCornerShape(10.dp))
                        .padding(10.dp),
                ) {
                    SonoraText(
                        text = "Ticks: ${Diagnostics.tickCount} • Max Δ: ${Diagnostics.maxTickDeltaMs} ms",
                        color = theme.accent,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(6.dp))
                    for (ev in diagEvents.take(25)) {
                        SonoraText(
                            text = "[${ev.tag}] ${ev.detail}",
                            color = theme.textSub,
                            fontSize = 11.sp,
                        )
                    }
                }
            }
        }
    }
}
