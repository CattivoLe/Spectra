package cattivole.spectra.assistant

import alexcattivo.localchat.kit.tools.ToolRegistry
import cattivole.spectra.core.BleDevice
import cattivole.spectra.core.MacAddress
import cattivole.spectra.core.PcapAnalyzer
import cattivole.spectra.platform.BleGattClient
import cattivole.spectra.platform.SensorLink
import cattivole.spectra.ui.tr
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.json.JSONObject
import kotlin.coroutines.coroutineContext

/**
 * Live actions the assistant may trigger on its own: a fresh scan or check, not just the last saved result.
 *
 * Deliberately excluded, on any phrasing of the question: deauth, LoRa transmission, firmware flashing, joining
 * a Wi‑Fi network, and anything else that sends something over the air or changes a setting. Those stay behind
 * the app's own buttons. What the model sees comes from the air and could be adversarial, so it never gets a
 * lever that transmits. `connect_bluetooth` is the one that opens a link rather than only listening: it reads
 * what one device publishes about itself and drops the link again; nothing is written. Mirrors AssistantActions.swift.
 */
class AssistantActions(val sources: AssistantSources, private val memory: AssistantMemory) {
    enum class Name(val raw: String) {
        SCAN_WIFI("scan_wifi"),
        SCAN_LAN("scan_lan"),
        SCAN_BLUETOOTH("scan_bluetooth"),
        CHECK_CONNECTION("check_connection"),
        SPEED_TEST("speed_test"),
        SCAN_PORTS("scan_ports"),
        CHECK_PORT_FORWARDING("check_port_forwarding"),
        CAPTURE_PCAP("capture_pcap"),
        CONNECT_BLUETOOTH("connect_bluetooth"),
        SEARCH_HISTORY("search_history"),
        REMEMBER("remember"),
        FORGET("forget");

        companion object {
            fun of(raw: String): Name? = entries.firstOrNull { it.raw == raw }
        }
    }

    val registry: ToolRegistry
        get() = ToolRegistry(
            grammar = GRAMMAR,
            instructions = INSTRUCTIONS,
            maxSteps = 4,
            preflight = { question -> preflightCalls(question, sources.model.ble.devices.values) },
            runningLabel = ::runningLabel,
            run = { name, args -> run(name, args) },
        )

    private suspend fun run(name: String, argsJson: String): String {
        val model = sources.model
        val tool = Name.of(name) ?: return "Unknown tool \"$name\"."
        return when (tool) {
            Name.SCAN_WIFI -> {
                // The phone's own radio lists networks without the sensor; the sensor adds clients, probes and deauth frames.
                model.phone.refresh()
                if (model.link.isConnected) model.link.scanNow()
                delay(500)
                waitWhile(15_000) { model.store.isScanning || model.phone.status == cattivole.spectra.platform.PhoneWifiScanner.Status.SCANNING }
                section(AssistantTopic.WIFI)
            }
            Name.SCAN_LAN -> {
                model.lan.scan()
                delay(400)
                waitWhile(25_000) { model.lan.scanning }
                section(AssistantTopic.LAN)
            }
            Name.SCAN_BLUETOOTH -> {
                model.ble.start()
                delay(3_000)
                section(AssistantTopic.BLUETOOTH)
            }
            Name.CHECK_CONNECTION -> {
                model.connection.check()
                delay(400)
                waitWhile(30_000) { model.connection.running }
                section(AssistantTopic.LINK)
            }
            Name.SPEED_TEST -> {
                model.speed.start()
                delay(400)
                waitWhile(30_000) { model.speed.running }
                section(AssistantTopic.LINK)
            }
            Name.SCAN_PORTS -> {
                val host = argument(argsJson, "host", "bssid")
                    ?: return "Missing or invalid \"host\". Name an IP address already seen in the LAN hosts list."
                val device = model.lan.devices.firstOrNull { it.ipAddress == host }
                    ?: return "\"$host\" is not a host this phone has seen on the LAN. Use an address from the LAN hosts list, or call scan_lan first."
                // Every TCP port takes minutes rather than seconds, so it happens only when the question asked for it.
                val everyPort = argument(argsJson, "text")?.let(::asksForEveryPort) ?: false
                model.lan.scanHostPorts(host, all = everyPort)
                delay(400)
                waitWhile(if (everyPort) 240_000 else 20_000) { device.portScanFraction != null }
                section(AssistantTopic.LAN)
            }
            Name.CHECK_PORT_FORWARDING -> {
                if (model.lan.devices.none { it.isGateway }) {
                    return "No router found on the local network yet, so its forwarding table cannot be read. Call scan_lan first."
                }
                model.lan.checkPortForwarding()
                delay(400)
                waitWhile(25_000) { model.lan.checkingForwarding }
                forwardingReport()
            }
            Name.CAPTURE_PCAP -> capture(argsJson)
            Name.CONNECT_BLUETOOTH -> connectBluetooth(argsJson)
            Name.SEARCH_HISTORY -> HistoryDigest.text(model.history.entries, argument(argsJson, "text", "host", "bssid") ?: "")
            Name.REMEMBER -> {
                val text = argument(argsJson, "text") ?: return "Missing \"text\". Provide the note to save."
                val subject = argument(argsJson, "host", "bssid")
                memory.add(text, subject)
                if (subject != null) "Saved to the dossier, filed under $subject." else "Saved to the dossier."
            }
            Name.FORGET -> {
                val text = argument(argsJson, "text") ?: return "Missing \"text\". Provide text to find the note to remove."
                if (memory.removeMatching(text)) "Removed from the dossier." else "No matching note found in the dossier."
            }
        }
    }

    private suspend fun capture(argsJson: String): String {
        val model = sources.model
        val link = model.link
        if (!link.isConnected) return "No sensor connected, so a traffic capture is not possible right now."
        val raw = argument(argsJson, "bssid", "host")
            ?: return "Missing or invalid \"bssid\". Name a Wi‑Fi access point's BSSID exactly as shown in the Wi‑Fi networks list, not its name and not an IP."
        val bssid = MacAddress.parse(raw)
        val network = bssid?.let { model.store.networks()[it] }
        if (bssid == null || network == null) {
            return "\"$raw\" is not a BSSID this phone has seen. Use one from the Wi‑Fi networks list, or call scan_wifi first."
        }
        if (link.rawCapturing) return "A capture is already running on this sensor. Wait for it to finish before starting another."
        val channel = network.latest.channel
        when (val support = link.captureSupport(channel)) {
            SensorLink.CaptureSupport.Ok -> Unit
            SensorLink.CaptureSupport.BandNotHeard ->
                return "This network is on 5 GHz (channel $channel), and the connected sensor hears 2.4 GHz only, so it cannot capture it. The ESP32-C5 sensor can."
            is SensorLink.CaptureSupport.FirmwareTooOld ->
                return "This network is on 5 GHz (channel $channel). The sensor's firmware ${support.version} captures on 2.4 GHz only; firmware ${SensorLink.FIVE_GHZ_CAPTURE_FIRMWARE} or newer captures on 5 GHz."
        }
        link.startRawCapture(channel, network = bssid)
        delay(captureDurationMs)
        link.stopRawCapture()
        waitWhile(5_000) { link.rawCapturing }
        val analysis = runCatching { PcapAnalyzer.analyze(link.rawCapturePcap()) }.getOrNull()
        if (analysis == null || analysis.summary.packetCount == 0) {
            return "No frames captured in ${captureDurationMs / 1000} seconds. The network may be quiet, out of range, or the sensor lost its Bluetooth link."
        }
        val ssid = network.revealedSsid ?: network.latest.ssid
        val name = if (ssid != null) "$ssid ($raw)" else raw
        // Kept like a capture decoded by hand, so a later question about "the capture" finds this one.
        sources.captures.keep(name, analysis, fromSensor = true)
        return CaptureDigest.text(analysis, name, "taken just now by the Spectra sensor on channel $channel", sources::describe)
    }

    private suspend fun connectBluetooth(argsJson: String): String {
        val model = sources.model
        val wanted = argument(argsJson, "text", "host")
            ?: return "Missing \"text\". Name the Bluetooth device to connect to, as it appears in the Bluetooth devices list."
        val device = bluetoothDevice(wanted, model.ble.devices.values)
            ?: return "\"${AssistantDigest.clean(wanted)}\" is not a Bluetooth device this phone can hear. Call scan_bluetooth first, then use a name from that list."
        val gatt = model.gatt
        gatt.connect(device.address)
        waitWhile(15_000) { gatt.state is BleGattClient.State.Connecting || gatt.state is BleGattClient.State.Discovering }
        // Characteristics arrive with the services; their values after that: give the reads a moment.
        if (gatt.state is BleGattClient.State.Connected) delay(2_500)
        val report = gattReport(device, gatt)
        gatt.disconnect()
        return report
    }

    /** What the device said about itself. Every name and value is flattened: an unknown device's own words. */
    private fun gattReport(device: BleDevice, gatt: BleGattClient): String {
        val name = AssistantDigest.clean(device.displayName)
        when (val state = gatt.state) {
            is BleGattClient.State.Failed -> return "Could not connect to \"$name\": ${AssistantDigest.clean(state.message, 100)}. " +
                "Many devices refuse a connection while they are paired with their owner's phone."
            BleGattClient.State.Connecting -> return "\"$name\" did not answer the connection request within 15 seconds. It may be out of range, " +
                "asleep, or accepting connections only from its paired phone. Nothing was read."
            BleGattClient.State.Discovering -> return "Connected to \"$name\", but it did not list its services within 15 seconds. Nothing was read."
            else -> Unit
        }
        val read = gatt.lastRead
        if (read == null || read.address != device.address || read.services.isEmpty()) {
            return "Connected to \"$name\", but it published no services to read. Headphones and trackers often keep everything behind pairing."
        }
        var text = GattDigest.text(read, device.displayName, device.rssi, "Read just now over Bluetooth from")
        if (gatt.endedAfterRead) text += "\nThe device closed the link after answering, which is normal."
        return text
    }

    /** The router's forwarding table, flattened: names in it are the router's own answer over UPnP. */
    private fun forwardingReport(): String {
        val lan = sources.model.lan
        val table = lan.devices.firstOrNull { it.isGateway }?.profile?.portForwarding
            ?: return "The router did not answer the port-forwarding request. Many routers keep UPnP off, which is " +
                "the safer setting; the table can then only be read from the router's own web page."
        if (!table.supported) {
            return "The router does not expose a port-forwarding service over UPnP (checked just now). That is the " +
                "safer setting: nothing on the network can open a port from the inside without the owner knowing. " +
                "Any forwarding it does have would have to be set by hand on the router's own page."
        }
        val active = table.mappings.filter { it.enabled }
        if (active.isEmpty()) {
            return "The router forwards no ports from the internet right now" +
                (table.externalIp?.let { " (external address $it)" } ?: "") + ". Nothing on the network is reachable from outside this way."
        }
        val lines = mutableListOf("Ports the router forwards from the internet to this network" + (table.externalIp?.let { ", external address $it" } ?: "") + ":")
        for (mapping in active.take(20)) {
            val parts = mutableListOf("${mapping.proto} ${mapping.externalPort} → ${mapping.internalClient}:${mapping.internalPort}")
            lan.devices.firstOrNull { it.ipAddress == mapping.internalClient }?.let { parts.add(AssistantDigest.clean(it.displayName)) }
            mapping.description?.takeIf { it.isNotEmpty() }?.let { parts.add("\"${AssistantDigest.clean(it, 50)}\"") }
            mapping.leaseSeconds?.takeIf { it > 0 }?.let { parts.add("lease ${it}s") }
            mapping.remoteHost?.takeIf { it.isNotEmpty() }?.let { parts.add("only from ${AssistantDigest.clean(it, 40)}") }
            lines.add("- " + parts.joinToString(" · "))
        }
        if (active.size > 20) lines.add("- …and ${active.size - 20} more.")
        lines.add("Each line means that device is reachable from the open internet on that port.")
        return lines.joinToString("\n")
    }

    private fun section(topic: AssistantTopic): String =
        AssistantDigest.section(topic, sources.snapshot()).ifEmpty { "Nothing new." }

    companion object {
        /**
         * How long one on-demand traffic capture listens before it is analyzed. Short on purpose: it pins the sensor
         * to one channel the whole time. A `var` so tests can shorten it.
         */
        var captureDurationMs = 6_000L

        /**
         * GBNF grammar: `{"tool": "<name>"}`, with an optional `"host"` or `"bssid"` field and an optional `"text"`.
         */
        val GRAMMAR = """
            root  ::= ws "{" ws "\"tool\"" ws ":" ws tool tail ws "}" ws
            tool  ::= "\"scan_wifi\"" | "\"scan_lan\"" | "\"scan_bluetooth\"" | "\"check_connection\"" | "\"speed_test\"" | "\"scan_ports\"" | "\"check_port_forwarding\"" | "\"capture_pcap\"" | "\"connect_bluetooth\"" | "\"search_history\"" | "\"remember\"" | "\"forget\""
            tail  ::= ( ws "," ws addrfield )? ( ws "," ws textfield )?
            addrfield ::= ( "\"host\"" | "\"bssid\"" ) ws ":" ws "\"" [a-zA-Z0-9._:-]+ "\""
            textfield ::= "\"text\"" ws ":" ws "\"" [^\"]+ "\""
            ws    ::= [ \t\n]*
        """.trimIndent()

        /** Appended to the system prompt: the exact syntax has to match [GRAMMAR] and the registry's call tag. */
        val INSTRUCTIONS = """
You can also run a live check on this phone instead of only reading what was already found. To run one, write exactly this and nothing else in your reply:
<tool_call>{"tool": "NAME"}
where NAME is one of: scan_wifi, scan_lan, scan_bluetooth, check_connection, speed_test, check_port_forwarding. For scan_ports, name a host you have already seen in the LAN hosts list:
<tool_call>{"tool": "scan_ports", "host": "192.168.1.1"}
That probes the usual ports. To probe every TCP port of that host instead, which takes much longer, add "text": "all" — do it only when the user asks for a full or deep scan:
<tool_call>{"tool": "scan_ports", "host": "192.168.1.1", "text": "all"}
For capture_pcap (needs the Spectra sensor), name a Wi‑Fi access point's BSSID exactly as shown in the Wi‑Fi networks list (not its name, not an IP), to pin the sensor to that network's channel for a few seconds and summarize what crossed it:
<tool_call>{"tool": "capture_pcap", "bssid": "AA:BB:CC:00:00:01"}
For connect_bluetooth, name a device exactly as it appears in the Bluetooth devices list, to connect to it and read what it tells about itself:
<tool_call>{"tool": "connect_bluetooth", "text": "Momentum 3"}
For search_history, say what to look for in everything saved under History — a network or device name, an IP, MAC or BSSID — or leave "text" out for an overview of all saved sessions:
<tool_call>{"tool": "search_history", "text": "Galaxy Buds"}
Stop right after the closing brace — no closing tag, nothing else on the line. Each one takes a few seconds, and you will be given its result to answer with next.
Which one:
- scan_wifi: Wi‑Fi networks around, channels, signal, security. Use when asked about channels, interference, which band or channel is best, what networks are nearby.
- scan_lan: devices on the local network, their ports, manufacturers. Use when asked about devices, hosts, who is on the network, what a device is.
- scan_bluetooth: Bluetooth devices and trackers nearby. Use for tracker detection, Bluetooth devices, AirTag.
- check_connection: internet connection check — DNS, latency, public IP, provider, access, TLS interception. Use when asked to check internet, verify connectivity, test access to a service, ping, check if something is reachable.
- speed_test: download/upload speed, latency under load, jitter. Use when asked about speed, bandwidth.
- scan_ports: scan open ports on a specific LAN host. Use when asked about what ports are open, what services a device runs, whether a device is secure.
- check_port_forwarding: ask the router which ports it forwards from the internet to devices on the network (UPnP/NAT-PMP). Use when asked about port forwarding, open ports on the router, whether anything is reachable from outside, or whether the router is safe («проброс портов», «что открыто наружу», «UPnP»). A forwarding rule nobody meant to add is one of the most serious things you can find: it puts a device on the open internet. It needs the LAN hosts list: call scan_lan first when there is none.
- capture_pcap: capture Wi‑Fi traffic on a network's channel for a few seconds. Use when asked to capture, sniff, or record traffic.
- connect_bluetooth: connect to ONE Bluetooth device and read its GATT services and characteristics — often its model, maker, firmware version and battery level. Use when asked to connect to a device, read it, read its GATT services, or find out what exactly it is («подключись к Momentum 3», «что это за устройство», «прочитай GATT наушников»). The device must be in the Bluetooth list first: call scan_bluetooth when it is not there. Reading takes a few seconds and the link is dropped again afterwards. When the attached data already holds a GATT read of that device and the user asks to analyze or explain it, explain that read instead of connecting again: name each standard service (Device Information, Battery, Heart Rate, …), say what the decoded values reveal (maker, model, firmware), and point out vendor-specific services and characteristics that accept writes without pairing.
- search_history: read everything saved under History, across all sessions and days: networks, LAN hosts with their ports, Bluetooth devices, connection checks, sensor summaries. Use when asked whether something was seen before, what changed since last time, what was at a place, or anything about earlier scans («было ли это устройство раньше», «что было в истории», «когда я видел эту сеть»). It reads saved data only; it does not scan.
IMPORTANT: when the user asks to check, verify, test or measure ANYTHING about their own network, devices or connection — call the matching tool. These are live measurements, not web lookups. A web search cannot ping a host, measure speed, or scan a network. When the user asks you to scan, check, test or measure something, in any language («просканируй блютуз», «проверь интернет», «замерь скорость», «проверь доступ к гуглу», «какой канал лучше»), call the matching tool even if older data is already attached. Otherwise call one only when the question needs fresher data than what you have. When a <tool_result> for the question is already there, answer from it rather than calling the same tool again. Never claim a check ran, or report a number from one, unless you got its result back.
When the attached data already has a traffic capture and the user asks to decode, explain or analyze it («расшифруй pcap», «что в захвате»), explain that capture: what kinds of frames or protocols it holds, which devices talked most and to whom, and anything unusual (deauthentication bursts, unknown devices, plain-text protocols). Call capture_pcap only when the user asks for a new capture. "Recorded traffic of this phone" is different: the phone's own connections, recorded on the phone itself. For it, say which servers and services the phone talked to, which apps or hosts were the busiest, what went unencrypted over plain HTTP, and anything that looks like tracking, advertising or an unexpected destination.
You have a dossier — a persistent notebook shared across all chats. Use it to save important findings, conclusions, and facts worth keeping: a suspicious device, a weak password, a resolved incident, a note about the user's network setup. To save a note:
<tool_call>{"tool": "remember", "text": "the note to save"}
When the note is about one device or network, file it under that device's address — an IP, a MAC or a BSSID, exactly as the data shows it. A filed note comes back by itself the next time that device is in front of the user, while an unfiled one travels with every question, so file whatever can be filed:
<tool_call>{"tool": "remember", "host": "192.168.1.64", "text": "the owner says this camera is theirs"}
To remove a note that is no longer relevant:
<tool_call>{"tool": "forget", "text": "search text to find the note"}
Save a note when you discover something the user should know next time, or when the user asks to remember something. Keep notes short and factual. The dossier section in <app_data> shows what is already saved.
        """.trimIndent()

        // MARK: - Plain requests, recognized by the app

        /**
         * Checks the question itself plainly asks to run, recognized here rather than left to the model: a small
         * model often answers «просканируй блютуз» from the attached snapshot instead of calling the tool.
         */
        fun requested(question: String): List<Name> {
            val text = question.lowercase()
            fun has(stems: List<String>) = AssistantDigest.matches(stems, text)
            if (has(Stems.aboutChecks)) return emptyList()
            val run = has(Stems.run)
            val nearby = has(Stems.nearby)
            if (!run && !nearby) return emptyList()
            val found = mutableSetOf<Name>()
            // Bluetooth and Wi‑Fi are what is "nearby": asking what is around already asks for a fresh look.
            if (has(Stems.bluetooth)) found.add(Name.SCAN_BLUETOOTH)
            if (has(Stems.wifi)) found.add(Name.SCAN_WIFI)
            // The rest takes a while (a LAN sweep, a speed test), so only an outright request runs it.
            if (run) {
                if (has(Stems.forwarding)) found.add(Name.CHECK_PORT_FORWARDING)
                if (has(Stems.lan)) found.add(Name.SCAN_LAN)
                if (has(Stems.speed)) found.add(Name.SPEED_TEST) else if (has(Stems.connection)) found.add(Name.CHECK_CONNECTION)
            }
            if (found.isEmpty()) {
                when {
                    has(Stems.devices) -> found.add(if (nearby) Name.SCAN_BLUETOOTH else Name.SCAN_LAN)
                    has(Stems.networks) -> found.add(if (nearby) Name.SCAN_WIFI else Name.SCAN_LAN)
                    // «подключись к Momentum 3»: sweep Bluetooth first so the model has a current list to name from.
                    has(Stems.connectTo) -> found.add(Name.SCAN_BLUETOOTH)
                }
            }
            return Name.entries.filter { it in found }
        }

        /** [requested] plus History and the one check with an argument the app can fill in itself: a device to read. */
        fun preflightCalls(question: String, devices: Collection<BleDevice>): List<String> {
            val calls = mutableListOf<String>()
            // A question about earlier scans gets History read for it, with the question as what to look for.
            if (asksAboutHistory(question)) {
                calls.add("{\"tool\": \"${Name.SEARCH_HISTORY.raw}\", \"text\": ${JSONObject.quote(question.take(300))}}")
            }
            return calls + scanCalls(question, devices)
        }

        fun asksAboutHistory(question: String): Boolean {
            val text = question.lowercase()
            return !AssistantDigest.matches(Stems.aboutChecks, text) && AssistantDigest.matches(Stems.history, text)
        }

        private fun scanCalls(question: String, devices: Collection<BleDevice>): List<String> {
            val names = requested(question).toMutableList()
            val device = bluetoothTarget(question, devices) ?: return names.map { it.raw }
            // The device is already heard: a fresh sweep first would only add a few seconds.
            names.remove(Name.SCAN_BLUETOOTH)
            return names.map { it.raw } + "{\"tool\": \"${Name.CONNECT_BLUETOOTH.raw}\", \"text\": ${JSONObject.quote(device.displayName)}}"
        }

        /** The heard device a question names and asks to connect to or read. Longest name wins, then strongest signal. */
        fun bluetoothTarget(question: String, devices: Collection<BleDevice>): BleDevice? {
            val text = question.lowercase()
            fun has(stems: List<String>) = AssistantDigest.matches(stems, text)
            if (has(Stems.aboutChecks) || !(has(Stems.connectTo) || has(Stems.readDevice))) return null
            return devices.filter { device ->
                val name = device.displayName.lowercase()
                name.length >= 3 && !name.contains('{') && !name.contains('}') && text.contains(name)
            }.maxWithOrNull(compareBy<BleDevice> { it.displayName.length }.thenBy { it.rssi })
        }

        /** Whether the scope the model passed to `scan_ports` means "every port" rather than the usual short list. */
        fun asksForEveryPort(text: String): Boolean =
            AssistantDigest.matches(listOf("all", "full", "every", "deep", "whole", "65535", "все", "весь", "полн", "глубок"), text.lowercase())

        /** The device the model named, matched loosely both ways; among several, the nearest one wins. */
        fun bluetoothDevice(wanted: String, devices: Collection<BleDevice>): BleDevice? {
            val needle = wanted.trim()
            if (needle.isEmpty()) return null
            return devices.filter {
                it.displayName.contains(needle, ignoreCase = true) || needle.contains(it.displayName, ignoreCase = true)
            }.maxByOrNull { it.rssi }
        }

        fun runningLabel(name: String): String = when (Name.of(name)) {
            Name.SCAN_WIFI -> tr("Scanning Wi‑Fi…")
            Name.SCAN_LAN -> tr("Scanning the network…")
            Name.SCAN_BLUETOOTH -> tr("Scanning Bluetooth…")
            Name.CHECK_CONNECTION -> tr("Checking the connection…")
            Name.SPEED_TEST -> tr("Measuring speed…")
            Name.SCAN_PORTS -> tr("Scanning ports…")
            Name.CHECK_PORT_FORWARDING -> tr("Asking the router about port forwarding…")
            Name.CAPTURE_PCAP -> tr("Capturing traffic…")
            Name.CONNECT_BLUETOOTH -> tr("Connecting to the device…")
            Name.SEARCH_HISTORY -> tr("Looking through History…")
            Name.REMEMBER -> tr("Saving a note…")
            Name.FORGET -> tr("Removing a note…")
            null -> tr("Running %s…", name)
        }

        private fun argument(argsJson: String, vararg keys: String): String? {
            val json = runCatching { JSONObject(argsJson) }.getOrNull() ?: return null
            for (key in keys) json.optString(key).trim().takeIf { it.isNotEmpty() && json.has(key) }?.let { return it }
            return null
        }

        /** Polls [condition] until it turns false, the timeout elapses, or the chat is cancelled. */
        private suspend fun waitWhile(timeoutMs: Long, condition: () -> Boolean) {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (condition() && coroutineContext.isActive && System.currentTimeMillis() < deadline) delay(300)
        }
    }

    private object Stems {
        /** Asking about what was saved earlier rather than what is around now. */
        val history = listOf(
            "history", "earlier", "previously", "last time", "last week", "yesterday", "saved session", "seen before",
            "истори", "раньше", "прошл", "вчера", "сохранённ", "сохраненн", "сесси", "встречал", "видел ли", "был ли", "была ли",
            "были ли", "было ли",
        )
        /** Asking to read what one device publishes about itself. Verbs only: «проанализируй GATT X» is about a read made. */
        val readDevice = listOf("read", "прочита", "прочт", "считай", "считать")
        /** Asking to run something, not just to read. "connect to", not "connect": that starts "connection". */
        val run = listOf(
            "scan", "rescan", "check", "test", "measure", "run", "refresh", "look for",
            "скан", "сканир", "просканир", "отсканир", "пересканир", "провер", "замер", "измер", "тест", "протестир",
            "обнов", "запуст", "найд", "поищ", "ищ", "подключ",
            "connect to",
        )
        val connectTo = listOf("connect to", "подключ", "приконнект")
        val forwarding = listOf(
            "port forward", "forwarded port", "upnp", "nat-pmp", "port mapping",
            "проброс", "пробрасыв", "переадресац", "перенаправлен", "юпнп",
        )
        val nearby = listOf("nearby", "around", "near me", "close by", "рядом", "вокруг", "поблизости", "неподалёку", "неподалеку")
        /** A question about checks rather than a request for one: «зачем сканировать блютуз?», «не сканируй». */
        val aboutChecks = listOf(
            "why", "how often", "how does", "don't scan", "don't check", "do not scan", "do not check",
            "зачем", "почему", "как часто", "как работает", "что такое", "что значит",
            "не скан", "не провер", "не надо скан", "не нужно скан", "не надо провер", "не нужно провер",
            "не подключ", "не надо подключ", "не нужно подключ",
        )
        val bluetooth = listOf(
            "bluetooth", "ble", "bt", "tracker", "airtag", "headphone", "earbud", "beacon",
            "блютуз", "блютус", "трекер", "метк", "наушник", "маяк",
        )
        val wifi = listOf("wi-fi", "wi‑fi", "wifi", "wlan", "ssid", "wireless", "access point", "вайфай", "вай", "точк", "беспровод")
        val lan = listOf("lan", "host", "who is on", "локальн", "хост", "кто в")
        val speed = listOf("speed", "speedtest", "скорост", "спидтест")
        val connection = listOf("internet", "connection", "ping", "dns", "vpn", "интернет", "соединен", "подключени", "пинг", "днс", "впн")
        val devices = listOf("device", "устройств", "девайс")
        val networks = listOf("network", "сет")
    }
}
