package cattivole.spectra.core

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class HistoryLine(
    val title: String,
    val detail: String,
    val trailing: String,
)

/**
 * One saved scan. A phone save keeps the records themselves, so a saved
 * network, host or Bluetooth device opens the same card as a live one. The
 * Cardputer sends summary [lines] only.
 */
class HistoryEntry(
    val id: String,
    val kind: String,
    val savedAt: Long,
    val source: String,
    val captureId: Long?,
    val lines: List<HistoryLine> = emptyList(),
    val networks: List<TrackedNetwork> = emptyList(),
    val probes: List<ProbingDevice> = emptyList(),
    val deauths: List<DeauthEvent> = emptyList(),
    val lan: List<LanDevice> = emptyList(),
    val bluetooth: List<BleDevice> = emptyList(),
) {
    val kindTitle: String
        get() = when (kind) {
            "lan" -> "LAN"
            "bluetooth" -> "Bluetooth"
            else -> "Wi‑Fi"
        }

    val hasRecords: Boolean
        get() = networks.isNotEmpty() || probes.isNotEmpty() || deauths.isNotEmpty() || lan.isNotEmpty() || bluetooth.isNotEmpty()

    val itemCount: Int
        get() = when (kind) {
            "networks" -> (networks.size + probes.size + deauths.size).takeIf { it > 0 } ?: lines.size
            "lan" -> lan.size.takeIf { it > 0 } ?: lines.size
            else -> bluetooth.size.takeIf { it > 0 } ?: lines.size
        }

    fun network(bssid: MacAddress): TrackedNetwork? = networks.firstOrNull { it.id == bssid }
    fun host(ip: String): LanDevice? = lan.firstOrNull { it.ipAddress == ip }
    fun ble(address: String): BleDevice? = bluetooth.firstOrNull { it.address == address }
    fun probe(mac: MacAddress): ProbingDevice? = probes.firstOrNull { it.mac == mac }
}

/**
 * Saved scans from the phone and from the Cardputer's side button.
 * A capture id from the sensor is imported once. The file is read and
 * written off the main thread; a large history must not stall the UI.
 */
class HistoryStore(context: Context) {
    var entries: List<HistoryEntry> = emptyList()
        private set

    private val file = File(context.applicationContext.filesDir, "spectra-history.json")
    private val listeners = mutableListOf<() -> Unit>()
    private val partials = mutableMapOf<Long, Partial>()
    private val encoded = mutableMapOf<String, JSONObject>()
    private val disk = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private class Partial(
        val kind: String,
        val unix: Long,
        var total: Int,
        val lines: MutableMap<Int, HistoryLine> = mutableMapOf(),
    )

    @Volatile private var loadedFromDisk: List<Pair<HistoryEntry, JSONObject>>? = null
    private val loading = CountDownLatch(1)
    private var merged = false

    init {
        disk.execute {
            loadedFromDisk = runCatching {
                if (!file.exists()) return@runCatching emptyList()
                val array = JSONArray(file.readText())
                (0 until array.length()).mapNotNull { index ->
                    val json = array.getJSONObject(index)
                    runCatching { decodeEntry(json) to json }.getOrNull()
                }
            }.getOrDefault(emptyList())
            loading.countDown()
            main.post { mergeLoaded() }
        }
    }

    /** Saves made before the file finished loading stay on top. */
    private fun mergeLoaded() {
        if (merged) return
        val loaded = loadedFromDisk ?: return
        merged = true
        val fresh = entries
        loaded.forEach { (entry, json) -> encoded[entry.id] = json }
        entries = (fresh + loaded.map { it.first }.filter { old -> fresh.none { it.id == old.id } }).take(LIMIT)
        if (fresh.isNotEmpty()) persist()
        publish()
    }

    /** The sensor's captures are matched against what is on disk, so they wait for it. */
    private fun ensureLoaded() {
        if (merged) return
        loading.await(3, TimeUnit.SECONDS)
        mergeLoaded()
    }

    fun observe(listener: () -> Unit): () -> Unit {
        listeners.add(listener)
        return { listeners.remove(listener) }
    }

    fun saveNetworks(store: NetworkStore, source: String) {
        val networks = store.networks().values.sortedWith(
            compareByDescending<TrackedNetwork> { it.isInRange }.thenByDescending { it.latest.rssi },
        )
        val probes = store.probingDevices
        val deauths = store.deauthEvents
        if (networks.isEmpty() && probes.isEmpty() && deauths.isEmpty()) return
        val json = JSONObject()
            .put("networks", JSONArray().apply { networks.forEach { put(HistoryCodec.network(it)) } })
            .put("probes", JSONArray().apply { probes.forEach { put(HistoryCodec.probe(it)) } })
            .put("deauths", JSONArray().apply { deauths.forEach { put(HistoryCodec.deauth(it)) } })
        append("networks", System.currentTimeMillis(), source, null, records = json)
    }

    fun saveLan(devices: List<LanDevice>, source: String) {
        if (devices.isEmpty()) return
        val json = JSONObject().put("lan", JSONArray().apply { devices.forEach { put(HistoryCodec.lan(it)) } })
        append("lan", System.currentTimeMillis(), source, null, records = json)
    }

    fun saveBluetooth(devices: Collection<BleDevice>, source: String) {
        if (devices.isEmpty()) return
        val rows = devices.sortedByDescending { it.rssi }
        val json = JSONObject().put("bluetooth", JSONArray().apply { rows.forEach { put(HistoryCodec.ble(it)) } })
        append("bluetooth", System.currentTimeMillis(), source, null, records = json)
    }

    /**
     * Folds one record from the sensor. True once the capture is stored, or was
     * stored already, and the sensor may delete it.
     */
    fun ingest(record: SensorProtocol.Message.Saved, source: String): Boolean {
        ensureLoaded()
        if (entries.any { it.captureId == record.captureId && it.source == source }) return true
        val kind = when (record.kind) {
            1 -> "networks"
            2 -> "lan"
            3 -> "bluetooth"
            else -> return false
        }
        if (record.total <= 0 || record.index !in 0 until record.total) return false
        val partial = partials.getOrPut(record.captureId) {
            Partial(kind, record.unix, record.total)
        }
        partial.total = record.total
        partial.lines[record.index] = HistoryLine(record.title, record.detail, record.trailing)
        if (partial.lines.size < partial.total) return false
        partials.remove(record.captureId)
        val ordered = (0 until partial.total).mapNotNull { partial.lines[it] }
        val whenMs = if (partial.unix == 0L) System.currentTimeMillis() else partial.unix * 1000
        append(partial.kind, whenMs, source, record.captureId, lines = ordered)
        return true
    }

    fun entry(id: String): HistoryEntry? = entries.firstOrNull { it.id == id }

    fun delete(id: String) {
        entries = entries.filterNot { it.id == id }
        encoded.remove(id)
        persist()
        publish()
    }

    fun clear() {
        entries = emptyList()
        encoded.clear()
        partials.clear()
        persist()
        publish()
    }

    private fun append(
        kind: String,
        savedAt: Long,
        source: String,
        captureId: Long?,
        lines: List<HistoryLine> = emptyList(),
        records: JSONObject? = null,
    ) {
        val json = (records ?: JSONObject())
            .put("id", UUID.randomUUID().toString())
            .put("kind", kind)
            .put("savedAt", savedAt)
            .put("source", source)
            .put("lines", JSONArray().apply {
                lines.forEach { put(JSONObject().put("title", it.title).put("detail", it.detail).put("trailing", it.trailing)) }
            })
        if (captureId != null) json.put("captureId", captureId)
        // Decoding the JSON again is the snapshot: the store keeps mutating its own objects.
        val entry = decodeEntry(json)
        if (entry.itemCount == 0) return
        encoded[entry.id] = json
        entries = (listOf(entry) + entries).take(LIMIT)
        encoded.keys.retainAll(entries.map { it.id }.toSet())
        persist()
        publish()
    }

    private fun publish() {
        listeners.toList().forEach { it() }
    }

    private fun persist() {
        val snapshot = entries.mapNotNull { encoded[it.id] }
        disk.execute {
            val array = JSONArray()
            snapshot.forEach { array.put(it) }
            runCatching {
                val temp = File(file.parentFile, file.name + ".tmp")
                temp.writeText(array.toString())
                if (!temp.renameTo(file)) {
                    file.writeText(array.toString())
                    temp.delete()
                }
            }
        }
    }

    companion object {
        const val LIMIT = 100

        /** Older files have only `lines`. Missing keys stay empty. */
        fun decodeEntry(obj: JSONObject): HistoryEntry {
            val linesJson = obj.optJSONArray("lines") ?: JSONArray()
            val lines = (0 until linesJson.length()).map { index ->
                val line = linesJson.getJSONObject(index)
                HistoryLine(line.optString("title"), line.optString("detail"), line.optString("trailing"))
            }
            fun <T> list(key: String, decode: (JSONObject) -> T?): List<T> {
                val array = obj.optJSONArray(key) ?: return emptyList()
                return (0 until array.length()).mapNotNull { index -> array.optJSONObject(index)?.let(decode) }
            }
            return HistoryEntry(
                id = obj.getString("id"),
                kind = obj.getString("kind"),
                savedAt = obj.getLong("savedAt"),
                source = obj.optString("source"),
                captureId = if (obj.has("captureId")) obj.getLong("captureId") else null,
                lines = lines,
                networks = list("networks", HistoryCodec::network),
                probes = list("probes", HistoryCodec::probe),
                deauths = list("deauths", HistoryCodec::deauth),
                lan = list("lan", HistoryCodec::lan),
                bluetooth = list("bluetooth", HistoryCodec::ble),
            )
        }
    }
}
