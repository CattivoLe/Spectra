import Foundation
import LocalChatKit

/// Live actions the assistant may trigger on its own: a fresh scan or check, not just the last saved result.
///
/// Deliberately excluded, on any phrasing of the question: deauth, LoRa transmission, firmware flashing, joining
/// a Wi‑Fi network, and anything else that sends something over the air or changes a setting. Those stay behind
/// the app's own buttons. A model only ever reads, measures, or probes the user's own network — the same things
/// the Checks tab already does with a tap — and what it sees comes from the air and could be adversarial, so it
/// never gets a lever that transmits.
///  
/// `connect_bluetooth` is the one that opens a link rather than only listening: it connects to a single device
/// and reads what that device publishes about itself, as the Bluetooth device screen does with a tap. It still
/// only reads — nothing is written to the device, and the link is dropped again as soon as the read is done.
@MainActor
struct AssistantActions {
    let sources: AssistantSources
    let memory: AssistantMemory

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
        case searchHistory = "search_history"
        case remember
        case forget
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
        tool  ::= "\\"scan_wifi\\"" | "\\"scan_lan\\"" | "\\"scan_bluetooth\\"" | "\\"check_connection\\"" | "\\"speed_test\\"" | "\\"scan_ports\\"" | "\\"check_port_forwarding\\"" | "\\"capture_pcap\\"" | "\\"connect_bluetooth\\"" | "\\"search_history\\"" | "\\"remember\\"" | "\\"forget\\""
        tail  ::= ( ws "," ws addrfield )? ( ws "," ws textfield )?
        addrfield ::= ( "\\"host\\"" | "\\"bssid\\"" ) ws ":" ws "\\"" [a-zA-Z0-9._:-]+ "\\""
        textfield ::= "\\"text\\"" ws ":" ws "\\"" [^\\"]+ "\\""
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
        where NAME is one of: scan_wifi, scan_lan, scan_bluetooth, check_connection, speed_test, \
        check_port_forwarding. For scan_ports, name a host you have already seen in the LAN hosts list:
        <tool_call>{"tool": "scan_ports", "host": "192.168.1.1"}
        That probes the usual ports. To probe every TCP port of that host instead, which takes much longer, \
        add "text": "all" — do it only when the user asks for a full or deep scan:
        <tool_call>{"tool": "scan_ports", "host": "192.168.1.1", "text": "all"}
        For capture_pcap, name a Wi‑Fi access point's BSSID exactly as shown in the Wi‑Fi networks list (not its \
        name, not an IP), to pin the sensor to that network's channel for a few seconds and summarize what crossed it:
        <tool_call>{"tool": "capture_pcap", "bssid": "AA:BB:CC:00:00:01"}
        For connect_bluetooth, name a device exactly as it appears in the Bluetooth devices list, to connect to \
        it and read what it tells about itself:
        <tool_call>{"tool": "connect_bluetooth", "text": "Momentum 3"}
        For search_history, say what to look for in everything saved under History — a network or device name, \
        an IP, MAC or BSSID — or leave "text" out for an overview of all saved sessions:
        <tool_call>{"tool": "search_history", "text": "Galaxy Buds"}
        Stop right after the closing brace — no closing tag, nothing else on the line. Each one takes a few \
        seconds, and you will be given its result to answer with next.
        Which one:
        - scan_wifi: Wi‑Fi networks around, channels, signal, security. Use when asked about channels, interference, \
        which band or channel is best, what networks are nearby.
        - scan_lan: devices on the local network, their ports, manufacturers. Use when asked about devices, hosts, \
        who is on the network, what a device is.
        - scan_bluetooth: Bluetooth devices and trackers nearby. Use for tracker detection, Bluetooth devices, AirTag.
        - check_connection: internet connection check — DNS, latency, public IP, provider, access, TLS interception. \
        Use when asked to check internet, verify connectivity, test access to a service, ping, check if something is reachable.
        - speed_test: download/upload speed, latency under load, jitter. Use when asked about speed, bandwidth.
        - scan_ports: scan open ports on a specific LAN host. Use when asked about what ports are open, what services \
        a device runs, whether a device is secure.
        - check_port_forwarding: ask the router which ports it forwards from the internet to devices on the network \
        (UPnP/NAT-PMP). Use when asked about port forwarding, open ports on the router, whether anything is \
        reachable from outside, or whether the router is safe («проброс портов», «что открыто наружу», «UPnP»). \
        A forwarding rule nobody meant to add is one of the most serious things you can find: it puts a device \
        on the open internet. It needs the LAN hosts list: call scan_lan first when there is none.
        - capture_pcap: capture Wi‑Fi traffic on a network's channel for a few seconds. Use when asked to capture, \
        sniff, or record traffic.
        - connect_bluetooth: connect to ONE Bluetooth device and read its GATT services and characteristics — \
        often its model, maker, firmware version and battery level. Use when asked to connect to a device, read \
        it, read its GATT services, or find out what exactly it is («подключись к Momentum 3», «что это за \
        устройство», «прочитай GATT наушников»). The device must be in the Bluetooth list first: call \
        scan_bluetooth when it is not there. Reading takes a few seconds and the link is dropped again afterwards. \
        When the attached data already holds a GATT read of that device and the user asks to analyze or explain \
        it, explain that read instead of connecting again: name each standard service (Device Information, \
        Battery, Heart Rate, …), say what the decoded values reveal (maker, model, firmware), and point out \
        vendor-specific services and characteristics that accept writes without pairing.
        - search_history: read everything saved under History, across all sessions and days: networks, LAN hosts \
        with their ports, Bluetooth devices, connection checks, sensor summaries. Use when asked whether something \
        was seen before, what changed since last time, what was at a place, or anything about earlier scans \
        («было ли это устройство раньше», «что было в истории», «когда я видел эту сеть»). It reads saved data \
        only; it does not scan.
        IMPORTANT: when the user asks to check, verify, test or measure ANYTHING about their own network, devices \
        or connection — call the matching tool. These are live measurements, not web lookups. A web search cannot \
        ping a host, measure speed, or scan a network. When the user asks you to scan, check, test or measure \
        something, in any language («просканируй блютуз», «проверь интернет», «замерь скорость», «проверь доступ \
        к гуглу», «какой канал лучше»), call the matching tool even if older data is already attached. \
        Otherwise call one only when the question needs fresher data than what you have. \
        When a <tool_result> for the question is already there, answer from it rather than calling the same \
        tool again. Never claim a check ran, or report a number from one, unless you got its result back.
        When the attached data already has a traffic capture and the user asks to decode, explain or analyze it \
        («расшифруй pcap», «что в захвате»), explain that capture: what kinds of frames or protocols it holds, \
        which devices talked most and to whom, and anything unusual (deauthentication bursts, unknown devices, \
        plain-text protocols). Call capture_pcap only when the user asks for a new capture. \
        "Recorded traffic of this phone" is different: the phone's own connections, recorded on the phone itself. \
        For it, say which servers and services the phone talked to, which apps or hosts were the busiest, what \
        went unencrypted over plain HTTP, and anything that looks like tracking, advertising or an unexpected destination.
        You have a dossier — a persistent notebook shared across all chats. Use it to save important findings, \
        conclusions, and facts worth keeping: a suspicious device, a weak password, a resolved incident, a note \
        about the user's network setup. To save a note:
        <tool_call>{"tool": "remember", "text": "the note to save"}
        When the note is about one device or network, file it under that device's address — an IP, a MAC or a \
        BSSID, exactly as the data shows it. A filed note comes back by itself the next time that device is in \
        front of the user, while an unfiled one travels with every question, so file whatever can be filed:
        <tool_call>{"tool": "remember", "host": "192.168.1.64", "text": "the owner says this camera is theirs"}
        To remove a note that is no longer relevant:
        <tool_call>{"tool": "forget", "text": "search text to find the note"}
        Save a note when you discover something the user should know next time, or when the user asks to remember \
        something. Keep notes short and factual. The dossier section in <app_data> shows what is already saved.
        """

    var registry: ToolRegistry {
        ToolRegistry(
            grammar: Self.grammar,
            maxSteps: 4,
            preflight: { [sources] question in Self.preflightCalls(for: question, devices: sources.ble.devices.values) },
            runningLabel: Self.runningLabel
        ) { [sources, memory] name, argsJSON in
            await Self.run(name, argsJSON: argsJSON, sources: sources, memory: memory)
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
            if has(RequestStems.forwarding) { found.insert(.checkPortForwarding) }
            if has(RequestStems.lan) { found.insert(.scanLAN) }
            if has(RequestStems.speed) { found.insert(.speedTest) }
            else if has(RequestStems.connection) { found.insert(.checkConnection) }
        }
        // Devices or networks with nothing more specific named: what is near the phone is Bluetooth (devices) or
        // Wi‑Fi (networks); asked to scan without "nearby", it is the user's own network.
        if found.isEmpty {
            if has(RequestStems.devices) { found.insert(nearby ? .scanBluetooth : .scanLAN) }
            else if has(RequestStems.networks) { found.insert(nearby ? .scanWifi : .scanLAN) }
            // «подключись к Momentum 3»: the name alone says nothing, so sweep Bluetooth first — the model then
            // has a current list to name a device from, and the question never reaches the web.
            else if has(RequestStems.connectTo) { found.insert(.scanBluetooth) }
        }
        return Name.allCases.filter(found.contains)
    }

    /// `requested(in:)` plus the one check with an argument the app can fill in itself: a Bluetooth device the
    /// question names and asks to connect to or read («подключись к Momentum 3», «прочитай GATT Galaxy Buds»). A
    /// small model asked that usually answers from the Bluetooth list instead of calling connect_bluetooth, so the
    /// app reads the device first and the model only has to explain what came back.
    static func preflightCalls(for question: String, devices: some Collection<BLEDevice>) -> [String] {
        var calls: [String] = []
        // A question about earlier scans gets History read for it, with the question as what to look for: a small
        // model rarely thinks of a tool for "was this here before?" and answers from today's snapshot instead.
        if asksAboutHistory(question), let text = jsonString(String(question.prefix(300))) {
            calls.append("{\"tool\": \"\(Name.searchHistory.rawValue)\", \"text\": \(text)}")
        }
        return calls + scanCalls(for: question, devices: devices)
    }

    static func asksAboutHistory(_ question: String) -> Bool {
        let text = question.lowercased()
        return !AssistantDigest.matches(RequestStems.aboutChecks, in: text)
            && AssistantDigest.matches(RequestStems.history, in: text)
    }

    private static func jsonString(_ value: String) -> String? {
        (try? JSONSerialization.data(withJSONObject: value, options: .fragmentsAllowed)).flatMap { String(data: $0, encoding: .utf8) }
    }

    private static func scanCalls(for question: String, devices: some Collection<BLEDevice>) -> [String] {
        var names = requested(in: question)
        guard let device = bluetoothTarget(in: question, among: devices),
              let quoted = try? JSONSerialization.data(withJSONObject: device.displayName, options: .fragmentsAllowed),
              let name = String(data: quoted, encoding: .utf8) else {
            return names.map(\.rawValue)
        }
        // The device is already heard: a fresh sweep first would only add a few seconds.
        names.removeAll { $0 == .scanBluetooth }
        return names.map(\.rawValue) + ["{\"tool\": \"\(Name.connectBluetooth.rawValue)\", \"text\": \(name)}"]
    }

    /// The heard Bluetooth device a question names and asks to connect to or read, if any. The longest name found
    /// in the question wins («Galaxy Buds2 Pro» over «Galaxy»), then the strongest signal.
    static func bluetoothTarget(in question: String, among devices: some Collection<BLEDevice>) -> BLEDevice? {
        let text = question.lowercased()
        func has(_ stems: [String]) -> Bool { AssistantDigest.matches(stems, in: text) }
        guard !has(RequestStems.aboutChecks), has(RequestStems.connectTo) || has(RequestStems.readDevice) else { return nil }
        let named = devices.filter { device in
            let name = device.displayName.lowercased()
            return name.count >= 3 && !name.contains("{") && !name.contains("}") && text.contains(name)
        }
        return named.max {
            ($0.displayName.count, $0.rssi) < ($1.displayName.count, $1.rssi)
        }
    }

    private enum RequestStems {
        /// Asking about what was saved earlier rather than what is around now.
        static let history = [
            "history", "earlier", "previously", "last time", "last week", "yesterday", "saved session", "seen before",
            "истори", "раньше", "прошл", "вчера", "сохранённ", "сохраненн", "сесси", "встречал", "видел ли", "был ли", "была ли",
            "были ли", "было ли",
        ]
        /// Asking to read what one device publishes about itself. Verbs only: «проанализируй GATT-сервисы X» is
        /// about a read already made (the device screen's ✨ button asks exactly that), not a request for a new one.
        static let readDevice = ["read", "прочита", "прочт", "считай", "считать"]
        /// Asking to run something, not just to read.
        static let run = [
            "scan", "rescan", "check", "test", "measure", "run", "refresh", "look for",
            "скан", "сканир", "просканир", "отсканир", "пересканир", "провер", "замер", "измер", "тест", "протестир",
            "обнов", "запуст", "найд", "поищ", "ищ", "подключ",
            // "connect to", not "connect": the bare word is the start of "connection", which is a question
            // about the internet link rather than a request to open one.
            "connect to",
        ]
        /// Asking to open a link to one device and read it. Only consulted once nothing more specific matched.
        static let connectTo = ["connect to", "подключ", "приконнект"]
        /// The router's own table of what it lets in from the internet.
        static let forwarding = [
            "port forward", "forwarded port", "upnp", "nat-pmp", "port mapping",
            "проброс", "пробрасыв", "переадресац", "перенаправлен", "юпнп",
        ]
        /// Asking what is around right now.
        static let nearby = ["nearby", "around", "near me", "close by", "рядом", "вокруг", "поблизости", "неподалёку", "неподалеку"]
        /// A question about checks rather than a request for one: «зачем сканировать блютуз?», «не сканируй».
        static let aboutChecks = [
            "why", "how often", "how does", "don't scan", "don't check", "do not scan", "do not check",
            "зачем", "почему", "как часто", "как работает", "что такое", "что значит",
            "не скан", "не провер", "не надо скан", "не нужно скан", "не надо провер", "не нужно провер",
            "не подключ", "не надо подключ", "не нужно подключ",
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
        case .checkPortForwarding: String(localized: "Asking the router about port forwarding…")
        case .capturePcap: String(localized: "Capturing traffic…")
        case .connectBluetooth: String(localized: "Connecting to the device…")
        case .searchHistory: String(localized: "Looking through History…")
        case .remember: String(localized: "Saving a note…")
        case .forget: String(localized: "Removing a note…")
        case nil: String(localized: "Running \(name)…")
        }
    }

    private static func run(_ name: String, argsJSON: String, sources: AssistantSources, memory: AssistantMemory) async -> String {
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

        case .searchHistory:
            return HistoryDigest.text(sources.history.entries, query: decodeArg(argsJSON, keys: ["text", "host", "bssid"]) ?? "")

        case .remember:
            guard let text = decodeArg(argsJSON, keys: ["text"]) else {
                return "Missing \"text\". Provide the note to save."
            }
            let subject = decodeArg(argsJSON, keys: ["host", "bssid"])
            memory.add(text, subject: subject)
            return subject.map { "Saved to the dossier, filed under \($0)." } ?? "Saved to the dossier."

        case .forget:
            guard let text = decodeArg(argsJSON, keys: ["text"]) else {
                return "Missing \"text\". Provide text to find the note to remove."
            }
            return memory.remove(matching: text)
                ? "Removed from the dossier."
                : "No matching note found in the dossier."
        }
    }

    /// Whether the scope the model passed to `scan_ports` means "every port" rather than the usual short list.
    static func asksForEveryPort(_ text: String) -> Bool {
        AssistantDigest.matches(["all", "full", "every", "deep", "whole", "65535", "все", "весь", "полн", "глубок"],
                                in: text.lowercased())
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

    private static func decodeArg(_ argsJSON: String, keys: [String]) -> String? {
        guard let data = argsJSON.data(using: .utf8),
              let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return nil }
        for key in keys {
            if let value = object[key] as? String { return value }
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
