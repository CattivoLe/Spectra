package cattivole.spectra.assistant

import cattivole.spectra.core.TrafficFlow
import cattivole.spectra.core.TrafficKind
import cattivole.spectra.core.kind
import cattivole.spectra.ui.bitrate
import cattivole.spectra.ui.tr
import java.text.DateFormat
import java.util.Date

/**
 * One thing another screen knows about a card's subject. [finding] is what the card shows, null when only the model
 * reads the tie; [line] is the tie in English, for the model.
 */
data class CrossLink(val finding: CardFinding?, val line: String)

/**
 * Ties between the app's screens. Each scanner sees one side of a device: the LAN scan its address and ports, the
 * sensor its radio link, Bluetooth its name, the router's table the ports opened to it, the recorder the phone's
 * traffic to it. A card about one of them reads the others here, so "the camera" is one thing on all of them.
 *
 * A tie is made by hardware address or IP address. By name only when the name is the device's own and exactly one
 * device on each side carries it, and then the tie says "probably". Pure rules over the snapshot, checked by
 * CrossLinksTest. Mirrors CrossLinks.swift.
 */
object CrossLinks {
    /** The most ties a card lists as findings; the model reads them all. */
    const val MAX_FINDINGS = 4

    /** A first sighting older than this is worth telling: the device is not new here. */
    const val KNOWN_AFTER_MS = 24L * 60 * 60 * 1000

    const val HEADING = "Ties to the app's other screens (matched by hardware address, IP address or name; \"probably\" marks a match by name alone):"

    /** The ties a card shows, most serious first. */
    fun findings(links: List<CrossLink>): List<CardFinding> =
        links.mapNotNull { it.finding }.withIndex().sortedWith(compareBy({ it.value.risk }, { it.index })).map { it.value }.take(MAX_FINDINGS)

    /** The ties as the model reads them, or nothing. */
    fun text(links: List<CrossLink>): String = if (links.isEmpty()) "" else (listOf(HEADING) + links.map { "- " + it.line }).joinToString("\n")

    /** The same, ready to follow a card's own data. */
    fun block(links: List<CrossLink>): String = if (links.isEmpty()) "" else "\n\n" + text(links)

    // MARK: - One device of the local network

    fun host(s: AssistantSnapshot, host: AssistantSnapshot.Host, now: Long = System.currentTimeMillis()): List<CrossLink> {
        val links = mutableListOf<CrossLink>()
        val mac = host.mac
        if (mac != null && !host.isGateway) {
            station(s, mac)?.let { (ap, station) ->
                val name = label(ap)
                links += CrossLink(
                    CardFinding(CardRisk.INFO, tr("On Wi‑Fi “%s”, channel %d; the sensor hears it at %d dBm.", name, ap.channel, station.rssi)),
                    "Wi-Fi: connected to \"$name\" (${ap.bssid}, channel ${ap.channel}, ${ap.security}); the sensor hears it at ${station.rssi} dBm",
                )
            }
            s.senders.firstOrNull { same(it.mac, mac) }?.let { sender ->
                links += CrossLink(
                    CardFinding(CardRisk.WARNING, tr("Sends data over Wi‑Fi the whole time (%s up), as a camera or microphone would.", bitrate(sender.bytesPerSecond * 8.0))),
                    "Sensor: it sends data over Wi-Fi the whole time, about ${kbit(sender.bytesPerSecond)} kbit/s up, as a camera or microphone would",
                )
            }
        }
        val targets = if (host.isGateway) emptyList() else forwardTargets(s).filter { it.client == host.ip }
        if (targets.isNotEmpty()) {
            val list = targets.take(4).joinToString(", ") { "${it.proto} ${it.externalPort} → ${it.internalPort}" }
            links += CrossLink(
                CardFinding(CardRisk.CRITICAL, tr("The router forwards ports from the internet to this device: %s.", list)),
                "Router: forwards from the internet to this device (outside port → its port): $list",
            )
            val open = exposed(s).filter { port -> targets.any { it.externalPort == port } }
            if (open.isNotEmpty()) {
                val ports = open.joinToString(", ")
                links += CrossLink(
                    CardFinding(CardRisk.CRITICAL, tr("Open from the internet right now: %s — the router forwards it to this device.", ports)),
                    "Connection check: port $ports of the public address answered from the internet, and the router forwards it to this device",
                )
            }
        }
        bluetoothTwin(s, host)?.let { twin ->
            val name = AssistantDigest.clean(twin.name, 30)
            links += CrossLink(
                CardFinding(CardRisk.INFO, tr("Also heard over Bluetooth as “%s” (%d dBm): probably the same device.", name, twin.rssi)),
                "Bluetooth: probably the same device as \"$name\", heard at ${twin.rssi} dBm${twin.tracker?.let { ", a ${AssistantDigest.clean(it)}" } ?: ""}",
            )
        }
        s.localPeers.firstOrNull { it.address == host.ip }?.let { peer ->
            links += CrossLink(
                CardFinding(
                    CardRisk.INFO,
                    if (peer.cleartext) tr("The phone talked to it in the recorded traffic: %d connections, some unencrypted.", peer.connections)
                    else tr("The phone talked to it in the recorded traffic: %d connections.", peer.connections),
                ),
                "Recorded traffic of the phone: ${peer.connections} connections to this device${if (peer.cleartext) ", some as plain HTTP" else ""}",
            )
        }
        talker(s, host.mac, host.ip)?.let { talker ->
            val name = AssistantDigest.clean(s.captureName.orEmpty(), 30)
            links += CrossLink(
                CardFinding(CardRisk.INFO, tr("In the last capture (%s): %d packets from or to it.", name, talker.packets)),
                "Last capture ($name): ${talker.packets} packets from or to this device",
            )
        }
        if (!host.isNew) known(host.firstSeen, now)?.let { links += firstSeen(it) }
        return links
    }

    // MARK: - The whole local network

    fun lan(s: AssistantSnapshot, now: Long = System.currentTimeMillis()): List<CrossLink> {
        val links = mutableListOf<CrossLink>()
        val open = exposed(s)
        for (target in forwardTargets(s).take(6)) {
            val rule = "${target.proto} ${target.externalPort}"
            val answers = target.externalPort in open
            val note = if (answers) "; the connection check found this port answering from the internet" else ""
            val host = s.hosts.firstOrNull { it.ip == target.client }
            links += if (host != null) {
                CrossLink(
                    if (answers) CardFinding(CardRisk.CRITICAL, tr("%s: open from the internet right now on port %d.", who(host), target.externalPort)) else null,
                    "Router: forwards $rule from the internet to ${who(host)}, its port ${target.internalPort}$note",
                )
            } else {
                CrossLink(
                    CardFinding(CardRisk.INFO, tr("The router forwards port %d to %s, which is not on the network now.", target.externalPort, target.client)),
                    "Router: forwards $rule from the internet to ${target.client}, which is not in the current host list$note",
                )
            }
        }
        for (sender in s.senders) {
            val host = s.hosts.firstOrNull { it.mac?.let { mac -> same(mac, sender.mac) } == true }?.takeIf { !it.isGateway } ?: continue
            links += CrossLink(
                CardFinding(CardRisk.WARNING, tr("%s sends data over Wi‑Fi the whole time, as a camera or microphone would.", who(host))),
                "Sensor: ${who(host)} sends data over Wi-Fi the whole time, about ${kbit(sender.bytesPerSecond)} kbit/s up, as a camera or microphone would",
            )
        }
        quietStations(s)?.let { (extra, stations) ->
            val list = stations.take(5).joinToString(", ") { it.mac }
            links += CrossLink(
                CardFinding(CardRisk.INFO, tr("The sensor hears %d more devices on your Wi‑Fi than the network scan found: asleep, or kept apart from the phone.", extra)),
                "Sensor: at least $extra devices are connected to the user's Wi-Fi that the LAN scan did not find (asleep, or isolated from the phone); stations with no matching host: $list",
            )
        }
        // Where each host is on the air, and its Bluetooth side: for the model only, the card would drown in them.
        var onAir = 0
        for (host in s.hosts) {
            if (host.isGateway || onAir >= 8) continue
            val (ap, station) = host.mac?.let { station(s, it) } ?: continue
            onAir++
            links += CrossLink(null, "Wi-Fi: ${who(host)} is connected to \"${label(ap)}\" (channel ${ap.channel}), ${station.rssi} dBm at the sensor")
        }
        var twins = 0
        for (host in s.hosts) {
            if (twins >= 4) break
            val twin = bluetoothTwin(s, host) ?: continue
            twins++
            links += CrossLink(null, "Bluetooth: ${who(host)} is probably the same device as \"${AssistantDigest.clean(twin.name, 30)}\", heard at ${twin.rssi} dBm")
        }
        return links
    }

    // MARK: - One Wi‑Fi network

    fun wifi(s: AssistantSnapshot, ap: AssistantSnapshot.Wifi, now: Long = System.currentTimeMillis()): List<CrossLink> {
        val links = mutableListOf<CrossLink>()
        if (ap.isCurrent) {
            links += if (s.hosts.isEmpty()) {
                CrossLink(CardFinding(CardRisk.INFO, tr("Your phone is on this network.")), "Phone: connected to this network; its local network has not been scanned")
            } else {
                CrossLink(
                    CardFinding(CardRisk.INFO, tr("Your phone is on this network: the network scan found %d devices on it.", s.hosts.size)),
                    "Local network: the phone is connected to this network; the LAN scan found ${s.hosts.size} devices on it",
                )
            }
            val risky = s.hosts.filter { !it.isThisPhone && (it.portRisks.isNotEmpty() || it.trust == "suspicious") }
            if (risky.isNotEmpty()) {
                val names = risky.take(3).joinToString(", ") { who(it) } + if (risky.size > 3) " +${risky.size - 3}" else ""
                val detail = risky.take(4).joinToString(" | ") { host ->
                    val why = host.portRisks.take(2).map { AssistantDigest.clean(it.title, 60) } +
                        if (host.trust == "suspicious") listOf("marked suspicious by the user") else emptyList()
                    "${who(host)} — ${why.joinToString("; ")}"
                }
                links += CrossLink(
                    CardFinding(CardRisk.WARNING, tr("Devices on it that need attention: %s.", names)),
                    "Local network: devices on this network with risky open ports or marked suspicious: $detail",
                )
            }
            s.link?.takeIf(::onWifi)?.let { links += connection(it) }
        }
        val gateway = s.hosts.firstOrNull { it.isGateway }
        if (gateway?.mac != null && near(gateway.mac, ap.bssid)) {
            links += CrossLink(
                CardFinding(CardRisk.INFO, tr("Most likely your router's access point: %s.", who(gateway))),
                "Local network: this access point is most likely the router ${who(gateway)}: their hardware addresses differ only in the last part",
            )
        }
        val stations = ap.stations.mapNotNull { station -> s.hosts.firstOrNull { it.mac?.let { mac -> same(mac, station.mac) } == true } }.filter { !it.isGateway }
        if (stations.isNotEmpty()) {
            val names = stations.take(4).joinToString(", ") { who(it) } + if (stations.size > 4) " +${stations.size - 4}" else ""
            links += CrossLink(
                CardFinding(CardRisk.INFO, tr("Devices of your network on it: %s.", names)),
                "Local network: devices connected to this access point that the LAN scan knows: ${stations.take(8).joinToString(", ") { who(it) }}",
            )
        }
        talker(s, ap.bssid)?.let { talker ->
            val name = AssistantDigest.clean(s.captureName.orEmpty(), 30)
            links += CrossLink(
                CardFinding(CardRisk.INFO, tr("The last capture (%s) holds %d packets of this access point.", name, talker.packets)),
                "Last capture ($name): ${talker.packets} packets from or to this access point",
            )
        }
        bluetoothTwin(s, ap)?.let { twin ->
            val name = AssistantDigest.clean(twin.name, 30)
            links += CrossLink(
                CardFinding(CardRisk.INFO, tr("A Bluetooth device with the same name is nearby (“%s”, %d dBm): probably the one that broadcasts this network.", name, twin.rssi)),
                "Bluetooth: a device named \"$name\" is heard at ${twin.rssi} dBm: probably the device that broadcasts this network",
            )
        }
        known(ap.firstSeen, now)?.let { links += firstSeen(it) }
        return links
    }

    /** What the connection check found on the network the phone is on. */
    private fun connection(link: AssistantSnapshot.Link): List<CrossLink> {
        val links = mutableListOf<CrossLink>()
        if (link.status == "captive") {
            links += CrossLink(
                CardFinding(CardRisk.WARNING, tr("The network asks for a sign-in page before it lets traffic out.")),
                "Connection check on this network: it asks for a sign-in page before it lets traffic out",
            )
        }
        if (link.intercepted == true) {
            links += CrossLink(
                CardFinding(CardRisk.CRITICAL, tr("HTTPS certificates look re-signed on this connection: possible interception.")),
                "Connection check on this network: HTTPS certificates are re-signed on the way (possible interception)",
            )
        }
        if (link.resolverLies == true) {
            links += CrossLink(
                CardFinding(CardRisk.WARNING, tr("The DNS resolver answers for names that do not exist.")),
                "Connection check on this network: the DNS resolver answers for names that do not exist",
            )
        }
        if (link.exposedPorts.isNotEmpty()) {
            val ports = link.exposedPorts.take(6).joinToString(", ")
            links += CrossLink(
                CardFinding(CardRisk.CRITICAL, tr("Ports reachable from the internet: %s.", ports)),
                "Connection check on this network: ports of its public address reachable from the internet: $ports",
            )
        }
        if (link.status == "online" && link.intercepted == false && link.resolverLies != true && link.exposedPorts.isEmpty()) {
            links += CrossLink(
                CardFinding(CardRisk.OK, tr("The connection check on it: the internet works, HTTPS is not intercepted.")),
                "Connection check on this network: the internet works and HTTPS certificates are genuine",
            )
        }
        return links
    }

    // MARK: - One Bluetooth device

    fun bluetooth(s: AssistantSnapshot, device: AssistantSnapshot.Bluetooth, now: Long = System.currentTimeMillis()): List<CrossLink> {
        val links = mutableListOf<CrossLink>()
        lanTwin(s, device)?.let { host ->
            val ports = when {
                !host.portsScanned -> ""
                host.ports.isEmpty() -> "; no open ports found"
                else -> "; open ports: " + host.ports.take(6).joinToString(", ")
            }
            val risk = host.portRisks.firstOrNull()
            links += if (risk != null) {
                val title = AssistantDigest.clean(risk.title, 60)
                CrossLink(
                    CardFinding(CardRisk.WARNING, tr("Probably the same device as %s on your local network, and it has a risky open port there: %s.", who(host), title)),
                    "Local network: probably the same device as ${who(host)}$ports; risky there: $title",
                )
            } else {
                CrossLink(
                    CardFinding(CardRisk.INFO, tr("Probably the same device as %s on your local network.", who(host))),
                    "Local network: probably the same device as ${who(host)}${host.maker?.let { ", maker ${AssistantDigest.clean(it)}" } ?: ""}$ports",
                )
            }
        }
        wifiTwin(s, device)?.let { ap ->
            links += CrossLink(
                CardFinding(CardRisk.INFO, tr("A Wi‑Fi network with the same name is on air (%s): probably this device's own access point.", ap.security)),
                "Wi-Fi: a network with the same name is on air (${ap.bssid}, channel ${ap.channel}, ${ap.security}): probably this device's own access point",
            )
        }
        known(device.firstSeen, now)?.let { links += firstSeen(it) }
        return links
    }

    // MARK: - The internet connection

    /** Where the ports that answer from the internet lead: the router's table names the device behind each. */
    fun internet(s: AssistantSnapshot): List<CrossLink> {
        val open = exposed(s)
        if (open.isEmpty()) return emptyList()
        val targets = forwardTargets(s)
        val tableRead = s.hosts.firstOrNull { it.isGateway }?.forwarding != null
        return open.take(4).map { port ->
            val target = targets.firstOrNull { it.externalPort == port }
            when {
                target != null -> {
                    val name = s.hosts.firstOrNull { it.ip == target.client }?.let(::who) ?: target.client
                    CrossLink(
                        CardFinding(CardRisk.CRITICAL, tr("Port %d open from the internet leads to %s.", port, name)),
                        "Router: port $port that answers from the internet is forwarded to $name, its port ${target.internalPort}",
                    )
                }
                tableRead -> CrossLink(
                    CardFinding(CardRisk.INFO, tr("Port %d answers from the internet but is not in the router's UPnP table: the router itself answers, or the port is forwarded by hand in its settings.", port)),
                    "Router: port $port answers from the internet but is not in the router's UPnP table: the router itself answers, or the port is forwarded by hand in its settings",
                )
                else -> CrossLink(null, "Router: port $port answers from the internet; the router's forwarding table has not been read, so the device behind it is unknown")
            }
        }
    }

    /** Ports answer from outside and the router is known, but its table has not been asked: that check names the device. */
    fun asksForwarding(s: AssistantSnapshot): Boolean {
        if (exposed(s).isEmpty()) return false
        val gateway = s.hosts.firstOrNull { it.isGateway } ?: return false
        return gateway.forwarding == null
    }

    // MARK: - Recorded traffic

    /** The devices of the local network the phone's recorded traffic went to, busiest first. */
    fun localPeers(flows: List<TrafficFlow>, hosts: Set<String>): List<AssistantSnapshot.LocalPeer> =
        flows.filter { it.destination in hosts && it.kind() != TrafficKind.DNS }
            .groupBy { it.destination }
            .map { (address, group) -> AssistantSnapshot.LocalPeer(address, group.size, group.any { it.kind() == TrafficKind.HTTP && !it.tls }) }
            .sortedWith(compareByDescending<AssistantSnapshot.LocalPeer> { it.connections }.thenBy { it.address })

    // MARK: - Matching

    fun same(a: String, b: String): Boolean = a.equals(b, ignoreCase = true)

    /** The access point a device is connected to, by its hardware address. */
    fun station(s: AssistantSnapshot, mac: String): Pair<AssistantSnapshot.Wifi, AssistantSnapshot.Station>? {
        for (ap in s.wifi) ap.stations.firstOrNull { same(it.mac, mac) }?.let { return ap to it }
        return null
    }

    fun forwardTargets(s: AssistantSnapshot): List<AssistantSnapshot.ForwardTarget> =
        s.hosts.firstOrNull { it.isGateway }?.forwarding?.targets.orEmpty()

    /** The check went out over Wi‑Fi: its public address is this network's, not the mobile operator's or a VPN's. */
    fun onWifi(link: AssistantSnapshot.Link): Boolean = link.via == null || link.via == "wifi"

    /** Ports of this network's public address that answered from the internet. */
    fun exposed(s: AssistantSnapshot): List<Int> = s.link?.takeIf(::onWifi)?.exposedPorts.orEmpty()

    fun talker(s: AssistantSnapshot, vararg addresses: String?): AssistantSnapshot.CaptureTalker? {
        val wanted = addresses.filterNotNull()
        return s.captureTalkers.firstOrNull { talker -> wanted.any { same(it, talker.address) } }
    }

    /**
     * Stations on the phone's own Wi‑Fi that match no host of the LAN scan, and how many of them cannot be the hosts
     * the scan found without a hardware address (the phone itself is one). Null when nothing is left over.
     */
    fun quietStations(s: AssistantSnapshot): Pair<Int, List<AssistantSnapshot.Station>>? {
        if (s.lanScanning) return null
        val macs = s.hosts.mapNotNull { it.mac?.lowercase() }.toSet()
        if (macs.isEmpty()) return null
        val mine = s.wifi.filter { it.isCurrent || (it.name.isNotEmpty() && it.name == s.wifiName) }
        val unknown = mine.flatMap { it.stations }.distinctBy { it.mac.lowercase() }.filter { it.mac.lowercase() !in macs }
        val extra = unknown.size - s.hosts.count { it.mac == null }
        return if (extra > 0) extra to unknown else null
    }

    /**
     * Two hardware addresses of one box: a router's access points differ from its LAN side in the last byte, and its
     * extra networks carry the "made up locally" bit in the first.
     */
    fun near(a: String, b: String): Boolean {
        val x = bytes(a) ?: return false
        val y = bytes(b) ?: return false
        return (x[0] and 0x02.inv()) == (y[0] and 0x02.inv()) && x.subList(1, 5) == y.subList(1, 5)
    }

    private fun bytes(text: String): List<Int>? = text.split(":").mapNotNull { it.toIntOrNull(16) }.takeIf { it.size == 6 }

    /** Names every phone or laptop of a kind carries: no tie can rest on them. */
    private val generic = setOf(
        "iphone", "ipad", "android", "macbook", "macbookpro", "macbookair", "imac", "airpods", "airpodspro", "applewatch",
        "appletv", "homepod", "galaxy", "pixel", "printer", "unknown", "device", "localhost", "router", "espressif",
    )

    /** A name without case, spaces and punctuation: "Momentum-3" and "MOMENTUM 3" are one. */
    fun nameKey(name: String): String = name.lowercase().filter { it.isLetterOrDigit() }

    fun namesMatch(a: String, b: String): Boolean {
        val x = nameKey(a)
        val y = nameKey(b)
        if (x.length < 4 || y.length < 4 || x in generic || y in generic) return false
        if (x == y) return true
        val (short, long) = if (x.length <= y.length) x to y else y to x
        return short.length >= 6 && long.contains(short)
    }

    /** The host has a name of its own: not its address, not just its maker. */
    private fun named(host: AssistantSnapshot.Host): Boolean =
        !host.isThisPhone && host.name != host.ip && nameKey(host.name) != nameKey(host.maker.orEmpty())

    private fun best(devices: List<AssistantSnapshot.Bluetooth>): AssistantSnapshot.Bluetooth? =
        devices.maxWithOrNull(compareBy({ it.nearby }, { it.rssi }))

    /** The Bluetooth device that carries the host's name, when one name on each side points to the other alone. */
    fun bluetoothTwin(s: AssistantSnapshot, host: AssistantSnapshot.Host): AssistantSnapshot.Bluetooth? {
        if (!named(host)) return null
        val found = s.bluetooth.filter { it.ownName && namesMatch(it.name, host.name) }
        val twin = best(found) ?: return null
        if (found.map { nameKey(it.name) }.toSet().size != 1) return null
        return twin.takeIf { s.hosts.count { named(it) && namesMatch(it.name, twin.name) } == 1 }
    }

    fun lanTwin(s: AssistantSnapshot, device: AssistantSnapshot.Bluetooth): AssistantSnapshot.Host? {
        if (!device.ownName) return null
        val host = s.hosts.filter { named(it) && namesMatch(it.name, device.name) }.singleOrNull() ?: return null
        return host.takeIf { bluetoothTwin(s, host) != null }
    }

    /** The network in range named exactly as the Bluetooth device: a printer's or a camera's own access point. */
    fun wifiTwin(s: AssistantSnapshot, device: AssistantSnapshot.Bluetooth): AssistantSnapshot.Wifi? {
        if (!device.ownName) return null
        return s.wifi.filter { it.inRange && !it.isHidden && sameName(it.name, device.name) }.maxByOrNull { it.rssi }
    }

    fun bluetoothTwin(s: AssistantSnapshot, ap: AssistantSnapshot.Wifi): AssistantSnapshot.Bluetooth? {
        if (ap.isHidden) return null
        return best(s.bluetooth.filter { it.ownName && sameName(it.name, ap.name) })
    }

    private fun sameName(a: String, b: String): Boolean {
        val x = nameKey(a)
        return x.length >= 4 && x !in generic && x == nameKey(b)
    }

    // MARK: - Words

    fun who(host: AssistantSnapshot.Host): String =
        if (host.name == host.ip) host.ip else "${AssistantDigest.clean(host.name, 30)} (${host.ip})"

    private fun label(ap: AssistantSnapshot.Wifi): String = if (ap.isHidden) ap.bssid else AssistantDigest.clean(ap.name, 30)

    private fun kbit(bytesPerSecond: Long): Long = bytesPerSecond * 8 / 1000

    private fun known(date: Long?, now: Long): Long? = date?.takeIf { it > 0 && now - it >= KNOWN_AFTER_MS }

    /** The day a device was first met, in the reader's words. */
    fun day(date: Long): String = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(date))

    private fun firstSeen(date: Long): CrossLink =
        CrossLink(CardFinding(CardRisk.INFO, tr("First seen on %s.", day(date))), "History: the app first met it on ${AssistantDigest.clock(date).take(10)}")
}
