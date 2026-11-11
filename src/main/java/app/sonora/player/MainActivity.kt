package app.sonora.player

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import app.sonora.player.ui.App
import app.sonora.player.ui.SonoraTheme
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var controller: ListenableFuture<MediaController>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        S.init(this)
        if (savedInstanceState == null) handleIntent(intent)
        setContent { SonoraTheme { App() } }
    }

    override fun onStart() {
        super.onStart()
        Engine.uiVisible = true
        // Binding a controller brings up PlaybackService, which owns the media session and notification.
        if (controller == null) controller = MediaController.Builder(this, SessionToken(this, ComponentName(this, PlaybackService::class.java))).buildAsync()
    }

    override fun onStop() {
        Engine.uiVisible = false
        Engine.saveResume()
        S.flush()
        super.onStop()
    }

    override fun onDestroy() {
        controller?.let { MediaController.releaseFuture(it) }
        controller = null
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** "Open with Sonora": the file is copied into the library (once) and played right away. */
    private fun handleIntent(i: Intent?) {
        val uri = i?.takeIf { it.action == Intent.ACTION_VIEW }?.data ?: return
        i.data = null
        S.scope.launch {
            S.loaded.first { it }
            Importer.importUris(listOf(uri), "open", playFirst = true)
        }
    }

    /** Hardware keyboard: Space = play/pause, arrows = seek, Shift+arrows = previous/next. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val step = S.settings.value.seekStepSec
        when (keyCode) {
            KeyEvent.KEYCODE_SPACE -> Engine.toggle()
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (event.isShiftPressed) Engine.next() else Engine.seekBy(step)
            KeyEvent.KEYCODE_DPAD_LEFT -> if (event.isShiftPressed) Engine.prev() else Engine.seekBy(-step)
            else -> return super.onKeyDown(keyCode, event)
        }
        return true
    }
}
