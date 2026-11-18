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
 * One kind from the same day and place shares an entry. Wi‑Fi stays separate
 * from the LAN session; the internet check belongs to that LAN session.
 * [placeKey] groups them, and a record is folded in by identity so
 * [firstSeen] keeps the earliest sighting.
 *
 * [importedFrom] is the device that exported a received file. Those sessions
 * stay on their own shelf and are not folded into this phone's history.
 */
class HistoryEntry(
    val id: String,
    val kind: String,
    val savedAt: Long,
    val source: String,
    val captureId: Long?,
    /**
     * Stable key grouping scans into one session: a geo bucket, or the LAN
     * network. The same kind from the same day and place shares one entry.
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
    /** Connection checks of this LAN, newest first. */
    val connections: List<SavedConnection> = emptyList(),
    /** Where this scan was saved. The phone's own fix, or the cap's fix on a sensor capture. */
    var latitude: Double? = null,
    var longitude: Double? = null,
    /** Device that exported this session. Null for a scan saved on this phone. */
    var importedFrom: String? = null,
) {
    val kindTitle: String
        get() = labelFor(kind)

    /** The row names the records this session actually holds. */
    fun shownKinds(): List<String> {
        val found = buildList {
            if (networks.isNotEmpty() || probes.isNotEmpty() || deauths.isNotEmpty()) add("networks")
            if (lan.isNotEmpty() || connections.isNotEmpty()) add("lan")
            if (bluetooth.isNotEmpty()) add("bluetooth")
            if (kind == "lora") add("lora")
        }
        return found.ifEmpty { listOf(kind) }
    }

    val rowTitle: String
        get() = shownKinds().joinToString(" · ") { labelFor(it) }

    val hasRecords: Boolean
        get() = networks.isNotEmpty() || probes.isNotEmpty() || deauths.isNotEmpty() ||
            lan.isNotEmpty() || bluetooth.isNotEmpty() || connections.isNotEmpty()

    val itemCount: Int
        get() {
            val records = networks.size + probes.size + deauths.size + lan.size + connections.size + bluetooth.size
            return records.takeIf { it > 0 } ?: lines.size
        }

    fun network(bssid: MacAddress): TrackedNetwork? = networks.firstOrNull { it.id == bssid }
    fun host(ip: String): LanDevice? = lan.firstOrNull { it.ipAddress == ip }
    fun ble(address: String): BleDevice? = bluetooth.firstOrNull { it.address == address }
    fun probe(mac: MacAddress): ProbingDevice? = probes.firstOrNull { it.mac == mac }

    /** Search across the session's place, source and the records it holds. */
    fun matchesQuery(needle: String): Boolean {
        val query = needle.trim()
        if (query.isEmpty()) return true
        fun hit(value: String?) = value?.contains(query, ignoreCase = true) == true
        if (hit(placeLabel) || hit(source) || hit(importedFrom) || hit(rowTitle)) return true
        if (lines.any { hit(it.title) || hit(it.detail) || hit(it.trailing) }) return true
        if (networks.any { hit(it.title) || hit(it.id.text) || hit(it.latest.ssid) }) return true
        if (probes.any { hit(it.displayName) || hit(it.mac.text) || it.ssids.any { ssid -> hit(ssid) } }) return true
        if (lan.any { hit(it.displayName) || hit(it.ipAddress) || hit(it.macAddress?.text) }) return true
        if (bluetooth.any { hit(it.displayName) || hit(it.address) }) return true
        if (deauths.any { hit(it.bssid.text) }) return true
        return false
    }
}

internal fun labelFor(kind: String): String = when (kind) {
    "lan" -> "LAN"
    "bluetooth" -> "Bluetooth"
    "lora" -> "LoRa"
    else -> "Wi‑Fi"
}

/**
 * Saved scans from the phone and from the Cardputer's side button.
 *
 * One entry collects a whole session of one kind. Wi‑Fi and the LAN stay
 * apart; the internet check folds into the LAN session for that network.
 * A capture id from the sensor is imported once and keeps its own entry.
 * A file received from someone else is tagged and kept on a separate shelf.
 * The file is read and written off the main thread; a large history must not
 * stall the UI.
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
        val combined = (fresh + loaded.map { it.first }.filter { old -> fresh.none { it.id == old.id } }).take(LIMIT)
        val joined = coalesce(combined)
        entries = joined
        if (fresh.isNotEmpty() || joined.map { it.id } != combined.map { it.id }) {
            encoded.keys.retainAll(joined.map { it.id }.toSet())
            joined.forEach { encoded[it.id] = encodeEntry(it) }
            persist()
        }
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

    fun upsertNetworks(store: NetworkStore, source: String) =
        upsertNetworks(store.networks().values, source, store.probingDevices, store.deauthEvents)

    fun upsertNetworks(
        all: Collection<TrackedNetwork>,
        source: String,
        probes: List<ProbingDevice> = emptyList(),
        deauths: List<DeauthEvent> = emptyList(),
    ) {
        val networks = all.sortedWith(
            compareByDescending<TrackedNetwork> { it.isInRange }.thenByDescending { it.latest.rssi },
        )
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

    /** Folds one finished connection check into today's LAN session for this network. */
    fun upsertConnection(saved: SavedConnection, networkKey: String?, networkLabel: String?, source: String) {
        val here = phoneLocation()
        upsert(
            "lan", source, networkKey ?: geoBucket(here), networkLabel ?: geoLabel(here),
            connections = listOf(saved),
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

    /** Earliest sighting of this BSSID across every saved session, including imported ones. */
    fun earliestNetworkSeen(bssid: MacAddress): Long? = entries.asSequence()
        .flatMap { it.networks.asSequence() }
        .filter { it.id == bssid }
        .minOfOrNull { it.firstSeen }

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

    /** Drops one shelf. Imported sessions stay when the phone's own shelf is cleared, and the reverse. */
    fun clearShelf(imported: Boolean) {
        entries = entries.filter { (it.importedFrom != null) != imported }
        encoded.keys.retainAll(entries.map { it.id }.toSet())
        persist()
        publish()
    }

    /**
     * Adds sessions from a shared history file. An id or sensor capture already
     * stored is skipped. Returns how many sessions were new, or -1 when the
     * file is not a history document.
     */
    fun importDocument(text: String): Int {
        ensureLoaded()
        val merged = mergeImport(entries, text) ?: return -1
        val (joined, added) = merged
        if (added == 0) return 0
        entries = joined
        encoded.clear()
        joined.forEach { encoded[it.id] = encodeEntry(it) }
        persist()
        publish()
        return added
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
        connections: List<SavedConnection> = emptyList(),
        latitude: Double? = null,
        longitude: Double? = null,
    ) {
        val now = System.currentTimeMillis()
        // The same kind shares today's session for this place. Wi‑Fi and the
        // LAN stay apart; the internet check uses the LAN kind, so it joins
        // that session. A sensor capture keeps its own entry. A fix a few
        // dozen metres away still joins, so GPS drift does not start a second card.
        val index = entries.indexOfFirst { entry ->
            accepts(entry, kind, placeKey, now, latitude, longitude)
        }
        if (index >= 0) {
            val old = entries[index]
            val mergedEntry = fold(
                HistoryEntry(
                    id = old.id,
                    kind = old.kind,
                    savedAt = now,
                    source = source,
                    captureId = null,
                    placeKey = placeKey,
                    placeLabel = placeLabel ?: old.placeLabel,
                    lines = lines,
                    networks = networks,
                    probes = probes,
                    deauths = deauths,
                    lan = lan,
                    bluetooth = bluetooth,
                    connections = connections,
                    latitude = latitude ?: old.latitude,
                    longitude = longitude ?: old.longitude,
                ),
                old,
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
                connections = connections,
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
        const val CONNECTION_LIMIT = 12

        /** A history file another Spectra can import. [from] is the device it came from. */
        fun encodeDocument(entries: List<HistoryEntry>, from: String): String {
            val array = JSONArray()
            entries.forEach { array.put(encodeEntry(it)) }
            return JSONObject().put("from", from).put("entries", array).toString()
        }

        /**
         * Folds a shared file into [existing]. Received sessions are tagged with
         * the exporting device and stay off this phone's shelf. The pair is the
         * resulting list and how many sessions were new. Null when the text is
         * not a history.
         */
        fun mergeImport(existing: List<HistoryEntry>, text: String): Pair<List<HistoryEntry>, Int>? {
            val trimmed = text.trim()
            val parsed = try {
                when {
                    trimmed.startsWith("[") -> null to JSONArray(trimmed)
                    trimmed.startsWith("{") -> {
                        val obj = JSONObject(trimmed)
                        when {
                            obj.has("entries") -> obj.optString("from").takeIf { it.isNotBlank() } to (obj.optJSONArray("entries") ?: return null)
                            obj.has("id") -> null to JSONArray().put(obj)
                            else -> return null
                        }
                    }
                    else -> return null
                }
            } catch (_: Exception) {
                return null
            }
            val (from, array) = parsed
            val decoded = (0 until array.length()).mapNotNull { index ->
                array.optJSONObject(index)?.let { runCatching { decodeEntry(it) }.getOrNull() }
            }
            if (decoded.isEmpty()) return null
            val origin = from ?: "Imported"
            decoded.forEach { entry ->
                if (entry.importedFrom.isNullOrBlank()) entry.importedFrom = origin
            }
            val ids = existing.map { it.id }.toMutableSet()
            val captures = existing.mapNotNull { it.captureId }.toMutableSet()
            val fresh = decoded.filter { entry ->
                if (entry.id in ids) return@filter false
                val capture = entry.captureId
                if (capture != null && capture in captures) return@filter false
                ids.add(entry.id)
                capture?.let { captures.add(it) }
                true
            }
            return coalesce(fresh + existing).take(LIMIT) to fresh.size
        }

        /**
         * Splits a session that holds both Wi‑Fi and LAN records, then joins
         * sessions of one kind that are the same outing. Imported sessions are
         * not folded into this phone's history.
         */
        fun coalesce(entries: List<HistoryEntry>): List<HistoryEntry> {
            val kept = mutableListOf<HistoryEntry>()
            val imported = mutableListOf<HistoryEntry>()
            for (entry in entries.flatMap { separateRadio(it) }.sortedByDescending { it.savedAt }) {
                if (entry.importedFrom != null) {
                    imported.add(entry)
                    continue
                }
                val at = kept.indexOfFirst { together(it, entry) }
                if (at < 0) kept.add(entry) else kept[at] = fold(kept[at], entry)
            }
            return (kept + imported).sortedByDescending { it.savedAt }
        }

        /** A file saved while Wi‑Fi and LAN shared a card becomes two sessions. */
        private fun separateRadio(entry: HistoryEntry): List<HistoryEntry> {
            val wifi = entry.networks.isNotEmpty() || entry.probes.isNotEmpty() || entry.deauths.isNotEmpty()
            val local = entry.lan.isNotEmpty() || entry.connections.isNotEmpty()
            if (!wifi || !local || entry.captureId != null) return listOf(entry)
            val wifiId = if (entry.kind == "lan") entry.id + ":wifi" else entry.id
            val lanId = if (entry.kind == "lan") entry.id else entry.id + ":lan"
            return listOf(
                HistoryEntry(
                    id = wifiId,
                    kind = "networks",
                    savedAt = entry.savedAt,
                    source = entry.source,
                    captureId = null,
                    placeKey = entry.placeKey,
                    placeLabel = entry.placeLabel,
                    lines = entry.lines,
                    networks = entry.networks,
                    probes = entry.probes,
                    deauths = entry.deauths,
                    bluetooth = entry.bluetooth,
                    latitude = entry.latitude,
                    longitude = entry.longitude,
                    importedFrom = entry.importedFrom,
                ),
                HistoryEntry(
                    id = lanId,
                    kind = "lan",
                    savedAt = entry.savedAt,
                    source = entry.source,
                    captureId = null,
                    placeKey = entry.placeKey,
                    placeLabel = entry.placeLabel,
                    lan = entry.lan,
                    connections = entry.connections,
                    latitude = entry.latitude,
                    longitude = entry.longitude,
                    importedFrom = entry.importedFrom,
                ),
            )
        }

        private fun accepts(
            entry: HistoryEntry,
            kind: String,
            placeKey: String,
            whenMs: Long,
            latitude: Double?,
            longitude: Double?,
        ): Boolean {
            if (entry.importedFrom != null || entry.captureId != null || !isSameDay(entry.savedAt, whenMs)) return false
            if (!sameFamily(entry.kind, kind)) return false
            return sameSpot(entry.placeKey, entry.latitude, entry.longitude, placeKey, latitude, longitude)
        }

        private fun together(a: HistoryEntry, b: HistoryEntry): Boolean {
            if (a.importedFrom != null || b.importedFrom != null) return false
            if (a.captureId != null || b.captureId != null) return false
            if (!isSameDay(a.savedAt, b.savedAt)) return false
            if (!sameFamily(a.kind, b.kind)) return false
            return sameSpot(a.placeKey, a.latitude, a.longitude, b.placeKey ?: "", b.latitude, b.longitude)
        }

        private fun sameFamily(a: String, b: String): Boolean = a == b

        private fun sameSpot(
            keyA: String?,
            latA: Double?,
            lonA: Double?,
            keyB: String,
            latB: Double?,
            lonB: Double?,
        ): Boolean {
            if ((keyA ?: "") == keyB) return true
            if (latA != null && lonA != null && latB != null && lonB != null) {
                return metres(latA, lonA, latB, lonB) <= MERGE_DISTANCE_METRES
            }
            return false
        }

        /** Keeps [base]'s id and lets its records win a tie. A LAN key outlives a geo bucket. */
        private fun fold(base: HistoryEntry, extra: HistoryEntry): HistoryEntry = HistoryEntry(
            id = base.id,
            kind = base.kind,
            savedAt = base.savedAt,
            source = base.source,
            captureId = null,
            placeKey = preferKey(base.placeKey, extra.placeKey),
            placeLabel = base.placeLabel ?: extra.placeLabel,
            lines = mergeLines(extra.lines, base.lines),
            networks = mergeNetworks(extra.networks, base.networks),
            probes = mergeBy(extra.probes, base.probes) { it.mac },
            deauths = mergeBy(extra.deauths, base.deauths) { it.id },
            lan = mergeLan(extra.lan, base.lan),
            bluetooth = mergeBle(extra.bluetooth, base.bluetooth),
            connections = mergeConnections(extra.connections, base.connections),
            latitude = base.latitude ?: extra.latitude,
            longitude = base.longitude ?: extra.longitude,
            importedFrom = base.importedFrom ?: extra.importedFrom,
        )

        private fun preferKey(a: String?, b: String?): String? = when {
            a?.startsWith("lan:") == true -> a
            b?.startsWith("lan:") == true -> b
            !a.isNullOrEmpty() -> a
            else -> b
        }

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

        /** Two scans within this many metres join one session. */
        const val MERGE_DISTANCE_METRES = 75.0

        /** Fast equirectangular distance in metres; exact enough at these ranges. */
        fun metres(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val dLat = (lat1 - lat2) * 111_320
            val dLon = (lon1 - lon2) * 111_320 * Math.cos(Math.toRadians(lat1))
            return Math.sqrt(dLat * dLat + dLon * dLon)
        }

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

        /** Newest first. A later check with the same start time replaces the earlier copy. */
        fun mergeConnections(existing: List<SavedConnection>, incoming: List<SavedConnection>): List<SavedConnection> {
            val seen = HashSet<Long>()
            val merged = mutableListOf<SavedConnection>()
            for (item in incoming + existing) {
                if (seen.add(item.checkedAt)) merged.add(item)
            }
            return merged.take(CONNECTION_LIMIT)
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
                ).also {
                    // Once new in this session, new for the whole session.
                    it.isNew = old.isNew || new.isNew
                }
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
            if (entry.connections.isNotEmpty()) json.put("connections", JSONArray().apply { entry.connections.forEach { put(it.toJson()) } })
            entry.captureId?.let { json.put("captureId", it) }
            entry.placeKey?.let { json.put("placeKey", it) }
            entry.placeLabel?.let { json.put("placeLabel", it) }
            val lat = entry.latitude
            val lon = entry.longitude
            if (lat != null && lon != null) json.put("latitude", lat).put("longitude", lon)
            entry.importedFrom?.let { json.put("importedFrom", it) }
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
                connections = list("connections") { SavedConnection.fromJson(it) },
                latitude = if (obj.has("latitude")) obj.getDouble("latitude") else null,
                longitude = if (obj.has("longitude")) obj.getDouble("longitude") else null,
                importedFrom = obj.optString("importedFrom").takeIf { it.isNotBlank() },
            )
        }
    }
}
