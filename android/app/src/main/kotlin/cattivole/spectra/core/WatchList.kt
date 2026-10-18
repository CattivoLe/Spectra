package cattivole.spectra.core

/**
 * Matching for the user's watch-list — BSSIDs and Bluetooth addresses they want
 * flagged the moment they are back on the air. The watched set itself is held
 * and persisted by the model; these helpers are pure, for tests.
 */
object WatchList {
    /** Watched networks in range right now, strongest first. */
    fun presentNetworks(ids: Set<String>, networks: Collection<TrackedNetwork>): List<TrackedNetwork> =
        networks.filter { it.isInRange && ids.contains(it.latest.bssid.text) }
            .sortedByDescending { it.latest.rssi }

    /** Watched Bluetooth devices heard recently, strongest first. */
    fun presentDevices(ids: Set<String>, devices: Collection<BleDevice>, now: Long): List<BleDevice> =
        devices.filter { it.isNearby(now) && ids.contains(it.address) }
            .sortedByDescending { it.rssi }
}
