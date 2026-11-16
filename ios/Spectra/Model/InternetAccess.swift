import Foundation
import Network
import Observation

/// Which connection a check goes out through.
nonisolated enum ProbeRoute: String, CaseIterable, Identifiable, Sendable {
    /// Wi-Fi or Ethernet, around any VPN.
    case wifi
    /// The mobile network, even while Wi-Fi is joined.
    case cellular
    /// Whatever the system uses, VPN included.
    case vpn

    var id: Self { self }

    static let vpnKey = "probeViaVpn"
    static let cellularKey = "probeCellular"

    /// Kept as two switches, so the older "through VPN" setting still reads.
    static func current(_ defaults: UserDefaults = .standard) -> ProbeRoute {
        if defaults.bool(forKey: vpnKey) { return .vpn }
        return defaults.bool(forKey: cellularKey) ? .cellular : .wifi
    }

    init(viaVpn: Bool, cellular: Bool) {
        self = viaVpn ? .vpn : (cellular ? .cellular : .wifi)
    }
}

/// Whether one site answered over HTTPS, and how fast.
nonisolated struct ResourceReach: Codable, Equatable, Sendable, Identifiable {
    var host: String
    var ok: Bool
    var millis: Int?
    var id: String { host }
}

/// The traffic light: what this connection lets through.
nonisolated enum InternetAccess: String, Codable, Sendable {
    /// Foreign sites open and so do the services that are often blocked.
    case full
    /// The internet works, but services that are often blocked do not open.
    case filtered
    /// Only sites from the provider's allow-list open; the rest of the internet does not.
    case whitelistOnly
    /// Nothing answers.
    case none
}

/// What the reference sites said. `open` are sites outside any allow-list;
/// `listed` are the ones a Russian allow-list keeps reachable when mobile
/// internet is restricted; `blocked` are services that are often blocked
/// one by one. Mirrors the Android `AccessReport`.
nonisolated struct AccessReport: Codable, Equatable, Sendable {
    var open: [ResourceReach]
    var listed: [ResourceReach]
    /// Nil in checks saved before blocked services were asked.
    var blocked: [ResourceReach]? = nil

    var verdict: InternetAccess {
        AccessCheck.verdict(
            openReached: open.filter(\.ok).count,
            listedReached: listed.filter(\.ok).count,
            blockedFailed: (blocked ?? []).filter { !$0.ok }.count
        )
    }

    /// The often-blocked services that did not open.
    var unreachable: [String] { (blocked ?? []).filter { !$0.ok }.map(\.host) }
}

nonisolated enum AccessCheck {
    /// Large foreign sites that an allow-list does not carry.
    static let openHosts = ["www.google.com", "www.cloudflare.com", "www.wikipedia.org", "github.com", "www.microsoft.com"]
    /// Sites the allow-list keeps reachable.
    static let listedHosts = ["ya.ru", "vk.com", "www.gosuslugi.ru", "www.ozon.ru", "mail.ru"]
    /// Services that are often blocked one by one while the rest of the
    /// internet works. A blocked one fails at the TLS handshake.
    static let blockedHosts = ["www.youtube.com", "www.instagram.com", "www.facebook.com", "x.com", "discord.com", "www.linkedin.com"]

    /// Two foreign sites answering is the internet: one alone can be a leak
    /// or a cache. Then two often-blocked services failing means it is
    /// filtered; one can be a site having a bad minute. With fewer than two
    /// foreign answers, any allow-listed site answering means the connection
    /// is up but fenced in.
    static func verdict(openReached: Int, listedReached: Int, blockedFailed: Int = 0) -> InternetAccess {
        if openReached >= 2 { return blockedFailed >= 2 ? .filtered : .full }
        if listedReached >= 1 { return .whitelistOnly }
        if openReached == 1 { return blockedFailed >= 2 ? .filtered : .full }
        return .none
    }

    /// The host of whatever the person typed: a bare name, a link, or a
    /// name with a path. Nil when there is no usable name in it.
    static func normalize(_ input: String) -> String? {
        var text = input.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        if let scheme = text.range(of: "://") { text = String(text[scheme.upperBound...]) }
        if let at = text.firstIndex(of: "@") { text = String(text[text.index(after: at)...]) }
        text = String(text.prefix { !"/?#".contains($0) })
        if let colon = text.lastIndex(of: ":"), text[text.index(after: colon)...].allSatisfy(\.isNumber) {
            text = String(text[..<colon])
        }
        while text.hasSuffix(".") { text.removeLast() }
        guard text.contains("."), !text.hasPrefix("."), !text.contains(where: \.isWhitespace), text.count <= 253 else { return nil }
        return text
    }

    /// A finished TLS handshake with the site's own certificate. That is
    /// where a filter cuts in: it reads the site name in the handshake and
    /// drops the connection. No page is requested, since some sites answer
    /// a bare request with silence.
    static func reach(_ host: String, interface: NWInterface.InterfaceType?) async -> ResourceReach {
        let millis = await LinkHTTP.connectMillis(host: host, port: 443, timeout: 6, interfaceType: interface, tls: true)
        return ResourceReach(host: host, ok: millis != nil, millis: millis)
    }

    static func reachAll(_ hosts: [String], interface: NWInterface.InterfaceType?) async -> [ResourceReach] {
        await withTaskGroup(of: ResourceReach.self) { group in
            for host in hosts { group.addTask { await reach(host, interface: interface) } }
            var found: [String: ResourceReach] = [:]
            for await item in group { found[item.host] = item }
            return hosts.compactMap { found[$0] }
        }
    }

    static func run(interface: NWInterface.InterfaceType?) async -> AccessReport {
        async let open = reachAll(openHosts, interface: interface)
        async let listed = reachAll(listedHosts, interface: interface)
        async let blocked = reachAll(blockedHosts, interface: interface)
        return await AccessReport(open: open, listed: listed, blocked: blocked)
    }
}

/// Sites the person wants to keep an eye on: tap one to see whether it opens
/// from the chosen connection. The list is kept across launches; the answers
/// are not, since they go stale in minutes.
@Observable
final class ResourceWatch {
    static let defaults = ["youtube.com", "telegram.org", "whatsapp.com", "instagram.com", "chatgpt.com", "github.com"]

    private(set) var hosts: [String]
    private(set) var results: [String: ResourceReach] = [:]
    private(set) var checking: Set<String> = []

    private let store: UserDefaults
    private let storageKey = "watchedResources"

    init(defaults store: UserDefaults = .standard) {
        self.store = store
        hosts = store.stringArray(forKey: storageKey) ?? Self.defaults
    }

    var isChecking: Bool { !checking.isEmpty }

    /// Adds a site. False when the text holds no site name or it is already listed.
    @discardableResult
    func add(_ input: String) -> Bool {
        guard let host = AccessCheck.normalize(input), !hosts.contains(host) else { return false }
        hosts.append(host)
        persist()
        return true
    }

    func remove(_ host: String) {
        hosts.removeAll { $0 == host }
        results[host] = nil
        checking.remove(host)
        persist()
    }

    func check(_ host: String) {
        guard hosts.contains(host), !checking.contains(host) else { return }
        checking.insert(host)
        Task { @MainActor in
            let interface = await Self.interface()
            let reach = await AccessCheck.reach(host, interface: interface.type)
            checking.remove(host)
            guard hosts.contains(host) else { return }
            results[host] = interface.available ? reach : ResourceReach(host: host, ok: false, millis: nil)
        }
    }

    func checkAll() {
        for host in hosts { check(host) }
    }

    /// The interface the route setting asks for.
    private static func interface() async -> (type: NWInterface.InterfaceType?, available: Bool) {
        switch await LinkHTTP.probeInterface(viaVpn: UserDefaults.standard.bool(forKey: ProbeRoute.vpnKey)) {
        case .system: (nil, true)
        case .pinned(let type): (type, true)
        case .unavailable: (nil, false)
        }
    }

    private func persist() {
        store.set(hosts, forKey: storageKey)
    }
}
