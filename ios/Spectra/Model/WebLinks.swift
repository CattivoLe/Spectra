import Foundation
import Observation

/// A device's web page the user kept, to open without scanning the network.
nonisolated struct WebLink: Codable, Identifiable, Equatable, Sendable {
    var id = UUID()
    /// What the user called it. Without it the device's own name is shown.
    var name: String?
    var deviceName: String
    /// The whole address, as the browser gets it. The user may add a path.
    var address: String
    /// Where the device was last seen, and the port the link was made from.
    var ip: String
    var port: Int
    /// Hardware address and names the device is recognised by; never its IP.
    var prints: [String]
    /// The network it is on, by `LANScanner.networkKey`, and its name.
    var network: String?
    var networkLabel: String?
    /// The device's own icon, and the one the user picked instead, by `WebLinkIcon.id`.
    var symbol: String
    var customIcon: String?
    /// The icon's colour, by `WebLinkColor`; the app's own without it.
    var color: String?
    /// What the device and its page say about themselves, to suggest icons by.
    var hints: [String]?

    var title: String { name ?? deviceName }
    var shownSymbol: String { WebLinkIcon.with(id: customIcon)?.symbol ?? symbol }
    var url: URL? { URL(string: address) }

    /// The address without the scheme, for a list row.
    var shownAddress: String {
        guard let separator = address.range(of: "://") else { return address }
        return String(address[separator.upperBound...])
    }

    /// What the user typed as a web address, or nil when a browser would not open it.
    static func normalized(_ typed: String) -> String? {
        let text = typed.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty, !text.contains(where: \.isWhitespace) else { return nil }
        let full = text.contains("://") ? text : "http://\(text)"
        guard let parts = URLComponents(string: full), let scheme = parts.scheme?.lowercased(),
              scheme == "http" || scheme == "https",
              let host = parts.host, !host.isEmpty, URL(string: full) != nil else { return nil }
        return full
    }

    /// The address with the device's IP replaced. One the user pointed at a
    /// name or another host stays as it is.
    static func moved(_ address: String, from old: String, to new: String) -> String {
        guard var parts = URLComponents(string: address), parts.host == old else { return address }
        parts.host = new
        return parts.string ?? address
    }
}

/// The colours an icon can have. Mirrors the Kotlin `WebLinkColor`.
nonisolated enum WebLinkColor: String, CaseIterable, Identifiable, Sendable {
    case red, orange, yellow, green, teal, blue, indigo, purple, pink, brown, gray

    var id: String { rawValue }

    var title: String {
        switch self {
        case .red: String(localized: "Red")
        case .orange: String(localized: "Orange")
        case .yellow: String(localized: "Yellow")
        case .green: String(localized: "Green")
        case .teal: String(localized: "Teal")
        case .blue: String(localized: "Blue")
        case .indigo: String(localized: "Indigo")
        case .purple: String(localized: "Purple")
        case .pink: String(localized: "Pink")
        case .brown: String(localized: "Brown")
        case .gray: String(localized: "Gray")
        }
    }
}

/// The network a scan walked, as the links remember it.
nonisolated struct WebLinkNetwork: Equatable, Sendable {
    /// The key to store, and every key the same network may have been stored under before.
    var key: String?
    var keys: [String]
    var label: String?

    func includes(_ link: WebLink) -> Bool {
        guard let network = link.network else { return true }
        return network == key || keys.contains(network)
    }
}

/// The links of one network.
nonisolated struct WebLinkGroup: Identifiable, Equatable, Sendable {
    var id: String
    var label: String?
    var links: [WebLink]
}

/// The kept web pages of devices. A router hands out addresses anew, so a link
/// follows its device by the fingerprints `KnownHosts` uses. Persisted across
/// launches, on this device only. Mirrors WebLinks.kt.
@Observable
final class WebLinks {
    private(set) var links: [WebLink]
    private let defaults: UserDefaults
    private let storageKey = "webLinks"

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        if let data = defaults.data(forKey: storageKey),
           let decoded = try? JSONDecoder().decode([WebLink].self, from: data) {
            links = decoded
        } else {
            links = []
        }
    }

    /// The kept devices by name, each once.
    var titles: [String] {
        var seen: Set<String> = []
        return links.map(\.title).filter { seen.insert($0).inserted }
    }

    /// The links by network, the one the phone is on first. A network is told by
    /// its key: what it is called depends on whether the Wi-Fi name was readable.
    func groups(current: WebLinkNetwork) -> [WebLinkGroup] {
        var groups: [WebLinkGroup] = []
        for link in links {
            let isCurrent = link.network != nil && current.includes(link)
            let id = isCurrent ? current.key ?? "" : link.network ?? ""
            if let index = groups.firstIndex(where: { $0.id == id }) {
                groups[index].links.append(link)
                groups[index].label = Self.better(groups[index].label, link.networkLabel)
            } else {
                groups.append(WebLinkGroup(id: id, label: link.networkLabel, links: [link]))
            }
        }
        func isCurrent(_ group: WebLinkGroup) -> Bool {
            (current.key != nil && group.id == current.key) || (current.label != nil && group.label == current.label)
        }
        return groups.filter(isCurrent) + groups.filter { !isCurrent($0) }
    }

    /// A Wi-Fi name rather than "192.168.1.0/24"; the newer of two names.
    private static func better(_ old: String?, _ new: String?) -> String? {
        guard let new else { return old }
        guard let old else { return new }
        return isSubnet(new) ? old : new
    }

    private static func isSubnet(_ label: String) -> Bool {
        label.wholeMatch(of: /\d+\.\d+\.\d+\.\d+\/\d+/) != nil
    }

    /// The link made from this port of this host, if there is one.
    func link(for device: LANDevice, port: Int, network: WebLinkNetwork) -> WebLink? {
        links.first {
            $0.port == port && $0.ip == device.ipAddress && network.includes($0) && !Self.conflicts($0, device)
        }
    }

    /// Keeps the web page on this port, or drops it when it is already kept.
    func toggle(_ device: LANDevice, port: Int, network: WebLinkNetwork) {
        if let existing = link(for: device, port: port, network: network) {
            remove(existing.id)
            return
        }
        guard let url = device.webAddress(port) else { return }
        links.append(WebLink(
            deviceName: device.displayName, address: url.absoluteString, ip: device.ipAddress, port: port,
            prints: Self.prints(of: device), network: network.key, networkLabel: network.label, symbol: device.deviceIcon,
            hints: Self.hints(of: device, port: port)
        ))
        persist()
    }

    /// A blank name, no icon and no colour go back to the usual; a bad address changes nothing.
    func edit(_ id: UUID, name: String, address: String, icon: String?, color: WebLinkColor?) {
        guard let index = links.firstIndex(where: { $0.id == id }), let address = WebLink.normalized(address) else { return }
        let name = name.trimmingCharacters(in: .whitespacesAndNewlines)
        links[index].name = name.isEmpty ? nil : name
        links[index].address = address
        links[index].customIcon = icon
        links[index].color = color?.rawValue
        persist()
    }

    /// Puts the links with these ids in this order, in the places they hold
    /// now: the links of other networks in between stay where they are.
    func reorder(_ ids: [UUID]) {
        let places = links.indices.filter { ids.contains(links[$0].id) }
        let moved = ids.compactMap { id in links.first { $0.id == id } }
        guard places.count == moved.count else { return }
        for (place, link) in zip(places, moved) { links[place] = link }
        persist()
    }

    func remove(_ id: UUID) {
        links.removeAll { $0.id == id }
        persist()
    }

    /// Finds the kept devices among those a scan of their network saw, and
    /// takes their new addresses and names.
    func follow(_ devices: [LANDevice], network: WebLinkNetwork) {
        guard !links.isEmpty, !devices.isEmpty else { return }
        let before = links
        for index in links.indices where network.includes(links[index]) {
            guard let home = home(of: before[index], among: devices, links: before, network: network) else { continue }
            var link = links[index]
            if home.ipAddress != link.ip {
                if link.deviceName == link.ip { link.deviceName = home.ipAddress }
                link.address = WebLink.moved(link.address, from: link.ip, to: home.ipAddress)
                link.ip = home.ipAddress
            }
            if home.displayName != home.ipAddress { link.deviceName = home.displayName }
            link.symbol = home.deviceIcon
            let hints = Self.hints(of: home, port: link.port)
            if !hints.isEmpty { link.hints = hints }
            link.network = network.key ?? link.network
            link.networkLabel = Self.better(link.networkLabel, network.label)
            let fresh = Self.prints(of: home)
            link.prints += fresh.filter { !link.prints.contains($0) }
            links[index] = link
        }
        if links != before { persist() }
    }

    /// The device a link is about. Another host may have taken its address,
    /// and two hosts may share a name: then it stays where it is.
    private func home(of link: WebLink, among devices: [LANDevice], links: [WebLink], network: WebLinkNetwork) -> LANDevice? {
        let here = devices.first { $0.ipAddress == link.ip }
        if link.prints.isEmpty { return here }
        let same = devices.filter { Self.recognizes(link, $0) }
        if let here, same.contains(where: { $0 === here }) { return here }
        guard same.count == 1, let found = same.first else { return nil }
        // A device with a link of its own is not this one under a new address.
        let taken = links.contains {
            $0.ip == found.ipAddress && network.includes($0) && ($0.prints.isEmpty || Self.recognizes($0, found))
        }
        return taken ? nil : found
    }

    private static func prints(of device: LANDevice) -> [String] {
        device.fingerprints.filter { !$0.hasPrefix("ip:") }
    }

    /// The page's own title first, then what is known of the device.
    private static func hints(of device: LANDevice, port: Int) -> [String] {
        let banner = device.profile.banners.first { $0.port == port }
        let upnp = device.profile.upnp
        return [banner?.title, banner?.realm, upnp?.friendlyName, upnp?.model, device.identity.model,
                device.identity.manufacturer ?? device.manufacturer]
            .compactMap { $0 }.filter { !$0.isEmpty }
    }

    private static func recognizes(_ link: WebLink, _ device: LANDevice) -> Bool {
        !conflicts(link, device) && prints(of: device).contains { link.prints.contains($0) }
    }

    /// Both hardware addresses are known and differ: not the same device.
    private static func conflicts(_ link: WebLink, _ device: LANDevice) -> Bool {
        guard let mac = device.macAddress else { return false }
        let kept = link.prints.filter { $0.hasPrefix("mac:") }
        return !kept.isEmpty && !kept.contains("mac:\(mac.description)")
    }

    private func persist() {
        guard let data = try? JSONEncoder().encode(links) else { return }
        defaults.set(data, forKey: storageKey)
    }
}

extension LANScanner {
    var webLinkNetwork: WebLinkNetwork {
        WebLinkNetwork(key: networkKey, keys: networkKeys, label: networkLabel)
    }
}

extension LANDevice {
    /// The browser address of a port that speaks HTTP: the banner grab says
    /// so, or the port is one web servers commonly use.
    func webAddress(_ number: Int) -> URL? {
        let https: Bool
        if let banner = profile.banners.first(where: { $0.port == number }) {
            https = banner.secure
        } else if LANProbes.secureWeb.contains(number) {
            https = true
        } else if LANProbes.plainWeb.contains(number) {
            https = false
        } else {
            return nil
        }
        let scheme = https ? "https" : "http"
        let isDefault = (https && number == 443) || (!https && number == 80)
        return URL(string: isDefault ? "\(scheme)://\(ipAddress)" : "\(scheme)://\(ipAddress):\(number)")
    }
}
