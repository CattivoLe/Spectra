package cattivole.spectra.assistant

import cattivole.spectra.core.CaptureAnalysis
import cattivole.spectra.core.Catalog
import cattivole.spectra.core.Endpoint
import cattivole.spectra.core.EndpointGroup
import cattivole.spectra.core.GattValue
import cattivole.spectra.core.RequestHit
import cattivole.spectra.core.TrackerHosts
import cattivole.spectra.core.TrafficFlow
import cattivole.spectra.core.kind
import cattivole.spectra.platform.BleGattClient
import java.util.Locale

/**
 * A decoded traffic capture written up for the assistant: what the Decode pcap screen shows, as compact text.
 * Used both for a capture the user already has and for one the model takes itself (`capture_pcap`).
 */
object CaptureDigest {
    /** Smaller than iOS (2400): Android runs the model on the CPU. */
    const val BUDGET = 1600

    fun text(analysis: CaptureAnalysis, name: String, origin: String, describe: (String) -> String? = { null }): String {
        val summary = analysis.summary
        fun endpoint(address: String) = describe(address)?.let { "$address (${AssistantDigest.clean(it)})" } ?: address
        var head = "Traffic capture \"${AssistantDigest.clean(name, 60)}\", $origin: ${summary.packetCount} packets, ${summary.capturedBytes} bytes"
        summary.duration?.let { head += String.format(Locale.US, ", over %.1f s", it) }
        val lines = mutableListOf("$head.")
        if (summary.linkTypes.isNotEmpty()) lines.add("Link layer: ${summary.linkTypes.joinToString(", ")}.")
        val radio = summary.linkTypes.any { it.startsWith("802.11") }
        if (radio) {
            lines.add(
                "These are Wi‑Fi frames heard over the air. Data frames of a protected network are encrypted, so a capture like " +
                    "this shows which devices talk to which and how much, and the management frames (beacons, probes, " +
                    "authentication, deauthentication) — not websites, passwords or messages.",
            )
        }
        if (summary.packetCount == 0) lines.add("It holds no packets: nothing was heard, or the capture never started.")
        if (summary.protocols.isNotEmpty()) lines.add("Protocols: " + summary.protocols.take(8).joinToString(", ") { "${it.name} ${it.count}" } + ".")
        if (summary.apps.isNotEmpty()) {
            val title = if (radio) "Frame types and applications" else "Applications (guessed from ports)"
            lines.add("$title: " + summary.apps.take(10).joinToString(", ") { "${it.name} ${it.count}" } + ".")
        }
        if (summary.talkers.isNotEmpty()) {
            lines.add("Busiest addresses: " + summary.talkers.take(6).joinToString("; ") { "${endpoint(it.endpoint)} ${it.packets} pkt/${it.bytes} B" } + ".")
        }
        if (summary.conversations.isNotEmpty()) {
            lines.add("Busiest pairs: " + summary.conversations.take(5).joinToString("; ") { "${endpoint(it.a)} ↔ ${endpoint(it.b)} ${it.packets} pkt" } + ".")
        }
        val first = analysis.rows.take(12).map { "- ${it.transport}: ${AssistantDigest.clean(it.info, 90)}" }
        if (first.isNotEmpty()) lines.add("First packets:\n" + first.joinToString("\n"))
        if (analysis.rowsTruncated) lines.add("(only part of a very large capture was counted)")
        var text = lines.joinToString("\n")
        if (text.length > BUDGET) text = text.take(BUDGET) + "\n…(cut)"
        return text
    }
}

/**
 * The phone's own recorded traffic (Internet tab → Traffic: connections forwarded through the on-device VPN) written up
 * for the assistant. Hosts, paths and headers come from the network, so each is flattened before the model sees it.
 */
object TrafficDigest {
    /** Smaller than iOS (2400): Android runs the model on the CPU. */
    const val BUDGET = 1600

    fun text(flows: List<TrafficFlow>, name: String, origin: String): String {
        val sent = flows.sumOf { it.sent }
        val received = flows.sumOf { it.received }
        var head = "Recorded traffic of this phone \"${AssistantDigest.clean(name, 60)}\", $origin: ${flows.size} connections, " +
            "${bytes(sent)} sent, ${bytes(received)} received"
        val first = flows.minOfOrNull { it.startedAt }
        val last = flows.maxOfOrNull { it.lastAt }
        if (first != null && last != null && last > first) head += String.format(Locale.US, ", over %.0f s", (last - first) / 1000.0)
        val lines = mutableListOf("$head.")
        lines.add(
            "These are the phone's own connections, recorded on the phone itself through its local VPN: which " +
                "servers each app reached and how much it exchanged. Encrypted (TLS) connections show only the server " +
                "name unless TLS decryption was on; plain HTTP shows the requests themselves.",
        )
        if (flows.isEmpty()) {
            lines.add("It holds no connections: nothing went out while recording, or recording never started.")
            return lines.joinToString("\n")
        }
        val kinds = flows.groupBy { it.kind() }.map { it.key.name to it.value.size }.sortedByDescending { it.second }
        lines.add("By kind: " + kinds.joinToString(", ") { "${it.first} ${it.second}" } + ".")
        // What was asked of those servers, by endpoint: the paths' changing parts are left out, they can name a person.
        val groups = EndpointGroup.build(flows)
        if (groups.isNotEmpty()) {
            fun name(group: EndpointGroup) = AssistantDigest.clean(group.key, 60)
            fun line(title: String, items: List<String>) = lines.add("$title: " + if (items.isEmpty()) "none." else items.joinToString("; ") + ".")
            lines.add("HTTP requests read: ${groups.sumOf { it.hits.size }}, endpoints: ${groups.size} ({id} stands for a changing part).")
            line("Most requested", groups.take(4).map { "${name(it)} ×${it.hits.size}" })
            line("With errors", groups.filter { it.errors > 0 }.sortedByDescending { it.errors }.take(3).map { "${name(it)} ${it.errors} of ${it.hits.size}" })
            line(
                "Slow (usually a second or more)",
                groups.filter { (it.typicalMs ?: 0) >= TrafficAudit.SLOW_TYPICAL_MS }.sortedByDescending { it.typicalMs }.take(3).map { "${name(it)} ${it.typicalMs} ms" },
            )
            val scans = TrafficAudit.scan(RequestHit.all(flows).filter { it.exchange.method.isNotEmpty() })
            val exposed = scans.filter { scan -> scan.secrets.any { scan.cleartext || it.place.inAddress } }
            line(
                "Secrets in addresses or sent unencrypted (values hidden)",
                if (exposed.isEmpty()) emptyList() else listOf("${exposed.size} requests, ${TrafficAudit.englishList(exposed.flatMap { it.secrets }.distinct(), 3)}"),
            )
        } else {
            val requests = flows.mapNotNull { flow -> flow.request?.let { flow.title to it } }
            if (requests.isNotEmpty()) {
                lines.add(
                    "Requests seen: " + requests.take(8).joinToString("; ") { (host, request) ->
                        val shown = if (' ' in request) "${request.substringBefore(' ')} ${Endpoint.template(request.substringAfter(' '))}" else request
                        "${AssistantDigest.clean(host, 40)} ${AssistantDigest.clean(shown, 60)}"
                    } + ".",
                )
            }
        }
        val trackers = flows.mapNotNull { TrackerHosts.serviceOf(it.title) }.distinctBy { it.name }
        lines.add(
            "Known analytics and advertising services contacted: " +
                if (trackers.isEmpty()) "none." else trackers.take(6).joinToString(", ") { "${it.name} (${TrafficAudit.englishKind(it.kind)})" } + ".",
        )
        val cleartext = flows.filter { it.kind() == cattivole.spectra.core.TrafficKind.HTTP && !it.tls }
        if (cleartext.isNotEmpty()) {
            lines.add("Unencrypted HTTP to: " + cleartext.map { it.title }.toSortedSet().take(8).joinToString(", ") { AssistantDigest.clean(it, 40) } + ".")
        }
        val refused = flows.count { it.refused }
        if (refused > 0) lines.add("$refused connections refused Spectra's certificate (the app pins its own, or the certificate is not trusted yet).")
        val dns = flows.count { it.kind() == cattivole.spectra.core.TrafficKind.DNS }
        if (dns > 0) lines.add("$dns DNS lookups.")
        // Last, so a long list of servers gives way.
        val byHost = flows.groupBy { it.title }.map { (host, group) ->
            HostRow(
                host,
                group.size,
                group.sumOf { it.sent + it.received },
                group.map { it.kind().name }.toSortedSet().toList(),
                group.map { it.destinationPort }.toSortedSet().toList(),
                group.mapNotNull { it.app }.toSortedSet().toList(),
            )
        }.sortedWith(compareByDescending<HostRow> { it.bytes }.thenBy { it.host })
        lines.add("Busiest servers (${byHost.size} in all):")
        var room = BUDGET - lines.sumOf { it.length + 1 } - 20
        var listed = 0
        for (entry in byHost.take(14)) {
            val parts = mutableListOf(
                AssistantDigest.clean(entry.host, 50),
                "${entry.count} conn",
                bytes(entry.bytes),
                entry.kinds.joinToString("/"),
                "port " + entry.ports.take(3).joinToString(","),
            )
            if (entry.apps.isNotEmpty()) parts.add("app " + entry.apps.take(2).joinToString(", ") { AssistantDigest.clean(it, 30) })
            val line = "- " + parts.joinToString(" · ")
            room -= line.length + 1
            if (room < 0) break
            lines.add(line)
            listed += 1
        }
        if (byHost.size > listed) lines.add("- …and ${byHost.size - listed} more.")
        var text = lines.joinToString("\n")
        if (text.length > BUDGET) text = text.take(BUDGET) + "\n…(cut)"
        return text
    }

    private class HostRow(val host: String, val count: Int, val bytes: Long, val kinds: List<String>, val ports: List<Int>, val apps: List<String>)

    private fun bytes(count: Long): String = when {
        count >= 1_048_576 -> String.format(Locale.US, "%.1f MB", count / 1_048_576.0)
        count >= 1024 -> "${count / 1024} KB"
        else -> "$count B"
    }
}

/**
 * A device's GATT services and characteristics written up for the assistant. Every name and value is flattened:
 * this is an unknown device's own words.
 */
object GattDigest {
    const val BUDGET = 1800

    fun text(read: BleGattClient.Read, name: String, rssi: Int?, intro: String): String {
        val lines = mutableListOf("$intro \"${AssistantDigest.clean(name)}\"${rssi?.let { " ($it dBm)" } ?: ""}, GATT services and characteristics:")
        for (service in read.services.take(12)) {
            val title = service.name?.let { "${AssistantDigest.clean(it)} (${service.uuid})" } ?: service.uuid
            lines.add("- Service $title")
            for (characteristic in service.characteristics.take(10)) {
                val parts = mutableListOf(AssistantDigest.clean(characteristic.name ?: Catalog.characteristicName(characteristic.uuid) ?: characteristic.uuid))
                if (characteristic.properties.isNotEmpty()) parts.add(characteristic.properties)
                val data = characteristic.value
                if (data != null && data.isNotEmpty()) {
                    val decoded = GattValue.decode(characteristic.uuid, data)
                    parts.add(
                        if (decoded != null) "= ${AssistantDigest.clean(decoded, 80)}"
                        else "= 0x" + data.take(16).joinToString("") { String.format("%02x", it) },
                    )
                }
                lines.add("  · " + parts.joinToString(" · "))
            }
            if (service.characteristics.isEmpty()) lines.add("  · (no characteristics readable without pairing)")
        }
        if (read.services.size > 12) lines.add("- …and ${read.services.size - 12} more services.")
        lines.add("Write/Notify only say what the device accepts; nothing was written to it.")
        var text = lines.joinToString("\n")
        if (text.length > BUDGET) text = text.take(BUDGET) + "\n…(cut)"
        return text
    }
}

/**
 * The traffic capture last decoded on the Decode pcap screen, kept in memory so the assistant can talk about it.
 * Only the decoded summary is kept, never sent anywhere.
 */
class CaptureShelf {
    class Entry(val name: String, val analysis: CaptureAnalysis, val origin: String, val decodedAt: Long)

    /** The phone's own traffic recorded on the Traffic screen, handed over with its ✨ button. */
    class Recording(val name: String, val flows: List<TrafficFlow>, val keptAt: Long)

    /** Where the cards of recorded requests read the recordings. Set once at launch. */
    var library = TrafficLibrary()

    /** Cards worked out off the main thread just before they are shown, newest last. */
    private val prepared = ArrayDeque<Pair<CardSubject, CardFacts>>()

    var latest: Entry? = null
        private set
    private var recording: Recording? = null

    /** The traffic the Traffic screen holds right now, read when the assistant is asked without the button. */
    var recordedTraffic: () -> Recording? = { null }

    fun keep(name: String, analysis: CaptureAnalysis, fromSensor: Boolean, at: Long = System.currentTimeMillis()) {
        latest = Entry(name, analysis, if (fromSensor) "taken by the Spectra sensor" else "a file opened on the phone", at)
    }

    fun keep(traffic: List<TrafficFlow>, name: String, at: Long = System.currentTimeMillis()) {
        recording = Recording(name, traffic, at)
    }

    fun keep(facts: CardFacts, subject: CardSubject) {
        prepared.removeAll { it.first == subject }
        prepared.addLast(subject to facts)
        if (prepared.size > 4) prepared.removeFirst()
    }

    fun prepared(subject: CardSubject): CardFacts? = prepared.lastOrNull { it.first == subject }?.second

    /** What the assistant reads as the recorded traffic: the one handed over last, else whatever the screen holds. */
    val currentRecording: Recording? get() = recording ?: recordedTraffic()
}
