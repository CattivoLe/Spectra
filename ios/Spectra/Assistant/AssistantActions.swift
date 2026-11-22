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
        guard trimmed.hasPrefix("{") else { return await Self.run(trimmed, argsJSON: "{}", sources: sources, memory: memory) }
        guard let data = trimmed.data(using: .utf8),
              let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let tool = object["tool"] as? String else { return "Bad call." }
        return await Self.run(tool, argsJSON: trimmed, sources: sources, memory: memory)
    }

    /// The step button for a check that needs no argument; the cards trim the question mark.
    static func button(for name: String) -> String? {
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
        """

    var registry: ToolRegistry {
        ToolRegistry(
            grammar: Self.grammar,
            maxSteps: 4,
            preflight: { [sources] question in
                Self.preflightCalls(
                    for: question, devices: sources.ble.devices.values,
                    hosts: sources.lan.devices.values.map { PortTarget(ip: $0.ipAddress, isGateway: $0.isGateway) }
                )
            },
            // Questions about the user's own network are answered from the app; the web adds nothing but a wait.
            skipsWebSearch: { AssistantDigest.answersFromApp($0) },
            offer: { Self.offer(for: $0) },
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

        var found: Set<Name> = []
        // Questions only a fresh check can answer, asked without "check": the model would answer them from the
        // snapshot — or, worse, say a site opens without trying it.
        if asksAboutForwarding(text) { found.insert(.checkPortForwarding) }
        if has(RequestStems.access) && has(RequestStems.sites) { found.insert(.checkConnection) }
        if has(RequestStems.speed) && has(RequestStems.speedAmount) && has(RequestStems.mine) { found.insert(.speedTest) }
        guard run || nearby else { return Name.allCases.filter(found.contains) }
        // Bluetooth and Wi‑Fi are what is "nearby": asking what is around already asks for a fresh look at them.
        if has(RequestStems.bluetooth) { found.insert(.scanBluetooth) }
        if has(RequestStems.wifi) { found.insert(.scanWifi) }
        // The rest takes a while (a LAN sweep, a speed test), so only an outright request runs it.
        if run {
            if has(RequestStems.forwarding) { found.insert(.checkPortForwarding) }
            if has(RequestStems.lan) { found.insert(.scanLAN) }
            if has(RequestStems.speed) { found.insert(.speedTest) }
            else if has(RequestStems.connection) && !found.contains(.speedTest) { found.insert(.checkConnection) }
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
    static func preflightCalls(
        for question: String, devices: some Collection<BLEDevice>, hosts: some Collection<PortTarget> = [PortTarget]()
    ) -> [String] {
        var calls: [String] = []
        // A question about earlier scans gets History read for it, with the question as what to look for: a small
        // model rarely thinks of a tool for "was this here before?" and answers from today's snapshot instead.
        if asksAboutHistory(question), let text = jsonString(String(question.prefix(300))) {
            calls.append("{\"tool\": \"\(Name.searchHistory.rawValue)\", \"text\": \(text)}")
        }
        // «Посмотри, какие порты открыты на 192.168.1.64»: the host is in the question, so the app scans it.
        if let host = portScanTarget(in: question, hosts: hosts), let quoted = jsonString(host) {
            let scope = asksForEveryPort(question) ? ", \"text\": \"all\"" : ""
            calls.append("{\"tool\": \"\(Name.scanPorts.rawValue)\", \"host\": \(quoted)\(scope)}")
        }
        return calls + scanCalls(for: question, devices: devices)
    }

    /// A LAN host the app can scan ports on by itself: one named in the question by IP, or the router.
    struct PortTarget: Equatable, Sendable {
        var ip: String
        var isGateway: Bool
    }

    /// Ports reachable from outside are the router's forwarding table, not a scan of one host from inside.
    private static func asksAboutForwarding(_ text: String) -> Bool {
        AssistantDigest.matches(RequestStems.forwarding, in: text)
            || (AssistantDigest.matches(RequestStems.ports, in: text) && AssistantDigest.matches(RequestStems.outside, in: text))
    }

    /// Whole IPv4 addresses in the text: 192.168.1.1 is not found inside 192.168.1.10.
    private static func ipv4Addresses(in text: String) -> Set<String> {
        let range = NSRange(text.startIndex..., in: text)
        return Set(ipv4.matches(in: text, range: range).compactMap { Range($0.range, in: text).map { String(text[$0]) } })
    }

    private static let ipv4 = try! NSRegularExpression(pattern: #"(?<![\d.])\d{1,3}(?:\.\d{1,3}){3}(?![\d.]*\d)"#)

    /// The host whose ports a question asks about: named by IP (and in the LAN list), or "the router" when the
    /// question outright asks for a scan — «какие порты обычно открыты у роутеров?» must not scan the user's own.
    static func portScanTarget(in question: String, hosts: some Collection<PortTarget>) -> String? {
        let text = question.lowercased()
        func has(_ stems: [String]) -> Bool { AssistantDigest.matches(stems, in: text) }
        guard !has(RequestStems.aboutChecks), has(RequestStems.ports), !asksAboutForwarding(text) else { return nil }
        let named = ipv4Addresses(in: text)
        if let host = hosts.first(where: { named.contains($0.ip) }) {
            return has(RequestStems.run) || has(RequestStems.lookAt) ? host.ip : nil
        }
        guard named.isEmpty, has(RequestStems.router), has(RequestStems.run) || has(RequestStems.showMe) else { return nil }
        return hosts.first(where: \.isGateway)?.ip
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
        static let readDevice = ["read", "прочита", "прочт", "считай", "считать", "firmware", "battery", "прошивк", "батаре", "заряд"]
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
        static let speed = ["speed", "speedtest", "mbps", "how fast", "скорост", "спидтест", "мегабит", "мбит"]
        /// Asking for the number now, which only a measurement gives: «сколько мегабит», «какая сейчас скорость».
        static let speedAmount = ["how fast", "how much", "how many", "сколько", "какая скорост", "какая сейчас", "какую скорост", "какой скорост"]
        /// The user's own connection now, not speeds in general («сколько мегабит нужно для 4K»).
        static let mine = ["my", "now", "current", "actual", "у меня", "мой", "моя", "моё", "мое", "сейчас", "реальн", "выдаёт", "выдает"]
        /// Whether something opens: answerable only by trying it.
        static let access = [
            "opens", "open?", "reachable", "load", "loads", "is down", "is up", "blocked", "accessible",
            "открывается", "открываются", "доступен", "доступна", "доступно", "доступны", "доступ к", "грузится", "загружается",
            "работает ли", "заблокирован",
        ]
        static let sites = [
            "site", "website", "youtube", "instagram", "telegram", "google", "whatsapp", "facebook", "tiktok", "twitter",
            "netflix", "discord", "chatgpt", "vk", ".com", ".ru", ".org", ".net", ".io",
            "сайт", "ютуб", "инстаграм", "телеграм", "гугл", "ватсап", "фейсбук", "тикток", "твиттер", "нетфликс", "дискорд", "вк", "вконтакт",
        ]
        static let ports = ["port", "ports", "порт"]
        static let outside = ["outside", "from the internet", "to the internet", "exposed", "наружу", "извне", "снаружи", "из интернета", "в интернет"]
        /// Asking about one named host's ports, which means a scan: «какие порты открыты на 192.168.1.64».
        static let lookAt = ["show", "which", "what", "open", "посмотр", "покаж", "какие", "открыт"]
        static let showMe = ["show", "посмотр", "покаж"]
        static let router = ["router", "gateway", "роутер", "маршрутизатор", "шлюз"]
        static let connection = ["internet", "connection", "ping", "dns", "vpn", "интернет", "соединен", "подключени", "пинг", "днс", "впн"]
        /// Devices or networks in general: see the fallback in `requested(in:)`.
        static let devices = ["device", "устройств", "девайс"]
        static let networks = ["network", "сет"]
    }

    /// What a tool is called in words, for an answer that names it («выполни check_connection»), and — for the checks
    /// that need no argument — the offer shown under that answer. The request is worded so that `requested(in:)`
    /// runs the check itself, like the starters in an empty chat.
    static func offer(for name: String) -> ToolOffer? {
        switch Name(rawValue: name) {
        case .scanWifi:
            ToolOffer(name: name, mention: String(localized: "a Wi‑Fi scan"), button: String(localized: "Scan Wi‑Fi?"),
                      request: String(localized: "Scan the Wi‑Fi around me: which channel is freer?"))
        case .scanLAN:
            ToolOffer(name: name, mention: String(localized: "a network scan"), button: String(localized: "Scan the local network?"),
                      request: String(localized: "Scan my local network and show me what these devices are"))
        case .scanBluetooth:
            ToolOffer(name: name, mention: String(localized: "a Bluetooth scan"), button: String(localized: "Scan Bluetooth?"),
                      request: String(localized: "Scan Bluetooth nearby: is there someone else's tracker?"))
        case .checkConnection:
            ToolOffer(name: name, mention: String(localized: "a connection check"), button: String(localized: "Check the connection?"),
                      request: String(localized: "Check my internet connection: DNS, latency, provider"))
        case .speedTest:
            ToolOffer(name: name, mention: String(localized: "a speed test"), button: String(localized: "Measure the speed?"),
                      request: String(localized: "Measure my internet speed"))
        case .checkPortForwarding:
            ToolOffer(name: name, mention: String(localized: "a port forwarding check"), button: String(localized: "Check port forwarding?"),
                      request: String(localized: "Check which ports my router forwards to the internet"))
        case .scanPorts: ToolOffer(name: name, mention: String(localized: "a port scan"))
        case .capturePcap: ToolOffer(name: name, mention: String(localized: "a traffic capture"))
        case .connectBluetooth: ToolOffer(name: name, mention: String(localized: "a connection to the device"))
        case .searchHistory: ToolOffer(name: name, mention: String(localized: "a search through History"))
        case .remember, .forget, .traceRoute, .checkSites, .checkSite, .expandRedirects, nil: nil
        }
    }

    /// A tool that ran, in words, for the note under the answer: «Ran: network scan», not «scan_lan».
    static func title(for name: String) -> String {
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
        case .searchHistory: String(localized: "History search")
        case .remember: String(localized: "saving a note")
        case .forget: String(localized: "removing a note")
        case .traceRoute: String(localized: "traceroute")
        case .checkSites: String(localized: "check of your sites")
        case .checkSite: String(localized: "certificate and domain check")
        case .expandRedirects: String(localized: "redirects")
        case nil: name
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
        case .checkPortForwarding: String(localized: "Asking the router about port forwarding…")
        case .capturePcap: String(localized: "Capturing traffic…")
        case .connectBluetooth: String(localized: "Connecting to the device…")
        case .searchHistory: String(localized: "Looking through History…")
        case .remember: String(localized: "Saving a note…")
        case .forget: String(localized: "Removing a note…")
        case .traceRoute: String(localized: "Tracing the path…")
        case .checkSites: String(localized: "Trying your sites…")
        case .checkSite: String(localized: "Asking the site and the registry…")
        case .expandRedirects: String(localized: "Following redirects…")
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
