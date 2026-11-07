// Sonora Native — DevicesAndDiag.kt
// Audio output route discovery, Sound Profile auto-binding, launcher icon alias switching, and Diagnostics ring buffer.

package app.sonora.player.audio

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import app.sonora.player.data.Settings
import app.sonora.player.data.Store
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class AudioDeviceRoute(
    val id: String,
    val name: String,
    val kind: String,
    val isHeadsetOrBt: Boolean,
)

class DeviceManager(
    private val context: Context,
    private val settingsStore: Store<Settings>,
    private val onToast: (String) -> Unit,
    private val onAutoResume: () -> Unit,
) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    val routes = Store<List<AudioDeviceRoute>>(emptyList())
    val activeRoute = Store(AudioDeviceRoute("speaker", "Phone Speaker", "Built-in Speaker", false))

    private val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            refreshRoutes(notifyConnect = true)
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            refreshRoutes(notifyConnect = false)
        }
    }

    fun init() {
        refreshRoutes(notifyConnect = false)
        audioManager.registerAudioDeviceCallback(callback, null)
    }

    fun refreshRoutes(notifyConnect: Boolean = false) {
        val outs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val mapped = ArrayList<AudioDeviceRoute>()
        for (dev in outs) {
            val (kind, isExternal) = classifyType(dev.type)
            if (kind == null) continue
            val prodName = dev.productName?.toString()?.trim().orEmpty()
            val label = if (prodName.isNotEmpty() && !prodName.equals(Build.MODEL, ignoreCase = true)) {
                prodName
            } else {
                kind
            }
            val key = "${dev.type}:${label.lowercase(Locale.ROOT)}"
            if (mapped.none { it.id == key }) {
                mapped.add(AudioDeviceRoute(id = key, name = label, kind = kind, isHeadsetOrBt = isExternal))
            }
        }
        if (mapped.isEmpty()) {
            mapped.add(AudioDeviceRoute("speaker", "Phone Speaker", "Built-in Speaker", false))
        }
        routes.replace(mapped)

        val preferred = mapped.firstOrNull { it.isHeadsetOrBt } ?: mapped.first()
        val prev = activeRoute.value
        activeRoute.replace(preferred)
        if (preferred.id != prev.id) {
            Diagnostics.log("audio.route", "${preferred.name} (${preferred.kind})")
            val s = settingsStore.value
            val boundProfileId = s.deviceMap[preferred.id] ?: s.deviceMap[preferred.name]
            if (boundProfileId != null) {
                val prof = s.profiles.firstOrNull { it.id == boundProfileId }
                if (prof != null) {
                    settingsStore.set {
                        it.copy(
                            eq = prof.eq,
                            preampDb = prof.preampDb,
                            bassDb = prof.bassDb,
                            midsDb = prof.midsDb,
                            trebleDb = prof.trebleDb,
                            width = prof.width,
                            crossfeed = prof.crossfeed,
                        )
                    }
                    onToast("Applied profile “${prof.name}” for ${preferred.name}")
                }
            }
            if (notifyConnect && preferred.isHeadsetOrBt && s.autoResume) {
                onAutoResume()
            }
        }
    }

    private fun classifyType(type: Int): Pair<String?, Boolean> = when (type) {
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth A2DP" to true
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLE_SPEAKER,
        AudioDeviceInfo.TYPE_BLE_BROADCAST -> "Bluetooth LE Audio" to true
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Wired Headphones" to true
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired Headset" to true
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB DAC / Audio" to true
        AudioDeviceInfo.TYPE_HDMI,
        AudioDeviceInfo.TYPE_HDMI_ARC,
        AudioDeviceInfo.TYPE_HDMI_EARC -> "HDMI Output" to true
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Built-in Speaker" to false
        else -> null to false
    }
}

object AppIconManager {
    private val ALIASES = mapOf(
        "default" to ".IconDefault",
        "general" to ".IconGeneral",
        "miku" to ".IconMiku",
        "teto" to ".IconTeto",
        "neru" to ".IconNeru",
        "vinyl" to ".IconVinyl",
    )

    fun applyAppIcon(context: Context, iconKeyOrTheme: String, currentTheme: String = iconKeyOrTheme) {
        val resolvedKey = when (iconKeyOrTheme) {
            "auto" -> currentTheme
            "dark", "light", "system" -> "general"
            else -> iconKeyOrTheme
        }
        val targetSuffix = ALIASES[resolvedKey] ?: ".IconDefault"
        val pm = context.packageManager
        val pkg = context.packageName

        runCatching {
            for ((_, suffix) in ALIASES) {
                val cn = ComponentName(pkg, "$pkg$suffix")
                val desired = if (suffix == targetSuffix) {
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                } else {
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                }
                if (pm.getComponentEnabledSetting(cn) != desired) {
                    pm.setComponentEnabledSetting(cn, desired, PackageManager.DONT_KILL_APP)
                }
            }
            Diagnostics.log("app.icon", "Switched launcher alias to $targetSuffix")
        }
    }
}

data class DiagEvent(
    val ts: Long,
    val tag: String,
    val detail: String,
)

object Diagnostics {
    private const val MAX_EVENTS = 300
    private val ring = ArrayDeque<DiagEvent>(MAX_EVENTS)
    val events = Store<List<DiagEvent>>(emptyList())

    var tickCount: Long = 0L
        private set
    var maxTickDeltaMs: Long = 0L
        private set

    @Synchronized
    fun log(tag: String, detail: String = "") {
        if (ring.size >= MAX_EVENTS) ring.removeFirst()
        ring.addLast(DiagEvent(System.currentTimeMillis(), tag, detail))
        events.replace(ring.toList().asReversed())
    }

    @Synchronized
    fun recordTick(deltaMs: Long = 100L, hidden: Boolean = false) {
        if (deltaMs in 1..9999 && deltaMs > maxTickDeltaMs) {
            maxTickDeltaMs = deltaMs
        }
        tickCount++
    }

    @Synchronized
    fun clear() {
        ring.clear()
        events.replace(emptyList())
        tickCount = 0L
        maxTickDeltaMs = 0L
    }

    fun buildReportJson(context: Context, trackCount: Int, activeTheme: String): String {
        val root = JSONObject()
        root.put("app", "Sonora Native")
        root.put("sdkInt", Build.VERSION.SDK_INT)
        root.put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
        root.put("theme", activeTheme)
        root.put("tracks", trackCount)
        root.put("ticks", tickCount)
        root.put("maxTickDeltaMs", maxTickDeltaMs)

        val arr = JSONArray()
        val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
        for (ev in events.value) {
            val o = JSONObject()
            o.put("time", fmt.format(Date(ev.ts)))
            o.put("tag", ev.tag)
            o.put("detail", ev.detail)
            arr.put(o)
        }
        root.put("events", arr)
        return root.toString(2)
    }

    fun copyReportToClipboard(context: Context, trackCount: Int, activeTheme: String): Boolean {
        return runCatching {
            val report = buildReportJson(context, trackCount, activeTheme)
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("Sonora Diagnostics", report))
            true
        }.getOrDefault(false)
    }
}
