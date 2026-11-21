package cattivole.spectra.assistant

import alexcattivo.localchat.kit.tools.ToolOffer
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
            preflight = { question ->
                preflightCalls(question, sources.model.ble.devices.values, sources.model.lan.devices.map { PortTarget(it.ipAddress, it.isGateway) })
            },
            // Questions about the user's own network are answered from the app; the web adds nothing but a wait.
            skipsWebSearch = AssistantDigest::answersFromApp,
            offer = ::offer,
            suggest = { answer, ran ->
                suggestedOffers(answer, ran, sources.model.ble.devices.values, sources.model.lan.devices.map { PortTarget(it.ipAddress, it.isGateway) })
            },
            runningLabel = ::runningLabel,
            run = { name, args -> run(name, args) },
        )

    /**
     * Runs a summary card's step: a bare tool name or a whole call (`{"tool": "scan_ports", "host": …}`), the same
     * way the model's call would run, but chosen by the app. Returns the tool's result text.
     */
    suspend fun perform(call: String): String {
        if (!call.trimStart().startsWith("{")) return run(call.trim(), "{}")
        val json = runCatching { JSONObject(call) }.getOrNull() ?: return "Bad call."
        return run(json.optString("tool"), call)
    }

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

    /** A LAN host the app can scan ports on by itself: one named in the question by IP, or the router. */
    data class PortTarget(val ip: String, val isGateway: Boolean)

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
Tools. To run a live check, reply with only the call, for example:
<tool_call>{"tool": "scan_wifi"}
Stop right after the closing brace: no closing tag, nothing else. You then get its result to answer with.
- scan_wifi: nearby Wi‑Fi networks, channels, signal, security.
- scan_lan: devices on the local network, their ports and makers.
- scan_bluetooth: nearby Bluetooth devices and trackers (AirTag).
- check_connection: internet check — DNS, latency, public IP, provider, TLS interception, and whether a site or service opens (YouTube, Instagram, Telegram…).
- speed_test: download/upload speed, latency under load, jitter.
- check_port_forwarding: which ports the router opened or forwards to the internet (UPnP/NAT-PMP) — use it, not scan_ports, for anything about ports open to the outside. A rule nobody meant to add puts a device on the open internet, one of the most serious findings. Needs the LAN hosts list: call scan_lan first when there is none.
- scan_ports: the usual open ports of one host from the LAN list, seen from inside the network:
<tool_call>{"tool": "scan_ports", "host": "192.168.1.1"}
When the user asks for a full, deep or all-ports scan, add "text": "all" (much slower):
<tool_call>{"tool": "scan_ports", "host": "192.168.1.1", "text": "all"}
- capture_pcap: a few seconds of Wi‑Fi traffic on one network's channel (needs the sensor); give the BSSID exactly as in the Wi‑Fi list:
<tool_call>{"tool": "capture_pcap", "bssid": "AA:BB:CC:00:00:01"}
- connect_bluetooth: connect to ONE device from the Bluetooth list and read its GATT data (model, maker, firmware, battery); call scan_bluetooth first when it is not listed:
<tool_call>{"tool": "connect_bluetooth", "text": "Momentum 3"}
- search_history: everything saved under History across sessions — was something seen before, what changed; leave "text" out for an overview. Reads saved data only:
<tool_call>{"tool": "search_history", "text": "Galaxy Buds"}
When the user asks to scan, check, test or measure anything about their own network, devices or connection, in any language («просканируй блютуз», «проверь интернет», «замерь скорость», «проверь доступ к гуглу»), call the matching tool even if older data is attached: a web search cannot measure anything. Otherwise call one only when fresher data is needed, and not again when its <tool_result> is already there. Never report a check whose result you did not get.
When the attached data already holds what the user asks to explain, explain it instead of running a tool again. A GATT read: the standard services, what the values reveal, vendor services that accept writes without pairing. A traffic capture: protocols, the busiest devices and pairs, anything unusual (deauthentication bursts, unknown devices, plain-text protocols). Recorded traffic of this phone: the servers and apps it talked to, plain HTTP, tracking or unexpected destinations.
Dossier: a notebook shared across chats, shown in <app_data>. Save short facts worth keeping next time (a suspicious device, a resolved incident, the user's setup). When the user asks you to remember something («запомни…»), reply with only the remember call. File a note about one device under its IP, MAC or BSSID so it comes back with that device:
<tool_call>{"tool": "remember", "host": "192.168.1.64", "text": "the owner says this camera is theirs"}
Leave "host" out for a general note. To remove a note:
<tool_call>{"tool": "forget", "text": "words from the note"}
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
            val found = mutableSetOf<Name>()
            // Questions only a fresh check can answer, asked without "check": the model would answer them from the
            // snapshot — or, worse, say a site opens without trying it.
            if (asksAboutForwarding(text)) found.add(Name.CHECK_PORT_FORWARDING)
            if (has(Stems.access) && has(Stems.sites)) found.add(Name.CHECK_CONNECTION)
            if (has(Stems.speed) && has(Stems.speedAmount) && has(Stems.mine)) found.add(Name.SPEED_TEST)
            if (!run && !nearby) return Name.entries.filter { it in found }
            // Bluetooth and Wi‑Fi are what is "nearby": asking what is around already asks for a fresh look.
            if (has(Stems.bluetooth)) found.add(Name.SCAN_BLUETOOTH)
            if (has(Stems.wifi)) found.add(Name.SCAN_WIFI)
            // The rest takes a while (a LAN sweep, a speed test), so only an outright request runs it.
            if (run) {
                if (has(Stems.forwarding)) found.add(Name.CHECK_PORT_FORWARDING)
                if (has(Stems.lan)) found.add(Name.SCAN_LAN)
                if (has(Stems.speed)) found.add(Name.SPEED_TEST) else if (has(Stems.connection) && Name.SPEED_TEST !in found) found.add(Name.CHECK_CONNECTION)
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

        /**
         * [requested] plus History and the checks with an argument the app can fill in itself: a device to read,
         * a host to scan ports on.
         */
        fun preflightCalls(question: String, devices: Collection<BleDevice>, hosts: Collection<PortTarget> = emptyList()): List<String> {
            val calls = mutableListOf<String>()
            // A question about earlier scans gets History read for it, with the question as what to look for.
            if (asksAboutHistory(question)) {
                calls.add("{\"tool\": \"${Name.SEARCH_HISTORY.raw}\", \"text\": ${JSONObject.quote(question.take(300))}}")
            }
            portScanTarget(question, hosts)?.let { host ->
                val scope = if (asksForEveryPort(question)) ", \"text\": \"all\"" else ""
                calls.add("{\"tool\": \"${Name.SCAN_PORTS.raw}\", \"host\": ${JSONObject.quote(host)}$scope}")
            }
            return calls + scanCalls(question, devices)
        }

        /** Ports reachable from outside are the router's forwarding table, not a scan of one host from inside. */
        private fun asksAboutForwarding(text: String): Boolean =
            AssistantDigest.matches(Stems.forwarding, text) || (AssistantDigest.matches(Stems.ports, text) && AssistantDigest.matches(Stems.outside, text))

        /** «Посмотри, какие порты открыты на 192.168.1.64», «просканируй все порты роутера»: the host is in the question. */
        fun portScanTarget(question: String, hosts: Collection<PortTarget>): String? {
            val text = question.lowercase()
            fun has(stems: List<String>) = AssistantDigest.matches(stems, text)
            if (has(Stems.aboutChecks) || !has(Stems.ports) || asksAboutForwarding(text)) return null
            val named = IPV4.findAll(text).map { it.value }.toSet()
            // A host named by IP and a question about its ports is enough; "the router" needs an outright request,
            // or «какие порты обычно открыты у роутеров?» would scan the user's own.
            hosts.firstOrNull { it.ip in named }?.let { host -> if (has(Stems.run) || has(Stems.lookAt)) return host.ip }
            if (named.isNotEmpty() || !has(Stems.router) || !(has(Stems.run) || has(Stems.showMe))) return null
            return hosts.firstOrNull { it.isGateway }?.ip
        }

        private val IPV4 = Regex("""(?<![\d.])\d{1,3}(?:\.\d{1,3}){3}(?![\d.]*\d)""")

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

        /**
         * What a tool is called in words, for an answer that names it («выполни check_connection»), and — for the
         * checks that need no argument — the offer shown under that answer. The request is worded so that
         * [requested] runs the check itself, like the starters in an empty chat.
         */
        fun offer(name: String): ToolOffer? = when (Name.of(name)) {
            Name.SCAN_WIFI -> ToolOffer(name, tr("a Wi‑Fi scan"), tr("Scan Wi‑Fi?"), tr("Scan the Wi‑Fi around me: which channel is freer?"))
            Name.SCAN_LAN -> ToolOffer(name, tr("a network scan"), tr("Scan the local network?"), tr("Scan my local network and show me what these devices are"))
            Name.SCAN_BLUETOOTH -> ToolOffer(name, tr("a Bluetooth scan"), tr("Scan Bluetooth?"), tr("Scan Bluetooth nearby: is there someone else's tracker?"))
            Name.CHECK_CONNECTION -> ToolOffer(name, tr("a connection check"), tr("Check the connection?"), tr("Check my internet connection: DNS, latency, provider"))
            Name.SPEED_TEST -> ToolOffer(name, tr("a speed test"), tr("Measure the speed?"), tr("Measure my internet speed"))
            Name.CHECK_PORT_FORWARDING -> ToolOffer(name, tr("a port forwarding check"), tr("Check port forwarding?"), tr("Check which ports my router forwards to the internet"))
            Name.SCAN_PORTS -> ToolOffer(name, tr("a port scan"))
            Name.CAPTURE_PCAP -> ToolOffer(name, tr("a traffic capture"))
            Name.CONNECT_BLUETOOTH -> ToolOffer(name, tr("a connection to the device"))
            Name.SEARCH_HISTORY -> ToolOffer(name, tr("a search through History"))
            Name.REMEMBER, Name.FORGET, null -> null
        }

        /**
         * Next steps the answer put in plain words, as buttons: «Проверьте открытые порты на `192.168.1.1`» offers a
         * port scan of that host. Only sentences that advise or offer something are read — «сканирование показало…»
         * reports, it does not suggest — and checks that already ran for this answer are left out.
         */
        fun suggestedOffers(
            answer: String,
            ran: Collection<String>,
            devices: Collection<BleDevice>,
            hosts: Collection<PortTarget>,
        ): List<ToolOffer> {
            val found = linkedMapOf<String, ToolOffer>()
            // Last sentences first: the closing «хотите, проверю…» is the step the answer leads to, and only three
            // buttons are shown.
            for (sentence in answer.split(SENTENCE_BREAK).asReversed()) {
                val text = sentence.trim().trim('*', '-', '•', ' ').replace("`", "")
                if (text.length < 6 || !advises(text)) continue
                for (call in preflightCalls(text, devices, hosts)) {
                    val offer = offerFor(call) ?: continue
                    if (offer.name in ran) continue
                    found.putIfAbsent(offer.request ?: offer.name, offer)
                }
            }
            return found.values.toList()
        }

        private val SENTENCE_BREAK = Regex("""(?<=[.!?…])\s+|\n+""")
        // No (?U): Android's regex engine rejects it, so letters are spelled as \p{L}.
        private val IMPERATIVE = Regex("""(?<![\p{L}\p{N}_])\p{L}{3,}(?:йте|ите|ьте)(?![\p{L}\p{N}_])""")
        private val ENGLISH_IMPERATIVES = setOf("scan", "rescan", "check", "run", "test", "measure", "try", "consider", "connect", "read")

        /** Whether a sentence advises the user or offers a step, rather than reporting what was found. */
        fun advises(sentence: String): Boolean {
            val text = sentence.lowercase()
            if (AssistantDigest.matches(Stems.advice, text) || IMPERATIVE.containsMatchIn(text)) return true
            // A question in an answer is an offer: «Проверить подключение к интернету?»
            if (text.trimEnd().endsWith("?")) return true
            // English imperative: «Run a speed test», «Check the router's ports».
            return text.split(Regex("""\s+""")).firstOrNull().orEmpty() in ENGLISH_IMPERATIVES
        }

        /** A preflight call (a bare name or a whole JSON object) as a button with the question that runs it. */
        private fun offerFor(call: String): ToolOffer? {
            if (!call.startsWith("{")) return offer(call)?.takeIf { it.request != null }
            val json = runCatching { JSONObject(call) }.getOrNull() ?: return null
            val name = json.optString("tool")
            return when (Name.of(name)) {
                Name.SCAN_PORTS -> {
                    val host = json.optString("host").takeIf { it.isNotEmpty() } ?: return null
                    if (json.optString("text") == "all") {
                        ToolOffer(name, tr("a port scan"), tr("Scan every port of %s?", host), tr("Scan all ports of %s", host))
                    } else {
                        ToolOffer(name, tr("a port scan"), tr("Scan the ports of %s?", host), tr("Scan the ports of %s", host))
                    }
                }
                Name.CONNECT_BLUETOOTH -> {
                    val device = json.optString("text").takeIf { it.isNotEmpty() } ?: return null
                    ToolOffer(name, tr("a connection to the device"), tr("Read “%s” over Bluetooth?", device), tr("Connect to %s and read its data", device))
                }
                else -> offer(name)?.takeIf { it.request != null }
            }
        }

        /** A tool that ran, in words, for the note under the answer: «Ran: network scan», not «scan_lan». */
        fun title(name: String): String = when (Name.of(name)) {
            Name.SCAN_WIFI -> tr("Wi‑Fi scan")
            Name.SCAN_LAN -> tr("network scan")
            Name.SCAN_BLUETOOTH -> tr("Bluetooth scan")
            Name.CHECK_CONNECTION -> tr("connection check")
            Name.SPEED_TEST -> tr("speed test")
            Name.SCAN_PORTS -> tr("port scan")
            Name.CHECK_PORT_FORWARDING -> tr("port forwarding check")
            Name.CAPTURE_PCAP -> tr("traffic capture")
            Name.CONNECT_BLUETOOTH -> tr("device connection")
            Name.SEARCH_HISTORY -> tr("History search")
            Name.REMEMBER -> tr("saving a note")
            Name.FORGET -> tr("removing a note")
            null -> name
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
            // Keys compared without case: a small model writes "bSSID" now and then.
            val present = json.keys().asSequence().associateBy { it.lowercase() }
            for (key in keys) {
                val actual = present[key.lowercase()] ?: continue
                json.optString(actual).trim().takeIf { it.isNotEmpty() }?.let { return it }
            }
            return null
        }

        /** Polls [condition] until it turns false, the timeout elapses, or the chat is cancelled. */
        private suspend fun waitWhile(timeoutMs: Long, condition: () -> Boolean) {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (condition() && coroutineContext.isActive && System.currentTimeMillis() < deadline) delay(300)
        }
    }

    private object Stems {
        /** Words of advice and offers: «рекомендую», «стоит», «можно», «хотите, я…», «I suggest». */
        val advice = listOf(
            "recommend", "suggest", "should", "you can", "you could", "you may", "worth", "next step", "want me", "i can", "let me",
            "рекоменд", "совет", "стоит", "можно", "имеет смысл", "предлага", "следует", "нужно", "надо", "хотите", "давайте",
            "следующий шаг", "дальше", "далее", "могу", "например",
        )
        /** Asking about what was saved earlier rather than what is around now. */
        val history = listOf(
            "history", "earlier", "previously", "last time", "last week", "yesterday", "saved session", "seen before",
            "истори", "раньше", "прошл", "вчера", "сохранённ", "сохраненн", "сесси", "встречал", "видел ли", "был ли", "была ли",
            "были ли", "было ли",
        )
        /** Asking to read what one device publishes about itself. Verbs only: «проанализируй GATT X» is about a read made. */
        val readDevice = listOf("read", "прочита", "прочт", "считай", "считать", "firmware", "battery", "прошивк", "батаре", "заряд")
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
        val speed = listOf("speed", "speedtest", "mbps", "how fast", "скорост", "спидтест", "мегабит", "мбит")
        /** Asking for the number now, which only a measurement gives: «сколько мегабит», «какая сейчас скорость». */
        val speedAmount = listOf("how fast", "how much", "how many", "сколько", "какая скорост", "какая сейчас", "какую скорост", "какой скорост")
        /** Whether something opens: answerable only by trying it. */
        val access = listOf(
            "opens", "open?", "reachable", "load", "loads", "is down", "is up", "blocked", "accessible",
            "открывается", "открываются", "доступен", "доступна", "доступно", "доступны", "доступ к", "грузится", "загружается",
            "работает ли", "заблокирован",
        )
        val sites = listOf(
            "site", "website", "youtube", "instagram", "telegram", "google", "whatsapp", "facebook", "tiktok", "twitter",
            "netflix", "discord", "chatgpt", "vk", ".com", ".ru", ".org", ".net", ".io",
            "сайт", "ютуб", "инстаграм", "телеграм", "гугл", "ватсап", "фейсбук", "тикток", "твиттер", "нетфликс", "дискорд", "вк", "вконтакт",
        )
        val ports = listOf("port", "ports", "порт")
        val outside = listOf("outside", "from the internet", "to the internet", "exposed", "наружу", "извне", "снаружи", "из интернета", "в интернет")
        /** Asking about one named host's ports, which means a scan: «какие порты открыты на 192.168.1.64». */
        val lookAt = listOf("show", "which", "what", "open", "посмотр", "покаж", "какие", "открыт")
        val showMe = listOf("show", "посмотр", "покаж")
        /** The user's own connection now, not speeds in general («сколько мегабит нужно для 4K»). */
        val mine = listOf("my", "now", "current", "actual", "у меня", "мой", "моя", "моё", "мое", "сейчас", "реальн", "выдаёт", "выдает")
        val router = listOf("router", "gateway", "роутер", "маршрутизатор", "шлюз")
        val connection = listOf("internet", "connection", "ping", "dns", "vpn", "интернет", "соединен", "подключени", "пинг", "днс", "впн")
        val devices = listOf("device", "устройств", "девайс")
        val networks = listOf("network", "сет")
    }
}
