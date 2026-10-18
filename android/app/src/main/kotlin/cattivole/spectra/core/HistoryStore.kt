package cattivole.spectra.core

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Calendar
import java.util.Locale
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
 * One saved session. A phone save keeps the records themselves, so a saved
 * network, host or Bluetooth device opens the same card as a live one. The
 * Cardputer sends summary [lines] only.
 *
 * Scans of the same kind, on the same day, in the same place are merged into
 * one entry rather than piling up: [placeKey] groups them, and a record is
 * folded in by identity so [firstSeen] keeps the earliest sighting.
 */
class HistoryEntry(
    val id: String,
    val kind: String,
    val savedAt: Long,
    val source: String,
    val captureId: Long?,
    /**
     * Stable key grouping scans into one session: a geo bucket, or the LAN
     * network. Entries with the same (day + kind + placeKey) are merged.
     */
    val placeKey: String? = null,
    /** Human label for the place, shown in the by-place view. */
    val placeLabel: String? = null,
    val lines: List<HistoryLine> = emptyList(),
    val networks: List<TrackedNetwork> = emptyList(),
    val probes: List<ProbingDevice> = emptyList(),
    val deauths: List<DeauthEvent> = emptyList(),
    val lan: List<LanDevice> = emptyList(),
    val bluetooth: List<BleDevice> = emptyList(),
    /** Where this scan was saved. The phone's own fix, or the cap's fix on a sensor capture. */
    var latitude: Double? = null,
    var longitude: Double? = null,
) {
    val kindTitle: String
        get() = when (kind) {
            "lan" -> "LAN"
            "bluetooth" -> "Bluetooth"
            "lora" -> "LoRa"
            else -> "Wi‑Fi"
        }

    val hasRecords: Boolean
        get() = networks.isNotEmpty() || probes.isNotEmpty() || deauths.isNotEmpty() || lan.isNotEmpty() || bluetooth.isNotEmpty()

    val itemCount: Int
        get() = when (kind) {
            "networks" -> (networks.size + probes.size + deauths.size).takeIf { it > 0 } ?: lines.size
            "lan" -> lan.size.takeIf { it > 0 } ?: lines.size
            "lora" -> lines.size
            else -> bluetooth.size.takeIf { it > 0 } ?: lines.size
        }

    fun network(bssid: MacAddress): TrackedNetwork? = networks.firstOrNull { it.id == bssid }
    fun host(ip: String): LanDevice? = lan.firstOrNull { it.ipAddress == ip }
    fun ble(address: String): BleDevice? = bluetooth.firstOrNull { it.address == address }
    fun probe(mac: MacAddress): ProbingDevice? = probes.firstOrNull { it.mac == mac }
}

/**
 * Saved scans from the phone and from the Cardputer's side button.
 *
 * One entry collects a whole session: scans of the same kind, on the same day,
 * in the same place are merged into it rather than piling up as duplicates.
 * A capture id from the sensor is imported once and keeps its own entry. The
 * file is read and written off the main thread; a large history must not stall
 * the UI.
 */
class HistoryStore(context: Context) {
    var entries: List<HistoryEntry> = emptyList()
        private set

    private val appContext = context.applicationContext
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
        var latitude: Double? = null,
        var longitude: Double? = null,
    )

    private val pendingFix = mutableMapOf<Long, Pair<Double, Double>>()

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

    // MARK: Phone-side autosave (upsert)

    fun upsertNetworks(store: NetworkStore, source: String) {
        val networks = store.networks().values.sortedWith(
            compareByDescending<TrackedNetwork> { it.isInRange }.thenByDescending { it.latest.rssi },
        )
        val probes = store.probingDevices
        val deauths = store.deauthEvents
        if (networks.isEmpty() && probes.isEmpty() && deauths.isEmpty()) return
        val here = phoneLocation()
        upsert(
            "networks", source, geoBucket(here), geoLabel(here),
            networks = networks.mapNotNull { snapshot(HistoryCodec.network(it), HistoryCodec::network) },
            probes = probes.mapNotNull { snapshot(HistoryCodec.probe(it), HistoryCodec::probe) },
            deauths = deauths.mapNotNull { snapshot(HistoryCodec.deauth(it), HistoryCodec::deauth) },
            latitude = here?.first, longitude = here?.second,
        )
    }

    fun upsertLan(devices: List<LanDevice>, networkKey: String?, networkLabel: String?, source: String) {
        if (devices.isEmpty()) return
        val here = phoneLocation()
        upsert(
            "lan", source, networkKey ?: geoBucket(here), networkLabel ?: geoLabel(here),
            lan = devices.mapNotNull { snapshot(HistoryCodec.lan(it), HistoryCodec::lan) },
            latitude = here?.first, longitude = here?.second,
        )
    }

    fun upsertBluetooth(devices: Collection<BleDevice>, source: String) {
        if (devices.isEmpty()) return
        val rows = devices.sortedByDescending { it.rssi }
        val here = phoneLocation()
        upsert(
            "bluetooth", source, geoBucket(here), geoLabel(here),
            bluetooth = rows.mapNotNull { snapshot(HistoryCodec.ble(it), HistoryCodec::ble) },
            latitude = here?.first, longitude = here?.second,
        )
    }

    /** The phone's last known coordinate, or null without location permission or a fix. */
    private fun phoneLocation(): Pair<Double, Double>? {
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_COARSE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return null
        }
        val manager = appContext.getSystemService(LocationManager::class.java) ?: return null
        val best = runCatching {
            manager.allProviders
                .mapNotNull { provider -> manager.getLastKnownLocation(provider) }
                .maxByOrNull { it.time }
        }.getOrNull() ?: return null
        return best.latitude to best.longitude
    }

    /**
     * The cap's fix for a capture, which may arrive before its rows. Applied
     * to a capture that is already stored when the sensor sends it again.
     */
    fun notePosition(captureId: Long, source: String, latitude: Double, longitude: Double) {
        ensureLoaded()
        pendingFix[captureId] = latitude to longitude
        partials[captureId]?.let {
            it.latitude = latitude
            it.longitude = longitude
        }
        val index = entries.indexOfFirst { it.captureId == captureId && it.source == source }
        if (index < 0) return
        val entry = entries[index]
        if (entry.latitude == latitude && entry.longitude == longitude) return
        entry.latitude = latitude
        entry.longitude = longitude
        encoded[entry.id]?.put("latitude", latitude)?.put("longitude", longitude)
        persist()
        publish()
    }

    /**
     * Folds one record from the sensor. True once the capture is stored, or was
     * stored already, and the sensor may delete it. Sensor captures keep their
     * own entries, matched by capture id, and never merge into a phone session.
     */
    fun ingest(record: SensorProtocol.Message.Saved, source: String): Boolean {
        ensureLoaded()
        if (entries.any { it.captureId == record.captureId && it.source == source }) return true
        val kind = when (record.kind) {
            1 -> "networks"
            2 -> "lan"
            3 -> "bluetooth"
            4 -> "lora"
            else -> return false
        }
        if (record.total <= 0 || record.index !in 0 until record.total) return false
        val partial = partials.getOrPut(record.captureId) {
            val created = Partial(kind, record.unix, record.total)
            pendingFix[record.captureId]?.let {
                created.latitude = it.first
                created.longitude = it.second
            }
            created
        }
        partial.total = record.total
        partial.lines[record.index] = HistoryLine(record.title, record.detail, record.trailing)
        if (partial.lines.size < partial.total) return false
        partials.remove(record.captureId)
        pendingFix.remove(record.captureId)
        val ordered = (0 until partial.total).mapNotNull { partial.lines[it] }
        val whenMs = if (partial.unix == 0L) System.currentTimeMillis() else partial.unix * 1000
        val here = partial.latitude?.let { lat -> partial.longitude?.let { lon -> lat to lon } }
        insert(
            HistoryEntry(
                id = UUID.randomUUID().toString(),
                kind = partial.kind,
                savedAt = whenMs,
                source = source,
                captureId = record.captureId,
                placeKey = geoBucket(here),
                placeLabel = geoLabel(here),
                lines = ordered,
                latitude = partial.latitude,
                longitude = partial.longitude,
            ),
        )
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
        pendingFix.clear()
        persist()
        publish()
    }

    // MARK: - Session upsert

    private fun upsert(
        kind: String,
        source: String,
        placeKey: String,
        placeLabel: String?,
        lines: List<HistoryLine> = emptyList(),
        networks: List<TrackedNetwork> = emptyList(),
        probes: List<ProbingDevice> = emptyList(),
        deauths: List<DeauthEvent> = emptyList(),
        lan: List<LanDevice> = emptyList(),
        bluetooth: List<BleDevice> = emptyList(),
        latitude: Double? = null,
        longitude: Double? = null,
    ) {
        val now = System.currentTimeMillis()
        // Merge into today's session for the same kind and place (sensor
        // captures keep their own entries, matched by captureId).
        val index = entries.indexOfFirst {
            it.kind == kind && it.captureId == null && (it.placeKey ?: "") == placeKey && isSameDay(it.savedAt, now)
        }
        if (index >= 0) {
            val old = entries[index]
            val mergedEntry = HistoryEntry(
                id = old.id,
                kind = kind,
                savedAt = now,
                source = source,
                captureId = null,
                placeKey = placeKey,
                placeLabel = placeLabel ?: old.placeLabel,
                lines = mergeLines(old.lines, lines),
                networks = mergeNetworks(old.networks, networks),
                probes = mergeBy(old.probes, probes) { it.mac },
                deauths = mergeBy(old.deauths, deauths) { it.id },
                lan = mergeLan(old.lan, lan),
                bluetooth = mergeBle(old.bluetooth, bluetooth),
                latitude = latitude ?: old.latitude,
                longitude = longitude ?: old.longitude,
            )
            val list = entries.toMutableList()
            list.removeAt(index)
            list.add(0, mergedEntry)
            entries = list
            encoded[mergedEntry.id] = encodeEntry(mergedEntry)
            persist()
            publish()
            return
        }
        insert(
            HistoryEntry(
                id = UUID.randomUUID().toString(),
                kind = kind,
                savedAt = now,
                source = source,
                captureId = null,
                placeKey = placeKey,
                placeLabel = placeLabel,
                lines = lines,
                networks = networks,
                probes = probes,
                deauths = deauths,
                lan = lan,
                bluetooth = bluetooth,
                latitude = latitude,
                longitude = longitude,
            ),
        )
    }

    private fun insert(entry: HistoryEntry) {
        if (entry.itemCount == 0) return
        encoded[entry.id] = encodeEntry(entry)
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

        /**
         * ~55 m geo bucket (0.0005°). Empty when there is no fix, so fixless
         * scans of a kind on a day still merge together.
         */
        fun geoBucket(location: Pair<Double, Double>?): String {
            if (location == null) return ""
            val lat = Math.round(location.first * 2000.0) // 1 / 0.0005
            val lon = Math.round(location.second * 2000.0)
            return "geo:${lat}_$lon"
        }

        fun geoLabel(location: Pair<Double, Double>?): String? =
            location?.let { String.format(Locale.US, "%.4f, %.4f", it.first, it.second) }

        private fun isSameDay(a: Long, b: Long): Boolean {
            val first = Calendar.getInstance().apply { timeInMillis = a }
            val second = Calendar.getInstance().apply { timeInMillis = b }
            return first.get(Calendar.YEAR) == second.get(Calendar.YEAR) &&
                first.get(Calendar.DAY_OF_YEAR) == second.get(Calendar.DAY_OF_YEAR)
        }

        // MARK: - Merge helpers (union by identity, min firstSeen / max lastSeen)

        fun mergeLines(existing: List<HistoryLine>, incoming: List<HistoryLine>): List<HistoryLine> {
            val result = existing.toMutableList()
            for (line in incoming) {
                if (result.none { it.title == line.title }) result.add(line)
            }
            return result
        }

        private fun <T> mergeBy(existing: List<T>, incoming: List<T>, key: (T) -> Any): List<T> {
            val result = existing.toMutableList()
            val index = HashMap<Any, Int>()
            existing.forEachIndexed { at, item -> index.putIfAbsent(key(item), at) }
            for (item in incoming) {
                val at = index[key(item)]
                if (at != null) {
                    result[at] = item
                } else {
                    index[key(item)] = result.size
                    result.add(item)
                }
            }
            return result
        }

        fun mergeNetworks(existing: List<TrackedNetwork>, incoming: List<TrackedNetwork>): List<TrackedNetwork> {
            val byId = LinkedHashMap<MacAddress, TrackedNetwork>()
            existing.forEach { byId[it.id] = it }
            for (new in incoming) {
                val old = byId[new.id]
                if (old == null) {
                    byId[new.id] = new
                    continue
                }
                // Keep whichever reading is more recent as the representative one.
                val newer = new.lastSeen >= old.lastSeen
                byId[new.id] = TrackedNetwork(
                    latest = if (newer) new.latest else old.latest,
                    firstSeen = minOf(old.firstSeen, new.firstSeen),
                    lastSeen = maxOf(old.lastSeen, new.lastSeen),
                    samples = mergeSamples(old.samples, new.samples, NetworkStore.SAMPLES_KEPT),
                    missedScans = if (newer) new.missedScans else old.missedScans,
                    clients = (if (newer) new.clients.ifEmpty { old.clients } else old.clients).toMutableList(),
                    revealedSsid = new.revealedSsid ?: old.revealedSsid,
                    vendorInfo = new.vendorInfo ?: old.vendorInfo,
                    traffic = if (newer) (new.traffic ?: old.traffic) else old.traffic,
                    source = old.source,
                )
            }
            return byId.values.sortedWith(
                compareByDescending<TrackedNetwork> { it.isInRange }.thenByDescending { it.latest.rssi },
            )
        }

        fun mergeLan(existing: List<LanDevice>, incoming: List<LanDevice>): List<LanDevice> {
            val byIp = LinkedHashMap<String, LanDevice>()
            existing.forEach { byIp[it.ipAddress] = it }
            for (new in incoming) {
                val old = byIp[new.ipAddress]
                if (old == null) {
                    byIp[new.ipAddress] = new
                    continue
                }
                val newer = if (new.lastSeen >= old.lastSeen) new else old
                byIp[new.ipAddress] = LanDevice(
                    ipAddress = newer.ipAddress,
                    macAddress = newer.macAddress ?: old.macAddress ?: new.macAddress,
                    hostnames = newer.hostnames.ifEmpty { old.hostnames.ifEmpty { new.hostnames } },
                    openPorts = newer.openPorts.ifEmpty { old.openPorts.ifEmpty { new.openPorts } },
                    services = newer.services.ifEmpty { old.services.ifEmpty { new.services } },
                    portsScanned = newer.portsScanned,
                    isGateway = newer.isGateway,
                    isLocalDevice = newer.isLocalDevice,
                    profile = newer.profile,
                    firstSeen = minOf(old.firstSeen, new.firstSeen),
                    lastSeen = maxOf(old.lastSeen, new.lastSeen),
                )
            }
            return byIp.values.sortedWith(
                compareByDescending<LanDevice> { it.isLocalDevice }
                    .thenByDescending { it.isGateway }
                    .thenBy { ipOrder(it.ipAddress) },
            )
        }

        fun mergeBle(existing: List<BleDevice>, incoming: List<BleDevice>): List<BleDevice> {
            val byAddress = LinkedHashMap<String, BleDevice>()
            existing.forEach { byAddress[it.address] = it }
            for (new in incoming) {
                val old = byAddress[new.address]
                if (old == null) {
                    byAddress[new.address] = new
                    continue
                }
                // Rebuild from the newer advertisement, with the spans joined.
                val base = if (new.lastSeen >= old.lastSeen) new else old
                val json = HistoryCodec.ble(base)
                    .put("firstSeen", minOf(old.firstSeen, new.firstSeen))
                    .put("lastSeen", maxOf(old.lastSeen, new.lastSeen))
                    .put("packets", old.packetCount + new.packetCount)
                val samples = mergeBleSamples(old.samples, new.samples, 120)
                json.put("samples", JSONArray().apply { samples.forEach { put(JSONArray().put(it.date).put(it.rssi)) } })
                byAddress[new.address] = HistoryCodec.ble(json) ?: base
            }
            return byAddress.values.sortedByDescending { it.rssi }
        }

        private fun mergeSamples(a: List<TrackedNetwork.Sample>, b: List<TrackedNetwork.Sample>, limit: Int): MutableList<TrackedNetwork.Sample> {
            val seen = HashSet<Long>()
            val merged = mutableListOf<TrackedNetwork.Sample>()
            for (sample in a + b) if (seen.add(sample.date)) merged.add(sample)
            merged.sortBy { it.date }
            if (merged.size > limit) merged.subList(0, merged.size - limit).clear()
            return merged
        }

        private fun mergeBleSamples(a: List<BleDevice.Sample>, b: List<BleDevice.Sample>, limit: Int): List<BleDevice.Sample> {
            val seen = HashSet<Long>()
            val merged = mutableListOf<BleDevice.Sample>()
            for (sample in a + b) if (seen.add(sample.date)) merged.add(sample)
            merged.sortBy { it.date }
            if (merged.size > limit) merged.subList(0, merged.size - limit).clear()
            return merged
        }

        private fun ipOrder(ip: String): Long {
            val parts = ip.split(".")
            if (parts.size != 4) return 0
            var value = 0L
            for (part in parts) {
                val octet = part.toIntOrNull() ?: return 0
                value = (value shl 8) or (octet.toLong() and 0xFF)
            }
            return value
        }

        private fun <T> snapshot(json: JSONObject, decode: (JSONObject) -> T?): T? =
            decode(JSONObject(json.toString()))

        private fun encodeEntry(entry: HistoryEntry): JSONObject {
            val json = JSONObject()
                .put("id", entry.id)
                .put("kind", entry.kind)
                .put("savedAt", entry.savedAt)
                .put("source", entry.source)
                .put("lines", JSONArray().apply {
                    entry.lines.forEach { put(JSONObject().put("title", it.title).put("detail", it.detail).put("trailing", it.trailing)) }
                })
            if (entry.networks.isNotEmpty()) json.put("networks", JSONArray().apply { entry.networks.forEach { put(HistoryCodec.network(it)) } })
            if (entry.probes.isNotEmpty()) json.put("probes", JSONArray().apply { entry.probes.forEach { put(HistoryCodec.probe(it)) } })
            if (entry.deauths.isNotEmpty()) json.put("deauths", JSONArray().apply { entry.deauths.forEach { put(HistoryCodec.deauth(it)) } })
            if (entry.lan.isNotEmpty()) json.put("lan", JSONArray().apply { entry.lan.forEach { put(HistoryCodec.lan(it)) } })
            if (entry.bluetooth.isNotEmpty()) json.put("bluetooth", JSONArray().apply { entry.bluetooth.forEach { put(HistoryCodec.ble(it)) } })
            entry.captureId?.let { json.put("captureId", it) }
            entry.placeKey?.let { json.put("placeKey", it) }
            entry.placeLabel?.let { json.put("placeLabel", it) }
            val lat = entry.latitude
            val lon = entry.longitude
            if (lat != null && lon != null) json.put("latitude", lat).put("longitude", lon)
            return json
        }

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
                placeKey = if (obj.has("placeKey")) obj.optString("placeKey") else null,
                placeLabel = if (obj.has("placeLabel")) obj.optString("placeLabel") else null,
                lines = lines,
                networks = list("networks", HistoryCodec::network),
                probes = list("probes", HistoryCodec::probe),
                deauths = list("deauths", HistoryCodec::deauth),
                lan = list("lan", HistoryCodec::lan),
                bluetooth = list("bluetooth", HistoryCodec::ble),
                latitude = if (obj.has("latitude")) obj.getDouble("latitude") else null,
                longitude = if (obj.has("longitude")) obj.getDouble("longitude") else null,
            )
        }
    }
}
