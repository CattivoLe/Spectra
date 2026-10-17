package cattivole.spectra.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class HistoryLine(
    val title: String,
    val detail: String,
    val trailing: String,
)

data class HistoryEntry(
    val id: String,
    val kind: String,
    val savedAt: Long,
    val source: String,
    val captureId: Long?,
    val lines: List<HistoryLine>,
) {
    val kindTitle: String
        get() = when (kind) {
            "lan" -> "LAN"
            "bluetooth" -> "Bluetooth"
            else -> "Wi‑Fi"
        }
}

/**
 * Saved scans from the phone and from the Cardputer's side button.
 * A capture id from the sensor is imported once.
 */
class HistoryStore(context: Context) {
    var entries: List<HistoryEntry> = emptyList()
        private set

    private val file = File(context.applicationContext.filesDir, "spectra-history.json")
    private val listeners = mutableListOf<() -> Unit>()
    private val partials = mutableMapOf<Long, Partial>()

    private class Partial(
        val kind: String,
        val unix: Long,
        var total: Int,
        val lines: MutableMap<Int, HistoryLine> = mutableMapOf(),
    )

    init {
        if (file.exists()) {
            runCatching {
                val array = JSONArray(file.readText())
                entries = (0 until array.length()).map { decodeEntry(array.getJSONObject(it)) }
            }
        }
    }

    fun observe(listener: () -> Unit): () -> Unit {
        listeners.add(listener)
        return { listeners.remove(listener) }
    }

    fun saveNetworks(store: NetworkStore, source: String) {
        val lines = store.networks().values
            .filter { it.isInRange }
            .sortedByDescending { it.latest.rssi }
            .map { network ->
                HistoryLine(
                    title = network.title,
                    detail = "${network.id.text} · ch ${network.latest.channel} · ${network.latest.auth.name}",
                    trailing = network.latest.rssi.toString(),
                )
            }
        append("networks", System.currentTimeMillis(), source, null, lines)
    }

    fun saveLan(devices: List<LanDevice>, source: String) {
        val lines = devices.map { device ->
            val detail = buildString {
                append(device.ipAddress)
                device.macAddress?.let { append(" · ${it.text}") }
                val identity = device.identity.label
                if (identity.isNotEmpty()) append(" · $identity")
            }
            HistoryLine(device.displayName, detail, "")
        }
        append("lan", System.currentTimeMillis(), source, null, lines)
    }

    fun saveBluetooth(devices: Collection<BleDevice>, source: String) {
        val lines = devices.sortedByDescending { it.rssi }.map { device ->
            HistoryLine(device.displayName, device.subtitle, device.rssi.toString())
        }
        append("bluetooth", System.currentTimeMillis(), source, null, lines)
    }

    /**
     * Folds one record from the sensor. True once the capture is stored, or was
     * stored already, and the sensor may delete it.
     */
    fun ingest(record: SensorProtocol.Message.Saved, source: String): Boolean {
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
        append(partial.kind, whenMs, source, record.captureId, ordered)
        return true
    }

    fun entry(id: String): HistoryEntry? = entries.firstOrNull { it.id == id }

    fun delete(id: String) {
        entries = entries.filterNot { it.id == id }
        persist()
        publish()
    }

    fun clear() {
        entries = emptyList()
        partials.clear()
        persist()
        publish()
    }

    private fun append(kind: String, savedAt: Long, source: String, captureId: Long?, lines: List<HistoryLine>) {
        if (lines.isEmpty()) return
        val entry = HistoryEntry(UUID.randomUUID().toString(), kind, savedAt, source, captureId, lines)
        entries = listOf(entry) + entries
        if (entries.size > 100) entries = entries.take(100)
        persist()
        publish()
    }

    private fun publish() {
        listeners.toList().forEach { it() }
    }

    private fun persist() {
        val array = JSONArray()
        for (entry in entries) {
            val lines = JSONArray()
            for (line in entry.lines) {
                lines.put(JSONObject().put("title", line.title).put("detail", line.detail).put("trailing", line.trailing))
            }
            val obj = JSONObject()
                .put("id", entry.id)
                .put("kind", entry.kind)
                .put("savedAt", entry.savedAt)
                .put("source", entry.source)
                .put("lines", lines)
            if (entry.captureId != null) obj.put("captureId", entry.captureId)
            array.put(obj)
        }
        runCatching { file.writeText(array.toString()) }
    }

    private fun decodeEntry(obj: JSONObject): HistoryEntry {
        val linesJson = obj.getJSONArray("lines")
        val lines = (0 until linesJson.length()).map { index ->
            val line = linesJson.getJSONObject(index)
            HistoryLine(line.getString("title"), line.optString("detail"), line.optString("trailing"))
        }
        return HistoryEntry(
            id = obj.getString("id"),
            kind = obj.getString("kind"),
            savedAt = obj.getLong("savedAt"),
            source = obj.optString("source"),
            captureId = if (obj.has("captureId")) obj.getLong("captureId") else null,
            lines = lines,
        )
    }
}
