import Foundation

enum LinkStatus: String, Codable, Equatable, Sendable {
    case online, captive, lan, offline, noNetwork
}

enum RemoteNote: String, Codable, Equatable, Sendable {
    case none, busy, failed
}

struct ProbeResult: Codable, Equatable, Sendable {
    var name: String
    var ok: Bool
    var millis: Int?
}

struct ExternalAddress: Codable, Equatable, Sendable {
    var ip: String
    var countryCode: String?
    var city: String?
    var isp: String?
    var asn: String?
    var colo: String?
    var warp = false
}

struct SpeedSample: Codable, Equatable, Sendable {
    var latencyMs: Int?
    var downBps: Double?
    var upBps: Double?
}

struct RegionReach: Codable, Equatable, Sendable, Identifiable {
    var code: String
    var host: String
    var ok: Bool
    var millis: Int?
    var id: String { code }
}

struct AbroadView: Codable, Equatable, Sendable, Identifiable {
    var code: String
    var city: String
    var ok: Bool
    var millis: Int?
    var id: String { code + city }
}

struct TraceHop: Codable, Equatable, Sendable {
    var address: String?
    var millis: Int?
}

struct LocalTrace: Codable, Equatable, Sendable {
    var target: String
    var hops: [TraceHop]
    var unavailable: Bool
}

struct RemoteTrace: Codable, Equatable, Sendable, Identifiable {
    var code: String
    var city: String
    var hops: [TraceHop]
    var reached: Bool
    var id: String { code + city }
}

struct CheckNode: Equatable, Sendable {
    var id: String
    var code: String
    var city: String
}

enum CheckStart: Equatable, Sendable {
    case ok(id: String, nodes: [CheckNode])
    case busy
    case failed
}

/// Filled in as a check runs. Replaced wholesale so SwiftUI notices each stage.
struct LinkSnapshot: Codable, Equatable, Sendable {
    var checkedAt = Date()
    var status: LinkStatus?
    /// English sentence; the view looks it up in the string catalog.
    var detail: String?
    var gateway: ProbeResult?
    var dns: ProbeResult?
    var web: ProbeResult?
    var external: ExternalAddress?
    var speed: SpeedSample?
    var speedFailed = false
    var regions: [RegionReach] = []
    var abroad: [AbroadView] = []
    var abroadNote: RemoteNote = .none
    var localTrace: LocalTrace?
    var remoteTraces: [RemoteTrace] = []
    var traceNote: RemoteNote = .none
}

/// A finished connection check, kept on the LAN history session for that network.
struct SavedConnection: Codable, Equatable, Identifiable {
    var viaVpn: Bool
    var snapshot: LinkSnapshot
    var id: Date { snapshot.checkedAt }
}

/// Parsers for the replies a connection check collects. Kept in step with Android `LinkParse`.
enum LinkParse {
    static func external(fromTrace text: String) -> ExternalAddress? {
        var map: [String: String] = [:]
        for line in text.split(whereSeparator: \.isNewline) {
            guard let split = line.firstIndex(of: "="), split != line.startIndex else { continue }
            map[String(line[..<split])] = String(line[line.index(after: split)...]).trimmingCharacters(in: .whitespaces)
        }
        guard let ip = map["ip"], !ip.isEmpty else { return nil }
        let loc = map["loc"]
        return ExternalAddress(
            ip: ip,
            countryCode: loc?.count == 2 ? loc : nil,
            colo: map["colo"].flatMap { $0.isEmpty ? nil : $0 },
            warp: map["warp"] == "on"
        )
    }

    static func mergeWhois(_ data: Data, into current: ExternalAddress) -> ExternalAddress {
        guard let root = jsonObject(data) else { return current }
        if let success = root["success"] as? Bool, !success { return current }
        let connection = root["connection"] as? [String: Any]
        let asnNumber = (connection?["asn"] as? NSNumber)?.intValue
        let isp = (connection?["isp"] as? String).flatMap { $0.isEmpty ? nil : $0 }
            ?? (connection?["org"] as? String).flatMap { $0.isEmpty ? nil : $0 }
        let code = root["country_code"] as? String
        let city = root["city"] as? String
        return ExternalAddress(
            ip: current.ip,
            countryCode: code?.count == 2 ? code : current.countryCode,
            city: city.flatMap { $0.isEmpty ? nil : $0 } ?? current.city,
            isp: isp ?? current.isp,
            asn: asnNumber.flatMap { $0 > 0 ? "AS\($0)" : nil } ?? current.asn,
            colo: current.colo,
            warp: current.warp
        )
    }

    static func checkStart(_ data: Data) -> CheckStart {
        guard let root = jsonObject(data) else { return .failed }
        if root["error"] as? String == "limit_exceeded" { return .busy }
        guard let id = root["request_id"] as? String, !id.isEmpty, (root["ok"] as? NSNumber)?.intValue == 1 else {
            return .failed
        }
        let nodesObject = root["nodes"] as? [String: Any] ?? [:]
        let nodes = nodesObject.map { key, value -> CheckNode in
            let place = value as? [Any] ?? []
            let code = place.first as? String ?? ""
            let city = place.count > 2 ? place[2] as? String ?? "" : ""
            return CheckNode(id: key, code: code, city: city)
        }
        return .ok(id: id, nodes: nodes)
    }

    /// Ping times from check-host are seconds. A null node is still working.
    static func abroad(_ data: Data, nodes: [CheckNode]) -> (complete: Bool, views: [AbroadView])? {
        guard let root = jsonObject(data) else { return nil }
        var complete = true
        var views: [AbroadView] = []
        for node in nodes {
            guard let value = root[node.id], !(value is NSNull) else {
                complete = false
                continue
            }
            guard let outer = value as? [Any], let pings = outer.first as? [Any] else {
                views.append(AbroadView(code: node.code, city: node.city, ok: false, millis: nil))
                continue
            }
            var best: Int?
            var ok = false
            for ping in pings {
                guard let row = ping as? [Any], row.first as? String == "OK" else { continue }
                ok = true
                guard let ms = secondsToMillis(row.dropFirst().first) else { continue }
                best = min(best ?? ms, ms)
            }
            views.append(AbroadView(code: node.code, city: node.city, ok: ok, millis: ok ? best : nil))
        }
        return (complete, views)
    }

    /// Traceroute `query_times` are already milliseconds, unlike ping.
    static func traces(_ data: Data, nodes: [CheckNode], target: String) -> (complete: Bool, traces: [RemoteTrace])? {
        guard let root = jsonObject(data) else { return nil }
        var complete = true
        var traces: [RemoteTrace] = []
        for node in nodes {
            guard let value = root[node.id], !(value is NSNull) else {
                complete = false
                continue
            }
            let hops = (value as? [Any]).flatMap { $0.first as? [Any] }.map(hops(of:)) ?? []
            traces.append(RemoteTrace(
                code: node.code,
                city: node.city,
                hops: hops,
                reached: hops.contains { $0.address == target }
            ))
        }
        return (complete, traces)
    }

    static func compactPath(_ hops: [TraceHop], limit: Int = 5) -> String {
        var collapsed: [String] = []
        for hop in hops {
            let part = hop.address ?? "*"
            if part == "*", collapsed.last == "*" { continue }
            collapsed.append(part)
        }
        guard !collapsed.isEmpty else { return "" }
        if collapsed.count <= limit { return collapsed.joined(separator: " → ") }
        return (Array(collapsed.prefix(2)) + ["…"] + Array(collapsed.suffix(2))).joined(separator: " → ")
    }

    private static func hops(of raw: [Any]) -> [TraceHop] {
        raw.map { hop in
            let replies = hop as? [Any] ?? []
            for reply in replies {
                guard let object = reply as? [String: Any],
                      let host = object["host"] as? String, !host.isEmpty else { continue }
                return TraceHop(address: host, millis: firstMillis(object["query_times"]))
            }
            return TraceHop(address: nil, millis: nil)
        }
    }

    private static func firstMillis(_ raw: Any?) -> Int? {
        guard let times = raw as? [Any] else { return nil }
        return times.compactMap(millisValue).first
    }

    private static func millisValue(_ raw: Any?) -> Int? {
        switch raw {
        case let number as NSNumber:
            return Int(number.doubleValue.rounded())
        case let text as String:
            return Double(text).map { Int($0.rounded()) }
        default:
            return nil
        }
    }

    private static func secondsToMillis(_ raw: Any?) -> Int? {
        let seconds: Double?
        switch raw {
        case let number as NSNumber:
            seconds = number.doubleValue
        case let text as String:
            seconds = Double(text)
        default:
            seconds = nil
        }
        guard let seconds else { return nil }
        return max(1, Int((seconds * 1000).rounded()))
    }

    private static func jsonObject(_ data: Data) -> [String: Any]? {
        try? JSONSerialization.jsonObject(with: data) as? [String: Any]
    }
}

func linkText(_ english: String) -> String {
    Bundle.main.localizedString(forKey: english, value: english, table: nil)
}
