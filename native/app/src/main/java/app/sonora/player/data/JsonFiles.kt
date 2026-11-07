package app.sonora.player.data

import android.util.AtomicFile
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

object SonoraJson {
    val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        encodeDefaults = true
        isLenient = true
    }
}

/** Crash-safe JSON file (AtomicFile = write temp + rename), same guarantee the old Java store had. */
class JsonFile<T>(private val file: File, private val serializer: KSerializer<T>, private val default: () -> T) {
    private val atomic = AtomicFile(file)

    fun load(): T = try {
        if (!file.exists()) default()
        else SonoraJson.json.decodeFromString(serializer, atomic.readFully().toString(Charsets.UTF_8))
    } catch (e: Exception) {
        default()
    }

    fun save(value: T) {
        val bytes = SonoraJson.json.encodeToString(serializer, value).toByteArray(Charsets.UTF_8)
        val out = atomic.startWrite()
        try {
            out.write(bytes)
            atomic.finishWrite(out)
        } catch (e: Exception) {
            atomic.failWrite(out)
            throw e
        }
    }
}

/** Mirror of the web app's createStore(): one immutable value, replace on write, observed as a StateFlow. */
class Store<T>(initial: T) {
    private val state = MutableStateFlow(initial)
    val flow: StateFlow<T> = state.asStateFlow()
    val value: T get() = state.value
    fun set(transform: (T) -> T) { state.update(transform) }
    fun replace(newValue: T) { state.value = newValue }
}
