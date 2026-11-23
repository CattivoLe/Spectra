package cattivole.spectra.core

data class DiscoveredPort(val number: Int, val service: String) {
    companion object {
        val commonPorts = listOf(
            21 to "FTP", 22 to "SSH", 23 to "Telnet", 25 to "SMTP", 53 to "DNS",
            80 to "HTTP", 81 to "HTTP", 110 to "POP3", 111 to "RPC", 135 to "MS RPC",
            139 to "NetBIOS", 143 to "IMAP", 443 to "HTTPS", 445 to "SMB", 515 to "LPD",
            548 to "AFP", 554 to "RTSP", 631 to "IPP", 993 to "IMAPS", 1400 to "Sonos",
            8554 to "RTSP", 1935 to "RTMP", 1883 to "MQTT", 2049 to "NFS", 3000 to "Dev", 3283 to "Apple Remote",
            3306 to "MySQL", 3389 to "RDP", 3689 to "DAAP", 5000 to "UPnP", 5001 to "HTTPS",
            5357 to "WSD", 5432 to "PostgreSQL", 5555 to "ADB", 5900 to "VNC",
            6379 to "Redis", 6466 to "Android TV", 6668 to "Tuya", 7000 to "AirPlay",
            7547 to "TR-069", 8000 to "HTTP", 8001 to "HTTP", 8008 to "Chromecast",
            8009 to "Chromecast", 8080 to "HTTP", 8081 to "HTTP", 8123 to "Home Assistant",
            8443 to "HTTPS", 8888 to "HTTP", 9000 to "Web", 9090 to "Web", 9100 to "Printer",
            10000 to "Webmin", 32400 to "Plex", 49152 to "UPnP", 62078 to "iPhone Sync",
        )

        fun serviceName(port: Int): String = commonPorts.firstOrNull { it.first == port }?.second ?: "TCP"

        /** Ports that usually answer a browser, as on iOS. */
        val plainWeb = setOf(80, 81, 8000, 8001, 8008, 8080, 8081, 8123, 8888, 9000, 9090, 32400, 5000, 1400)
        val secureWeb = setOf(443, 8443, 5001)
    }
}

/** The browser address of [port] when it speaks HTTP, default ports left out; null otherwise. */
fun LanDevice.webAddress(port: Int): String? {
    val banner = profile.banners.firstOrNull { it.port == port }
    if (banner == null && port !in DiscoveredPort.plainWeb && port !in DiscoveredPort.secureWeb) return null
    val secure = banner?.secure ?: (port in DiscoveredPort.secureWeb)
    val scheme = if (secure) "https" else "http"
    val default = if (secure) 443 else 80
    return if (port == default) "$scheme://$ipAddress" else "$scheme://$ipAddress:$port"
}

/**
 * Where a camera's video stream lives, for a player like VLC. Mirrors the iOS
 * `StreamAddress`; keep the paths in step.
 */
object StreamAddress {
    val rtspPorts = setOf(554, 8554)
    const val RTMP_PORT = 1935

    /**
     * The stream path a maker's cameras use by default. A bare address shows
     * nothing on most cameras; the player asks for the password itself.
     */
    private val pathsByMaker = listOf(
        listOf("hikvision", "ezviz", "hiwatch") to "/Streaming/Channels/101",
        listOf("dahua", "amcrest", "imou", "lorex") to "/cam/realmonitor?channel=1&subtype=0",
        listOf("reolink") to "/h264Preview_01_main",
        listOf("tp-link", "tapo") to "/stream1",
        listOf("uniview", "unv") to "/media/video1",
        listOf("axis") to "/axis-media/media.amp",
        listOf("foscam") to "/videoMain",
    )

    fun rtspPath(maker: String?): String {
        val folded = maker?.lowercase() ?: return "/"
        return pathsByMaker.firstOrNull { (names, _) -> names.any { it in folded } }?.second ?: "/"
    }

    /**
     * The player address of [port], or null when the port carries no stream.
     * [speaksRtsp] is true when the port answered an RTSP probe, whatever its number.
     */
    fun url(ip: String, port: Int, speaksRtsp: Boolean, maker: String?): String? {
        val host = if (':' in ip) "[$ip]" else ip
        return when {
            speaksRtsp || port in rtspPorts -> {
                val portPart = if (port == 554) "" else ":$port"
                "rtsp://$host$portPart${rtspPath(maker)}"
            }
            port == RTMP_PORT -> "rtmp://$host:$port/"
            else -> null
        }
    }
}

/** The player address of [port] when it carries a camera stream; null otherwise. */
fun LanDevice.streamAddress(port: Int): String? {
    val maker = listOfNotNull(
        identity.manufacturer, manufacturer, profile.upnp?.manufacturer,
        profile.rtsp.firstOrNull { it.port == port }?.server,
    ).joinToString(" ").ifEmpty { null }
    return StreamAddress.url(ipAddress, port, profile.rtsp.any { it.port == port }, maker)
}

data class SubnetInfo(
    val localIp: String,
    val prefixLength: Int,
    val gateway: String?,
    val interfaceName: String?,
) {
    val hostCount: Int
        get() {
            if (prefixLength >= 31) return 0
            val hosts = (1 shl (32 - prefixLength)) - 2
            return hosts.coerceAtLeast(0)
        }

    /**
     * Network address and prefix, e.g. `192.168.1.0/24`. Stable across a DHCP
     * lease change, unlike [localIp], so it keys a saved LAN session.
     */
    val networkLabel: String
        get() {
            val ip = parseIpv4(localIp) ?: return "$localIp/$prefixLength"
            val mask = if (prefixLength <= 0) 0L else (-1L shl (32 - prefixLength)) and 0xFFFF_FFFFL
            val network = ip and mask
            return "${formatIpv4(network)}/$prefixLength"
        }

    private companion object {
        fun parseIpv4(text: String): Long? {
            val parts = text.split(".")
            if (parts.size != 4) return null
            var value = 0L
            for (part in parts) {
                val octet = part.toIntOrNull() ?: return null
                if (octet !in 0..255) return null
                value = (value shl 8) or octet.toLong()
            }
            return value
        }

        fun formatIpv4(value: Long): String =
            "${(value shr 24) and 0xFF}.${(value shr 16) and 0xFF}.${(value shr 8) and 0xFF}.${value and 0xFF}"
    }
}

class LanDevice(
    val ipAddress: String,
    var macAddress: MacAddress? = null,
    hostnames: List<String> = emptyList(),
    var openPorts: List<DiscoveredPort> = emptyList(),
    var services: List<ServiceAdvertisement> = emptyList(),
    var portsScanned: Boolean = false,
    var isGateway: Boolean = false,
    var isLocalDevice: Boolean = false,
    profile: LanProfile = LanProfile(),
    val firstSeen: Long = System.currentTimeMillis(),
    var lastSeen: Long = firstSeen,
) {
    var hostnames: List<String> = hostnames
        set(value) {
            field = value
            refreshPinnedName()
        }

    var profile: LanProfile = profile
        set(value) {
            field = value
            refreshPinnedName()
        }

    var pinnedName: String? = null
        private set

    /**
     * Never seen on this network before this scan. Set by [KnownHosts];
     * carried across rescans so the mark stays until the list is cleared.
     */
    var isNew: Boolean = false

    /** What the user called this host, and what they think of it. Filled from [DeviceLabels]. */
    var customName: String? = null
    var trust: DeviceTrust = DeviceTrust.UNKNOWN
    var note: String? = null

    /** 0..1 while a port scan of this host runs. */
    var portScanFraction: Float? = null

    /** When all 65535 TCP ports were last checked; null if only the common list was. */
    var allPortsScannedAt: Long? = null

    val allPortsScanned: Boolean get() = allPortsScannedAt != null
    private var pinnedRank = 0

    val manufacturer: String?
        get() {
            val mac = macAddress ?: return null
            if (mac.isLocallyAdministered) return null
            return mac.vendor() ?: Catalog.suspiciousOui(mac.oui)
        }

    val identity: HostIdentity
        get() {
            val upnp = profile.upnp
            // The SMB / NetBIOS name is often all a Mac or PC with file sharing gives away.
            val found = HostIdentity.recognize(
                names = hostnames + listOfNotNull(profile.windows?.computerName),
                services = services,
                ports = openPorts.map { it.number },
                vendor = manufacturer,
                upnpName = upnp?.friendlyName,
                upnpModel = upnp?.model,
                upnpMaker = upnp?.manufacturer,
                upnpType = upnp?.deviceType,
            )
            return if (isLocalDevice) HostIdentity.mergeBlanks(found, HostIdentity.thisDevice()) else found
        }

    val displayName: String
        get() {
            customName?.let { return it }
            val pinned = pinnedName?.takeUnless { isWeakHostname(it) }
            return pinned
                ?: identity.model
                ?: identity.manufacturer
                ?: manufacturer
                ?: pinnedName
                ?: if (identity.operatingSystem == "Android") "Android" else ipAddress
        }

    /** [displayName], unless all there is is the address. */
    val knownName: String? get() = displayName.takeUnless { it == ipAddress }

    val notable: NotableDevice?
        get() = NotableDevice.classify(
            manufacturer = manufacturer,
            oui = macAddress?.oui,
            upnpType = profile.upnp?.deviceType,
            hasRtsp = profile.rtsp.isNotEmpty(),
        )

    init {
        refreshPinnedName()
    }

    fun preservePinnedName(from: LanDevice?) {
        val other = from ?: return
        if (other.pinnedRank > pinnedRank) {
            pinnedName = other.pinnedName
            pinnedRank = other.pinnedRank
        }
    }

    private fun refreshPinnedName() {
        consider(profile.upnp?.friendlyName, 3)
        consider(profile.windows?.computerName, 2)
        for (name in hostnames) consider(name, 2)
    }

    private fun consider(raw: String?, rank: Int) {
        val shown = presentable(raw ?: return) ?: return
        if (shown.equals(ipAddress, ignoreCase = true)) return
        val quality = if (isWeakHostname(shown)) rank - 1 else rank
        if (quality > pinnedRank || (quality == pinnedRank && quality >= 2 && shown != pinnedName)) {
            pinnedName = shown
            pinnedRank = quality
        }
    }

    companion object {
        fun isWeakHostname(name: String): Boolean {
            if (isOpaqueIdentifier(name)) return true
            val folded = name.lowercase().replace('-', ' ').replace('_', ' ')
            return folded == "android" || folded == "windows" || folded.startsWith("android ")
        }

        /**
         * Long hex strings and UUIDs are identifiers, not names: a TV that
         * publishes "1b067baf05912b622b215c7a…" reads better as its model or maker.
         */
        fun isOpaqueIdentifier(name: String): Boolean {
            val compact = name.filter { it != '-' && it != '_' }
            return compact.length >= 12 && compact.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
        }

        fun usefulHostname(name: String, ip: String): String? {
            if (Miio.isInstanceName(name)) return null
            val shown = presentable(name) ?: return null
            if (shown.equals(ip, ignoreCase = true)) return null
            return shown
        }

        private fun presentable(raw: String): String? =
            LanIdentity.presentableName(LanIdentity.tidyInstanceName(raw))
    }
}
