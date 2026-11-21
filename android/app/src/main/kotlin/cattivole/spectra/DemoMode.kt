package cattivole.spectra

import cattivole.spectra.core.BleFields
import cattivole.spectra.core.DiscoveredPort
import cattivole.spectra.core.HttpBanner
import cattivole.spectra.core.LanDevice
import cattivole.spectra.core.LanProfile
import cattivole.spectra.core.MacAddress
import cattivole.spectra.core.RtspService
import cattivole.spectra.core.ServiceAdvertisement
import cattivole.spectra.core.SubnetInfo
import cattivole.spectra.core.UPnPIdentity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * Made-up networks, hosts and Bluetooth devices for screenshots (docs/images,
 * see docs/screenshots.md). A debuggable build turns it on when started with
 * `adb shell am start -n cattivole.spectra/.MainActivity --ez demo true`.
 *
 * Nothing here is real: SSIDs and names are invented, MAC addresses are a real
 * vendor prefix (so the vendor shows) plus an obvious pattern, IP addresses are
 * a private range. The phone's own Wi‑Fi scan, the LAN sweep and the Bluetooth
 * radio stay off, so no neighbour's data can end up in a picture.
 */
object DemoMode {
    fun start(model: SpectraModel, scope: CoroutineScope) {
        model.phone.demo = true
        model.link.setDemo(true)
        model.lan.showDemo(subnet, lanHosts())
        scope.launch {
            val random = Random(7)
            while (isActive) {
                val now = System.currentTimeMillis()
                for (device in bluetooth) {
                    model.ble.absorbDemo(device.address, device.fields, device.rssi + random.nextInt(-3, 4), now)
                }
                delay(1_500)
            }
        }
    }

    private val subnet = SubnetInfo(localIp = "192.168.1.23", prefixLength = 24, gateway = "192.168.1.1", interfaceName = "wlan0")

    private fun mac(text: String): MacAddress = requireNotNull(MacAddress.parse(text))

    private fun lanHosts(): List<LanDevice> {
        val now = System.currentTimeMillis()
        fun ports(vararg numbers: Int) = numbers.map { DiscoveredPort(it, DiscoveredPort.serviceName(it)) }
        fun banner(port: Int, server: String?, title: String?, secure: Boolean = false) =
            HttpBanner(port, secure, 200, server, null, title, null)
        return listOf(
            LanDevice(
                ipAddress = "192.168.1.1",
                macAddress = mac("50:FF:20:00:00:01"),
                hostnames = listOf("my.keenetic.net"),
                openPorts = ports(53, 80, 443, 1900),
                portsScanned = true,
                isGateway = true,
                profile = LanProfile(
                    upnp = UPnPIdentity("Keenetic Giga", "Keenetic", "KN-1012", "urn:schemas-upnp-org:device:InternetGatewayDevice:2", null),
                    banners = listOf(banner(80, null, "Keenetic Web")),
                ),
                firstSeen = now - DAY * 40,
                lastSeen = now,
            ),
            LanDevice(
                ipAddress = "192.168.1.23",
                hostnames = listOf("Pixel-8"),
                portsScanned = true,
                isLocalDevice = true,
                firstSeen = now - DAY * 40,
                lastSeen = now,
            ),
            LanDevice(
                ipAddress = "192.168.1.10",
                macAddress = mac("00:11:32:00:00:10"),
                hostnames = listOf("DiskStation.local"),
                openPorts = ports(22, 80, 139, 443, 445, 5000, 5001),
                services = listOf(ServiceAdvertisement("_smb._tcp", "DiskStation"), ServiceAdvertisement("_http._tcp", "DiskStation")),
                portsScanned = true,
                profile = LanProfile(
                    upnp = UPnPIdentity("DiskStation", "Synology", "DS220+", null, null),
                    banners = listOf(banner(5000, "nginx", "Synology DiskStation")),
                ),
                firstSeen = now - DAY * 40,
                lastSeen = now,
            ),
            LanDevice(
                ipAddress = "192.168.1.31",
                macAddress = mac("00:03:93:00:00:31"),
                hostnames = listOf("MacBook-Air.local"),
                openPorts = ports(5000, 7000),
                services = listOf(
                    ServiceAdvertisement("_airplay._tcp", "MacBook Air", "Mac14,2"),
                    ServiceAdvertisement("_companion-link._tcp", "MacBook Air"),
                ),
                portsScanned = true,
                firstSeen = now - DAY * 30,
                lastSeen = now,
            ),
            LanDevice(
                ipAddress = "192.168.1.42",
                macAddress = mac("00:1A:11:00:00:42"),
                hostnames = listOf("Living-Room-TV.local"),
                openPorts = ports(8008, 8009, 8443),
                services = listOf(ServiceAdvertisement("_googlecast._tcp", "Living Room TV", "Chromecast")),
                portsScanned = true,
                firstSeen = now - DAY * 25,
                lastSeen = now,
            ),
            LanDevice(
                ipAddress = "192.168.1.57",
                macAddress = mac("00:BC:99:00:00:57"),
                openPorts = ports(23, 80, 554, 8000),
                portsScanned = true,
                profile = LanProfile(
                    banners = listOf(banner(80, "App-webs/", "Network Camera")),
                    rtsp = listOf(RtspService(554, "Hikvision RTSP", "OPTIONS, DESCRIBE, SETUP, PLAY")),
                ),
                firstSeen = now - DAY * 12,
                lastSeen = now,
            ),
            LanDevice(
                ipAddress = "192.168.1.64",
                macAddress = mac("00:04:3C:00:00:64"),
                hostnames = listOf("Sonos-Kitchen.local"),
                openPorts = ports(1400, 1443),
                services = listOf(ServiceAdvertisement("_sonos._tcp", "Kitchen")),
                portsScanned = true,
                firstSeen = now - DAY * 20,
                lastSeen = now,
            ),
            LanDevice(
                ipAddress = "192.168.1.88",
                macAddress = mac("00:4B:12:00:00:88"),
                hostnames = listOf("smart-plug"),
                openPorts = ports(6668),
                portsScanned = true,
                firstSeen = now - 60_000,
                lastSeen = now,
            ).also { it.isNew = true },
        )
    }

    private class DemoBle(val address: String, val rssi: Int, val fields: BleFields)

    private fun hex(text: String): ByteArray =
        text.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    /** Little-endian company id, then the payload, as a manufacturer data field carries them. */
    private fun maker(company: Int, payload: String): ByteArray =
        byteArrayOf((company and 0xFF).toByte(), ((company shr 8) and 0xFF).toByte()) + hex(payload)

    private val bluetooth = listOf(
        // AirPods Pro (model 0x0E20) in their case: proximity pairing, batteries 80/90 %, case 60 %.
        DemoBle("4C:11:22:00:00:01", -48, BleFields(manufacturerData = maker(0x004C, "07 19 01 0E 20 2B 98 06 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00"))),
        // An AirTag away from its owner: the long Find My advertisement.
        DemoBle("D2:33:44:00:00:02", -67, BleFields(manufacturerData = maker(0x004C, "12 19 10 " + "00".repeat(22) + " 01 00"))),
        // An iPhone nearby (Nearby Info).
        DemoBle("5E:55:66:00:00:03", -58, BleFields(manufacturerData = maker(0x004C, "10 05 1B 18 00 00 00"))),
        DemoBle("E1:77:88:00:00:04", -61, BleFields(localName = "Galaxy Watch6 (3F2A)", manufacturerData = maker(0x0075, "01 00 02 00 01"))),
        DemoBle("C8:99:AA:00:00:05", -74, BleFields(localName = "Polar H10 7A1B2C3D", serviceUuids = listOf("0000180d-0000-1000-8000-00805f9b34fb"), manufacturerData = maker(0x006B, "33 1E 00"))),
        DemoBle("F4:BB:CC:00:00:06", -79, BleFields(localName = "JBL Flip 6", manufacturerData = maker(0x0057, "00 00 01"))),
        DemoBle("E9:DD:EE:00:00:07", -83, BleFields(serviceUuids = listOf("0000feed-0000-1000-8000-00805f9b34fb"))),
        DemoBle("C3:12:34:00:00:08", -88, BleFields(localName = "Mi Smart Band 8", manufacturerData = maker(0x038F, "00 01"))),
    )

    private const val DAY = 24 * 60 * 60 * 1000L
}
