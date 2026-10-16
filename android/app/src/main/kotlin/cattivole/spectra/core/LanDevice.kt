package cattivole.spectra.core

data class DiscoveredPort(val number: Int, val service: String) {
    companion object {
        val commonPorts = listOf(
            21 to "FTP", 22 to "SSH", 23 to "Telnet", 25 to "SMTP", 53 to "DNS",
            80 to "HTTP", 81 to "HTTP", 110 to "POP3", 111 to "RPC", 135 to "MS RPC",
            139 to "NetBIOS", 143 to "IMAP", 443 to "HTTPS", 445 to "SMB", 515 to "LPD",
            548 to "AFP", 554 to "RTSP", 631 to "IPP", 993 to "IMAPS", 1400 to "Sonos",
            8554 to "RTSP", 1883 to "MQTT", 2049 to "NFS", 3000 to "Dev", 3283 to "Apple Remote",
            3306 to "MySQL", 3389 to "RDP", 3689 to "DAAP", 5000 to "UPnP", 5001 to "HTTPS",
            5357 to "WSD", 5432 to "PostgreSQL", 5555 to "ADB", 5900 to "VNC",
            6379 to "Redis", 6466 to "Android TV", 6668 to "Tuya", 7000 to "AirPlay",
            7547 to "TR-069", 8000 to "HTTP", 8001 to "HTTP", 8008 to "Chromecast",
            8009 to "Chromecast", 8080 to "HTTP", 8081 to "HTTP", 8123 to "Home Assistant",
            8443 to "HTTPS", 8888 to "HTTP", 9000 to "Web", 9090 to "Web", 9100 to "Printer",
            10000 to "Webmin", 32400 to "Plex", 49152 to "UPnP", 62078 to "iPhone Sync",
        )

        fun serviceName(port: Int): String = commonPorts.firstOrNull { it.first == port }?.second ?: "TCP"
    }
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
}

class LanDevice(
    val ipAddress: String,
    var macAddress: MacAddress? = null,
    hostnames: List<String> = emptyList(),
    var openPorts: List<DiscoveredPort> = emptyList(),
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
    private var pinnedRank = 0

    val manufacturer: String?
        get() {
            val mac = macAddress ?: return null
            return mac.vendor() ?: Catalog.suspiciousOui(mac.oui)
        }

    val displayName: String
        get() = pinnedName ?: manufacturer ?: ipAddress

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
        if (rank > pinnedRank || (rank == pinnedRank && rank >= 2 && shown != pinnedName)) {
            pinnedName = shown
            pinnedRank = rank
        }
    }

    companion object {
        fun usefulHostname(name: String, ip: String): String? {
            val shown = presentable(name) ?: return null
            if (shown.equals(ip, ignoreCase = true)) return null
            return shown
        }

        private fun presentable(raw: String): String? {
            var shown = LanIdentity.presentableName(raw) ?: return null
            if (shown.endsWith(".local")) {
                shown = shown.dropLast(6).trim()
                shown = LanIdentity.presentableName(shown) ?: return null
            }
            return shown
        }
    }
}
