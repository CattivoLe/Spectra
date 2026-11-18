import Foundation

/// Everything kept under History, searchable by the assistant (`search_history`). The live snapshot only ever
/// listed the saved sessions by date and size; this reads what is inside them — every network, host, Bluetooth
/// device, connection check and sensor summary — merged per thing across sessions, so "was this device here
/// before?" and "what did the café's Wi‑Fi look like last week?" have answers. Names come from the air and the
/// network, so every one goes through `AssistantDigest.clean`.
enum HistoryDigest {
    /// Upper bound in characters: the result shares the model's context with the question and the snapshot.
    static let budget = 2600

    /// One thing (a network, a host, a device…) as seen across every session that has it.
    struct Item {
        var kind: String
        /// What identifies it across sessions: a BSSID, MAC, IP, device id, or a summary line's title.
        var key: String
        var name: String
        /// What was recorded the last time it was seen.
        var detail: String
        /// Lower-cased name, key and maker: what a search is matched against.
        var haystack: String
        var dates: [Date] = []
        var places: [String] = []

        var sessions: Int { dates.count }
        var first: Date { dates.min() ?? .distantPast }
        var last: Date { dates.max() ?? .distantPast }
    }

    // MARK: Building

    static func items(_ entries: [HistoryEntry]) -> [Item] {
        var byKey: [String: Item] = [:]
        // Oldest first, so the detail that stays is the latest.
        for entry in entries.sorted(by: { $0.savedAt < $1.savedAt }) {
            let place = entry.placeLabel ?? entry.importedFrom.map { "from \($0)" }
            for var item in records(of: entry) {
                let id = item.kind + "|" + item.key
                if var known = byKey[id] {
                    known.name = item.name
                    known.detail = item.detail
                    known.haystack = item.haystack
                    item = known
                }
                item.dates.append(entry.savedAt)
                if let place, !item.places.contains(place) { item.places.append(place) }
                byKey[id] = item
            }
        }
        return Array(byKey.values)
    }

    private static func records(of entry: HistoryEntry) -> [Item] {
        var found: [Item] = []
        func add(_ kind: String, key: String, name: String, detail: String, maker: String? = nil, aliases: [String] = []) {
            let haystack = ([name, key, maker ?? ""] + aliases).joined(separator: " ").lowercased()
            found.append(Item(kind: kind, key: key, name: name, detail: detail, haystack: haystack))
        }

        for network in entry.networks {
            let reading = network.latest
            let name = network.revealedSSID ?? reading.ssid ?? "(hidden)"
            var parts = [reading.auth.name, "ch \(reading.channel)", "\(reading.rssi) dBm"]
            if !network.clients.isEmpty { parts.append("\(network.clients.count) clients") }
            add("Wi‑Fi", key: reading.bssid.description, name: name, detail: parts.joined(separator: " · "))
        }
        for probe in entry.probes {
            let looking = probe.ssids.prefix(5).joined(separator: ", ")
            add("Wi‑Fi probe", key: probe.mac.description, name: probe.displayName,
                detail: looking.isEmpty ? "probing for any network" : "looking for \(looking)", maker: probe.manufacturer)
        }
        for deauth in entry.deauths {
            add("Deauth", key: "\(deauth.source)>\(deauth.target)", name: "deauthentication from \(deauth.source)",
                detail: "network \(deauth.bssid), \(deauth.count) frames, reason \(deauth.reason)\(deauth.isBroadcast ? ", to everyone" : "")")
        }
        for saved in entry.lan {
            let device = saved.device()
            var parts: [String] = []
            if let maker = device.manufacturer { parts.append(maker) }
            if saved.isGateway { parts.append("router") }
            if saved.isNew { parts.append("new on the network that day") }
            if saved.portsScanned {
                parts.append(saved.openPorts.isEmpty ? "no open ports"
                             : "open " + saved.openPorts.prefix(8).map { "\($0.number)/\($0.service)" }.joined(separator: ","))
            }
            let network = entry.placeLabel.map { " on \($0)" } ?? ""
            add("LAN", key: saved.macAddress?.description ?? saved.ipAddress, name: "\(device.displayName) \(saved.ipAddress)",
                detail: parts.joined(separator: " · ") + network, maker: device.manufacturer,
                aliases: saved.hostnames + [saved.ipAddress])
        }
        for saved in entry.bluetooth {
            let device = saved.device()
            var parts = ["\(saved.rssi) dBm"]
            if let company = saved.companyName { parts.append(company) }
            if saved.lastSeen > saved.firstSeen {
                parts.append("nearby \(Int(saved.lastSeen.timeIntervalSince(saved.firstSeen) / 60)) min")
            }
            add("Bluetooth", key: saved.id.uuidString, name: device.displayName, detail: parts.joined(separator: " · "),
                maker: saved.companyName)
        }
        for connection in entry.connections {
            let snapshot = connection.snapshot
            var parts: [String] = []
            if let status = snapshot.status { parts.append(status.rawValue) }
            if let external = snapshot.external {
                parts.append("public \(external.ip)")
                if let isp = external.isp { parts.append(isp) }
                if let country = external.countryCode { parts.append(country) }
            }
            if let down = snapshot.speed?.downBps { parts.append(String(format: "%.0f Mbit/s down", down / 1_000_000)) }
            if connection.viaVpn { parts.append("via VPN") }
            add("Connection check", key: "check \(snapshot.checkedAt.timeIntervalSince1970)",
                name: "connection check \(clock(snapshot.checkedAt))", detail: parts.joined(separator: " · "))
        }
        for line in entry.lines {
            add(entry.kind == .lora ? "LoRa" : "\(entry.kind.title) (sensor)", key: line.title, name: line.title,
                detail: [line.detail, line.trailing].filter { !$0.isEmpty }.joined(separator: " · "))
        }
        return found
    }

    // MARK: Writing up

    /// What the history holds about `query`: the things it names, or, when it names nothing that was ever
    /// saved, an overview — the sessions, and what came back again and again.
    static func text(_ entries: [HistoryEntry], query: String, now: Date = Date()) -> String {
        guard !entries.isEmpty else {
            return "History is empty: nothing has been saved yet. Scans are saved there automatically, or with Save to History."
        }
        let all = items(entries)
        let kinds = kindsAsked(in: query)
        let pool = kinds.isEmpty ? all : all.filter { kinds.contains($0.kind) }
        let words = searchWords(in: query)
        let scored = pool.compactMap { item -> (Item, Int)? in
            let hits = words.filter { item.haystack.contains($0) }.count
            return hits > 0 ? (item, hits) : nil
        }
        let found = scored.sorted { $0.1 != $1.1 ? $0.1 > $1.1 : $0.0.last > $1.0.last }.map(\.0)

        var lines: [String]
        if !found.isEmpty {
            lines = ["History matching the question (\(found.count) of \(all.count) things saved across \(entries.count) sessions):"]
            for item in found.prefix(15) { lines.append(describe(item)) }
            if found.count > 15 { lines.append("- …and \(found.count - 15) more.") }
        } else {
            lines = overview(entries, items: pool, kinds: kinds)
            if !words.isEmpty {
                lines.insert("Nothing saved matches \(words.prefix(5).map { "\"\(AssistantDigest.clean($0, limit: 30))\"" }.joined(separator: ", ")).", at: 0)
            }
        }
        var text = lines.joined(separator: "\n")
        if text.count > budget { text = String(text.prefix(budget)) + "\n…(cut)" }
        return text
    }

    private static func describe(_ item: Item) -> String {
        var line = "- [\(item.kind)] \(AssistantDigest.clean(item.name, limit: 50))"
        if !item.key.hasPrefix("check "), item.kind != "Bluetooth", !item.name.contains(item.key) { line += " \(item.key)" }
        if !item.detail.isEmpty { line += " · \(AssistantDigest.clean(item.detail, limit: 140))" }
        line += item.sessions == 1
            ? " · seen once, \(clock(item.last))"
            : " · in \(item.sessions) sessions, \(clock(item.first)) – \(clock(item.last))"
        if !item.places.isEmpty {
            line += " · at " + item.places.prefix(3).map { AssistantDigest.clean($0, limit: 40) }.joined(separator: "; ")
        }
        return line
    }

    private static func overview(_ entries: [HistoryEntry], items: [Item], kinds: Set<String>) -> [String] {
        let sessions = entries.sorted { $0.savedAt > $1.savedAt }
        let shown = kinds.isEmpty ? sessions : sessions.filter { entry in
            records(of: entry).contains { kinds.contains($0.kind) }
        }
        var lines = ["History: \(sessions.count) saved sessions, \(clock(sessions.last?.savedAt ?? .now)) – \(clock(sessions.first?.savedAt ?? .now)). Newest first:"]
        for entry in shown.prefix(12) {
            var line = "- \(clock(entry.savedAt)) · \(entry.kind.title) · \(entry.itemCount) items"
            if let place = entry.placeLabel { line += " · \(AssistantDigest.clean(place, limit: 40))" }
            if entry.newHostCount > 0 { line += " · \(entry.newHostCount) new hosts" }
            if let from = entry.importedFrom { line += " · imported from \(AssistantDigest.clean(from, limit: 30))" }
            lines.append(line)
        }
        if shown.count > 12 { lines.append("- …and \(shown.count - 12) older.") }

        let recurring = items.filter { $0.sessions > 1 }.sorted { $0.sessions > $1.sessions }
        if !recurring.isEmpty {
            lines.append("Seen again and again:")
            for item in recurring.prefix(10) { lines.append(describe(item)) }
        }
        let recent = items.filter { $0.sessions == 1 }.sorted { $0.last > $1.last }
        if !recent.isEmpty {
            lines.append("Seen only once, newest first:")
            for item in recent.prefix(8) { lines.append(describe(item)) }
        }
        lines.append("Name a network, device, address or MAC to get everything saved about it.")
        return lines
    }

    // MARK: Reading the question

    /// The kinds the question limits itself to, if any.
    static func kindsAsked(in query: String) -> Set<String> {
        let text = query.lowercased()
        var kinds: Set<String> = []
        if AssistantDigest.matches(["wi-fi", "wi‑fi", "wifi", "вай", "ssid", "точк", "bssid"], in: text) {
            kinds.formUnion(["Wi‑Fi", "Wi‑Fi probe", "Deauth", "Wi‑Fi (sensor)"])
        }
        if AssistantDigest.matches(["lan", "локальн", "хост", "host", "router", "роутер", "шлюз"], in: text) {
            kinds.formUnion(["LAN", "LAN (sensor)"])
        }
        if AssistantDigest.matches(["bluetooth", "блютуз", "блютус", "ble", "трекер", "tracker", "наушник", "airtag"], in: text) {
            kinds.formUnion(["Bluetooth", "Bluetooth (sensor)"])
        }
        if AssistantDigest.matches(["internet", "интернет", "connection check", "провайдер", "provider", "скорост", "speed"], in: text) {
            kinds.insert("Connection check")
        }
        if AssistantDigest.matches(["lora", "лора", "meshtastic"], in: text) { kinds.insert("LoRa") }
        return kinds
    }

    /// The words worth looking for: names, addresses, MACs. Dots, colons and dashes stay inside a word so
    /// addresses survive; words that only steer the question («история», «было», «wifi») are dropped.
    static func searchWords(in query: String) -> [String] {
        let words = query.lowercased()
            .split(whereSeparator: { !$0.isLetter && !$0.isNumber && !".:-".contains($0) })
            .map { $0.trimmingCharacters(in: CharacterSet(charactersIn: ".:-")) }
            .filter { $0.count >= 3 && !ignored.contains($0) }
        var seen: Set<String> = []
        return words.filter { seen.insert($0).inserted }
    }

    private static let ignored: Set<String> = [
        "что", "как", "где", "когда", "был", "была", "было", "были", "есть", "это", "этот", "эта", "эти", "мой", "моя",
        "мои", "моей", "мою", "моих", "все", "всё", "всех", "весь", "там", "тут", "уже", "ещё", "еще", "или", "при", "для",
        "истории", "история", "историю", "историй", "раньше", "прошлый", "прошлой", "прошлом", "прошлые", "вчера",
        "сохранённых", "сохраненных", "сохранённые", "сохраненные", "сессии", "сессий", "сессия", "найди", "покажи",
        "посмотри", "проверь", "расскажи", "видел", "видели", "встречал", "встречалось", "устройство", "устройства",
        "устройств", "сеть", "сети", "сетей", "сетью", "найден", "найдено", "какие", "какой", "какая", "сколько",
        "the", "and", "was", "were", "have", "has", "had", "this", "that", "what", "when", "where", "which", "seen",
        "history", "saved", "session", "sessions", "before", "earlier", "previous", "last", "time", "device", "devices",
        "network", "networks", "show", "find", "all", "any", "from", "with", "about", "did", "ever",
        "wifi", "wi-fi", "lan", "bluetooth", "блютуз", "блютус", "вайфай", "трекер", "трекеры", "трекеров",
    ]

    private static func clock(_ date: Date) -> String {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = "yyyy-MM-dd HH:mm"
        return formatter.string(from: date)
    }
}
