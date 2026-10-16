package cattivole.spectra.core

import kotlin.random.Random

/**
 * Stand-in for the Cardputer. It emits the same wire messages as the firmware
 * so the screens can be exercised without a radio.
 */
class DemoSensor(
    private val emit: (ByteArray) -> Unit,
    private val schedule: (delayMs: Long, block: () -> Unit) -> Unit,
    private val cancelSchedule: () -> Unit,
    private val random: Random = Random.Default,
) {
    var intervalSeconds: Int = 5
        set(value) {
            field = value
            scheduleNext()
        }

    private var scanId = 0
    private val networks = makeNetworks()
    private var battery = 72

    fun scan() {
        scanId = (scanId + 1) and 0xFFFF
        emit(SensorProtocol.encode(SensorProtocol.Message.ScanStarted(scanId)))
        var apSent = 0
        for (network in networks) {
            network.drift(random)
            val reading = network.reading(random) ?: continue
            emit(SensorProtocol.encode(SensorProtocol.Message.AccessPoint(scanId, reading)))
            apSent += 1
        }
        var stations = 0
        for (client in clients) {
            if (random.nextDouble() <= 0.15) continue
            val drifted = client.copy(rssi = client.rssi + random.nextInt(-4, 5))
            emit(SensorProtocol.encode(SensorProtocol.Message.Station(scanId, drifted)))
            stations += 1
        }
        var probes = 0
        for (probe in probeRequests) {
            if (random.nextDouble() <= 0.3) continue
            val drifted = probe.copy(rssi = probe.rssi + random.nextInt(-4, 5))
            emit(SensorProtocol.encode(SensorProtocol.Message.ProbeReq(scanId, drifted)))
            probes += 1
        }
        for (event in deauths) {
            if (random.nextDouble() <= 0.7) continue
            emit(SensorProtocol.encode(SensorProtocol.Message.Deauth(scanId, event)))
        }
        if (random.nextDouble() > 0.2) {
            emit(
                SensorProtocol.encode(
                    SensorProtocol.Message.SsidReveal(
                        scanId,
                        MacAddress(byteArrayOf(0x50, 0xC7.toByte(), 0xBF.toByte(), 0x1E, 0x77, 0xA7.toByte())),
                        -76,
                        "HiddenCam_Stream",
                    ),
                ),
            )
        }
        networks.forEachIndexed { index, network ->
            if (network.reading(Random(0)) == null && network.base.ssid == null && false) return@forEachIndexed
            val ssid = network.base.ssid
            val hotspot = ssid?.startsWith("iPhone") == true || ssid?.startsWith("DIRECT") == true
            val bssid = MacAddress(
                byteArrayOf(
                    if (hotspot) 0x7A else 0x50,
                    0xC7.toByte(),
                    0xBF.toByte(),
                    0x1E,
                    (index * 17).toByte(),
                    (0xA0 + index).toByte(),
                ),
            )
            val base = when (index) {
                0 -> 185_000L
                7 -> 420_000L
                else -> random.nextLong(200, 15_000)
            }
            val jitter = random.nextLong(-base / 5, base / 5 + 1)
            val bytes = (base + jitter).coerceAtLeast(0)
            val packets = (bytes / random.nextInt(400, 1200)).toInt()
            emit(SensorProtocol.encode(SensorProtocol.Message.Traffic(scanId, bssid, bytes, packets)))
        }
        emitVendor()
        battery = (battery + random.nextInt(-1, 2)).coerceIn(8, 100)
        emit(SensorProtocol.encode(SensorProtocol.Message.Battery(scanId, battery, battery < 25)))
        emit(
            SensorProtocol.encode(
                SensorProtocol.Message.ScanFinished(scanId, apSent, 4800, stations, probes),
            ),
        )
        scheduleNext()
    }

    fun stop() {
        cancelSchedule()
    }

    private fun scheduleNext() {
        cancelSchedule()
        if (intervalSeconds <= 0) return
        schedule(intervalSeconds * 1000L) { scan() }
    }

    private fun emitVendor() {
        val entries = listOf(2 to ("Keenetic Extra" to "Keenetic"), 3 to ("Archer C6" to "TP-Link"))
        for ((index, names) in entries) {
            val bssid = MacAddress(
                byteArrayOf(0x50, 0xC7.toByte(), 0xBF.toByte(), 0x1E, (index * 17).toByte(), (0xA0 + index).toByte()),
            )
            emit(SensorProtocol.encode(SensorProtocol.Message.VendorIe(scanId, bssid, names.first, names.second)))
        }
    }

    private class DemoNetwork(
        val base: AccessPointReading,
        val centre: Int,
        var current: Double,
        val flakiness: Double,
    ) {
        fun reading(random: Random): AccessPointReading? {
            if (random.nextDouble() < flakiness) return null
            return AccessPointReading(
                bssid = base.bssid,
                rssi = current.toInt(),
                channel = base.channel,
                secondaryChannel = base.secondaryChannel,
                auth = base.auth,
                pairwiseCipher = base.pairwiseCipher,
                groupCipher = base.groupCipher,
                capabilities = base.capabilities,
                countryCode = base.countryCode,
                ssidBytes = base.ssidBytes,
            )
        }

        fun drift(random: Random) {
            current += random.nextDouble(-3.0, 3.0)
            current += (centre - current) * 0.3
        }
    }

    companion object {
        private fun makeNetworks(): List<DemoNetwork> {
            data class Spec(
                val ssid: String,
                val channel: Int,
                val secondary: SecondaryChannel,
                val auth: AuthMode,
                val caps: Int,
                val rssi: Int,
            )
            val specs = listOf(
                Spec("Home", 6, SecondaryChannel.NONE, AuthMode.WPA2_WPA3_PSK, Capabilities.B or Capabilities.G or Capabilities.N or Capabilities.AX, -42),
                Spec("Home-Guest", 6, SecondaryChannel.NONE, AuthMode.WPA2_PSK, Capabilities.B or Capabilities.G or Capabilities.N or Capabilities.AX, -44),
                Spec("Keenetic-4120", 1, SecondaryChannel.ABOVE, AuthMode.WPA2_PSK, Capabilities.B or Capabilities.G or Capabilities.N, -61),
                Spec("TP-Link_2.4GHz_7F21", 11, SecondaryChannel.NONE, AuthMode.WPA_WPA2_PSK, Capabilities.B or Capabilities.G or Capabilities.N or Capabilities.WPS, -67),
                Spec("MGTS_GPON_8812", 3, SecondaryChannel.NONE, AuthMode.WPA2_PSK, Capabilities.B or Capabilities.G or Capabilities.N, -72),
                Spec("Coffee Corner", 9, SecondaryChannel.BELOW, AuthMode.OPEN, Capabilities.B or Capabilities.G or Capabilities.N, -76),
                Spec("DIRECT-5B-HP OfficeJet", 6, SecondaryChannel.NONE, AuthMode.WPA2_PSK, Capabilities.G or Capabilities.N, -79),
                Spec("", 11, SecondaryChannel.NONE, AuthMode.WPA2_PSK, Capabilities.B or Capabilities.G or Capabilities.N, -81),
                Spec("iPhone 15", 1, SecondaryChannel.NONE, AuthMode.WPA3_PSK, Capabilities.B or Capabilities.G or Capabilities.N or Capabilities.AX, -83),
                Spec("Rostelecom_A2", 13, SecondaryChannel.NONE, AuthMode.WEP, Capabilities.B or Capabilities.G, -88),
            )
            return specs.mapIndexed { index, spec ->
                val hotspot = spec.ssid.startsWith("iPhone") || spec.ssid.startsWith("DIRECT")
                val bssid = MacAddress(
                    byteArrayOf(
                        if (hotspot) 0x7A else 0x50,
                        0xC7.toByte(),
                        0xBF.toByte(),
                        0x1E,
                        (index * 17).toByte(),
                        (0xA0 + index).toByte(),
                    ),
                )
                val cipher = when (spec.auth) {
                    AuthMode.OPEN -> Cipher.NONE
                    AuthMode.WEP -> Cipher(2)
                    else -> Cipher.CCMP
                }
                val base = AccessPointReading(
                    bssid = bssid,
                    rssi = spec.rssi,
                    channel = spec.channel,
                    secondaryChannel = spec.secondary,
                    auth = spec.auth,
                    pairwiseCipher = cipher,
                    groupCipher = if (spec.auth == AuthMode.WPA_WPA2_PSK) Cipher.TKIP else cipher,
                    capabilities = spec.caps,
                    countryCode = if (hotspot) null else "RU",
                    ssidBytes = spec.ssid.encodeToByteArray(),
                )
                val flakiness = when {
                    spec.rssi < -80 -> 0.35
                    spec.rssi < -70 -> 0.1
                    else -> 0.0
                }
                DemoNetwork(base, spec.rssi, spec.rssi.toDouble(), flakiness)
            }
        }

        private val home = mac(0x50, 0xC7, 0xBF, 0x1E, 0x00, 0xA0)
        private val guest = mac(0x50, 0xC7, 0xBF, 0x1E, 0x11, 0xA1)
        private val keenetic = mac(0x50, 0xC7, 0xBF, 0x1E, 0x22, 0xA2)
        private val tplink = mac(0x50, 0xC7, 0xBF, 0x1E, 0x33, 0xA3)
        private val coffee = mac(0x50, 0xC7, 0xBF, 0x1E, 0x55, 0xA5)

        private val clients = listOf(
            WifiClient(mac(0xF0, 0x18, 0x98, 0x01, 0x02, 0x03), home, -38, true),
            WifiClient(mac(0x3C, 0x22, 0xFB, 0x04, 0x05, 0x06), home, -51, true),
            WifiClient(mac(0x48, 0xD6, 0xD5, 0x07, 0x08, 0x09), home, -62, true),
            WifiClient(mac(0x08, 0x08, 0xC2, 0x0A, 0x0B, 0x0C), home, -55, true),
            WifiClient(mac(0x7A, 0x12, 0x34, 0x0D, 0x0E, 0x0F), guest, -48, true),
            WifiClient(mac(0x30, 0xAE, 0xA4, 0x10, 0x11, 0x12), keenetic, -65, true),
            WifiClient(mac(0xB8, 0x27, 0xEB, 0x13, 0x14, 0x15), keenetic, -71, true),
            WifiClient(mac(0x68, 0x54, 0xFD, 0x16, 0x17, 0x18), tplink, -70, true),
            WifiClient(mac(0x7E, 0xAA, 0xBB, 0x19, 0x1A, 0x1B), coffee, -78, true),
            WifiClient(mac(0x7E, 0xCC, 0xDD, 0x1C, 0x1D, 0x1E), coffee, -80, true),
            WifiClient(mac(0x7A, 0x55, 0x66, 0x1F, 0x20, 0x21), null, -74, false),
            WifiClient(mac(0x7E, 0x77, 0x88, 0x22, 0x23, 0x24), null, -82, false),
        )

        private val probeRequests = listOf(
            ProbeRequest(mac(0x7A, 0x55, 0x66, 0x1F, 0x20, 0x21), -72, "Sheraton_WiFi", 1),
            ProbeRequest(mac(0x7A, 0x55, 0x66, 0x1F, 0x20, 0x21), -72, "Home-5G", 6),
            ProbeRequest(mac(0x7A, 0x55, 0x66, 0x1F, 0x20, 0x21), -74, null, 11),
            ProbeRequest(mac(0x7E, 0x77, 0x88, 0x22, 0x23, 0x24), -80, "FreeWiFi_Airport", 1),
            ProbeRequest(mac(0x7E, 0x77, 0x88, 0x22, 0x23, 0x24), -80, "AndroidAP", 6),
            ProbeRequest(mac(0xF0, 0x18, 0x98, 0x01, 0x02, 0x03), -36, "Home", 6),
            ProbeRequest(mac(0x08, 0x08, 0xC2, 0x0A, 0x0B, 0x0C), -53, "Home", 6),
            ProbeRequest(mac(0x08, 0x08, 0xC2, 0x0A, 0x0B, 0x0C), -54, "Office_Corp", 1),
            ProbeRequest(mac(0x08, 0x08, 0xC2, 0x0A, 0x0B, 0x0C), -55, "Starbucks", 11),
            ProbeRequest(mac(0x7A, 0xAA, 0xBB, 0x30, 0x31, 0x32), -68, "Airbnb_Guest", 1),
            ProbeRequest(mac(0x7A, 0xAA, 0xBB, 0x30, 0x31, 0x32), -69, "Holiday_Inn", 6),
            ProbeRequest(mac(0x7A, 0xAA, 0xBB, 0x30, 0x31, 0x32), -70, "eduroam", 11),
        )

        private val deauths = listOf(
            DeauthEvent(
                mac(0xDE, 0xAD, 0xBE, 0xEF, 0x00, 0x01),
                mac(0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF),
                home, 7, -45, 6, 24,
            ),
            DeauthEvent(
                mac(0xDE, 0xAD, 0xBE, 0xEF, 0x00, 0x01),
                mac(0xF0, 0x18, 0x98, 0x01, 0x02, 0x03),
                home, 1, -47, 6, 8,
            ),
        )

        private fun mac(vararg values: Int) = MacAddress(values.map { it.toByte() }.toByteArray())
    }
}
