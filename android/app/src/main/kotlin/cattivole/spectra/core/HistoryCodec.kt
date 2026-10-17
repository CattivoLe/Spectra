package cattivole.spectra.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * JSON for the records a phone save keeps. Saved objects are rebuilt from
 * this, so an entry is a snapshot: the live store goes on changing its own
 * objects without touching what was saved.
 */
object HistoryCodec {
    // Wi-Fi

    fun network(network: TrackedNetwork): JSONObject = JSONObject()
        .put("reading", reading(network.latest))
        .put("firstSeen", network.firstSeen)
        .put("lastSeen", network.lastSeen)
        .put("samples", JSONArray().apply { network.samples.forEach { put(JSONArray().put(it.date).put(it.rssi)) } })
        .put("missedScans", network.missedScans)
        .put("clients", JSONArray().apply { network.clients.forEach { put(client(it)) } })
        .putOpt("revealedSsid", network.revealedSsid)
        .putOpt("vendorName", network.vendorInfo?.deviceName)
        .putOpt("vendorMaker", network.vendorInfo?.manufacturer)
        .putOpt("trafficBytes", network.traffic?.bytes)
        .putOpt("trafficPackets", network.traffic?.packets)
        .put("source", network.source.name)

    fun network(json: JSONObject): TrackedNetwork? {
        val latest = reading(json.optJSONObject("reading") ?: return null) ?: return null
        val samples = json.optJSONArray("samples").orEmpty().mapNotNull { item ->
            (item as? JSONArray)?.let { TrackedNetwork.Sample(it.getLong(0), it.getInt(1)) }
        }
        val vendorName = json.optStringOrNull("vendorName")
        val vendorMaker = json.optStringOrNull("vendorMaker")
        return TrackedNetwork(
            latest = latest,
            firstSeen = json.optLong("firstSeen"),
            lastSeen = json.optLong("lastSeen"),
            samples = samples.toMutableList(),
            missedScans = json.optInt("missedScans"),
            clients = json.optJSONArray("clients").orEmpty().mapNotNull { (it as? JSONObject)?.let(::client) }.toMutableList(),
            revealedSsid = json.optStringOrNull("revealedSsid"),
            vendorInfo = if (vendorName != null || vendorMaker != null) VendorDeviceInfo(vendorName.orEmpty(), vendorMaker.orEmpty()) else null,
            traffic = if (json.has("trafficBytes")) TrafficSnapshot(json.optLong("trafficBytes"), json.optInt("trafficPackets")) else null,
            source = runCatching { RadioSource.valueOf(json.optString("source")) }.getOrDefault(RadioSource.SENSOR),
        )
    }

    private fun reading(reading: AccessPointReading): JSONObject = JSONObject()
        .put("bssid", reading.bssid.text)
        .put("rssi", reading.rssi)
        .put("channel", reading.channel)
        .put("secondary", reading.secondaryChannel.raw)
        .put("auth", reading.auth.raw)
        .put("pairwise", reading.pairwiseCipher.raw)
        .put("group", reading.groupCipher.raw)
        .put("capabilities", reading.capabilities)
        .putOpt("country", reading.countryCode)
        .put("ssid", hex(reading.ssidBytes))
        .put("band", reading.band.name)
        .putOpt("width", reading.widthMHz)
        .putOpt("centre", reading.centreChannel)
        .put("visibility", reading.visibility.name)
        .putOpt("phy", reading.phyNote)

    private fun reading(json: JSONObject): AccessPointReading? {
        val bssid = MacAddress.parse(json.optString("bssid")) ?: return null
        return AccessPointReading(
            bssid = bssid,
            rssi = json.optInt("rssi", -100),
            channel = json.optInt("channel"),
            secondaryChannel = SecondaryChannel.from(json.optInt("secondary")),
            auth = AuthMode(json.optInt("auth")),
            pairwiseCipher = Cipher(json.optInt("pairwise")),
            groupCipher = Cipher(json.optInt("group")),
            capabilities = json.optInt("capabilities"),
            countryCode = json.optStringOrNull("country"),
            ssidBytes = unhex(json.optString("ssid")),
            band = runCatching { Band.valueOf(json.optString("band")) }.getOrDefault(Band.TWO_FOUR),
            widthMHz = if (json.has("width")) json.optInt("width") else null,
            visibility = runCatching { SsidVisibility.valueOf(json.optString("visibility")) }.getOrDefault(SsidVisibility.NAMED),
            phyNote = json.optStringOrNull("phy"),
            centreChannel = if (json.has("centre")) json.optDouble("centre") else null,
        )
    }

    private fun client(client: WifiClient): JSONObject = JSONObject()
        .put("mac", client.mac.text)
        .putOpt("ap", client.apBssid?.text)
        .put("rssi", client.rssi)
        .put("associated", client.isAssociated)

    private fun client(json: JSONObject): WifiClient? {
        val mac = MacAddress.parse(json.optString("mac")) ?: return null
        return WifiClient(
            mac = mac,
            apBssid = json.optStringOrNull("ap")?.let { MacAddress.parse(it) },
            rssi = json.optInt("rssi", -100),
            isAssociated = json.optBoolean("associated"),
        )
    }

    fun probe(device: ProbingDevice): JSONObject = JSONObject()
        .put("mac", device.mac.text)
        .put("rssi", device.rssi)
        .put("ssids", JSONArray(device.ssids))
        .put("channel", device.channel)

    fun probe(json: JSONObject): ProbingDevice? {
        val mac = MacAddress.parse(json.optString("mac")) ?: return null
        return ProbingDevice(
            mac = mac,
            rssi = json.optInt("rssi", -100),
            ssids = json.optJSONArray("ssids").orEmpty().map { it.toString() }.toMutableList(),
            channel = json.optInt("channel"),
        )
    }

    fun deauth(event: DeauthEvent): JSONObject = JSONObject()
        .put("source", event.source.text)
        .put("target", event.target.text)
        .put("bssid", event.bssid.text)
        .put("reason", event.reason)
        .put("rssi", event.rssi)
        .put("channel", event.channel)
        .put("count", event.count)

    fun deauth(json: JSONObject): DeauthEvent? {
        val source = MacAddress.parse(json.optString("source")) ?: return null
        val target = MacAddress.parse(json.optString("target")) ?: return null
        val bssid = MacAddress.parse(json.optString("bssid")) ?: return null
        return DeauthEvent(
            source = source,
            target = target,
            bssid = bssid,
            reason = json.optInt("reason"),
            rssi = json.optInt("rssi", -100),
            channel = json.optInt("channel"),
            count = json.optInt("count", 1),
        )
    }

    // LAN

    fun lan(device: LanDevice): JSONObject = JSONObject()
        .put("ip", device.ipAddress)
        .putOpt("mac", device.macAddress?.text)
        .put("hostnames", JSONArray(device.hostnames))
        .put("ports", JSONArray().apply { device.openPorts.forEach { put(JSONArray().put(it.number).put(it.service)) } })
        .put("services", JSONArray().apply {
            device.services.forEach { put(JSONObject().put("type", it.type).put("name", it.name).putOpt("model", it.model)) }
        })
        .put("portsScanned", device.portsScanned)
        .put("gateway", device.isGateway)
        .put("local", device.isLocalDevice)
        .put("profile", profile(device.profile))
        .put("firstSeen", device.firstSeen)
        .put("lastSeen", device.lastSeen)

    fun lan(json: JSONObject): LanDevice? {
        val ip = json.optStringOrNull("ip") ?: return null
        return LanDevice(
            ipAddress = ip,
            macAddress = json.optStringOrNull("mac")?.let { MacAddress.parse(it) },
            hostnames = json.optJSONArray("hostnames").orEmpty().map { it.toString() },
            openPorts = json.optJSONArray("ports").orEmpty().mapNotNull { item ->
                (item as? JSONArray)?.let { DiscoveredPort(it.getInt(0), it.optString(1)) }
            },
            services = json.optJSONArray("services").orEmpty().mapNotNull { item ->
                (item as? JSONObject)?.let { ServiceAdvertisement(it.optString("type"), it.optString("name"), it.optStringOrNull("model")) }
            },
            portsScanned = json.optBoolean("portsScanned"),
            isGateway = json.optBoolean("gateway"),
            isLocalDevice = json.optBoolean("local"),
            profile = json.optJSONObject("profile")?.let(::profile) ?: LanProfile(),
            firstSeen = json.optLong("firstSeen"),
            lastSeen = json.optLong("lastSeen"),
        )
    }

    private fun profile(profile: LanProfile): JSONObject = JSONObject()
        .putOpt("upnp", profile.upnp?.let {
            JSONObject()
                .putOpt("name", it.friendlyName)
                .putOpt("maker", it.manufacturer)
                .putOpt("model", it.model)
                .putOpt("type", it.deviceType)
                .putOpt("server", it.server)
        })
        .put("banners", JSONArray().apply {
            profile.banners.forEach {
                put(
                    JSONObject()
                        .put("port", it.port)
                        .put("secure", it.secure)
                        .putOpt("status", it.status)
                        .putOpt("server", it.server)
                        .putOpt("poweredBy", it.poweredBy)
                        .putOpt("title", it.title)
                        .putOpt("realm", it.realm),
                )
            }
        })
        .put("rtsp", JSONArray().apply {
            profile.rtsp.forEach { put(JSONObject().put("port", it.port).putOpt("server", it.server).putOpt("methods", it.methods)) }
        })
        .putOpt("windows", profile.windows?.let {
            JSONObject()
                .putOpt("computer", it.computerName)
                .putOpt("domain", it.domain)
                .put("shares", JSONArray(it.shares))
                .put("guest", it.guestSession)
        })

    private fun profile(json: JSONObject): LanProfile = LanProfile(
        upnp = json.optJSONObject("upnp")?.let {
            UPnPIdentity(
                friendlyName = it.optStringOrNull("name"),
                manufacturer = it.optStringOrNull("maker"),
                model = it.optStringOrNull("model"),
                deviceType = it.optStringOrNull("type"),
                server = it.optStringOrNull("server"),
            )
        },
        banners = json.optJSONArray("banners").orEmpty().mapNotNull { item ->
            (item as? JSONObject)?.let {
                HttpBanner(
                    port = it.optInt("port"),
                    secure = it.optBoolean("secure"),
                    status = if (it.has("status")) it.optInt("status") else null,
                    server = it.optStringOrNull("server"),
                    poweredBy = it.optStringOrNull("poweredBy"),
                    title = it.optStringOrNull("title"),
                    realm = it.optStringOrNull("realm"),
                )
            }
        },
        rtsp = json.optJSONArray("rtsp").orEmpty().mapNotNull { item ->
            (item as? JSONObject)?.let { RtspService(it.optInt("port"), it.optStringOrNull("server"), it.optStringOrNull("methods")) }
        },
        windows = json.optJSONObject("windows")?.let {
            WindowsHost(
                computerName = it.optStringOrNull("computer"),
                domain = it.optStringOrNull("domain"),
                shares = it.optJSONArray("shares").orEmpty().map { share -> share.toString() },
                guestSession = it.optBoolean("guest"),
            )
        },
    )

    // Bluetooth

    fun ble(device: BleDevice): JSONObject = JSONObject()
        .put("address", device.address)
        .put("kind", device.addressKind)
        .put("transport", device.transport.name)
        .putOpt("localName", device.localName)
        .putOpt("peripheralName", device.peripheralName)
        .putOpt("companyId", device.companyId)
        .put("payload", hex(device.manufacturerPayload))
        .put("services", JSONArray(device.services.map { it.uuid }))
        .put("solicited", JSONArray(device.solicitedServices.map { it.uuid }))
        .put("serviceData", JSONObject().apply { device.serviceData.forEach { put(it.uuid, hex(it.data)) } })
        .putOpt("txPower", device.txPower)
        .putOpt("connectable", device.isConnectable)
        .put("rssi", device.rssi)
        .put("samples", JSONArray().apply { device.samples.forEach { put(JSONArray().put(it.date).put(it.rssi)) } })
        .put("packets", device.packetCount)
        .put("firstSeen", device.firstSeen)
        .put("lastSeen", device.lastSeen)
        .putOpt("class", device.deviceClass)

    /** Rebuilt through the advertisement parser, so names and Apple messages come back as they were read. */
    fun ble(json: JSONObject): BleDevice? {
        val address = json.optStringOrNull("address") ?: return null
        val transport = runCatching { BleDevice.Transport.valueOf(json.optString("transport")) }.getOrDefault(BleDevice.Transport.LE)
        val firstSeen = json.optLong("firstSeen")
        val device = BleDevice(address, json.optString("kind"), json.optInt("rssi", -100), firstSeen, transport)
        val companyId = if (json.has("companyId")) json.optInt("companyId") else null
        val payload = unhex(json.optString("payload"))
        val manufacturer = companyId?.let { byteArrayOf((it and 0xFF).toByte(), ((it shr 8) and 0xFF).toByte()) + payload }
        val serviceData = json.optJSONObject("serviceData")?.let { data ->
            data.keys().asSequence().associateWith { unhex(data.optString(it)) }
        }.orEmpty()
        val fields = BleFields(
            localName = json.optStringOrNull("localName"),
            manufacturerData = manufacturer,
            serviceUuids = json.optJSONArray("services").orEmpty().map { it.toString() },
            solicitedServiceUuids = json.optJSONArray("solicited").orEmpty().map { it.toString() },
            serviceData = serviceData,
            txPower = if (json.has("txPower")) json.optInt("txPower") else null,
            isConnectable = if (json.has("connectable")) json.optBoolean("connectable") else null,
        )
        device.restore(
            advertisement = BleAdvertisement.parse(fields),
            peripheralName = json.optStringOrNull("peripheralName"),
            samples = json.optJSONArray("samples").orEmpty().mapIndexedNotNull { index, item ->
                (item as? JSONArray)?.let { BleDevice.Sample(index + 1, it.getLong(0), it.getInt(1)) }
            },
            packets = json.optInt("packets"),
            lastSeen = json.optLong("lastSeen", firstSeen),
            deviceClass = json.optStringOrNull("class"),
        )
        return device
    }

    // Helpers

    private fun JSONArray?.orEmpty(): List<Any> {
        if (this == null) return emptyList()
        return (0 until length()).map { get(it) }
    }

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (has(key) && !isNull(key)) optString(key) else null

    private fun unhex(text: String): ByteArray {
        val clean = text.filter { it.isLetterOrDigit() }
        if (clean.length % 2 != 0) return ByteArray(0)
        return ByteArray(clean.length / 2) { index ->
            clean.substring(index * 2, index * 2 + 2).toIntOrNull(16)?.toByte() ?: 0
        }
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
}
