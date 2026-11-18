package cattivole.spectra.assistant

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * What the assistant keeps between chats: notes it wrote down, and what each device has looked like over time.
 * Notes carry an optional subject — an address, a BSSID, a device name — and only the notes whose subject is in
 * front of the user right now travel with a question. Mirrors AssistantMemory.swift.
 */
class AssistantMemory(private val file: File) {
    data class Note(val id: UUID, val text: String, val createdAt: Long, val subject: String?)

    /** What one device has been, across every time it was seen. */
    data class Baseline(
        val subject: String,
        val label: String,
        val kind: Kind,
        val firstSeen: Long,
        val lastSeen: Long,
        val sightings: Int,
        /** Every port ever seen open on it. */
        val ports: List<Int> = emptyList(),
        val rssiStrongest: Int? = null,
        val rssiWeakest: Int? = null,
    ) {
        enum class Kind { HOST, WIFI, BLUETOOTH }

        /** Seen often enough, and for long enough, to call anything "usual" for it. */
        val isEstablished: Boolean get() = sightings >= 3 && lastSeen - firstSeen >= 3_600_000L
    }

    /** One device as it looks right now, on its way into a baseline. */
    data class Sighting(
        val subject: String,
        val label: String,
        val kind: Baseline.Kind,
        val rssi: Int? = null,
        val ports: List<Int> = emptyList(),
        /** False while the ports of this device have not been probed, so an empty list is "unknown", not "none". */
        val portsKnown: Boolean = false,
    )

    var notes: List<Note> by mutableStateOf(emptyList())
        private set
    var baselines: Map<String, Baseline> by mutableStateOf(emptyMap())
        private set

    init {
        load()
    }

    // MARK: - Notes

    fun add(text: String, subject: String? = null) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        val key = normalize(subject)
        if (notes.any { it.text == trimmed && it.subject == key }) return
        val next = (notes + Note(UUID.randomUUID(), trimmed, System.currentTimeMillis(), key)).toMutableList()
        if (next.size > NOTE_LIMIT) {
            // Drop the oldest unattached note; if they are all attached, the oldest of those.
            val victim = next.indexOfFirst { it.subject == null }.takeIf { it >= 0 } ?: 0
            next.removeAt(victim)
        }
        notes = next
        save()
    }

    fun remove(id: UUID) {
        notes = notes.filterNot { it.id == id }
        save()
    }

    fun removeMatching(text: String): Boolean {
        val lowered = text.lowercase()
        val key = normalize(text)
        val before = notes.size
        notes = notes.filterNot { it.text.lowercase().contains(lowered) || it.subject == key }
        if (notes.size != before) {
            save()
            return true
        }
        return false
    }

    fun removeAll() {
        notes = emptyList()
        baselines = emptyMap()
        save()
    }

    // MARK: - Baselines

    /** Folds what is on screen now into the long-term picture. Called often and cheap on purpose. */
    fun observe(sightings: List<Sighting>, now: Long = System.currentTimeMillis()) {
        if (sightings.isEmpty()) return
        var changed = false
        val next = baselines.toMutableMap()
        for (sighting in sightings) {
            val key = normalize(sighting.subject) ?: continue
            var entry = next[key] ?: Baseline(key, sighting.label, sighting.kind, now, now, 0)
            // A sighting within the same few minutes is the same visit, not a new one.
            if (entry.sightings == 0 || now - entry.lastSeen > 300_000L) {
                entry = entry.copy(sightings = entry.sightings + 1)
                changed = true
            }
            if (entry.label != sighting.label && sighting.label.isNotEmpty()) {
                entry = entry.copy(label = sighting.label)
                changed = true
            }
            entry = entry.copy(lastSeen = now)
            sighting.rssi?.let { rssi ->
                if (rssi > (entry.rssiStrongest ?: Int.MIN_VALUE)) {
                    entry = entry.copy(rssiStrongest = rssi)
                    changed = true
                }
                if (rssi < (entry.rssiWeakest ?: Int.MAX_VALUE)) {
                    entry = entry.copy(rssiWeakest = rssi)
                    changed = true
                }
            }
            if (sighting.portsKnown) {
                val merged = (entry.ports + sighting.ports).toSortedSet().toList()
                if (merged != entry.ports) {
                    entry = entry.copy(ports = merged)
                    changed = true
                }
            }
            next[key] = entry
        }
        baselines = next
        if (changed) save()
    }

    /** What is unusual about this device compared with every earlier time it was seen. */
    fun departures(sighting: Sighting, now: Long = System.currentTimeMillis()): List<String> {
        val key = normalize(sighting.subject) ?: return emptyList()
        val base = baselines[key]?.takeIf { it.isEstablished } ?: return emptyList()
        val found = mutableListOf<String>()
        if (sighting.portsKnown) {
            val fresh = (sighting.ports.toSet() - base.ports.toSet()).sorted()
            if (fresh.isNotEmpty()) found.add("ports not seen on it before: " + fresh.joinToString(", "))
        }
        if (now - base.lastSeen > 7L * 24 * 3600 * 1000) found.add("not seen for over a week before now")
        return found
    }

    // MARK: - What travels with a question

    /** The dossier as the model sees it: notes about what is in front of the user, the general ones, and baselines. */
    fun digest(subjects: Set<String>, budget: Int = 700): String? {
        val relevant = notes.filter { note ->
            val subject = note.subject ?: return@filter true
            subject in subjects || subjects.any { it.contains(subject) || subject.contains(it) }
        }
        val established = baselines.values.filter { it.isEstablished && it.subject in subjects }.sortedByDescending { it.sightings }
        if (relevant.isEmpty() && established.isEmpty()) return null
        val lines = mutableListOf("Dossier (what you noted in earlier chats, and what Spectra has seen before):")
        // Newest last, so a note written minutes ago is the one that survives the budget cut below.
        for (note in relevant.sortedBy { it.createdAt }.takeLast(20)) {
            lines.add("- " + (note.subject?.let { "[$it] " } ?: "") + note.text)
        }
        for (base in established.take(6)) {
            val parts = mutableListOf("${base.label} (${base.subject})", "seen ${base.sightings}× since ${day(base.firstSeen)}")
            if (base.ports.isNotEmpty()) parts.add("usual ports: " + base.ports.take(8).joinToString(", "))
            val strong = base.rssiStrongest
            val weak = base.rssiWeakest
            if (strong != null && weak != null && strong != weak) parts.add("signal $weak…$strong dBm")
            lines.add("- usual for " + parts.joinToString(" · "))
        }
        var text = lines.joinToString("\n")
        if (text.length > budget) text = text.take(budget) + "\n…(dossier trimmed)"
        return text
    }

    // MARK: - Persistence

    private fun load() {
        val raw = runCatching { file.readText() }.getOrNull() ?: return
        runCatching {
            val root = JSONObject(raw)
            val list = root.optJSONArray("notes") ?: JSONArray()
            notes = (0 until list.length()).map { list.getJSONObject(it) }.map {
                Note(
                    UUID.fromString(it.getString("id")),
                    it.getString("text"),
                    it.getLong("createdAt"),
                    if (it.isNull("subject")) null else it.optString("subject").ifEmpty { null },
                )
            }
            val bases = root.optJSONObject("baselines") ?: JSONObject()
            baselines = bases.keys().asSequence().associateWith { key ->
                val o = bases.getJSONObject(key)
                Baseline(
                    subject = o.getString("subject"),
                    label = o.getString("label"),
                    kind = runCatching { Baseline.Kind.valueOf(o.getString("kind")) }.getOrDefault(Baseline.Kind.HOST),
                    firstSeen = o.getLong("firstSeen"),
                    lastSeen = o.getLong("lastSeen"),
                    sightings = o.getInt("sightings"),
                    ports = o.optJSONArray("ports")?.let { array -> (0 until array.length()).map { array.getInt(it) } }.orEmpty(),
                    rssiStrongest = if (o.has("rssiStrongest")) o.getInt("rssiStrongest") else null,
                    rssiWeakest = if (o.has("rssiWeakest")) o.getInt("rssiWeakest") else null,
                )
            }
        }
    }

    private fun save() {
        val root = JSONObject()
        root.put("notes", JSONArray(notes.map {
            JSONObject().put("id", it.id.toString()).put("text", it.text).put("createdAt", it.createdAt).put("subject", it.subject ?: JSONObject.NULL)
        }))
        val bases = JSONObject()
        for ((key, base) in baselines) {
            bases.put(key, JSONObject().apply {
                put("subject", base.subject)
                put("label", base.label)
                put("kind", base.kind.name)
                put("firstSeen", base.firstSeen)
                put("lastSeen", base.lastSeen)
                put("sightings", base.sightings)
                put("ports", JSONArray(base.ports))
                base.rssiStrongest?.let { put("rssiStrongest", it) }
                base.rssiWeakest?.let { put("rssiWeakest", it) }
            })
        }
        root.put("baselines", bases)
        runCatching {
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, file.name + ".tmp")
            temp.writeText(root.toString())
            temp.renameTo(file)
        }
    }

    companion object {
        /** How many notes are kept at all. Past this the oldest general notes go first. */
        const val NOTE_LIMIT = 120

        /** Lower-cased and trimmed, so `AA:BB:CC` written by hand and printed by the app are one subject. */
        fun normalize(subject: String?): String? = subject?.trim()?.lowercase()?.ifEmpty { null }

        private fun day(time: Long): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(time))
    }
}
