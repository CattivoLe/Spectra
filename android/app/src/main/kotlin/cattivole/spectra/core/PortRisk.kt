package cattivole.spectra.core

/** How much an open service should worry the owner of the network. */
enum class RiskLevel(val label: String) {
    /** Worth knowing, nothing to fix: SSH on a Mac, a printer's queue. */
    INFO("Note"),

    /** Unencrypted or unnecessary, but not a way in by itself. */
    LOW("Low risk"),

    /** A service that is attacked for its passwords, or that hands out data. */
    MEDIUM("Needs attention"),

    /** Clear-text logins, debug shells, services known for no password at all. */
    HIGH("High risk"),
}

/**
 * One thing the port scan says about a host, with the ports behind it. The
 * text is the English key; [detailArg] fills its `%s` when there is one.
 */
data class RiskFinding(
    val level: RiskLevel,
    val title: String,
    val detail: String,
    val ports: List<Int>,
    val detailArg: String? = null,
)

/**
 * Reads an open-port list the way a security review would: which services
 * send passwords in the clear, which are known for shipping without one, and
 * which merely deserve a glance. Pure: nothing is probed or logged in.
 * Mirrors the iOS `PortRisk`; keep the rules in step.
 */
object PortRisk {
    private class Rule(val ports: Set<Int>, val level: RiskLevel, val title: String, val detail: String)

    private const val GUEST_SHARES = "Shares open to guests"
    private const val UPNP_CONTROL = "UPnP port control"

    private val rules = listOf(
        Rule(setOf(23), RiskLevel.HIGH, "Telnet",
            "Logins cross the network in clear text. Telnet is the first door botnets try, usually with factory passwords."),
        Rule(setOf(21), RiskLevel.HIGH, "FTP",
            "Passwords and files are sent unencrypted; anyone on the Wi-Fi can read them."),
        Rule(setOf(7547), RiskLevel.HIGH, "TR-069 remote management",
            "The port an internet provider uses to manage routers. Flaws in it have let worms in; it should not answer on the Wi-Fi."),
        Rule(setOf(5555), RiskLevel.HIGH, "Android debugging (ADB)",
            "Network debugging gives a shell on the device without a password. Turn it off when not in use."),
        Rule(setOf(6379), RiskLevel.HIGH, "Redis",
            "Redis accepts commands without a password unless it was configured with one."),
        Rule(setOf(3389), RiskLevel.MEDIUM, "Remote Desktop",
            "RDP is constantly tried with guessed passwords. Make sure the account has a strong one."),
        Rule(setOf(5900), RiskLevel.MEDIUM, "VNC screen sharing",
            "VNC often runs with a weak password or none, and its traffic is not encrypted."),
        Rule(setOf(3283), RiskLevel.MEDIUM, "Apple Remote Desktop",
            "The Mac can be controlled from the network. Fine if you set it up; otherwise turn off Remote Management."),
        Rule(setOf(139, 445), RiskLevel.MEDIUM, "File sharing (SMB)",
            "Shared folders are reachable from the Wi-Fi. Check that each share asks for a password."),
        Rule(setOf(2049), RiskLevel.MEDIUM, "NFS shares",
            "NFS trusts the client's word about who is asking. Anyone on the network can usually mount an unrestricted export."),
        Rule(setOf(1883), RiskLevel.MEDIUM, "MQTT broker",
            "Smart-home messages pass through here. Many brokers accept connections without a login, which lets anyone read sensors and send commands."),
        Rule(setOf(3306, 5432), RiskLevel.MEDIUM, "Database",
            "A database answers on the network. It should listen on localhost only unless other machines need it."),
        Rule(setOf(554, 8554), RiskLevel.MEDIUM, "Video stream (RTSP)",
            "The camera's stream is reachable from the Wi-Fi. Many cameras ship with a default password; change it."),
        Rule(setOf(10000), RiskLevel.MEDIUM, "Webmin",
            "A server administration panel. Keep it updated and behind a strong password."),
        Rule(setOf(111, 135), RiskLevel.LOW, "RPC services",
            "Legacy remote-procedure ports. Rarely needed on a home network and often a hint that other old services are on."),
        Rule(setOf(1900, 5000, 49152), RiskLevel.LOW, "UPnP",
            "UPnP lets any device on the Wi-Fi reconfigure this one without a login. On a router it can open ports to the internet."),
        Rule(setOf(25, 110, 143), RiskLevel.LOW, "Unencrypted mail",
            "Mail protocols without TLS. Passwords may cross the network in the clear."),
        Rule(setOf(80, 81, 8000, 8001, 8008, 8080, 8081, 8888, 9000, 9090), RiskLevel.LOW, "Web page without encryption",
            "Anything typed into this page, like a password, crosses the Wi-Fi unencrypted."),
        Rule(setOf(3000), RiskLevel.LOW, "Development server",
            "A developer tool is listening on the network. Such servers are not built to be reachable by others."),
        Rule(setOf(9100, 515), RiskLevel.LOW, "Open print queue",
            "Anyone on the Wi-Fi can send print jobs."),
        Rule(setOf(22), RiskLevel.INFO, "SSH",
            "Remote login is on. Safe with a strong password or keys; a factory password is not."),
    )

    /** The level of one open port, or null when it is nothing to note. */
    fun level(port: Int): RiskLevel? = rules.firstOrNull { port in it.ports }?.level

    /**
     * What the host's open ports and probes say, worst first. Empty when the
     * ports have not been scanned or none of them is of note.
     */
    fun findings(device: LanDevice): List<RiskFinding> {
        if (!device.portsScanned) return emptyList()
        val open = device.openPorts.map { it.number }.toSet()
        val findings = mutableListOf<RiskFinding>()

        val inbound = device.profile.inbound.filter { it.enabled }
        if (inbound.isNotEmpty()) {
            findings.add(
                RiskFinding(
                    RiskLevel.HIGH,
                    "Reachable from the internet",
                    "The router forwards %s from the internet to this device. Anyone online can try those ports, not just people on the Wi-Fi.",
                    inbound.map { it.internalPort }.distinct().sorted(),
                    inbound.joinToString(", ") { "${it.proto} ${it.externalPort}" },
                ),
            )
        }
        val table = device.profile.portForwarding
        if (device.isGateway && table != null && table.supported) {
            val active = table.mappings.count { it.enabled }
            if (active > 0) {
                findings.add(
                    RiskFinding(
                        RiskLevel.MEDIUM,
                        "Port forwarding is on",
                        "Rules from the internet to devices on this network: %s. Each one is a door; keep only the ones you set up yourself.",
                        emptyList(),
                        active.toString(),
                    ),
                )
            }
            findings.add(
                RiskFinding(
                    RiskLevel.LOW,
                    UPNP_CONTROL,
                    "Any device on the Wi-Fi can ask the router to open a port to the internet, without a login. Turn UPnP off in the router unless a game console or similar needs it.",
                    emptyList(),
                ),
            )
        }

        val windows = device.profile.windows
        if (windows != null && windows.guestSession && windows.shares.isNotEmpty()) {
            findings.add(
                RiskFinding(
                    RiskLevel.HIGH,
                    GUEST_SHARES,
                    "%s accepted a connection without a password. Anyone on the Wi-Fi can read them.",
                    listOf(445).filter { it in open },
                    windows.shares.joinToString(", "),
                ),
            )
        }

        for (rule in rules) {
            val hit = rule.ports.intersect(open).sorted()
            if (hit.isEmpty()) continue
            // The shares finding above already covers SMB on that host.
            if (445 in rule.ports && findings.any { it.title == GUEST_SHARES }) continue
            if (1900 in rule.ports && findings.any { it.title == UPNP_CONTROL }) continue
            var finding = RiskFinding(rule.level, rule.title, rule.detail, hit)
            if (device.isGateway && 80 in rule.ports) {
                finding = finding.copy(
                    title = "Router admin page without encryption",
                    detail = "The router's password crosses the Wi-Fi in the clear when you log in. Use the https address if the router offers one.",
                )
            } else if (device.isGateway && 23 in rule.ports) {
                finding = finding.copy(
                    detail = "Telnet on a router is how most router botnets got in. Turn it off in the router's settings.",
                )
            }
            findings.add(finding)
        }

        return findings.sortedWith(compareByDescending<RiskFinding> { it.level }.thenBy { it.ports.firstOrNull() ?: 0 })
    }

    /** The worst level among the host's findings; null when there is none. */
    fun level(device: LanDevice): RiskLevel? = findings(device).maxOfOrNull { it.level }
}

val LanDevice.riskFindings: List<RiskFinding> get() = PortRisk.findings(this)
val LanDevice.riskLevel: RiskLevel? get() = PortRisk.level(this)

/** Medium or worse: the host gets a mark in the list and counts in the summary. */
val LanDevice.needsAttention: Boolean get() = (riskLevel ?: RiskLevel.INFO) >= RiskLevel.MEDIUM
