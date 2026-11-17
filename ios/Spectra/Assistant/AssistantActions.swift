import Foundation
import LocalChatKit

/// Live actions the assistant may trigger on its own: a fresh scan or check, not just the last saved result.
///
/// Deliberately excluded, on any phrasing of the question: deauth, LoRa transmission, firmware flashing, joining
/// a Wi‑Fi network, and anything else that sends something over the air or changes a setting. Those stay behind
/// the app's own buttons. A model only ever reads, measures, or probes the user's own network — the same things
/// the Checks tab already does with a tap — and what it sees comes from the air and could be adversarial, so it
/// never gets a lever that transmits.
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
        case capturePcap = "capture_pcap"
    }

    /// How long one on-demand traffic capture listens before it is analyzed. Short on purpose: it pins the sensor
    /// to one channel the whole time, so it cannot also keep up with any other scan. A `var`, not a `let`, so
    /// tests can shorten it instead of actually waiting out the real duration.
    static var captureDuration: Duration = .seconds(6)

    /// GBNF grammar: `{"tool": "<name>"}`, with an optional field after it — `"host"` (an IP, for scan_ports) or
    /// `"bssid"` (a Wi‑Fi access point's address, for capture_pcap). Both take the same shape of value; the
    /// executor below reads whichever key is present and ignores the field entirely where a tool needs neither.
    static let grammar = """
        root  ::= ws "{" ws "\\"tool\\"" ws ":" ws tool tail ws "}" ws
        tool  ::= "\\"scan_wifi\\"" | "\\"scan_lan\\"" | "\\"scan_bluetooth\\"" | "\\"check_connection\\"" | "\\"speed_test\\"" | "\\"scan_ports\\"" | "\\"capture_pcap\\""
        tail  ::= ( ws "," ws field )?
        field ::= ( "\\"host\\"" | "\\"bssid\\"" ) ws ":" ws value
        value ::= "\\"" [a-zA-Z0-9._:-]+ "\\""
        ws    ::= [ \\t\\n]*
        """

    /// Appended to the system prompt: the exact syntax has to match `grammar` and `ToolRegistry.callTag`. Stops
    /// at the closing brace, with no closing tag: once the grammar's `{...}` is complete it allows nothing but
    /// whitespace or end of turn, and `LlamaEngine` ends generation right there, so a `</tool_call>` could never
    /// be written anyway.
    static let instructions = """
        You can also run a live check on this phone instead of only reading what was already found. To run one, \
        write exactly this and nothing else in your reply:
        <tool_call>{"tool": "NAME"}
        where NAME is one of: scan_wifi, scan_lan, scan_bluetooth, check_connection, speed_test. For scan_ports, \
        name a host you have already seen in the LAN hosts list:
        <tool_call>{"tool": "scan_ports", "host": "192.168.1.1"}
        For capture_pcap, name a Wi‑Fi access point's BSSID exactly as shown in the Wi‑Fi networks list (not its \
        name, not an IP), to pin the sensor to that network's channel for a few seconds and summarize what crossed it:
        <tool_call>{"tool": "capture_pcap", "bssid": "AA:BB:CC:00:00:01"}
        Stop right after the closing brace — no closing tag, nothing else on the line. Each one takes a few \
        seconds, and you will be given its result to answer with next.
        Which one: scan_bluetooth for Bluetooth devices and trackers nearby, scan_wifi for Wi‑Fi networks around, \
        scan_lan for devices on the user's own network, check_connection for the internet connection, speed_test \
        for its speed. When the user asks you to scan, check, test or measure something, in any language \
        («просканируй блютуз», «проверь интернет», «замерь скорость»), call the matching one even if older data \
        is already attached. Otherwise call one only when the question needs fresher data than what you have. \
        When a <tool_result> for the question is already there, answer from it rather than calling the same \
        tool again. Never claim a check ran, or report a number from one, unless you got its result back.
        """

    var registry: ToolRegistry {
        ToolRegistry(
            grammar: Self.grammar,
            maxSteps: 4,
            preflight: { question in Self.requested(in: question).map(\.rawValue) },
            runningLabel: Self.runningLabel
        ) { [sources] name, argsJSON in
            await Self.run(name, argsJSON: argsJSON, sources: sources)
        }
    }

    // MARK: - Plain requests, recognized by the app

    /// Checks the question itself plainly asks to run, recognized here rather than left to the model: a small model
    /// often answers «просканируй блютуз» from the attached snapshot instead of calling the tool, and «какие блютуз
    /// устройства рядом» deserves a fresh look either way. Only argument-free checks; ports and captures stay the
    /// model's call. Stems are compared the way `AssistantDigest.matches(_:in:)` does: word starts, short Latin
    /// ones as whole words, anything with a space or punctuation as plain text.
    static func requested(in question: String) -> [Name] {
        let text = question.lowercased()
        func has(_ stems: [String]) -> Bool { AssistantDigest.matches(stems, in: text) }

        if has(RequestStems.aboutChecks) { return [] }
        let run = has(RequestStems.run)
        let nearby = has(RequestStems.nearby)
        guard run || nearby else { return [] }

        var found: Set<Name> = []
        // Bluetooth and Wi‑Fi are what is "nearby": asking what is around already asks for a fresh look at them.
        if has(RequestStems.bluetooth) { found.insert(.scanBluetooth) }
        if has(RequestStems.wifi) { found.insert(.scanWifi) }
        // The rest takes a while (a LAN sweep, a speed test), so only an outright request runs it.
        if run {
            if has(RequestStems.lan) { found.insert(.scanLAN) }
            if has(RequestStems.speed) { found.insert(.speedTest) }
            else if has(RequestStems.connection) { found.insert(.checkConnection) }
        }
        // Devices or networks with nothing more specific named: what is near the phone is Bluetooth (devices) or
        // Wi‑Fi (networks); asked to scan without "nearby", it is the user's own network.
        if found.isEmpty {
            if has(RequestStems.devices) { found.insert(nearby ? .scanBluetooth : .scanLAN) }
            else if has(RequestStems.networks) { found.insert(nearby ? .scanWifi : .scanLAN) }
        }
        return Name.allCases.filter(found.contains)
    }

    private enum RequestStems {
        /// Asking to run something, not just to read.
        static let run = [
            "scan", "rescan", "check", "test", "measure", "run", "refresh", "look for",
            "скан", "сканир", "просканир", "отсканир", "пересканир", "провер", "замер", "измер", "тест", "протестир",
            "обнов", "запуст", "найд", "поищ", "ищ",
        ]
        /// Asking what is around right now.
        static let nearby = ["nearby", "around", "near me", "close by", "рядом", "вокруг", "поблизости", "неподалёку", "неподалеку"]
        /// A question about checks rather than a request for one: «зачем сканировать блютуз?», «не сканируй».
        static let aboutChecks = [
            "why", "how often", "how does", "don't scan", "don't check", "do not scan", "do not check",
            "зачем", "почему", "как часто", "как работает", "что такое", "что значит",
            "не скан", "не провер", "не надо скан", "не нужно скан", "не надо провер", "не нужно провер",
        ]
        static let bluetooth = [
            "bluetooth", "ble", "bt", "tracker", "airtag", "headphone", "earbud", "beacon",
            "блютуз", "блютус", "трекер", "метк", "наушник", "маяк",
        ]
        static let wifi = ["wi-fi", "wi‑fi", "wifi", "wlan", "ssid", "wireless", "access point", "вайфай", "вай", "точк", "беспровод"]
        static let lan = ["lan", "host", "who is on", "локальн", "хост", "кто в"]
        static let speed = ["speed", "speedtest", "скорост", "спидтест"]
        static let connection = ["internet", "connection", "ping", "dns", "vpn", "интернет", "соединен", "подключени", "пинг", "днс", "впн"]
        /// Devices or networks in general: see the fallback in `requested(in:)`.
        static let devices = ["device", "устройств", "девайс"]
        static let networks = ["network", "сет"]
    }

    static func runningLabel(for name: String) -> String {
        switch Name(rawValue: name) {
        case .scanWifi: String(localized: "Scanning Wi‑Fi…")
        case .scanLAN: String(localized: "Scanning the network…")
        case .scanBluetooth: String(localized: "Scanning Bluetooth…")
        case .checkConnection: String(localized: "Checking the connection…")
        case .speedTest: String(localized: "Measuring speed…")
        case .scanPorts: String(localized: "Scanning ports…")
        case .capturePcap: String(localized: "Capturing traffic…")
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
            await waitWhile(timeout: 30) { sources.speed.running }
            return section(.link, sources: sources)

        case .scanPorts:
            guard let host = decodeArg(argsJSON, keys: ["host", "bssid"]) else {
                return "Missing or invalid \"host\". Name an IP address already seen in the LAN hosts list."
            }
            guard sources.lan.devices[host] != nil else {
                return "\"\(host)\" is not a host this phone has seen on the LAN. Use an address from the LAN hosts list, or call scan_lan first."
            }
            sources.lan.scanPorts(for: host)
            try? await Task.sleep(for: .milliseconds(400))
            await waitWhile(timeout: 20) { sources.lan.devices[host]?.portScanFraction != nil }
            return section(.lan, sources: sources)

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
            sources.link.startRawCapture(channel: network.latest.channel, network: bssid)
            try? await Task.sleep(for: captureDuration)
            sources.link.stopRawCapture()
            await waitWhile(timeout: 5) { sources.link.rawCapturing }
            guard let analysis = try? PcapAnalyzer.analyze(sources.link.rawCapturePcap()), analysis.summary.packetCount > 0 else {
                return "No frames captured in \(Int(captureDuration.components.seconds)) seconds. The network may be quiet, out of range, or the sensor lost its Bluetooth link."
            }
            return captureSummary(analysis, ssid: network.revealedSSID ?? network.latest.ssid, bssid: bssidString)
        }
    }

    private static func decodeArg(_ argsJSON: String, keys: [String]) -> String? {
        guard let data = argsJSON.data(using: .utf8),
              let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return nil }
        for key in keys {
            if let value = object[key] as? String { return value }
        }
        return nil
    }

    /// A short-sample traffic capture (see `SensorLink.startRawCapture`): a few seconds pinned to one channel,
    /// not a full capture. Summarized the same way `.pcap` files opened by hand are, on the Pcap screen.
    private static func captureSummary(_ analysis: CaptureAnalysis, ssid: String?, bssid: String) -> String {
        let summary = analysis.summary
        let name = ssid.map { "\"\(AssistantDigest.clean($0))\" (\(bssid))" } ?? bssid
        let seconds = summary.duration.map { String(format: "%.1f", $0) } ?? "a few"
        var lines = ["Traffic capture on \(name): \(summary.packetCount) frames, \(summary.capturedBytes) bytes, over \(seconds)s."]
        if !summary.protocols.isEmpty {
            let top = summary.protocols.sorted { $0.count > $1.count }.prefix(6)
            lines.append("Protocols: " + top.map { "\($0.name) (\($0.count))" }.joined(separator: ", ") + ".")
        }
        if !summary.talkers.isEmpty {
            let top = summary.talkers.sorted { $0.bytes > $1.bytes }.prefix(5)
            lines.append("Busiest devices: " + top.map { "\($0.endpoint) (\($0.bytes) B, \($0.packets) pkt)" }.joined(separator: "; ") + ".")
        }
        if analysis.rowsTruncated { lines.append("(this only summarizes part of the capture; more frames were seen than are counted here)") }
        return lines.joined(separator: "\n")
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
