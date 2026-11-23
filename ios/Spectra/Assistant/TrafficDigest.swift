import Foundation

/// The phone's own recorded traffic (the Traffic screen on the LAN tab: connections forwarded through the on-device
/// VPN) written up for the assistant, the way `CaptureDigest` writes up a pcap. Hosts, paths and headers come from
/// the network, so every one is flattened with `AssistantDigest.clean` before it reaches the model.
nonisolated enum TrafficDigest {
    /// Upper bound in characters, the same as a capture's: it shares the question with other app data.
    static let budget = 2400

    static func text(_ flows: [TrafficFlow], name: String, origin: String) -> String {
        let sent = flows.reduce(Int64(0)) { $0 + $1.sent }
        let received = flows.reduce(Int64(0)) { $0 + $1.received }
        var head = "Recorded traffic of this phone \"\(AssistantDigest.clean(name, limit: 60))\", \(origin): \(flows.count) connections, "
            + "\(bytes(sent)) sent, \(bytes(received)) received"
        if let first = flows.map(\.startedAt).min(), let last = flows.map(\.lastAt).max(), last > first {
            head += String(format: ", over %.0f s", Double(last - first) / 1000)
        }
        var lines = [head + "."]
        lines.append("These are the phone's own connections, recorded on the phone itself through its local VPN: which "
                     + "servers each app reached and how much it exchanged. Encrypted (TLS) connections show only the server "
                     + "name unless TLS decryption was on; plain HTTP shows the requests themselves.")
        guard !flows.isEmpty else {
            lines.append("It holds no connections: nothing went out while recording, or recording never started.")
            return lines.joined(separator: "\n")
        }

        let kinds = Dictionary(grouping: flows, by: \.kind)
            .map { ($0.key.rawValue.uppercased(), $0.value.count) }
            .sorted { $0.1 > $1.1 }
        lines.append("By kind: " + kinds.map { "\($0.0) \($0.1)" }.joined(separator: ", ") + ".")

        // What was asked of those servers, by endpoint: the paths' changing parts are left out, they can name a person.
        let groups = EndpointGroup.build(flows)
        if !groups.isEmpty {
            func name(_ group: EndpointGroup) -> String { AssistantDigest.clean(group.endpoint.key, limit: 70) }
            func line(_ title: String, _ items: [String]) { lines.append("\(title): " + (items.isEmpty ? "none." : items.joined(separator: "; ") + ".")) }
            lines.append("HTTP requests read: \(groups.reduce(0) { $0 + $1.hits.count }), endpoints: \(groups.count) ({id} stands for a changing part).")
            line("Most requested", groups.prefix(5).map { "\(name($0)) ×\($0.hits.count)" })
            line("With errors", groups.filter { $0.errors > 0 }.sorted { $0.errors > $1.errors }.prefix(4)
                .map { "\(name($0)) \($0.errors) of \($0.hits.count)" })
            line("Slow (usually a second or more)", groups.filter { ($0.typicalMs ?? 0) >= TrafficAudit.slowTypicalMs }
                .sorted { ($0.typicalMs ?? 0) > ($1.typicalMs ?? 0) }.prefix(4).map { "\(name($0)) \($0.typicalMs ?? 0) ms" })
            let scans = TrafficAudit.scan(RequestHit.all(flows).filter { !$0.exchange.method.isEmpty })
            let exposed = scans.filter { scan in scan.secrets.contains { scan.cleartext || $0.place.inAddress } }
            line("Secrets in addresses or sent unencrypted (values hidden)", exposed.isEmpty ? []
                : ["\(exposed.count) requests, \(TrafficAudit.englishList(AssistantCards.unique(exposed.flatMap(\.secrets), by: \.self), limit: 3))"])
        } else {
            let requests = flows.compactMap { flow in flow.request.map { (flow.title, $0) } }
            if !requests.isEmpty {
                lines.append("Requests seen: " + requests.prefix(8).map { host, request in
                    let parts = request.split(separator: " ", maxSplits: 1).map(String.init)
                    let shown = parts.count == 2 ? "\(parts[0]) \(Endpoint.template(parts[1]))" : request
                    return "\(AssistantDigest.clean(host, limit: 40)) \(AssistantDigest.clean(shown, limit: 60))"
                }.joined(separator: "; ") + ".")
            }
        }
        let trackers = AssistantCards.unique(flows.compactMap { TrackerHosts.service(of: $0.title) }, by: \.name)
        lines.append("Known analytics and advertising services contacted: "
                     + (trackers.isEmpty ? "none." : trackers.prefix(8).map { "\($0.name) (\(TrafficAudit.englishKind($0.kind)))" }.joined(separator: ", ") + "."))
        let cleartext = flows.filter { $0.kind == .http && !$0.tls }
        if !cleartext.isEmpty {
            lines.append("Unencrypted HTTP to: " + Set(cleartext.map(\.title)).sorted().prefix(8)
                .map { AssistantDigest.clean($0, limit: 40) }.joined(separator: ", ") + ".")
        }
        let refused = flows.filter(\.refused)
        if !refused.isEmpty {
            lines.append("\(refused.count) connections refused Spectra's certificate (the app pins its own, or the certificate is not trusted yet).")
        }
        let dns = flows.filter { $0.kind == .dns }.count
        if dns > 0 { lines.append("\(dns) DNS lookups.") }

        // Per host, busiest first: the clearest picture of who the phone talks to. Last, so a long list gives way.
        let byHost = Dictionary(grouping: flows, by: \.title).map { host, group in
            (host: host,
             count: group.count,
             bytes: group.reduce(Int64(0)) { $0 + $1.sent + $1.received },
             kinds: Set(group.map(\.kind.rawValue)).sorted(),
             ports: Set(group.map(\.destinationPort)).sorted(),
             apps: Set(group.compactMap(\.app)).sorted())
        }.sorted { ($1.bytes, $0.host) < ($0.bytes, $1.host) }
        lines.append("Busiest servers (\(byHost.count) in all):")
        var room = budget - lines.reduce(0) { $0 + $1.count + 1 } - 20
        var listed = 0
        for entry in byHost.prefix(14) {
            var parts = [AssistantDigest.clean(entry.host, limit: 50),
                         "\(entry.count) conn",
                         bytes(entry.bytes),
                         entry.kinds.joined(separator: "/").uppercased(),
                         "port " + entry.ports.prefix(3).map(String.init).joined(separator: ",")]
            if !entry.apps.isEmpty { parts.append("app " + entry.apps.prefix(2).map { AssistantDigest.clean($0, limit: 30) }.joined(separator: ", ")) }
            let line = "- " + parts.joined(separator: " · ")
            room -= line.count + 1
            if room < 0 { break }
            lines.append(line)
            listed += 1
        }
        if byHost.count > listed { lines.append("- …and \(byHost.count - listed) more.") }

        var text = lines.joined(separator: "\n")
        if text.count > budget { text = String(text.prefix(budget)) + "\n…(cut)" }
        return text
    }

    private static func bytes(_ count: Int64) -> String {
        count >= 1_048_576 ? String(format: "%.1f MB", Double(count) / 1_048_576)
            : count >= 1024 ? "\(count / 1024) KB" : "\(count) B"
    }
}
