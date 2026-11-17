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
    }

    /// GBNF grammar: `{"tool": "<name>"}`, with an optional `"host"` field `scan_ports` needs. Any tool may carry
    /// a host field; the executor below simply ignores it where it is not needed.
    static let grammar = """
        root ::= ws "{" ws "\\"tool\\"" ws ":" ws tool tail ws "}" ws
        tool ::= "\\"scan_wifi\\"" | "\\"scan_lan\\"" | "\\"scan_bluetooth\\"" | "\\"check_connection\\"" | "\\"speed_test\\"" | "\\"scan_ports\\""
        tail ::= ( ws "," ws "\\"host\\"" ws ":" ws host )?
        host ::= "\\"" [a-zA-Z0-9._:-]+ "\\""
        ws   ::= [ \\t\\n]*
        """

    /// Appended to the system prompt: the exact syntax has to match `grammar` and `ToolRegistry.callTag`.
    static let instructions = """
        You can also run a live check on this phone instead of only reading what was already found. To run one, \
        write exactly this and nothing else in your reply:
        <tool_call>{"tool": "NAME"}</tool_call>
        where NAME is one of: scan_wifi, scan_lan, scan_bluetooth, check_connection, speed_test. For scan_ports, \
        name a host you have already seen in the LAN hosts list:
        <tool_call>{"tool": "scan_ports", "host": "192.168.1.1"}</tool_call>
        Each one takes a few seconds, and you will be given its result to answer with next. Call one only when \
        the question needs fresher data than what you already have; otherwise just answer. Never claim a check \
        ran, or report a number from one, unless you actually called it and got the result back.
        """

    var registry: ToolRegistry {
        ToolRegistry(grammar: Self.grammar, maxSteps: 4, runningLabel: Self.runningLabel) { [sources] name, argsJSON in
            await Self.run(name, argsJSON: argsJSON, sources: sources)
        }
    }

    static func runningLabel(for name: String) -> String {
        switch Name(rawValue: name) {
        case .scanWifi: String(localized: "Scanning Wi‑Fi…")
        case .scanLAN: String(localized: "Scanning the network…")
        case .scanBluetooth: String(localized: "Scanning Bluetooth…")
        case .checkConnection: String(localized: "Checking the connection…")
        case .speedTest: String(localized: "Measuring speed…")
        case .scanPorts: String(localized: "Scanning ports…")
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
            guard let host = decodeHost(argsJSON) else {
                return "Missing or invalid \"host\". Name an IP address already seen in the LAN hosts list."
            }
            guard sources.lan.devices[host] != nil else {
                return "\"\(host)\" is not a host this phone has seen on the LAN. Use an address from the LAN hosts list, or call scan_lan first."
            }
            sources.lan.scanPorts(for: host)
            try? await Task.sleep(for: .milliseconds(400))
            await waitWhile(timeout: 20) { sources.lan.devices[host]?.portScanFraction != nil }
            return section(.lan, sources: sources)
        }
    }

    private static func decodeHost(_ argsJSON: String) -> String? {
        guard let data = argsJSON.data(using: .utf8),
              let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return nil }
        return object["host"] as? String
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
