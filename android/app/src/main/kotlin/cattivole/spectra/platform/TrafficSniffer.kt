package cattivole.spectra.platform

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import cattivole.spectra.core.TrafficFlow
import cattivole.spectra.core.TrafficSession
import cattivole.spectra.core.LocalCa
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** What the VPN thread last published. The service and the screen share it. */
internal object CaptureBus {
    var running: Boolean = false
        private set
    var flows: List<TrafficFlow> = emptyList()
        private set
    var startedAt: Long = 0
        private set
    var error: String? = null
        private set

    /** When on, new TLS connections are opened with [LocalCa] and read as HTTP. */
    val decryptTls: AtomicBoolean = AtomicBoolean(false)

    /** The authority that signs those connections. Null until the key has been read. */
    @Volatile
    var authority: LocalCa? = null

    private val listeners = mutableListOf<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    fun listen(listener: () -> Unit): () -> Unit {
        listeners += listener
        return { listeners -= listener }
    }

    fun publish(running: Boolean, flows: List<TrafficFlow>, startedAt: Long, error: String?) {
        main.post {
            this.running = running
            this.flows = flows
            this.startedAt = startedAt
            this.error = error
            listeners.toList().forEach { it() }
        }
    }
}

/**
 * The recording the LAN tab starts and saves. The live list is whatever the
 * VPN last forwarded; a save writes that list to disk so it can be opened later.
 */
class TrafficSniffer(context: Context) {
    var running: Boolean = false
        private set
    var flows: List<TrafficFlow> = emptyList()
        private set
    var startedAt: Long = 0
        private set
    var error: String? = null
        private set
    var saved: List<TrafficSession> = emptyList()
        private set

    /** True when the live list has grown since the last save. */
    var unsaved: Boolean = false
        private set

    /** Row kept at the top of the list. `session:flow:exchange`. */
    var focusedKey: String? = null
        private set

    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("spectra", android.content.Context.MODE_PRIVATE)
    private val dir = File(app.filesDir, "traffic").apply { mkdirs() }
    private val caDir = File(dir, "ca")
    private val listeners = mutableListOf<() -> Unit>()
    private val disk = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var savedBytes = -1L
    private var detach = {}

    /** True when new TLS connections are opened with this app's certificate. */
    val decryptTls: Boolean get() = CaptureBus.decryptTls.get()

    init {
        CaptureBus.decryptTls.set(prefs.getBoolean(KEY_TLS, false))
        running = CaptureBus.running
        flows = CaptureBus.flows
        startedAt = CaptureBus.startedAt
        error = CaptureBus.error
        detach = CaptureBus.listen {
            running = CaptureBus.running
            flows = CaptureBus.flows
            startedAt = CaptureBus.startedAt
            error = CaptureBus.error
            val bytes = flows.sumOf { it.sent + it.received }
            if (bytes != savedBytes) unsaved = flows.isNotEmpty()
            if (running && flows.isEmpty()) unsaved = false
            changed()
        }
        disk.execute {
            val ca = runCatching { LocalCa.open(caDir) }.getOrNull()
            val loaded = dir.listFiles { file -> file.extension == "json" }
                ?.mapNotNull { file -> runCatching { TrafficSession.fromJson(file.readText()) }.getOrNull() }
                ?.sortedByDescending { it.stoppedAt }
                ?.take(KEEP)
                .orEmpty()
            main.post {
                CaptureBus.authority = ca
                saved = loaded
                changed()
            }
        }
    }

    fun observe(listener: () -> Unit): () -> Unit {
        listeners += listener
        return { listeners -= listener }
    }

    fun setDecryptTls(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_TLS, enabled).apply()
        CaptureBus.decryptTls.set(enabled)
        changed()
        if (enabled && CaptureBus.authority == null) {
            disk.execute {
                val ca = runCatching { LocalCa.open(caDir) }.getOrNull()
                main.post {
                    CaptureBus.authority = ca
                    changed()
                }
            }
        }
    }

    fun certificateDer(): ByteArray? = CaptureBus.authority?.certificateDer()

    fun authority(): LocalCa? = CaptureBus.authority

    fun certificateFingerprint(): String? = CaptureBus.authority?.fingerprint()

    fun start() {
        error = null
        if (CaptureBus.decryptTls.get() && CaptureBus.authority == null) {
            CaptureBus.authority = runCatching { LocalCa.open(caDir) }.getOrNull()
        }
        ContextCompat.startForegroundService(app, Intent(app, SnifferVpnService::class.java))
    }

    fun stop() {
        app.startService(Intent(app, SnifferVpnService::class.java).setAction(SnifferVpnService.ACTION_STOP))
    }

    /** Writes the live list. Null when there is nothing to keep. */
    fun save(): TrafficSession? {
        if (flows.isEmpty()) return null
        val session = TrafficSession(
            id = UUID.randomUUID().toString(),
            startedAt = startedAt.takeIf { it > 0 } ?: flows.minOf { it.startedAt },
            stoppedAt = System.currentTimeMillis(),
            flows = flows,
        )
        dir.mkdirs()
        File(dir, "${session.id}.json").writeText(session.toJson().toString())
        val next = listOf(session) + saved
        next.drop(KEEP).forEach { File(dir, "${it.id}.json").delete() }
        saved = next.take(KEEP)
        savedBytes = flows.sumOf { it.sent + it.received }
        unsaved = false
        changed()
        return session
    }

    fun session(id: String): TrafficSession? = saved.firstOrNull { it.id == id }

    fun delete(id: String) {
        File(dir, "$id.json").delete()
        saved = saved.filterNot { it.id == id }
        changed()
    }

    fun toggleFocus(key: String) {
        focusedKey = if (focusedKey == key) null else key
        changed()
    }

    fun clear() {
        if (running) return
        flows = emptyList()
        unsaved = false
        savedBytes = -1L
        error = null
        if (focusedKey?.startsWith("live:") == true) focusedKey = null
        changed()
    }

    /** Drops the screen listener. The recording keeps running in its service. */
    fun close() {
        detach()
    }

    private fun changed() {
        listeners.toList().forEach { it() }
    }

    private companion object {
        const val KEEP = 30
        const val KEY_TLS = "traffic_decrypt_tls"
    }
}
