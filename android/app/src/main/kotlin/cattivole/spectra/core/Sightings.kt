package cattivole.spectra.core

/**
 * When each network, host and Bluetooth device was first met, across every session in History.
 *
 * A list starts empty on each launch, so "first seen" counted from there was always today. The dates come from
 * here instead, and go on into the new session, so they outlive the old ones History drops. Sessions imported
 * from another phone do not count: that phone met those devices, not this one. Mirrors the iOS `Sightings`.
 */
class Sightings(entries: Iterable<HistoryEntry> = emptyList()) {
    private val wifi = HashMap<MacAddress, Long>()
    private val bluetooth = HashMap<String, Long>()

    /** Hosts by anything that names them: `mac:…`, `name:…`, and the address within one network, `<network>|ip:…`. */
    private val hosts = HashMap<String, Long>()

    init {
        entries.forEach(::note)
    }

    fun note(entry: HistoryEntry) {
        if (entry.importedFrom != null) return
        for (network in entry.networks) wifi.keepEarlier(network.id, network.firstSeen)
        for (device in entry.bluetooth) bluetooth.keepEarlier(device.address, device.firstSeen)
        for (host in entry.lan) {
            for (key in keys(host, listOfNotNull(entry.placeKey))) hosts.keepEarlier(key, host.firstSeen)
        }
    }

    fun first(bssid: MacAddress): Long? = wifi[bssid]

    fun firstBluetooth(address: String): Long? = bluetooth[address]

    /**
     * The host by its hardware address or a name of its own on any network, or by its address on [networks]: the keys
     * one network may have been saved under.
     */
    fun first(device: LanDevice, networks: List<String>): Long? = keys(device, networks).mapNotNull { hosts[it] }.minOrNull()

    /** Moves each record's first sighting in [entry] back to the earliest one known. True when any moved. */
    fun date(entry: HistoryEntry): Boolean {
        if (entry.importedFrom != null) return false
        var moved = false
        for (network in entry.networks) {
            val date = first(network.id) ?: continue
            if (date < network.firstSeen) { network.firstSeen = date; moved = true }
        }
        for (device in entry.bluetooth) {
            val date = firstBluetooth(device.address) ?: continue
            if (date < device.firstSeen) { device.firstSeen = date; moved = true }
        }
        for (host in entry.lan) {
            val date = first(host, listOfNotNull(entry.placeKey)) ?: continue
            if (date < host.firstSeen) { host.firstSeen = date; moved = true }
        }
        return moved
    }

    /** The phone itself is on every network, so it is dated per network, by its address there. */
    private fun keys(device: LanDevice, networks: List<String>): List<String> {
        val own = if (device.isLocalDevice) emptyList() else device.fingerprints.filterNot { it.startsWith("ip:") }
        return own + networks.filter { it.isNotEmpty() }.map { "$it|ip:${device.ipAddress}" }
    }

    /** A date of 0 was never written: it is a record saved without one. */
    private fun <K> HashMap<K, Long>.keepEarlier(key: K, date: Long) {
        if (date <= 0) return
        val known = this[key]
        if (known == null || date < known) this[key] = date
    }
}
