import Foundation

/// What a card's "More detail" or "Deep analysis" asks the web: a text query and, for a device, a picture query.
nonisolated struct WebLookup: Equatable, Sendable {
    var query: String
    var imageQuery: String?
}

/// The web queries for a summary card. Only what names a product goes out — its maker and model, a link's domain —
/// never an address, a MAC, a network name or a name a person gave their device. Mirrors CardWeb.kt.
@MainActor
enum CardWeb {
    /// The subjects a web search can add something to: what a device is and what is known about it, or a site.
    static func lookup(_ subject: CardSubject, action: CardAction, sources: AssistantSources, locale: Locale = .current) -> WebLookup? {
        guard action == .details || action == .deep else { return nil }
        let russian = locale.language.languageCode?.identifier == "ru"
        switch subject {
        case .host(let ip):
            return sources.lan.devices[ip].flatMap { device(host($0), action: action, russian: russian) }
        case .bluetooth(let id):
            guard let uuid = UUID(uuidString: id), let ble = sources.ble.devices[uuid] else { return nil }
            return device(product(maker: ble.companyName, name: personal(ble.localName ?? ble.peripheralName)), action: action, russian: russian)
        case .wifi(let bssid):
            guard let address = BSSID(string: bssid), let network = sources.store.networks[address] else { return nil }
            // The access point's own WPS name («Keenetic Giga») or its maker; never the network's name.
            let info = network.vendorInfo
            let maker = info.flatMap { $0.manufacturer.isEmpty ? nil : $0.manufacturer }
                ?? (address.isLocallyAdministered ? nil : OUIDatabase.lookup(bytes: address.bytes))
            let name = info.flatMap { $0.deviceName.isEmpty ? nil : $0.deviceName }
            return device(product(maker: maker, name: name), action: action, russian: russian, router: true)
        case .link(let text):
            return linkLookup(text, russian: russian)
        case .lan, .traffic, .capture, .internet:
            return nil
        }
    }

    /// A link's domain and the words that find complaints about it; nothing else of the link goes out.
    nonisolated static func linkLookup(_ text: String, russian: Bool) -> WebLookup? {
        guard let link = LinkInspector.inspect(text) else { return nil }
        var host = link.asciiHost ?? link.host
        if host.hasPrefix("www.") { host.removeFirst(4) }
        guard !host.trimmingCharacters(in: .whitespaces).isEmpty else { return nil }
        return WebLookup(query: russian ? "\(host) отзывы мошенничество" : "\(host) scam reviews", imageQuery: nil)
    }

    nonisolated static let miioUnknown = "Mi Home miIO"

    /// A maker and a product name, the parts that identify a device; `nil` when there is neither.
    nonisolated struct Product: Equatable, Sendable {
        var maker: String?
        var name: String?
    }

    nonisolated static func product(maker: String?, name: String?) -> Product? {
        let cleanMaker = maker?.trimmingCharacters(in: .whitespaces).nilIfEmpty
        // «UE55U8092FUXXH AllShare1.0»: a protocol and its version are not part of the model.
        let words = (name ?? "").split(whereSeparator: \.isWhitespace).map(String.init)
            .filter { $0.wholeMatch(of: /(?i)\p{L}+[-_]?v?\d+(?:\.\d+)+/) == nil }
        var cleanName = words.joined(separator: " ").trimmingCharacters(in: .whitespaces).nilIfEmpty
        if let named = cleanName, let cleanMaker, named.caseInsensitiveCompare(cleanMaker) == .orderedSame { cleanName = nil }
        guard cleanMaker != nil || cleanName != nil else { return nil }
        return Product(maker: cleanMaker, name: cleanName)
    }

    /// A LAN host by what identified it: UPnP model, the mDNS model id, the recognized model, the maker.
    private static func host(_ device: LANDevice) -> Product? {
        guard !device.isLocalDevice else { return nil }
        let identity = device.identity
        let maker = identity.manufacturer ?? device.manufacturer ?? device.profile.upnp?.manufacturer
        let name: String?
        if let miio = device.services.first(where: { $0.type.lowercased().contains("miio") }) {
            name = miio.model ?? miioUnknown
        } else {
            name = device.profile.upnp?.model
                ?? device.services.compactMap { service in service.model.flatMap { !$0.contains(",") || $0.count < 30 ? $0 : nil } }.first
                ?? identity.model
        }
        return product(maker: maker, name: name.flatMap { LANDevice.isOpaqueIdentifier($0) ? nil : $0 })
    }

    /// A Bluetooth name that a person gave the device («Alex's AirPods», «iPhone Анны») is not sent; a product name is.
    nonisolated static func personal(_ name: String?) -> String? {
        guard let text = name?.trimmingCharacters(in: .whitespaces), text.count >= 3 else { return nil }
        if text.contains(/(?i)['’]s\b/) { return nil }
        if LANDevice.isOpaqueIdentifier(text) { return nil }
        // «iPhone Анны», «MacBook (Alex)»: a device kind with someone's name after it.
        let lastWord = text.split(separator: " ").last.map(String.init) ?? text
        if text.contains(/(?i)^(?:iphone|ipad|macbook|galaxy|pixel)\b.*(?:\(|—|-|\s)\p{Lu}\p{Ll}+\)?$/),
           !lastWord.contains(/(?i)\b(?:pro|max|plus|ultra|mini|air|fold|flip|watch|buds|tab)\b/) {
            return nil
        }
        return text
    }

    nonisolated static func device(_ product: Product?, action: CardAction, russian: Bool, router: Bool = false) -> WebLookup? {
        guard let product else { return nil }
        let name = [product.maker, product.name].compactMap { $0 }.joined(separator: " ")
        let kind = router && product.name == nil ? (russian ? " роутер" : " router") : ""
        let topic = action == .deep ? (russian ? " уязвимости прошивка" : " vulnerabilities firmware")
                                    : (russian ? " характеристики безопасность" : " specs security")
        // A maker alone shows its logo, not the device: a picture only when the model is known.
        let picture = product.name != nil && product.name != miioUnknown ? name : nil
        return WebLookup(query: name + kind + topic, imageQuery: picture)
    }
}

private extension String {
    var nilIfEmpty: String? { isEmpty ? nil : self }
}
