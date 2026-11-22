package cattivole.spectra.platform

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import cattivole.spectra.core.TlsHostFilter
import cattivole.spectra.core.TrafficBodies
import cattivole.spectra.core.TrafficFlow
import cattivole.spectra.core.TrafficSession
import cattivole.spectra.core.LocalCa
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

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

    /** Which hosts are decrypted. Read by the proxy for every new TLS connection. */
    val hostFilter: AtomicReference<TlsHostFilter> = AtomicReference(TlsHostFilter())

    /** Where the live recording writes HTTP bodies. */
    @Volatile
    var bodies: TrafficBodies? = null

    /** Apps keep refusing the certificate during this recording. */
    @Volatile
    var refusedCertificate: Boolean = false
        private set

    fun noteRefusedCertificate() {
        main.post {
            refusedCertificate = true
            listeners.toList().forEach { it() }
        }
    }

    fun clearNotice() {
        main.post { refusedCertificate = false }
    }

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

    /** The saved session this recording was written to. A second save updates it instead of adding another. */
    var currentSessionId: String? = null
        private set

    /** The recording on screen is saved as it stands. */
    val isCurrentSaved: Boolean get() = currentSessionId != null && !unsaved

    /** Apps keep refusing this app's certificate, and none has accepted it: it is probably not trusted. */
    val refusedCertificate: Boolean get() = CaptureBus.refusedCertificate

    /** Row kept at the top of the list. `session:flow:exchange`. */
    var focusedKey: String? = null
        private set

    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("spectra", android.content.Context.MODE_PRIVATE)
    private val dir = File(app.filesDir, "traffic").apply { mkdirs() }

    /**
     * The certificate authority lives outside backed-up storage, and its keys
     * are sealed by the Keystore: neither a backup nor a transfer to a new
     * phone carries them. Earlier versions kept it in [dir]`/ca`.
     */
    private val caDir = File(app.noBackupFilesDir, "traffic-ca")
    private val legacyCaDir = File(dir, "ca")

    private fun openAuthority(): LocalCa? = runCatching {
        LocalCa.relocate(legacyCaDir, caDir)
        LocalCa.open(caDir, KeystoreKeyGuard())
    }.getOrNull()
    private val listeners = mutableListOf<() -> Unit>()
    private val disk = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var savedBytes = -1L
    private var detach = {}

    /** True when new TLS connections are opened with this app's certificate. */
    val decryptTls: Boolean get() = CaptureBus.decryptTls.get()

    /** Which hosts are decrypted, like SSL Proxying in Charles. */
    val hostFilter: TlsHostFilter get() = CaptureBus.hostFilter.get()

    /** Bodies of the recording on screen. Kept out of backups: they can hold decrypted traffic. */
    private val liveBodies = TrafficBodies(File(app.noBackupFilesDir, "traffic-live-bodies"))

    init {
        CaptureBus.decryptTls.set(prefs.getBoolean(KEY_TLS, false))
        CaptureBus.hostFilter.set(
            TlsHostFilter(
                mode = TlsHostFilter.Mode.of(prefs.getString(KEY_TLS_MODE, null)),
                only = prefs.getString(KEY_TLS_ONLY, null).orEmpty().lines().filter { it.isNotBlank() },
                except = prefs.getString(KEY_TLS_EXCEPT, null).orEmpty().lines().filter { it.isNotBlank() },
            ),
        )
        CaptureBus.bodies = liveBodies
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
            val ca = openAuthority()
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
                val ca = openAuthority()
                main.post {
                    CaptureBus.authority = ca
                    changed()
                }
            }
        }
    }

    fun setHostFilter(filter: TlsHostFilter) {
        prefs.edit()
            .putString(KEY_TLS_MODE, filter.mode.key)
            .putString(KEY_TLS_ONLY, filter.only.joinToString("\n"))
            .putString(KEY_TLS_EXCEPT, filter.except.joinToString("\n"))
            .apply()
        CaptureBus.hostFilter.set(filter)
        changed()
    }

    /** Hosts whose apps refused the certificate in the recording on screen, so they can be left encrypted. */
    fun refusedHosts(flows: List<TrafficFlow> = this.flows): List<String> =
        flows.filter { it.refused }.mapNotNull { it.host?.takeIf { host -> host.isNotEmpty() } }.distinct().sorted()

    /** A stored body of the live recording, or of the saved session [sessionId]. Reads the disk. */
    fun body(sessionId: String?, flowId: Long, index: Int, request: Boolean): ByteArray? {
        val store = if (sessionId == null) liveBodies else TrafficBodies(bodiesDir(sessionId))
        return store.read(flowId, index, request)
    }

    /** The payload of a WebSocket message, live or saved. Reads the disk. */
    fun message(sessionId: String?, flowId: Long, index: Int, number: Int): ByteArray? {
        val store = if (sessionId == null) liveBodies else TrafficBodies(bodiesDir(sessionId))
        return store.readMessage(flowId, index, number)
    }

    private fun bodiesDir(sessionId: String) = File(dir, "$sessionId.bodies")

    fun certificateFingerprint(): String? = CaptureBus.authority?.fingerprint()

    fun start() {
        error = null
        currentSessionId = null
        liveBodies.clear()
        if (CaptureBus.decryptTls.get() && CaptureBus.authority == null) {
            CaptureBus.authority = openAuthority()
        }
        ContextCompat.startForegroundService(app, Intent(app, SnifferVpnService::class.java))
    }

    fun stop() {
        app.startService(Intent(app, SnifferVpnService::class.java).setAction(SnifferVpnService.ACTION_STOP))
    }

    /** Writes the live list; one recording is one session, re-saving updates it. Null when there is nothing to keep. */
    fun save(): TrafficSession? {
        if (flows.isEmpty()) return null
        val id = currentSessionId ?: UUID.randomUUID().toString()
        val session = TrafficSession(
            id = id,
            startedAt = startedAt.takeIf { it > 0 } ?: flows.minOf { it.startedAt },
            stoppedAt = System.currentTimeMillis(),
            flows = flows,
        )
        dir.mkdirs()
        File(dir, "${session.id}.json").writeText(session.toJson().toString())
        liveBodies.copyTo(bodiesDir(id))
        val next = listOf(session) + saved.filterNot { it.id == id }
        next.drop(KEEP).forEach {
            File(dir, "${it.id}.json").delete()
            bodiesDir(it.id).deleteRecursively()
        }
        saved = next.take(KEEP)
        currentSessionId = id
        savedBytes = flows.sumOf { it.sent + it.received }
        unsaved = false
        changed()
        return session
    }

    fun session(id: String): TrafficSession? = saved.firstOrNull { it.id == id }

    fun delete(id: String) {
        File(dir, "$id.json").delete()
        bodiesDir(id).deleteRecursively()
        if (currentSessionId == id) currentSessionId = null
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
        liveBodies.clear()
        unsaved = false
        savedBytes = -1L
        currentSessionId = null
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
        const val KEY_TLS_MODE = "traffic_tls_mode"
        const val KEY_TLS_ONLY = "traffic_tls_only"
        const val KEY_TLS_EXCEPT = "traffic_tls_except"
    }
}
