package cattivole.spectra.core

/**
 * Access points followed across scans. The sensor and the phone radio are
 * separate streams: a phone sweep must not age out a network the Cardputer
 * still hears, and the other way around. When both have seen a BSSID, the
 * sensor record wins while it is still in range, because it carries clients,
 * ciphers and the real channel width.
 */
class NetworkStore {
    private val sensor = linkedMapOf<MacAddress, TrackedNetwork>()
    private val phone = linkedMapOf<MacAddress, TrackedNetwork>()

    var isScanning: Boolean = false
        private set
    var lastScanFinished: Long? = null
        private set
    var lastScanDurationMs: Int? = null
        private set
    var scanCount: Int = 0
        private set

    var clients: List<WifiClient> = emptyList()
        private set
    var probingDevices: List<ProbingDevice> = emptyList()
        private set
    var deauthEvents: List<DeauthEvent> = emptyList()
        private set

    /** What each client sent in the latest scan. Empty with a sensor that does not count by client. */
    var clientTraffic: List<ClientTraffic> = emptyList()
        private set

    /** Clients that sent like a camera or a microphone in recent scans, steady or not yet. */
    var senders: Map<MacAddress, TrackedSender> = emptyMap()
        private set

    /** Those that kept it up, busiest first. */
    val steadySenders: List<TrackedSender>
        get() = senders.values.filter { it.isSteady }.sortedByDescending { it.traffic.upBytesPerSecond }

    val probingClients: List<WifiClient> get() = clients.filter { !it.isAssociated }
    val deauthAlert: Boolean get() = deauthEvents.any { it.severity >= DeauthEvent.Severity.WARNING }

    private var currentScanId: Int? = null
    private val seenInCurrentScan = mutableSetOf<MacAddress>()
    private val pendingClients = mutableListOf<WifiClient>()
    private val pendingProbes = mutableListOf<ProbeRequest>()
    private val pendingDeauths = mutableListOf<DeauthEvent>()
    private val pendingTraffic = linkedMapOf<MacAddress, TrafficSnapshot>()
    private val pendingClientTraffic = mutableListOf<ClientTraffic>()

    private val listeners = mutableListOf<() -> Unit>()

    /** Gets networks just before they leave the list, so History can keep them. */
    var onEvict: ((List<TrackedNetwork>) -> Unit)? = null

    /** When a network was first met, if it was before: its card says so instead of "today". */
    var earlier: ((MacAddress) -> Long?)? = null

    /** The dates came in after the list had started to fill. */
    fun datesChanged() {
        val lookup = earlier ?: return
        for (network in sensor.values + phone.values) {
            lookup(network.id)?.let { if (it < network.firstSeen) network.firstSeen = it }
        }
        publish()
    }

    fun observe(listener: () -> Unit): () -> Unit {
        listeners.add(listener)
        return { listeners.remove(listener) }
    }

    /** What the phone radio reported, including networks the sensor list shows instead. */
    fun phoneSightings(): Collection<TrackedNetwork> = phone.values.toList()

    /** Sensor record when it is fresh, otherwise the phone's sighting. */
    fun networks(): Map<MacAddress, TrackedNetwork> {
        val merged = linkedMapOf<MacAddress, TrackedNetwork>()
        merged.putAll(phone)
        for ((id, fromSensor) in sensor) {
            val fromPhone = phone[id]
            merged[id] = when {
                fromSensor.isInRange -> fromSensor
                fromPhone != null && fromPhone.isInRange -> fromPhone
                else -> fromSensor
            }
        }
        return merged
    }

    fun network(id: MacAddress): TrackedNetwork? = networks()[id]

    fun handle(message: SensorProtocol.Message, at: Long = System.currentTimeMillis()) {
        when (message) {
            is SensorProtocol.Message.ScanStarted -> {
                currentScanId = message.scanId
                seenInCurrentScan.clear()
                pendingClients.clear()
                pendingProbes.clear()
                pendingDeauths.clear()
                pendingTraffic.clear()
                pendingClientTraffic.clear()
                isScanning = true
            }
            is SensorProtocol.Message.AccessPoint -> record(sensor, message.reading, at, RadioSource.SENSOR)
            is SensorProtocol.Message.Station -> recordClient(message.client)
            is SensorProtocol.Message.ProbeReq -> recordProbe(message.probe)
            is SensorProtocol.Message.Deauth -> pendingDeauths.add(message.event)
            is SensorProtocol.Message.SsidReveal -> {
                if (message.ssid.isNotEmpty()) sensor[message.bssid]?.revealedSsid = message.ssid
            }
            is SensorProtocol.Message.VendorIe -> {
                sensor[message.bssid]?.vendorInfo = VendorDeviceInfo(message.deviceName, message.manufacturer)
            }
            is SensorProtocol.Message.Traffic -> {
                pendingTraffic[message.bssid] = TrafficSnapshot(message.bytes, message.packets)
            }
            is SensorProtocol.Message.ClientSent -> pendingClientTraffic.add(message.traffic)
            is SensorProtocol.Message.Battery, is SensorProtocol.Message.Saved,
            is SensorProtocol.Message.Lora, is SensorProtocol.Message.LoraScan,
            is SensorProtocol.Message.LoraProgress,
            is SensorProtocol.Message.SavedPosition, is SensorProtocol.Message.Gps,
            is SensorProtocol.Message.KnownWifi,
            is SensorProtocol.Message.RawFrame,
            -> Unit
            is SensorProtocol.Message.ScanFinished -> finishSensorScan(message, at)
        }
        publish()
    }

    /**
     * One complete pass of the phone radio. Networks the phone did not report
     * age on their own counter and never touch the sensor's.
     */
    fun applyPhoneSnapshot(readings: List<AccessPointReading>, at: Long = System.currentTimeMillis()) {
        val seen = mutableSetOf<MacAddress>()
        for (reading in readings) {
            seen.add(reading.bssid)
            record(phone, reading, at, RadioSource.PHONE, countMiss = false)
        }
        for (id in phone.keys) {
            if (id !in seen) phone[id]?.let { it.missedScans += 1 }
        }
        evictGone(phone)
        publish()
    }

    private fun evictGone(from: MutableMap<MacAddress, TrackedNetwork>) {
        val gone = from.values.filter { it.missedScans >= SCANS_BEFORE_REMOVAL }
        if (gone.isEmpty()) return
        onEvict?.invoke(gone)
        gone.forEach { from.remove(it.id) }
    }

    fun clear() {
        sensor.clear()
        phone.clear()
        clients = emptyList()
        probingDevices = emptyList()
        deauthEvents = emptyList()
        clientTraffic = emptyList()
        senders = emptyMap()
        lastScanFinished = null
        lastScanDurationMs = null
        scanCount = 0
        publish()
    }

    fun abandonScan() {
        currentScanId = null
        isScanning = false
        publish()
    }

    private fun finishSensorScan(message: SensorProtocol.Message.ScanFinished, at: Long) {
        if (message.scanId == currentScanId) {
            for (id in sensor.keys) {
                if (id !in seenInCurrentScan) sensor[id]?.let { it.missedScans += 1 }
            }
            evictGone(sensor)
            assignClients()
            aggregateProbes()
            deauthEvents = pendingDeauths.sortedByDescending { it.count }
            pendingDeauths.clear()
            for ((id, network) in sensor) network.noteTraffic(pendingTraffic[id], judged = message.clientCount == null)
            pendingTraffic.clear()
            clientTraffic = pendingClientTraffic.toList()
            senders = if (message.clientCount == null) emptyMap() else TrackedSender.next(senders, clientTraffic)
            pendingClientTraffic.clear()
        }
        currentScanId = null
        isScanning = false
        lastScanFinished = at
        lastScanDurationMs = message.durationMs
        scanCount += 1
    }

    private fun record(
        into: MutableMap<MacAddress, TrackedNetwork>,
        reading: AccessPointReading,
        at: Long,
        source: RadioSource,
        countMiss: Boolean = true,
    ) {
        if (countMiss && source == RadioSource.SENSOR) seenInCurrentScan.add(reading.bssid)
        val sample = TrackedNetwork.Sample(at, reading.rssi)
        val existing = into[reading.bssid]
        if (existing != null) {
            existing.latest = reading
            existing.lastSeen = at
            existing.missedScans = 0
            existing.samples.add(sample)
            if (existing.samples.size > SAMPLES_KEPT) {
                existing.samples.subList(0, existing.samples.size - SAMPLES_KEPT).clear()
            }
        } else {
            into[reading.bssid] = TrackedNetwork(
                latest = reading,
                firstSeen = earlier?.invoke(reading.bssid)?.takeIf { it < at } ?: at,
                lastSeen = at,
                samples = mutableListOf(sample),
                source = source,
            )
        }
    }

    private fun recordProbe(probe: ProbeRequest) {
        val index = pendingProbes.indexOfFirst { it.mac == probe.mac && it.ssid == probe.ssid }
        if (index >= 0) {
            if (probe.rssi > pendingProbes[index].rssi) pendingProbes[index].rssi = probe.rssi
        } else {
            pendingProbes.add(probe)
        }
    }

    private fun aggregateProbes() {
        val byMac = linkedMapOf<MacAddress, ProbingDevice>()
        for (probe in pendingProbes) {
            val device = byMac[probe.mac]
            if (device != null) {
                if (probe.rssi > device.rssi) device.rssi = probe.rssi
                val ssid = probe.ssid
                if (!ssid.isNullOrEmpty() && ssid !in device.ssids) device.ssids.add(ssid)
            } else {
                val ssids = probe.ssid?.takeIf { it.isNotEmpty() }?.let { mutableListOf(it) } ?: mutableListOf()
                byMac[probe.mac] = ProbingDevice(probe.mac, probe.rssi, ssids, probe.channel)
            }
        }
        probingDevices = byMac.values.sortedByDescending { it.rssi }
        pendingProbes.clear()
    }

    private fun recordClient(client: WifiClient) {
        val index = pendingClients.indexOfFirst { it.mac == client.mac }
        if (index >= 0) {
            val existing = pendingClients[index]
            if (client.isAssociated && !existing.isAssociated) {
                pendingClients[index] = client
            } else if (client.rssi > existing.rssi) {
                existing.rssi = client.rssi
            }
        } else {
            pendingClients.add(client)
        }
    }

    private fun assignClients() {
        clients = pendingClients.sortedByDescending { it.rssi }
        for (network in sensor.values) network.clients = mutableListOf()
        for (client in pendingClients) {
            if (!client.isAssociated) continue
            val bssid = client.apBssid ?: continue
            sensor[bssid]?.clients?.add(client)
        }
        for (network in sensor.values) {
            network.clients.sortByDescending { it.rssi }
        }
        pendingClients.clear()
    }

    private fun publish() {
        listeners.toList().forEach { it() }
    }

    companion object {
        const val SCANS_BEFORE_OUT_OF_RANGE = 3
        /** Gone from the list after this many passes in a row without it. */
        const val SCANS_BEFORE_REMOVAL = 6
        const val SAMPLES_KEPT = 120
    }
}
