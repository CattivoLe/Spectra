import Foundation
import LocalChatKit

/// The live checks a summary card's step buttons run: a fresh scan or check, not just the last saved result.
///
/// Deliberately excluded: deauth, LoRa transmission, firmware flashing, joining a Wi‑Fi network, and anything else
/// that sends something over the air or changes a setting. Those stay behind the app's own buttons. The steps come
/// from the card's rules, never from the model.
///  
/// `connect_bluetooth` is the one that opens a link rather than only listening: it connects to a single device
/// and reads what that device publishes about itself, as the Bluetooth device screen does with a tap. It still
/// only reads — nothing is written to the device, and the link is dropped again as soon as the read is done.
@MainActor
struct AssistantActions {
    let sources: AssistantSources

    enum Name: String, CaseIterable {
        case scanWifi = "scan_wifi"
        case scanLAN = "scan_lan"
        case scanBluetooth = "scan_bluetooth"
        case checkConnection = "check_connection"
        case speedTest = "speed_test"
        case scanPorts = "scan_ports"
        case checkPortForwarding = "check_port_forwarding"
        case capturePcap = "capture_pcap"
        case connectBluetooth = "connect_bluetooth"
        /// A traceroute to "host". Card steps only.
        case traceRoute = "trace_route"
        /// The user's own list of sites (Internet → Resources), each tried once. Card steps only.
        case checkSites = "check_sites"
        /// The certificate and the registry record of the link in "text". Card steps only.
        case checkSite = "check_site"
        /// The redirects of the link in "text", followed by their headers only. Card steps only.
        case expandRedirects = "expand_redirects"
    }

    /// Runs a summary card's step: a bare tool name or a whole call (`{"tool": "scan_ports", "host": …}`), as the
    /// card's rules wrote it. Returns the tool's result text.
    func perform(_ call: String) async -> String {
        let trimmed = call.trimmingCharacters(in: .whitespacesAndNewlines)
        guard trimmed.hasPrefix("{") else { return await Self.run(trimmed, argsJSON: "{}", sources: sources) }
        guard let data = trimmed.data(using: .utf8),
              let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let tool = object["tool"] as? String else { return "Bad call." }
        return await Self.run(tool, argsJSON: trimmed, sources: sources)
    }

    /// The step button for a check that needs no argument; the cards trim the question mark.
    nonisolated static func button(for name: String) -> String? {
        switch Name(rawValue: name) {
        case .scanWifi: String(localized: "Scan Wi‑Fi?")
        case .scanLAN: String(localized: "Scan the local network?")
        case .scanBluetooth: String(localized: "Scan Bluetooth?")
        case .checkConnection: String(localized: "Check the connection?")
        case .speedTest: String(localized: "Measure the speed?")
        case .checkPortForwarding: String(localized: "Check port forwarding?")
        default: nil
        }
    }

    /// How long one on-demand traffic capture listens before it is analyzed. Short on purpose: it pins the sensor
    /// to one channel the whole time, so it cannot also keep up with any other scan. A `var`, not a `let`, so
    /// tests can shorten it instead of actually waiting out the real duration.
    static var captureDuration: Duration = .seconds(6)

    /// A tool that ran, in words, for the note under the answer: «Ran: network scan», not «scan_lan».
    nonisolated static func title(for name: String) -> String {
        switch Name(rawValue: name) {
        case .scanWifi: String(localized: "Wi‑Fi scan")
        case .scanLAN: String(localized: "network scan")
        case .scanBluetooth: String(localized: "Bluetooth scan")
        case .checkConnection: String(localized: "connection check")
        case .speedTest: String(localized: "speed test")
        case .scanPorts: String(localized: "port scan")
        case .checkPortForwarding: String(localized: "port forwarding check")
        case .capturePcap: String(localized: "traffic capture")
        case .connectBluetooth: String(localized: "device connection")
        case .traceRoute: String(localized: "traceroute")
        case .checkSites: String(localized: "check of your sites")
        case .checkSite: String(localized: "certificate and domain check")
        case .expandRedirects: String(localized: "redirects")
        case nil: name
        }
    }

    nonisolated static func runningLabel(for name: String) -> String {
        switch Name(rawValue: name) {
        case .scanWifi: String(localized: "Scanning Wi‑Fi…")
        case .scanLAN: String(localized: "Scanning the network…")
        case .scanBluetooth: String(localized: "Scanning Bluetooth…")
        case .checkConnection: String(localized: "Checking the connection…")
        case .speedTest: String(localized: "Measuring speed…")
        case .scanPorts: String(localized: "Scanning ports…")
        case .checkPortForwarding: String(localized: "Asking the router about port forwarding…")
        case .capturePcap: String(localized: "Capturing traffic…")
        case .connectBluetooth: String(localized: "Connecting to the device…")
        case .traceRoute: String(localized: "Tracing the path…")
        case .checkSites: String(localized: "Trying your sites…")
        case .checkSite: String(localized: "Asking the site and the registry…")
        case .expandRedirects: String(localized: "Following redirects…")
        case nil: String(localized: "Running \(name)…")
        }
    }

    private static func run(_ name: String, argsJSON: String, sources: AssistantSources) async -> String {
        guard let tool = Name(rawValue: name) else { return "Unknown tool \"\(name)\"." }
        switch tool {
        case .scanWifi:
            guard sources.link.isConnected else {
                return "No sensor connected, so a fresh Wi‑Fi scan is not possible right now. Answer from what is already known, and say the sensor is not connected."
            }
            sources.link.scanNow()
            try? await Task.sleep(for: .milliseconds(500))
            await waitWhile(timeout: 15) { sources.store.isScanning }
            return section(.wifi, sources: sources)

        case .scanLAN:
            sources.lan.scan()
            try? await Task.sleep(for: .milliseconds(400))
            await waitWhile(timeout: 25) { sources.lan.isScanning }
            if sources.lan.state == .localNetworkDenied {
                return "iOS local network access is turned off for Spectra, so the scan cannot reach any device. Say so, and that it is turned on in Settings → Privacy & Security → Local Network."
            }
            return section(.lan, sources: sources)

        case .scanBluetooth:
            sources.ble.start()
            try? await Task.sleep(for: .seconds(3))
            return section(.bluetooth, sources: sources)

        case .checkConnection:
            sources.probe.check()
            try? await Task.sleep(for: .milliseconds(400))
            await waitWhile(timeout: 30) { sources.probe.running }
            return section(.link, sources: sources)

        case .speedTest:
            sources.speed.start(viaVpn: ProbeRoute.current() == .vpn)
            try? await Task.sleep(for: .milliseconds(400))
            await waitWhile(timeout: 60) { sources.speed.running }
            return section(.link, sources: sources)

        case .traceRoute:
            let host = decodeArg(argsJSON, keys: ["host", "text"]) ?? RouteTrace.defaultTarget
            sources.tracer.run(host)
            try? await Task.sleep(for: .milliseconds(400))
            await waitWhile(timeout: 120) { sources.tracer.running }
            return sources.snapshot().route.map { AssistantDigest.routeLines($0).joined(separator: "\n") } ?? "The traceroute did not start."

        case .checkSites:
            guard !sources.resources.hosts.isEmpty else {
                return "The user's list of sites is empty (Internet → Resources), so there is nothing to try."
            }
            sources.resources.checkAll()
            try? await Task.sleep(for: .milliseconds(400))
            await waitWhile(timeout: 30) { sources.resources.isChecking }
            let lines = AssistantDigest.siteLines(sources.snapshot().sites)
            return lines.isEmpty ? "None of the sites answered in time." : lines.joined(separator: "\n")

        case .checkSite:
            guard let target = webLink(argsJSON) else { return "Missing or invalid \"text\": give the link to check." }
            let report = await SiteProbe.probe(host: target.asciiHost ?? target.host, domain: LinkShelf.registryName(target))
            sources.links.keep(site: report, for: target.link)
            return InternetCards.siteText(report)

        case .expandRedirects:
            guard let target = webLink(argsJSON), let start = LinkShelf.start(target) else {
                return "Missing or invalid \"text\": give the link to expand."
            }
            let trace = await RedirectTracer.trace(start)
            sources.links.keep(redirects: trace, for: target.link)
            return InternetCards.redirectText(trace)

        case .scanPorts:
            guard let host = decodeArg(argsJSON, keys: ["host", "bssid"]) else {
                return "Missing or invalid \"host\". Name an IP address already seen in the LAN hosts list."
            }
            guard sources.lan.devices[host] != nil else {
                return "\"\(host)\" is not a host this phone has seen on the LAN. Use an address from the LAN hosts list, or call scan_lan first."
            }
            // Every TCP port takes minutes rather than seconds, so it happens only when the question asked for it.
            let everyPort = decodeArg(argsJSON, keys: ["text"]).map { Self.asksForEveryPort($0) } ?? false
            sources.lan.scanPorts(for: host, scope: everyPort ? .all : .common)
            try? await Task.sleep(for: .milliseconds(400))
            await waitWhile(timeout: everyPort ? 240 : 20) { sources.lan.devices[host]?.portScanFraction != nil }
            return section(.lan, sources: sources)

        case .checkPortForwarding:
            guard sources.lan.gateway != nil else {
                return "No router found on the local network yet, so its forwarding table cannot be read. Call scan_lan first."
            }
            sources.lan.checkPortForwarding()
            try? await Task.sleep(for: .milliseconds(400))
            await waitWhile(timeout: 25) { sources.lan.checkingForwarding }
            return forwardingReport(sources.lan)

        case .capturePcap:
            guard sources.link.isConnected else {
                return "No sensor connected, so a traffic capture is not possible right now."
            }
            guard let bssidString = decodeArg(argsJSON, keys: ["bssid", "host"]) else {
                return "Missing or invalid \"bssid\". Name a Wi‑Fi access point's BSSID exactly as shown in the Wi‑Fi networks list, not its name and not an IP."
            }
            guard let bssid = BSSID(string: bssidString), let network = sources.store.networks[bssid] else {
                return "\"\(bssidString)\" is not a BSSID this phone has seen. Use one from the Wi‑Fi networks list, or call scan_wifi first."
            }
            guard !sources.link.rawCapturing else {
                return "A capture is already running on this sensor. Wait for it to finish before starting another."
            }
            let channel = network.latest.channel
            switch sources.link.captureSupport(channel: channel) {
            case .ok: break
            case .bandNotHeard:
                return "This network is on 5 GHz (channel \(channel)), and the connected sensor hears 2.4 GHz only, so it cannot capture it. The ESP32-C5 sensor can."
            case .firmwareTooOld(let version):
                return "This network is on 5 GHz (channel \(channel)). The sensor's firmware \(version) captures on 2.4 GHz only; firmware \(SensorLink.fiveGHzCaptureFirmware) or newer captures on 5 GHz."
            }
            sources.link.startRawCapture(channel: channel, network: bssid)
            try? await Task.sleep(for: captureDuration)
            sources.link.stopRawCapture()
            await waitWhile(timeout: 5) { sources.link.rawCapturing }
            guard let analysis = try? PcapAnalyzer.analyze(sources.link.rawCapturePcap()), analysis.summary.packetCount > 0 else {
                return "No frames captured in \(Int(captureDuration.components.seconds)) seconds. The network may be quiet, out of range, or the sensor lost its Bluetooth link."
            }
            let name = (network.revealedSSID ?? network.latest.ssid).map { "\($0) (\(bssidString))" } ?? bssidString
            // Kept like a capture decoded by hand, so a later question about "the capture" finds this one.
            sources.captures.keep(name: name, analysis: analysis, fromSensor: true)
            return CaptureDigest.text(analysis, name: name, origin: "taken just now by the Spectra sensor on channel \(channel)",
                                      describe: sources.describe)

        case .connectBluetooth:
            guard let wanted = decodeArg(argsJSON, keys: ["text", "host"]) else {
                return "Missing \"text\". Name the Bluetooth device to connect to, as it appears in the Bluetooth devices list."
            }
            guard let device = bluetoothDevice(named: wanted, among: sources.ble.devices.values) else {
                return "\"\(AssistantDigest.clean(wanted))\" is not a Bluetooth device this phone can hear. "
                    + "Call scan_bluetooth first, then use a name from that list."
            }
            // CoreBluetooth only hands out peripherals it has heard: a scan that was stopped meanwhile still
            // has them, but a device that moved away does not answer, which the timeout below covers.
            if !sources.ble.isScanning { sources.ble.start() }
            sources.ble.connectGATT(device.id)
            await waitWhile(timeout: 15) {
                switch sources.ble.gattState {
                case .connecting, .discovering: true
                default: false
                }
            }
            // Characteristics arrive after the services, and their values after that: wait until every service
            // has its list (or the link ends), then give the reads a moment before writing it up.
            if sources.ble.gattState == .connected {
                await waitWhile(timeout: 5) {
                    sources.ble.gattState == .connected && sources.ble.gattServices.contains { $0.characteristics.isEmpty }
                }
                try? await Task.sleep(for: .seconds(1.5))
            }
            let report = gattReport(for: device, scanner: sources.ble)
            sources.ble.disconnectGATT()
            return report
        }
    }

    /// Whether the scope the model passed to `scan_ports` means "every port" rather than the usual short list.
    static func asksForEveryPort(_ text: String) -> Bool {
        let words = text.lowercased().split { !$0.isLetter && !$0.isNumber }
        return words.contains { word in ["all", "full", "every", "deep", "whole", "65535", "все", "весь", "полн", "глубок"].contains { word.hasPrefix($0) } }
    }

    /// The router's forwarding table. What it says is the router's own answer over UPnP, so names and
    /// descriptions in it are flattened before the model sees them.
    private static func forwardingReport(_ lan: LANScanner) -> String {
        guard let table = lan.gateway?.profile.portForwarding else {
            return "The router did not answer the port-forwarding request. Many routers keep UPnP off, which is "
                + "the safer setting; the table can then only be read from the router's own web page."
        }
        guard table.supported else {
            return "The router does not expose a port-forwarding service over UPnP (checked just now). That is the "
                + "safer setting: nothing on the network can open a port from the inside without the owner knowing. "
                + "Any forwarding it does have would have to be set by hand on the router's own page."
        }
        let active = table.mappings.filter(\.enabled)
        guard !active.isEmpty else {
            return "The router forwards no ports from the internet right now"
                + (table.externalIP.map { " (external address \($0))" } ?? "") + ". Nothing on the network is reachable from outside this way."
        }
        var lines = ["Ports the router forwards from the internet to this network"
                     + (table.externalIP.map { ", external address \($0)" } ?? "") + ":"]
        for mapping in active.prefix(20) {
            var parts = ["\(mapping.proto) \(mapping.externalPort) → \(mapping.internalClient):\(mapping.internalPort)"]
            if let name = lan.devices[mapping.internalClient]?.displayName { parts.append(AssistantDigest.clean(name)) }
            if let note = mapping.description, !note.isEmpty { parts.append("\"\(AssistantDigest.clean(note, limit: 50))\"") }
            if let lease = mapping.leaseSeconds, lease > 0 { parts.append("lease \(lease)s") }
            if let remote = mapping.remoteHost, !remote.isEmpty { parts.append("only from \(AssistantDigest.clean(remote, limit: 40))") }
            lines.append("- " + parts.joined(separator: " · "))
        }
        if active.count > 20 { lines.append("- …and \(active.count - 20) more.") }
        lines.append("Each line means that device is reachable from the open internet on that port.")
        return lines.joined(separator: "\n")
    }

    /// The device the model named. Names come from the air, so a loose match is on purpose: the model is quoting
    /// a list it was shown, not an identifier. Among several, the nearest one wins.
    private static func bluetoothDevice(named wanted: String, among devices: some Collection<BLEDevice>) -> BLEDevice? {
        let needle = wanted.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !needle.isEmpty else { return nil }
        let matching = devices.filter {
            $0.displayName.localizedCaseInsensitiveContains(needle) || needle.localizedCaseInsensitiveContains($0.displayName)
        }
        return matching.max { $0.rssi < $1.rssi }
    }

    /// What the device said about itself. Every name and value is flattened with `AssistantDigest.clean`: this is
    /// an unknown device's own words, and it reaches the model inside a tool result.
    private static func gattReport(for device: BLEDevice, scanner: BLEScanner) -> String {
        let name = AssistantDigest.clean(device.displayName)
        switch scanner.gattState {
        case .failed(let reason):
            return "Could not connect to \"\(name)\": \(AssistantDigest.clean(reason, limit: 100)). "
                + "Many devices refuse a connection while they are paired with their owner's phone."
        case .connecting:
            return "\"\(name)\" did not answer the connection request within 15 seconds. It may be out of range, "
                + "asleep, or accepting connections only from its paired phone. Nothing was read."
        case .discovering:
            return "Connected to \"\(name)\", but it did not list its services within 15 seconds. Nothing was read."
        default:
            break
        }
        guard !scanner.gattServices.isEmpty, let read = scanner.lastRead, read.deviceID == device.id else {
            return "Connected to \"\(name)\", but it published no services to read. "
                + "Headphones and trackers often keep everything behind pairing."
        }
        var text = GATTDigest.text(read, intro: "Read just now over Bluetooth from")
        if scanner.gattState == .ended { text += "\nThe device closed the link after answering, which is normal." }
        return text
    }

    /// The web link with a name in the call's "text": only such a link has a certificate and redirects.
    private static func webLink(_ argsJSON: String) -> LinkInspection? {
        guard let text = decodeArg(argsJSON, keys: ["text"]), let inspection = LinkInspector.inspect(text),
              LinkShelf.isWeb(inspection) else { return nil }
        return inspection
    }

    private static func decodeArg(_ argsJSON: String, keys: [String]) -> String? {
        guard let data = argsJSON.data(using: .utf8),
              let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return nil }
        // Keys compared without case: a small model writes "bSSID" now and then.
        let present = Dictionary(object.map { ($0.key.lowercased(), $0.value) }, uniquingKeysWith: { first, _ in first })
        for key in keys {
            if let value = present[key.lowercased()] as? String { return value }
        }
        return nil
    }

    private static func section(_ topic: AssistantTopic, sources: AssistantSources) -> String {
        let text = AssistantDigest.section(topic, snapshot: sources.snapshot())
        return text.isEmpty ? "Nothing new." : text
    }

    /// Polls `condition` until it turns false, the timeout elapses, or the surrounding chat is cancelled —
    /// whichever comes first. A tool always returns something: cancellation just means it returns early.
    private static func waitWhile(timeout: TimeInterval, interval: Duration = .milliseconds(300), _ condition: @escaping () -> Bool) async {
        let deadline = ContinuousClock.now.advanced(by: .seconds(timeout))
        while condition(), !Task.isCancelled, ContinuousClock.now < deadline {
            try? await Task.sleep(for: interval)
        }
    }
}
