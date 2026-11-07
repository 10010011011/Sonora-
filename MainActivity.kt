package app.sonora.player

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.sonora.player.ui.screens.SonoraShell

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        if (savedInstanceState == null) {
            handleOpenWithIntent(intent)
        } else {
            intent = Intent(Intent.ACTION_MAIN)
        }
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Graph.controller.connect()
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        setContent { SonoraShell() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleOpenWithIntent(intent)
    }

    private fun handleOpenWithIntent(intent: Intent?) {
        val uri = intent?.takeIf { Intent.ACTION_VIEW == it.action }?.data ?: return
        intent.action = Intent.ACTION_MAIN
        Graph.importer.importSingleUri(uri) { record ->
            if (record != null) {
                val idx = Graph.repo.tracks.value.indexOfFirst { it.id == record.id }
                if (idx >= 0) Graph.controller.playNow(idx)
            } else {
                Graph.toast("Could not open this file")
            }
        }
    }
}
