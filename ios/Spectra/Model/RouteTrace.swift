import Darwin
import Foundation
import Network
import Observation

/// Where a hop's address is, as ipwho.is places it.
nonisolated struct HopPlace: Codable, Equatable, Sendable {
    var latitude: Double
    var longitude: Double
    var city: String?
    var countryCode: String?
    var isp: String?
}

/// The pure parts of a traceroute to a target the person chose. Mirrors the
/// Android `RouteTrace`.
nonisolated enum RouteTrace {
    static let defaultTarget = "1.1.1.1"

    /// The host in what was typed: a name, an IP address, or a link.
    static func target(from input: String) -> String? {
        var text = input.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        if let scheme = text.range(of: "://") { text = String(text[scheme.upperBound...]) }
        if text.hasPrefix("[") , let close = text.firstIndex(of: "]") {
            text = String(text[text.index(after: text.startIndex)..<close])
            return IPv6Address(text) != nil ? text : nil
        }
        if IPv6Address(text) != nil { return text }
        text = String(text.prefix { !"/?#".contains($0) })
        if let colon = text.lastIndex(of: ":") { text = String(text[..<colon]) }
        while text.hasSuffix(".") { text.removeLast() }
        guard !text.isEmpty, !text.contains(where: \.isWhitespace), text.count <= 253 else { return nil }
        return text
    }

    /// Addresses the internet can place: not private, not CGNAT, not
    /// link-local or loopback.
    static func isPublic(_ address: String) -> Bool {
        if let v4 = IPv4Address(address) {
            let b = [UInt8](v4.rawValue)
            if b[0] == 10 || b[0] == 127 || b[0] == 0 || b[0] >= 224 { return false }
            if b[0] == 172, (16...31).contains(b[1]) { return false }
            if b[0] == 192, b[1] == 168 { return false }
            if b[0] == 169, b[1] == 254 { return false }
            if b[0] == 100, (64...127).contains(b[1]) { return false }
            return true
        }
        if let v6 = IPv6Address(address) {
            return [UInt8](v6.rawValue)[0] & 0xE0 == 0x20
        }
        return false
    }

    /// One ipwho.is reply, or nil when it has no position.
    static func place(_ data: Data) -> HopPlace? {
        guard let root = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else { return nil }
        if let success = root["success"] as? Bool, !success { return nil }
        guard let lat = (root["latitude"] as? NSNumber)?.doubleValue,
              let lon = (root["longitude"] as? NSNumber)?.doubleValue,
              !(lat == 0 && lon == 0) else { return nil }
        let connection = root["connection"] as? [String: Any]
        func text(_ value: Any?) -> String? { (value as? String).flatMap { $0.isEmpty ? nil : $0 } }
        return HopPlace(
            latitude: lat, longitude: lon,
            city: text(root["city"]),
            countryCode: text(root["country_code"]),
            isp: text(connection?["isp"]) ?? text(connection?["org"])
        )
    }

    /// The hops that have a place, in path order, with consecutive hops in
    /// the same spot folded into one point so the line does not stutter.
    static func path(_ hops: [TraceHop], places: [String: HopPlace]) -> [(ttl: Int, place: HopPlace)] {
        var points: [(ttl: Int, place: HopPlace)] = []
        for (index, hop) in hops.enumerated() {
            guard let address = hop.address, let place = places[address] else { continue }
            if let last = points.last, abs(last.place.latitude - place.latitude) < 0.01, abs(last.place.longitude - place.longitude) < 0.01 {
                continue
            }
            points.append((index + 1, place))
        }
        return points
    }
}

/// Runs a traceroute from this phone to the chosen target over the route the
/// connection check uses, then asks ipwho.is where each public hop is.
@Observable
final class RouteTracer {
    private(set) var running = false
    private(set) var phase = ""
    private(set) var target: String?
    private(set) var address: String?
    private(set) var trace: LocalTrace?
    private(set) var places: [String: HopPlace] = [:]
    private(set) var failure: String?

    private var task: Task<Void, Never>?

    func run(_ input: String) {
        guard let host = RouteTrace.target(from: input) else {
            failure = String(localized: "That is not a site name or an IP address.")
            return
        }
        task?.cancel()
        running = true
        failure = nil
        target = host
        address = nil
        trace = nil
        places = [:]
        task = Task { @MainActor in
            await perform(host)
            running = false
            phase = ""
        }
    }

    func stop() {
        task?.cancel()
        running = false
        phase = ""
    }

    @MainActor
    private func perform(_ host: String) async {
        let viaVpn = UserDefaults.standard.bool(forKey: ProbeRoute.vpnKey)
        let interface: NWInterface.InterfaceType?
        switch await LinkHTTP.probeInterface(viaVpn: viaVpn) {
        case .unavailable:
            failure = String(localized: "There is no network for the chosen connection.")
            return
        case .system: interface = nil
        case .pinned(let type): interface = type
        }
        let interfaceName: String? = if let interface { await LinkHTTP.interfaceName(interface) } else { nil }

        phase = String(localized: "Looking up the address")
        let preferV4 = LocalTracer.hasAddress(family: AF_INET, interface: interfaceName)
        guard let resolved = await Self.resolve(host, preferV4: preferV4), !Task.isCancelled else {
            if !Task.isCancelled { failure = String(localized: "Could not find the address of \(host).") }
            return
        }
        address = resolved

        phase = String(localized: "Tracing the path")
        var found = await LocalTracer.trace(to: resolved, interfaceName: interfaceName)
        guard !Task.isCancelled else { return }
        trace = found
        if found.unavailable {
            failure = String(localized: "This phone blocked the probes a traceroute needs.")
            return
        }

        phase = String(localized: "Placing the hops on the map")
        let hopAddresses = found.hops.compactMap(\.address)
        async let names = LocalTracer.names(for: hopAddresses)
        async let located = Self.locate(Array(Set(hopAddresses.filter(RouteTrace.isPublic))))
        let (nameMap, placeMap) = await (names, located)
        guard !Task.isCancelled else { return }
        for index in found.hops.indices {
            if let hopAddress = found.hops[index].address { found.hops[index].name = nameMap[hopAddress] }
        }
        trace = found
        places = placeMap
    }

    /// The first address of a name, IPv4 preferred when the interface has
    /// one. A literal address comes straight back.
    nonisolated private static func resolve(_ host: String, preferV4: Bool) async -> String? {
        if IPv4Address(host) != nil || IPv6Address(host) != nil { return host }
        return await Task.detached { () -> String? in
            var hints = addrinfo()
            hints.ai_family = AF_UNSPEC
            hints.ai_socktype = SOCK_DGRAM
            var info: UnsafeMutablePointer<addrinfo>?
            guard getaddrinfo(host, nil, &hints, &info) == 0, let first = info else { return nil }
            defer { freeaddrinfo(first) }
            var v4: String?
            var v6: String?
            var cursor: UnsafeMutablePointer<addrinfo>? = first
            while let entry = cursor {
                if let raw = entry.pointee.ai_addr {
                    var text = [CChar](repeating: 0, count: Int(NI_MAXHOST))
                    if getnameinfo(raw, entry.pointee.ai_addrlen, &text, socklen_t(text.count), nil, 0, NI_NUMERICHOST) == 0 {
                        let value = String(cString: text)
                        if entry.pointee.ai_family == AF_INET, v4 == nil { v4 = value }
                        if entry.pointee.ai_family == AF_INET6, v6 == nil { v6 = value }
                    }
                }
                cursor = entry.pointee.ai_next
            }
            return preferV4 ? (v4 ?? v6) : (v6 ?? v4)
        }.value
    }

    /// ipwho.is for each public hop, all at once. Where a hop is does not
    /// depend on the route asked over, so the system's own HTTP client does
    /// it: it reuses one connection instead of waiting for each to close.
    nonisolated private static func locate(_ addresses: [String]) async -> [String: HopPlace] {
        await withTaskGroup(of: (String, HopPlace?).self) { group in
            for address in addresses {
                group.addTask {
                    guard let url = URL(string: "https://ipwho.is/\(address)") else { return (address, nil) }
                    var request = URLRequest(url: url, timeoutInterval: 8)
                    request.setValue("Spectra", forHTTPHeaderField: "User-Agent")
                    guard let (data, response) = try? await URLSession.shared.data(for: request),
                          (response as? HTTPURLResponse)?.statusCode == 200 else { return (address, nil) }
                    return (address, RouteTrace.place(data))
                }
            }
            var result: [String: HopPlace] = [:]
            for await (address, place) in group {
                if let place { result[address] = place }
            }
            return result
        }
    }
}
