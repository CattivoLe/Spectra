package cattivole.spectra.platform

import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import cattivole.spectra.core.RepeatRequest
import cattivole.spectra.core.BodyView
import cattivole.spectra.core.BodyPreview
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import cattivole.spectra.core.MapRule
import cattivole.spectra.core.ThrottleSettings
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

    /** How fast connections may go while recording. */
    val throttle: AtomicReference<ThrottleSettings> = AtomicReference(ThrottleSettings())

    /** Map Local rules, for new decrypted connections. */
    val mapRules: AtomicReference<List<MapRule>> = AtomicReference(emptyList())

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

    /** Apps keep refusing this app's certificate, and none has accepted it: not installed, or apps that take no user certificate. */
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
        CaptureBus.throttle.set(ThrottleSettings.fromJson(prefs.getString(KEY_THROTTLE, null)))
        CaptureBus.mapRules.set(MapRule.listFromJson(prefs.getString(KEY_MAP_LOCAL, null)))
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

    /** How fast connections may go while recording, like Throttle Settings in Charles. */
    val throttle: ThrottleSettings get() = CaptureBus.throttle.get()

    fun setThrottle(settings: ThrottleSettings) {
        prefs.edit().putString(KEY_THROTTLE, settings.toJson()).apply()
        CaptureBus.throttle.set(settings)
        changed()
    }

    /** The request the Repeat screen opens with. */
    /** The text opened on its own screen. */
    var openText: cattivole.spectra.core.OpenText? = null

    var repeatDraft: cattivole.spectra.core.RepeatRequest? = null

    /** The rule a new Map Local rule screen opens with, made from a recorded response. */
    var ruleDraft: MapRule? = null

    /** Makes the Repeat screen's draft of a recorded request, its body included. */
    suspend fun draftRepeat(flow: TrafficFlow, index: Int, sessionId: String?) {
        val exchange = flow.exchanges[index]
        val body = withContext(Dispatchers.IO) { if (exchange.requestBodyStored) body(sessionId, flow.id, index, true) else null }
        repeatDraft = RepeatRequest.from(flow, exchange, body)
    }

    /** Makes a Map Local rule that answers a recorded request with the response it got. */
    suspend fun draftRule(flow: TrafficFlow, index: Int, sessionId: String?) {
        val exchange = flow.exchanges[index]
        val body = withContext(Dispatchers.IO) { if (exchange.responseBodyStored) body(sessionId, flow.id, index, false) else null }
        val text = when (val view = body?.let { BodyPreview.of(it, exchange.header("Content-Type", response = true)) }) {
            is BodyView.Json -> view.text
            is BodyView.Text -> view.text
            else -> exchange.responseJson.orEmpty()
        }
        ruleDraft = MapRule(
            id = UUID.randomUUID().toString(),
            host = flow.host ?: flow.destination,
            path = exchange.path,
            status = exchange.status ?: 200,
            contentType = exchange.header("Content-Type", response = true)?.substringBefore(';')?.trim() ?: "application/json",
            body = text,
        )
    }

    /** Map Local rules: requests they match are answered by the phone itself. */
    val mapRules: List<MapRule> get() = CaptureBus.mapRules.get()

    fun setMapRules(rules: List<MapRule>) {
        prefs.edit().putString(KEY_MAP_LOCAL, MapRule.listToJson(rules)).apply()
        CaptureBus.mapRules.set(rules)
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

    /**
     * Whether the phone has this very certificate among its user-installed authorities. Reads the system store.
     * Null when the store could not be read. Apps that ignore user certificates refuse it all the same.
     */
    fun certificateInstalled(): Boolean? {
        val wanted = CaptureBus.authority?.certificateDer() ?: return null
        return runCatching {
            val store = java.security.KeyStore.getInstance("AndroidCAStore").apply { load(null) }
            store.aliases().toList().any { alias -> store.getCertificate(alias)?.encoded?.contentEquals(wanted) == true }
        }.getOrNull()
    }

    /** Writes the certificate to Downloads for the user to install: Android lets no app install an authority itself. */
    fun saveCertificate(): Boolean {
        val der = CaptureBus.authority?.certificateDer() ?: return false
        val pem = "-----BEGIN CERTIFICATE-----\n" +
            android.util.Base64.encodeToString(der, android.util.Base64.NO_WRAP).chunked(64).joinToString("\n") +
            "\n-----END CERTIFICATE-----\n"
        return runCatching {
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                val resolver = app.contentResolver
                val downloads = android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI
                // Saved before by this app: replaced, so there is one file to pick rather than "spectra-ca (2).crt".
                resolver.delete(downloads, "${android.provider.MediaStore.Downloads.DISPLAY_NAME} = ?", arrayOf(CERTIFICATE_FILE))
                val uri = resolver.insert(
                    downloads,
                    android.content.ContentValues().apply {
                        put(android.provider.MediaStore.Downloads.DISPLAY_NAME, CERTIFICATE_FILE)
                        put(android.provider.MediaStore.Downloads.MIME_TYPE, "application/x-x509-ca-cert")
                    },
                ) ?: error("no uri")
                resolver.openOutputStream(uri)!!.use { it.write(pem.toByteArray()) }
            } else {
                @Suppress("DEPRECATION")
                val folder = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
                File(folder, CERTIFICATE_FILE).writeText(pem)
            }
        }.isSuccess
    }

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
        const val CERTIFICATE_FILE = "spectra-ca.crt"
        const val KEY_TLS = "traffic_decrypt_tls"
        const val KEY_TLS_MODE = "traffic_tls_mode"
        const val KEY_TLS_ONLY = "traffic_tls_only"
        const val KEY_TLS_EXCEPT = "traffic_tls_except"
        const val KEY_THROTTLE = "traffic_throttle"
        const val KEY_MAP_LOCAL = "traffic_map_local"
    }
}
