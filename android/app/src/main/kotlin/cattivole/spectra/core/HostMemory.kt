package cattivole.spectra.core

import org.json.JSONObject

/** Where [KnownHosts] and [DeviceLabels] keep their text; preferences in the app, a variable in tests. */
interface TextStore {
    fun read(key: String): String?
    fun write(key: String, value: String)
}

class MemoryTextStore : TextStore {
    private val values = HashMap<String, String>()
    override fun read(key: String): String? = values[key]
    override fun write(key: String, value: String) {
        values[key] = value
    }
}

/**
 * The hosts each network has shown before, so a scan can point out the ones
 * that were never there. Kept per network key and persisted across launches,
 * apart from History: clearing saved scans does not make every device new
 * again. Mirrors the iOS `KnownHosts`.
 *
 * A host is matched by any of several fingerprints: its MAC when known, the
 * names it gives out, and its address. A later scan that loses the MAC still
 * finds the host by name or address.
 */
class KnownHosts(private val store: TextStore = MemoryTextStore()) {
    /** What one scan found out about its devices. */
    data class Verdict(
        /** The first scan of this network only records what is there. */
        val isBaseline: Boolean,
        val newIps: List<String>,
    )

    /** network key -> fingerprint -> first time it was seen. */
    private val networks = HashMap<String, MutableMap<String, Long>>()

    init {
        runCatching {
            val root = JSONObject(store.read(KEY) ?: "{}")
            for (network in root.keys()) {
                val prints = root.getJSONObject(network)
                val map = HashMap<String, Long>()
                for (print in prints.keys()) map[print] = prints.getLong(print)
                networks[network] = map
            }
        }
    }

    fun knows(network: String): Boolean = networks.containsKey(network)

    /**
     * Flags the devices this network has never shown and remembers all of
     * them. The gateway and this phone are never new.
     */
    fun classify(devices: Collection<LanDevice>, network: String, now: Long = System.currentTimeMillis()): Verdict {
        val isBaseline = !networks.containsKey(network)
        val known = networks.getOrPut(network) { HashMap() }
        val newIps = mutableListOf<String>()
        for (device in devices) {
            val prints = device.fingerprints
            val seen = prints.any { it in known }
            if (!isBaseline && !seen && !device.isGateway && !device.isLocalDevice) {
                device.isNew = true
                newIps.add(device.ipAddress)
            }
            // A host matched by one fingerprint teaches the others, so the
            // same device found later by its address alone is still known.
            val since = prints.mapNotNull { known[it] }.minOrNull() ?: now
            for (print in prints) if (print !in known) known[print] = since
        }
        persist()
        return Verdict(isBaseline, newIps)
    }

    /** When this network first showed the device, by any of its fingerprints. */
    fun firstSeen(device: LanDevice, network: String): Long? {
        val known = networks[network] ?: return null
        return device.fingerprints.mapNotNull { known[it] }.minOrNull()
    }

    /** Drops everything learned about a network: its next scan is a baseline again. */
    fun forget(network: String) {
        networks.remove(network)
        persist()
    }

    private fun persist() {
        val root = JSONObject()
        for ((network, prints) in networks) root.put(network, JSONObject(prints as Map<*, *>))
        store.write(KEY, root.toString())
    }

    private companion object {
        const val KEY = "knownHosts"
    }
}

/** What the owner thinks of a host. */
enum class DeviceTrust(val label: String) {
    /** Nothing decided yet. */
    UNKNOWN("Not decided"),

    /** Mine, or a guest I know: no need to look at it again. */
    TRUSTED("Trusted"),

    /** Something to keep an eye on. */
    SUSPICIOUS("Suspicious"),
}

/** A name, a verdict and a note the user attached to a host. */
data class DeviceLabel(val name: String? = null, val trust: DeviceTrust = DeviceTrust.UNKNOWN, val note: String? = null) {
    val isEmpty: Boolean get() = name == null && trust == DeviceTrust.UNKNOWN && note == null

    /** Blank text counts as no text. */
    fun trimmed(): DeviceLabel = DeviceLabel(name?.trim()?.ifEmpty { null }, trust, note?.trim()?.ifEmpty { null })
}

/**
 * The user's labels for hosts, found again by the same fingerprints
 * [KnownHosts] uses. Persisted across launches, on this phone only.
 */
class DeviceLabels(private val store: TextStore = MemoryTextStore()) {
    private val labels = HashMap<String, DeviceLabel>()

    init {
        runCatching {
            val root = JSONObject(store.read(KEY) ?: "{}")
            for (print in root.keys()) {
                val item = root.getJSONObject(print)
                labels[print] = DeviceLabel(
                    name = item.optString("name").ifEmpty { null },
                    trust = DeviceTrust.entries.firstOrNull { it.name == item.optString("trust") } ?: DeviceTrust.UNKNOWN,
                    note = item.optString("note").ifEmpty { null },
                )
            }
        }
    }

    /**
     * The label for this host. A match on the address alone only counts when
     * the address is all the host has: another device that took over the
     * address must not inherit a "trusted".
     */
    fun label(device: LanDevice): DeviceLabel? {
        val prints = device.fingerprints
        val onlyAddress = prints.all { it.startsWith("ip:") }
        for (print in prints) {
            if (print.startsWith("ip:") && !onlyAddress) continue
            labels[print]?.let { return it }
        }
        return null
    }

    /**
     * Stores the label under every fingerprint of the host, replacing what
     * those fingerprints had, and writes it onto the host.
     */
    fun set(label: DeviceLabel, device: LanDevice) {
        val cleaned = label.trimmed()
        for (print in device.fingerprints) {
            if (cleaned.isEmpty) labels.remove(print) else labels[print] = cleaned
        }
        persist()
        apply(device)
    }

    /** Copies the stored label, if any, onto the host. */
    fun apply(device: LanDevice) {
        val label = label(device)
        device.customName = label?.name
        device.trust = label?.trust ?: DeviceTrust.UNKNOWN
        device.note = label?.note
        if (device.trust == DeviceTrust.TRUSTED) device.isNew = false
    }

    private fun persist() {
        val root = JSONObject()
        for ((print, label) in labels) {
            val item = JSONObject().put("trust", label.trust.name)
            label.name?.let { item.put("name", it) }
            label.note?.let { item.put("note", it) }
            root.put(print, item)
        }
        store.write(KEY, root.toString())
    }

    private companion object {
        const val KEY = "deviceLabels"
    }
}

/**
 * Every way this host can be recognised again: hardware address, the names
 * it announces, and its address. Weak names ("android") and bare identifiers
 * are left out, since two devices can share them.
 */
val LanDevice.fingerprints: List<String>
    get() {
        val prints = mutableListOf<String>()
        macAddress?.let { prints.add("mac:${it.text}") }
        val names = hostnames + listOfNotNull(profile.upnp?.friendlyName, profile.windows?.computerName)
        for (raw in names) {
            val name = LanDevice.usefulHostname(raw, ipAddress) ?: continue
            val folded = name.lowercase().removeSuffix(".local")
            if (folded.isEmpty() || LanDevice.isWeakHostname(folded)) continue
            val print = "name:$folded"
            if (print !in prints) prints.add(print)
        }
        prints.add("ip:$ipAddress")
        return prints
    }
