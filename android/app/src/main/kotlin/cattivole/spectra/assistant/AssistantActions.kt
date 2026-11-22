package cattivole.spectra.assistant

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
 * The live checks a summary card's step buttons run: a fresh scan or check, not just the last saved result.
 *
 * Deliberately excluded: deauth, LoRa transmission, firmware flashing, joining a Wi‑Fi network, and anything else
 * that sends something over the air or changes a setting. Those stay behind the app's own buttons. The steps come
 * from the card's rules, never from the model. `connect_bluetooth` is the one that opens a link rather than only listening: it reads
 * what one device publishes about itself and drops the link again; nothing is written. Mirrors AssistantActions.swift.
 */
class AssistantActions(val sources: AssistantSources) {
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
        /** A traceroute to "host". */
        TRACE_ROUTE("trace_route"),
        /** The user's own list of sites (Connection → Resources), each tried once. */
        CHECK_SITES("check_sites"),
        /** The certificate and the registry record of the link in "text". */
        CHECK_SITE("check_site"),
        /** The redirects of the link in "text", followed by their headers only. */
        EXPAND_REDIRECTS("expand_redirects");

        companion object {
            fun of(raw: String): Name? = entries.firstOrNull { it.raw == raw }
        }
    }

    /**
     * Runs a summary card's step: a bare tool name or a whole call (`{"tool": "scan_ports", "host": …}`), as the
     * card's rules wrote it. Returns the tool's result text.
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
                waitWhile(60_000) { model.speed.running }
                section(AssistantTopic.LINK)
            }
            Name.TRACE_ROUTE -> {
                val host = argument(argsJson, "host", "text") ?: cattivole.spectra.core.RouteTrace.DEFAULT_TARGET
                model.connection.traceRoute(host)
                delay(400)
                waitWhile(120_000) { model.connection.routeRunning }
                sources.snapshot().route?.let { AssistantDigest.routeLines(it).joinToString("\n") } ?: "The traceroute did not start."
            }
            Name.CHECK_SITES -> {
                val connection = model.connection
                if (connection.resources.isEmpty()) return "The user's list of sites is empty (Connection → Resources), so there is nothing to try."
                connection.checkAllResources()
                delay(400)
                waitWhile(30_000) { connection.checkingResources.isNotEmpty() }
                AssistantDigest.siteLines(sources.snapshot().sites).joinToString("\n").ifEmpty { "None of the sites answered in time." }
            }
            Name.CHECK_SITE -> {
                val target = webLink(argsJson) ?: return "Missing or invalid \"text\": give the link to check."
                val report = cattivole.spectra.platform.SiteProbe.probe(target.asciiHost ?: target.host, LinkShelf.registryName(target))
                sources.links.keepSite(target.link, report)
                InternetCards.siteText(report)
            }
            Name.EXPAND_REDIRECTS -> {
                val target = webLink(argsJson) ?: return "Missing or invalid \"text\": give the link to expand."
                val trace = cattivole.spectra.platform.RedirectTracer.trace(LinkShelf.start(target))
                sources.links.keepRedirects(target.link, trace)
                InternetCards.redirectText(trace)
            }
            Name.SCAN_PORTS -> {
                val host = argument(argsJson, "host", "bssid")
                    ?: return "Missing or invalid \"host\". Name an IP address already seen in the LAN hosts list."
                val device = model.lan.devices.firstOrNull { it.ipAddress == host }
                    ?: return "\"$host\" is not a host this phone has seen on the LAN. Use an address from the LAN hosts list, or call scan_lan first."
                // Every TCP port takes minutes rather than seconds: only the "Scan every port" step asks for it.
                val everyPort = argument(argsJson, "text").equals("all", ignoreCase = true)
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

    /** The web link with a name in the call's "text": only such a link has a certificate and redirects. */
    private fun webLink(argsJson: String): cattivole.spectra.core.LinkInspection? =
        argument(argsJson, "text")?.let { cattivole.spectra.core.LinkInspector.inspect(it) }?.takeIf(LinkShelf::isWeb)

    private fun section(topic: AssistantTopic): String =
        AssistantDigest.section(topic, sources.snapshot()).ifEmpty { "Nothing new." }

    companion object {
        /**
         * How long one on-demand traffic capture listens before it is analyzed. Short on purpose: it pins the sensor
         * to one channel the whole time. A `var` so tests can shorten it.
         */
        var captureDurationMs = 6_000L

        /** The device a step names, matched loosely both ways; among several, the nearest one wins. */
        fun bluetoothDevice(wanted: String, devices: Collection<BleDevice>): BleDevice? {
            val needle = wanted.trim()
            if (needle.isEmpty()) return null
            return devices.filter {
                it.displayName.contains(needle, ignoreCase = true) || needle.contains(it.displayName, ignoreCase = true)
            }.maxByOrNull { it.rssi }
        }

        /** The step button for a check that needs no argument; the cards trim the question mark. */
        fun button(name: String): String? = when (Name.of(name)) {
            Name.SCAN_WIFI -> tr("Scan Wi‑Fi?")
            Name.SCAN_LAN -> tr("Scan the local network?")
            Name.SCAN_BLUETOOTH -> tr("Scan Bluetooth?")
            Name.CHECK_CONNECTION -> tr("Check the connection?")
            Name.SPEED_TEST -> tr("Measure the speed?")
            Name.CHECK_PORT_FORWARDING -> tr("Check port forwarding?")
            else -> null
        }

        /** A check that ran, in words: the heading of its result on a card. */
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
            Name.TRACE_ROUTE -> tr("traceroute")
            Name.CHECK_SITES -> tr("check of your sites")
            Name.CHECK_SITE -> tr("certificate and domain check")
            Name.EXPAND_REDIRECTS -> tr("redirects")
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
            Name.TRACE_ROUTE -> tr("Tracing the path…")
            Name.CHECK_SITES -> tr("Trying your sites…")
            Name.CHECK_SITE -> tr("Asking the site and the registry…")
            Name.EXPAND_REDIRECTS -> tr("Following redirects…")
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

        /** Polls [condition] until it turns false, the timeout elapses, or the step is stopped. */
        private suspend fun waitWhile(timeoutMs: Long, condition: () -> Boolean) {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (condition() && coroutineContext.isActive && System.currentTimeMillis() < deadline) delay(300)
        }
    }
}
