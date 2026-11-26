package cattivole.spectra.assistant

import cattivole.spectra.ui.tr
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * What Spectra knows right now, flattened to plain values the assistant can read. Built from the live scanners by
 * [AssistantSources]; kept free of them so the rules below can be checked by tests. Mirrors the iOS AssistantSnapshot.
 */
data class AssistantSnapshot(
    val wifiName: String? = null,
    val networkLabel: String? = null,
    val sensorConnected: Boolean = false,
    val sensorName: String? = null,
    val lastWifiScan: Long? = null,
    val wifi: List<Wifi> = emptyList(),
    val findings: List<Finding> = emptyList(),
    val deauthFrames: Int = 0,
    val probingDevices: Int = 0,
    val hosts: List<Host> = emptyList(),
    val lanScanning: Boolean = false,
    val bluetooth: List<Bluetooth> = emptyList(),
    val sessions: List<Session> = emptyList(),
    val link: Link? = null,
    /** The traffic capture last taken or opened, already written up (see [CaptureDigest]). Null when there is none. */
    val capture: String? = null,
    /** The last GATT read of a Bluetooth device, already written up (see [GattDigest]). Null when none was made. */
    val gatt: String? = null,
    /** Which device [gatt] was read from. */
    val gattAddress: String? = null,
    /** The same read, one entry per characteristic: what the summary card turns into findings. */
    val gattItems: List<GattItem> = emptyList(),
    /** The user's own sites that were checked, with their last answer. */
    val sites: List<SiteReach> = emptyList(),
    /** The last traceroute, when one was run. */
    val route: Route? = null,
    /** Devices that send data the whole time, by the sensor's count. */
    val senders: List<Sender> = emptyList(),
    /** Devices of the local network the phone's recorded traffic went to. */
    val localPeers: List<LocalPeer> = emptyList(),
    /** The last decoded capture's name and its busiest addresses. */
    val captureName: String? = null,
    val captureTalkers: List<CaptureTalker> = emptyList(),
) {
    /** A device the sensor heard connected to an access point. */
    data class Station(val mac: String, val rssi: Int)

    /** A device the sensor counted sending data the whole time, as a camera or a microphone would. */
    data class Sender(val mac: String, val bssid: String, val bytesPerSecond: Long)

    /** One enabled rule of the router's forwarding table: a port of the public address and where it leads. */
    data class ForwardTarget(val proto: String, val externalPort: Int, val client: String, val internalPort: Int)

    /** A device of the local network the phone's recorded traffic went to. [cleartext]: some of it went as plain HTTP. */
    data class LocalPeer(val address: String, val connections: Int, val cleartext: Boolean = false)

    /** An address among the busiest of the last decoded capture: a MAC in a Wi‑Fi capture, an IP otherwise. */
    data class CaptureTalker(val address: String, val packets: Int)

    /** [vendor]: in a service the Bluetooth catalog does not name, i.e. the maker's own. */
    data class GattItem(val service: String, val name: String, val value: String?, val writable: Boolean, val vendor: Boolean)

    /**
     * The router's answer to a port-forwarding check: [rules] as "TCP 8080 → 192.168.1.50:80", [targets] the same
     * rules by their parts: what ties a forwarded port to the device it leads to.
     */
    data class Forwarding(val supported: Boolean, val rules: List<String>, val targets: List<ForwardTarget> = emptyList())

    data class Wifi(
        val name: String,
        val bssid: String,
        val rssi: Int,
        val channel: Int,
        val band: String,
        val security: String,
        val maker: String? = null,
        val isCurrent: Boolean = false,
        val isHidden: Boolean = false,
        val inRange: Boolean = true,
        val clients: Int = 0,
        val streaming: Boolean = false,
        /** The devices the sensor heard connected to it: what ties a network to the hosts of the LAN scan. */
        val stations: List<Station> = emptyList(),
        /** When the app first heard it, in any session. */
        val firstSeen: Long? = null,
    )

    /** [bssids]: the access points the finding is about. */
    data class Finding(val severity: String, val title: String, val detail: String, val bssids: List<String> = emptyList())

    data class Host(
        val ip: String,
        val mac: String? = null,
        val name: String,
        val maker: String? = null,
        val system: String? = null,
        val ports: List<String> = emptyList(),
        val portsScanned: Boolean = false,
        /** All 65535 TCP ports were checked, not only the common list. */
        val allPortsScanned: Boolean = false,
        val risks: List<String> = emptyList(),
        val isGateway: Boolean = false,
        val isThisPhone: Boolean = false,
        val isNew: Boolean = false,
        val trust: String = "unknown",
        /** The user's own note about the host. */
        val note: String? = null,
        /** [risks] with their weight: what the summary card ranks by. */
        val portRisks: List<PortRiskItem> = emptyList(),
        /** The router's forwarding table once a check read it; null before that. Gateway only. */
        val forwarding: Forwarding? = null,
        /** When the app first met it, in any session. */
        val firstSeen: Long? = null,
    )

    data class PortRiskItem(val title: String, val high: Boolean)

    data class Bluetooth(
        val name: String,
        val company: String? = null,
        val rssi: Int,
        val nearby: Boolean = true,
        val minutesNearby: Int = 0,
        val tracker: String? = null,
        /** "info", "watch" or "alert" for trackers. */
        val trackerLevel: String? = null,
        val away: Boolean? = null,
        val address: String = "",
        /** It says a name of its own: [name] is not the maker's or a made-up one, so it can be matched by. */
        val ownName: Boolean = true,
        /** When the app first heard it, in any session. */
        val firstSeen: Long? = null,
    )

    data class Session(val kind: String, val date: Long, val source: String, val place: String?, val count: Int)

    data class Link(
        val checkedAt: Long,
        val status: String? = null,
        val detail: String? = null,
        val gatewayMs: Int? = null,
        val dnsMs: Int? = null,
        val webMs: Int? = null,
        val publicIp: String? = null,
        val provider: String? = null,
        val country: String? = null,
        val downMbps: Double? = null,
        val upMbps: Double? = null,
        val latencyMs: Int? = null,
        val jitterMs: Int? = null,
        val intercepted: Boolean? = null,
        val resolverLies: Boolean? = null,
        val exposedPorts: List<Int> = emptyList(),
        val access: String? = null,
        /** Whether the router, the name lookup and a web page answered at all; null when not asked. */
        val gatewayOk: Boolean? = null,
        val dnsOk: Boolean? = null,
        val webOk: Boolean? = null,
        /** Who really answers the name lookups: the resolver the authoritative servers see. */
        val resolverName: String? = null,
        val resolverCountry: String? = null,
        /** Often-blocked services that did not open (access check). */
        val blocked: List<String> = emptyList(),
        /** How many reference sites the TLS check could reach; null when it did not run. */
        val tlsReached: Int? = null,
        /** The ports reachable from outside were asked about (an empty [exposedPorts] then means none). */
        val exposureChecked: Boolean = false,
        /** Bufferbloat grade A–F: how much latency grows under a full load. */
        val bufferbloat: String? = null,
        val speedFailed: Boolean = false,
        /** "wifi", "cellular" or "vpn": which way the check went out. */
        val via: String? = null,
        /** Traffic left through Cloudflare WARP. */
        val warp: Boolean = false,
    )

    /** One of the user's own sites (Connection → Resources), as it answered last. */
    data class SiteReach(val host: String, val ok: Boolean, val millis: Int? = null)

    /** The last traceroute from the Traceroute screen. [failure] is the app's English sentence when it did not run. */
    data class Route(
        val target: String,
        val address: String? = null,
        val hops: List<RouteHop> = emptyList(),
        val failure: String? = null,
        val running: Boolean = false,
    ) {
        /** The last hop answered from the target's own address. */
        val reached: Boolean get() = address != null && hops.lastOrNull { it.address != null }?.address == address

        /** Hops at the end that never answered: a firewall dropping probes, more often than a break. */
        val silentTail: Int get() = hops.takeLastWhile { it.address == null }.size
    }

    /** [place]: "Frankfurt, DE · Cloudflare" when the hop is public and placed. */
    data class RouteHop(val address: String?, val millis: Int?, val place: String? = null)
}

/** The parts of the app's data a summary card reads. */
enum class AssistantTopic { WIFI, THREATS, LAN, BLUETOOTH, HISTORY, LINK, CAPTURE }

/**
 * Writes the app's data up compactly for the model: one section per topic, sorted by importance rather than dumped
 * whole, since a small model has a short memory. Mirrors AssistantDigest.swift.
 */
object AssistantDigest {
    /** One topic's section rendered from a fresh snapshot: what a card and a step's result read. */
    fun section(topic: AssistantTopic, snapshot: AssistantSnapshot, now: Long = System.currentTimeMillis(), focus: String = ""): String {
        val lines = when (topic) {
            AssistantTopic.WIFI -> wifiSection(snapshot, focus)
            AssistantTopic.THREATS -> threatSection(snapshot)
            AssistantTopic.LAN -> lanSection(snapshot)
            AssistantTopic.BLUETOOTH -> bluetoothSection(snapshot)
            AssistantTopic.HISTORY -> historySection(snapshot)
            AssistantTopic.LINK -> linkSection(snapshot)
            AssistantTopic.CAPTURE -> captureSection(snapshot)
        }
        return lines.joinToString("\n")
    }

    // MARK: - Sections

    /** [focus] is a lower-cased BSSID: that network goes first. */
    private fun wifiSection(s: AssistantSnapshot, focus: String = ""): List<String> {
        val present = s.wifi.filter { it.inRange }
        if (present.isEmpty()) return listOf("Wi-Fi networks: none heard yet.")
        fun named(ap: AssistantSnapshot.Wifi) = focus.isNotEmpty() && focus.contains(ap.bssid.lowercase())
        val ranked = present.sortedWith(
            compareByDescending<AssistantSnapshot.Wifi> { named(it) }
                .thenByDescending { it.isCurrent }
                .thenByDescending { it.security == "Open" }
                .thenByDescending { it.rssi },
        )
        val limit = 14
        val lines = mutableListOf("Wi-Fi networks in range (${present.size}), strongest first, name · channel · signal · security · maker:")
        for (ap in ranked.take(limit)) {
            val parts = mutableListOf(if (ap.isHidden) "(hidden)" else "\"${clean(ap.name)}\"", "${ap.band} ch${ap.channel}", "${ap.rssi} dBm", ap.security)
            ap.maker?.let { parts.add(clean(it)) }
            if (ap.isCurrent) parts.add("CURRENT")
            if (ap.clients > 0) parts.add("${ap.clients} clients")
            if (ap.streaming) parts.add("steady traffic")
            lines.add("- " + parts.joinToString(" · ") + " · ${ap.bssid}")
        }
        if (ranked.size > limit) lines.add("- …and ${ranked.size - limit} more, weaker.")
        val crowded = present.groupBy { it.channel }.filter { it.value.size >= 4 }
        crowded.maxByOrNull { it.value.size }?.let { lines.add("Busiest channel: ${it.key} with ${it.value.size} networks.") }
        return lines
    }

    private fun threatSection(s: AssistantSnapshot): List<String> {
        val lines = mutableListOf<String>()
        for (finding in s.findings.take(6)) lines.add("- [${finding.severity}] ${clean(finding.title)}: ${clean(finding.detail, 160)}")
        if (s.deauthFrames > 0) lines.add("- ${s.deauthFrames} deauthentication frames seen in the latest Wi-Fi scan.")
        for (tracker in s.bluetooth.filter { it.tracker != null && it.trackerLevel != "info" }.take(3)) {
            lines.add("- [${tracker.trackerLevel ?: "watch"}] ${clean(tracker.tracker ?: "tracker")} nearby for ${tracker.minutesNearby} min${if (tracker.away == true) ", away from its owner" else ""}.")
        }
        for (host in s.hosts.filter { it.risks.isNotEmpty() }.take(4)) {
            lines.add("- LAN ${host.ip} (${clean(host.name)}): ${host.risks.take(3).joinToString("; ") { clean(it, 60) }}.")
        }
        for (host in s.hosts.filter { it.trust == "suspicious" }.take(3)) {
            lines.add("- LAN ${host.ip} (${clean(host.name)}) was marked suspicious by the user.")
        }
        s.link?.let { link ->
            if (link.intercepted == true) lines.add("- HTTPS certificates look re-signed on this connection (possible interception).")
            if (link.resolverLies == true) lines.add("- The DNS resolver answers for names that do not exist.")
            if (link.exposedPorts.isNotEmpty()) lines.add("- Ports reachable from the internet: ${link.exposedPorts.take(8).joinToString(", ")}.")
        }
        if (lines.isEmpty()) {
            return listOf("Findings: none right now (no deauth, evil-twin, tracker or risky-port alerts in the data).")
        }
        return listOf("Findings the app raised, most serious first:") + lines
    }

    private fun lanSection(s: AssistantSnapshot): List<String> {
        if (s.hosts.isEmpty()) return listOf("LAN hosts: none found yet (run a LAN scan).")
        fun weight(h: AssistantSnapshot.Host) =
            (if (h.trust == "suspicious") 8 else 0) + (if (h.risks.isEmpty()) 0 else 4) + (if (h.isNew) 2 else 0) + (if (h.isGateway) 1 else 0)
        val ranked = s.hosts.sortedByDescending { weight(it) }
        val limit = 16
        val lines = mutableListOf("Hosts on ${clean(s.networkLabel ?: "the local network")} (${s.hosts.size}${if (s.lanScanning) ", scan still running" else ""}):")
        for (host in ranked.take(limit)) {
            val parts = mutableListOf(host.ip)
            host.mac?.let(parts::add)
            parts.add(clean(host.name))
            host.maker?.let { if (!host.name.contains(it)) parts.add(clean(it)) }
            host.system?.let { parts.add(clean(it)) }
            if (host.isGateway) parts.add("GATEWAY")
            if (host.isThisPhone) parts.add("THIS PHONE")
            if (host.isNew) parts.add("NEW")
            if (host.trust != "unknown") parts.add(host.trust)
            if (host.portsScanned) {
                val scope = if (host.allPortsScanned) " (all 65535 ports checked)" else ""
                parts.add((if (host.ports.isEmpty()) "no open ports" else "open: " + host.ports.take(8).joinToString(",")) + scope)
            }
            host.note?.takeIf { it.isNotEmpty() }?.let { parts.add("note: ${clean(it, 50)}") }
            lines.add("- " + parts.joinToString(" · "))
        }
        if (ranked.size > limit) lines.add("- …and ${ranked.size - limit} more.")
        return lines
    }

    private fun bluetoothSection(s: AssistantSnapshot): List<String> {
        // The last GATT read goes first: when it exists, it is usually what the card is about.
        val gatt = listOfNotNull(s.gatt)
        if (s.bluetooth.isEmpty()) return gatt + "Bluetooth devices: none heard yet."
        val ranked = s.bluetooth.sortedWith(
            compareByDescending<AssistantSnapshot.Bluetooth> { it.tracker != null }.thenByDescending { it.nearby }.thenByDescending { it.rssi },
        )
        val limit = 12
        val lines = gatt.toMutableList()
        lines.add("Bluetooth devices (${s.bluetooth.size}, ${s.bluetooth.count { it.nearby }} nearby), strongest first:")
        for (device in ranked.take(limit)) {
            val parts = mutableListOf(clean(device.name))
            device.company?.let { if (!device.name.contains(it)) parts.add(clean(it)) }
            parts.add("${device.rssi} dBm")
            if (!device.nearby) parts.add("out of range")
            if (device.minutesNearby >= 5) parts.add("${device.minutesNearby} min nearby")
            device.tracker?.let { parts.add("TRACKER: ${clean(it)}" + if (device.away == true) " away from owner" else "") }
            lines.add("- " + parts.joinToString(" · "))
        }
        if (ranked.size > limit) lines.add("- …and ${ranked.size - limit} more.")
        return lines
    }

    private fun historySection(s: AssistantSnapshot): List<String> {
        if (s.sessions.isEmpty()) return listOf("History: nothing saved yet.")
        val lines = mutableListOf("Saved sessions, newest first:")
        for (session in s.sessions.sortedByDescending { it.date }.take(8)) {
            val place = session.place?.let { " · ${clean(it, 40)}" } ?: ""
            lines.add("- ${clock(session.date)} · ${session.kind} · ${session.count} items · from ${clean(session.source, 30)}$place")
        }
        return lines
    }

    private fun captureSection(s: AssistantSnapshot): List<String> =
        listOf(
            s.capture ?: ("Traffic capture: none yet. The user can take one from a Wi‑Fi network's card (Capture traffic), open a .pcap " +
                "under Wi‑Fi → ⋮ → Decode pcap, or record the phone's own traffic on the Internet tab → Traffic; you can also " +
                "take a short Wi‑Fi capture yourself with capture_pcap."),
        )

    private fun linkSection(s: AssistantSnapshot): List<String> {
        val link = s.link ?: return listOf("Connection check: not run yet (Internet tab → Connection).") + siteLines(s.sites)
        val lines = mutableListOf("Latest connection check, ${clock(link.checkedAt)}:")
        when (link.via) {
            "vpn" -> lines.add("- Checked through the VPN: the public address and provider are the VPN's")
            "cellular" -> lines.add("- Checked over the mobile network, not Wi‑Fi")
        }
        link.status?.let { status -> lines.add("- Status: $status${link.detail?.let { " — ${clean(it, 120)}" } ?: ""}") }
        val latency = listOfNotNull(
            link.gatewayMs?.let { "gateway $it ms" },
            link.dnsMs?.let { "DNS $it ms" },
            link.webMs?.let { "web $it ms" },
        )
        if (latency.isNotEmpty()) lines.add("- Round trips: " + latency.joinToString(", "))
        link.publicIp?.let { ip ->
            lines.add("- Public address $ip${link.provider?.let { " · ${clean(it, 40)}" } ?: ""}${link.country?.let { " · $it" } ?: ""}")
        }
        link.downMbps?.let { down ->
            var speed = "- Speed: ${format(down)} Mbit/s down"
            link.upMbps?.let { speed += ", ${format(it)} Mbit/s up" }
            link.latencyMs?.let { speed += ", latency $it ms" }
            link.jitterMs?.let { speed += ", jitter $it ms" }
            lines.add(speed)
        }
        link.bufferbloat?.let { lines.add("- Latency under load: grade $it (A is best, F means calls stutter while something downloads)") }
        if (link.speedFailed && link.downMbps == null) lines.add("- Speed test: did not finish")
        val failed = listOfNotNull(
            "router".takeIf { link.gatewayOk == false },
            "DNS".takeIf { link.dnsOk == false },
            "web".takeIf { link.webOk == false },
        )
        if (failed.isNotEmpty()) lines.add("- Did not answer: " + failed.joinToString(", "))
        link.resolverName?.let { name ->
            lines.add("- DNS resolver the servers see: ${clean(name, 40)}${link.resolverCountry?.let { " · $it" } ?: ""}")
        }
        if (link.resolverLies == true) lines.add("- The DNS resolver answers for names that do not exist (redirects typos to its own page or filters)")
        when (link.intercepted) {
            true -> lines.add("- HTTPS: certificates are re-signed on the way — interception by a proxy, an antivirus or an attacker")
            false -> lines.add("- HTTPS: genuine certificates${link.tlsReached?.let { " on $it reference sites" } ?: ""}, nothing re-signs them")
            null -> Unit
        }
        if (link.exposedPorts.isNotEmpty()) {
            lines.add("- Ports of the public address reachable from the internet: ${link.exposedPorts.joinToString(", ")}")
        } else if (link.exposureChecked) {
            lines.add("- No usual port of the public address answers from the internet")
        }
        link.access?.let { lines.add("- Internet access: $it") }
        if (link.blocked.isNotEmpty()) lines.add("- Often-blocked services that did not open: ${link.blocked.joinToString(", ") { clean(it, 40) }}")
        if (link.warp) lines.add("- Traffic leaves through Cloudflare WARP")
        return lines + siteLines(s.sites)
    }

    /**
     * The user's own sites, as they answered last. Two plain lists with an explicit "none": read as one run-on line
     * ("a opens; b opens; …") a small model took the last site for one that failed.
     */
    fun siteLines(sites: List<AssistantSnapshot.SiteReach>): List<String> {
        if (sites.isEmpty()) return emptyList()
        val open = sites.filter { it.ok }
        val closed = sites.filter { !it.ok }
        return listOf(
            "The user's own sites, ${sites.size} tried:",
            "- Open: " + open.joinToString(", ") { site -> clean(site.host, 40) + (site.millis?.let { " ($it ms)" } ?: "") }.ifEmpty { "none" },
            "- Do not open: " + closed.joinToString(", ") { clean(it.host, 40) }.ifEmpty { "none" },
        )
    }

    /**
     * A traceroute, hop by hop: what a "trace" check returns and the deep analysis reads. [compact] keeps the
     * outcome and the places along the way only: every hop costs the short summary seconds of reading.
     */
    fun routeLines(route: AssistantSnapshot.Route, compact: Boolean = false): List<String> {
        val target = clean(route.target, 60) + (route.address?.takeIf { it != route.target }?.let { " ($it)" } ?: "")
        if (route.hops.isEmpty()) {
            return listOf("Traceroute to $target: " + (route.failure ?: if (route.running) "still running" else "no hops recorded"))
        }
        val outcome = when {
            route.reached -> "reached in ${route.hops.size} hops"
            route.silentTail > 0 -> "did not reach it; the last ${route.silentTail} hops did not answer (often a firewall dropping probes)"
            else -> "did not reach it"
        }
        val lines = mutableListOf("Traceroute to $target: $outcome")
        route.failure?.let { lines.add("- Note: $it") }
        if (compact) {
            val places = route.hops.mapNotNull { it.place?.let { place -> clean(place, 50) } }.distinct()
            if (places.isNotEmpty()) lines.add("- Places along the way: " + places.joinToString(" → "))
            route.hops.lastOrNull { it.millis != null }?.let { lines.add("- Last answering hop: ${it.address} · ${it.millis} ms") }
            InternetCards.slowestHop(route.hops)?.let { (index, jump) -> lines.add("- The delay grows most at hop ${index + 1} (${route.hops[index].address}): +$jump ms") }
            return lines
        }
        route.hops.forEachIndexed { index, hop ->
            val parts = listOfNotNull(hop.address ?: "*", hop.millis?.let { "$it ms" }, hop.place?.let { clean(it, 50) })
            lines.add("${index + 1}. " + parts.joinToString(" · "))
        }
        return lines
    }

    // MARK: - Helpers

    /** Names come from the air and from the network: a hostile one can carry instructions. Flatten it to one short line. */
    fun clean(text: String, limit: Int = 40): String {
        val flat = buildString {
            for (ch in text) {
                val type = Character.getType(ch)
                append(if (type == Character.CONTROL.toInt() || type == Character.FORMAT.toInt()) ' ' else ch)
            }
        }
        val single = flat.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ").replace("<", "‹").replace(">", "›")
        return if (single.length > limit) single.take(limit) + "…" else single
    }

    private fun format(value: Double): String =
        if (value >= 100) Math.round(value).toString() else String.format(Locale.US, "%.1f", value)

    fun clock(time: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(time))
}
