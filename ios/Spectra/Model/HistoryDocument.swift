import CryptoKit
import Foundation

/// The history file Spectra shares between iPhone and Android. It follows the
/// Android layout (`HistoryStore.encodeDocument` and `HistoryCodec`) key for
/// key: `{"from": …, "entries": […]}`, times in Unix milliseconds, addresses
/// as text, SSIDs and advertisement bytes in hex. Keep the two in step.
enum HistoryDocument {
    private typealias Object = [String: Any]

    static func file(_ entries: [HistoryEntry], from: String, now: Date = .now) -> Exporter.File {
        let root: Object = ["from": from, "entries": entries.map(entry)]
        let data = (try? JSONSerialization.data(withJSONObject: root, options: [.sortedKeys])) ?? Data()
        return Exporter.File(name: "spectra-\(fileStamp.string(from: now)).json", contents: String(decoding: data, as: UTF8.self))
    }

    /// The sender's name and its sessions, or nil when the data is not a
    /// history. A bare array of sessions, or one session, is read too.
    static func decode(_ data: Data) -> (from: String?, entries: [HistoryEntry])? {
        guard let root = try? JSONSerialization.jsonObject(with: data) else { return nil }
        let from: String?
        let items: [Any]
        if let array = root as? [Any] {
            from = nil
            items = array
        } else if let object = root as? Object, let list = object["entries"] as? [Any] {
            from = text(object["from"])
            items = list
        } else if let object = root as? Object, object["id"] != nil {
            from = nil
            items = [object]
        } else {
            return nil
        }
        let decoded = items.compactMap { ($0 as? Object).flatMap(entry) }
        return decoded.isEmpty ? nil : (from, decoded)
    }

    // MARK: Session

    private static func entry(_ entry: HistoryEntry) -> Object {
        var object: Object = [
            "id": entry.id.uuidString.lowercased(),
            "kind": entry.kind.rawValue,
            "savedAt": millis(entry.savedAt),
            "source": entry.source,
            "lines": entry.lines.map { ["title": $0.title, "detail": $0.detail, "trailing": $0.trailing] },
        ]
        if !entry.networks.isEmpty { object["networks"] = entry.networks.map(network) }
        if !entry.probes.isEmpty { object["probes"] = entry.probes.map(probe) }
        if !entry.deauths.isEmpty { object["deauths"] = entry.deauths.map(deauth) }
        if !entry.lan.isEmpty { object["lan"] = entry.lan.map(lan) }
        if !entry.bluetooth.isEmpty { object["bluetooth"] = entry.bluetooth.map(ble) }
        let connections = entry.connections.compactMap(connection)
        if !connections.isEmpty { object["connections"] = connections }
        object["captureId"] = entry.captureID.map { Int($0) }
        object["placeKey"] = entry.placeKey
        object["placeLabel"] = entry.placeLabel
        if let latitude = entry.latitude, let longitude = entry.longitude {
            object["latitude"] = latitude
            object["longitude"] = longitude
        }
        object["importedFrom"] = entry.importedFrom
        return object
    }

    private static func entry(_ object: Object) -> HistoryEntry? {
        guard let id = text(object["id"]),
              let kind = text(object["kind"]).flatMap(HistoryEntry.Kind.init(rawValue:)),
              let savedAt = date(object["savedAt"]) else { return nil }
        var entry = HistoryEntry(
            kind: kind,
            savedAt: savedAt,
            source: object["source"] as? String ?? "",
            captureID: int(object["captureId"]).flatMap { UInt32(exactly: $0) },
            placeKey: object["placeKey"] as? String,
            placeLabel: text(object["placeLabel"]),
            lines: objects(object["lines"]).map {
                HistoryLine(title: $0["title"] as? String ?? "", detail: $0["detail"] as? String ?? "", trailing: $0["trailing"] as? String ?? "")
            },
            networks: objects(object["networks"]).compactMap(network),
            probes: objects(object["probes"]).compactMap(probe),
            deauths: objects(object["deauths"]).compactMap(deauth),
            lan: objects(object["lan"]).compactMap(lan),
            bluetooth: objects(object["bluetooth"]).compactMap(ble),
            connections: objects(object["connections"]).compactMap(connection),
            latitude: double(object["latitude"]),
            longitude: double(object["longitude"])
        )
        entry.id = UUID(uuidString: id) ?? stableID(id)
        entry.importedFrom = text(object["importedFrom"])
        return entry
    }

    // MARK: Wi-Fi

    private static func network(_ network: TrackedNetwork) -> Object {
        var object: Object = [
            "reading": reading(network.latest),
            "firstSeen": millis(network.firstSeen),
            "lastSeen": millis(network.lastSeen),
            "samples": network.samples.map { [millis($0.date), $0.rssi] },
            "missedScans": network.missedScans,
            "clients": network.clients.map(client),
            "source": "SENSOR",
        ]
        object["revealedSsid"] = network.revealedSSID
        object["vendorName"] = network.vendorInfo?.deviceName
        object["vendorMaker"] = network.vendorInfo?.manufacturer
        if let traffic = network.traffic {
            object["trafficBytes"] = Int(traffic.bytes)
            object["trafficPackets"] = traffic.packets
        }
        return object
    }

    private static func network(_ object: Object) -> TrackedNetwork? {
        guard let latest = (object["reading"] as? Object).flatMap(reading) else { return nil }
        let lastSeen = date(object["lastSeen"]) ?? .distantPast
        let vendorName = text(object["vendorName"])
        let vendorMaker = text(object["vendorMaker"])
        return TrackedNetwork(
            latest: latest,
            firstSeen: date(object["firstSeen"]) ?? lastSeen,
            lastSeen: lastSeen,
            samples: arrays(object["samples"]).compactMap { pair in
                guard pair.count == 2, let when = date(pair[0]), let rssi = int(pair[1]) else { return nil }
                return TrackedNetwork.Sample(date: when, rssi: rssi)
            },
            missedScans: int(object["missedScans"]) ?? 0,
            clients: objects(object["clients"]).compactMap(client),
            revealedSSID: text(object["revealedSsid"]),
            vendorInfo: vendorName == nil && vendorMaker == nil ? nil : VendorDeviceInfo(deviceName: vendorName ?? "", manufacturer: vendorMaker ?? ""),
            traffic: int(object["trafficBytes"]).map { TrafficSnapshot(bytes: UInt32(clamping: $0), packets: int(object["trafficPackets"]) ?? 0) }
        )
    }

    private static func reading(_ reading: AccessPointReading) -> Object {
        var object: Object = [
            "bssid": reading.bssid.description,
            "rssi": reading.rssi,
            "channel": reading.channel,
            "secondary": Int(reading.secondaryChannel.rawValue),
            "auth": Int(reading.auth.rawValue),
            "pairwise": Int(reading.pairwiseCipher.rawValue),
            "group": Int(reading.groupCipher.rawValue),
            "capabilities": Int(reading.capabilities.rawValue),
            "ssid": hex(reading.ssidBytes),
            "band": reading.band == .fiveGHz ? "FIVE" : "TWO_FOUR",
            "visibility": reading.ssid == nil ? "HIDDEN" : "NAMED",
        ]
        object["country"] = reading.countryCode
        return object
    }

    private static func reading(_ object: Object) -> AccessPointReading? {
        // Channel numbers here mean 2.4 or 5 GHz; a 6 GHz network would land on the wrong band.
        guard text(object["band"]) != "SIX",
              let bssid = text(object["bssid"]).flatMap(BSSID.init(string:)) else { return nil }
        return AccessPointReading(
            bssid: bssid,
            rssi: int(object["rssi"]) ?? -100,
            channel: int(object["channel"]) ?? 0,
            secondaryChannel: SecondaryChannel(rawValue: UInt8(clamping: int(object["secondary"]) ?? 0)) ?? SecondaryChannel.none,
            auth: AuthMode(rawValue: UInt8(clamping: int(object["auth"]) ?? 0)),
            pairwiseCipher: Cipher(rawValue: UInt8(clamping: int(object["pairwise"]) ?? 0)),
            groupCipher: Cipher(rawValue: UInt8(clamping: int(object["group"]) ?? 0)),
            capabilities: Capabilities(rawValue: UInt8(truncatingIfNeeded: int(object["capabilities"]) ?? 0)),
            countryCode: text(object["country"]),
            ssidBytes: unhex(object["ssid"] as? String ?? "")
        )
    }

    private static func client(_ client: WiFiClient) -> Object {
        var object: Object = ["mac": client.mac.description, "rssi": client.rssi, "associated": client.isAssociated]
        object["ap"] = client.apBSSID?.description
        return object
    }

    private static func client(_ object: Object) -> WiFiClient? {
        guard let mac = text(object["mac"]).flatMap(BSSID.init(string:)) else { return nil }
        return WiFiClient(
            mac: mac,
            apBSSID: text(object["ap"]).flatMap(BSSID.init(string:)),
            rssi: int(object["rssi"]) ?? -100,
            isAssociated: bool(object["associated"])
        )
    }

    private static func probe(_ device: ProbingDevice) -> Object {
        ["mac": device.mac.description, "rssi": device.rssi, "ssids": device.ssids, "channel": device.channel]
    }

    private static func probe(_ object: Object) -> ProbingDevice? {
        guard let mac = text(object["mac"]).flatMap(BSSID.init(string:)) else { return nil }
        return ProbingDevice(
            mac: mac,
            rssi: int(object["rssi"]) ?? -100,
            ssids: object["ssids"] as? [String] ?? [],
            channel: int(object["channel"]) ?? 0
        )
    }

    private static func deauth(_ event: DeauthEvent) -> Object {
        [
            "source": event.source.description, "target": event.target.description, "bssid": event.bssid.description,
            "reason": Int(event.reason), "rssi": event.rssi, "channel": event.channel, "count": event.count,
        ]
    }

    private static func deauth(_ object: Object) -> DeauthEvent? {
        guard let source = text(object["source"]).flatMap(BSSID.init(string:)),
              let target = text(object["target"]).flatMap(BSSID.init(string:)),
              let bssid = text(object["bssid"]).flatMap(BSSID.init(string:)) else { return nil }
        return DeauthEvent(
            source: source, target: target, bssid: bssid,
            reason: UInt16(clamping: int(object["reason"]) ?? 0),
            rssi: int(object["rssi"]) ?? -100,
            channel: int(object["channel"]) ?? 0,
            count: int(object["count"]) ?? 1
        )
    }

    // MARK: LAN

    private static func lan(_ saved: SavedLAN) -> Object {
        var object: Object = [
            "ip": saved.ipAddress,
            "hostnames": saved.hostnames,
            "ports": saved.openPorts.map { [$0.number, $0.service] as [Any] },
            "services": saved.services.map { service -> Object in
                var item: Object = ["type": service.type, "name": service.name]
                item["model"] = service.model
                return item
            },
            "portsScanned": saved.portsScanned,
            "gateway": saved.isGateway,
            "local": saved.isLocalDevice,
            "new": saved.isNew,
            "profile": profile(saved.profile),
            "firstSeen": millis(saved.firstSeen),
            "lastSeen": millis(saved.lastSeen),
        ]
        object["mac"] = saved.macAddress?.description
        return object
    }

    private static func lan(_ object: Object) -> SavedLAN? {
        guard let ip = text(object["ip"]) else { return nil }
        let lastSeen = date(object["lastSeen"]) ?? .distantPast
        return SavedLAN(LANDevice(
            ipAddress: ip,
            macAddress: text(object["mac"]).flatMap(BSSID.init(string:)).map { MACAddress(bytes: $0.bytes) },
            hostnames: object["hostnames"] as? [String] ?? [],
            openPorts: arrays(object["ports"]).compactMap { pair in
                guard let number = pair.first.flatMap(int) else { return nil }
                return DiscoveredPort(number: number, service: pair.count > 1 ? pair[1] as? String ?? "" : "")
            },
            services: objects(object["services"]).map {
                ServiceAdvertisement(type: $0["type"] as? String ?? "", name: $0["name"] as? String ?? "", model: text($0["model"]))
            },
            portsScanned: bool(object["portsScanned"]),
            isGateway: bool(object["gateway"]),
            isLocalDevice: bool(object["local"]),
            isNew: bool(object["new"]),
            profile: (object["profile"] as? Object).map(profile) ?? LANProfile(),
            firstSeen: date(object["firstSeen"]) ?? lastSeen,
            lastSeen: lastSeen
        ))
    }

    private static func profile(_ profile: LANProfile) -> Object {
        var object: Object = [
            "banners": profile.banners.map { banner -> Object in
                var item: Object = ["port": banner.port, "secure": banner.secure]
                item["status"] = banner.status
                item["server"] = banner.server
                item["poweredBy"] = banner.poweredBy
                item["title"] = banner.title
                item["realm"] = banner.realm
                return item
            },
            "rtsp": profile.rtsp.map { service -> Object in
                var item: Object = ["port": service.port]
                item["server"] = service.server
                item["methods"] = service.methods
                return item
            },
        ]
        if let upnp = profile.upnp {
            var item: Object = [:]
            item["name"] = upnp.friendlyName
            item["maker"] = upnp.manufacturer
            item["model"] = upnp.model
            item["type"] = upnp.deviceType
            item["server"] = upnp.server
            object["upnp"] = item
        }
        if let windows = profile.windows {
            var item: Object = ["shares": windows.shares, "guest": windows.guestSession]
            item["computer"] = windows.computerName
            item["domain"] = windows.domain
            object["windows"] = item
        }
        if let forwarding = profile.portForwarding {
            var item: Object = [
                "checkedAt": millis(forwarding.checkedAt),
                "supported": forwarding.supported,
                "mappings": forwarding.mappings.map(mapping),
            ]
            item["externalIP"] = forwarding.externalIP
            object["forwarding"] = item
        }
        if !profile.inbound.isEmpty {
            object["inbound"] = profile.inbound.map(mapping)
        }
        return object
    }

    private static func mapping(_ mapping: PortMapping) -> Object {
        var item: Object = [
            "external": mapping.externalPort, "internal": mapping.internalPort,
            "client": mapping.internalClient, "proto": mapping.proto, "enabled": mapping.enabled,
        ]
        item["description"] = mapping.description
        item["lease"] = mapping.leaseSeconds
        item["remote"] = mapping.remoteHost
        return item
    }

    private static func mapping(_ object: Object) -> PortMapping? {
        guard let external = int(object["external"]), let client = text(object["client"]) else { return nil }
        return PortMapping(
            externalPort: external, internalPort: int(object["internal"]) ?? external, internalClient: client,
            proto: text(object["proto"]) ?? "TCP", description: text(object["description"]),
            enabled: (object["enabled"] as? NSNumber)?.boolValue ?? true,
            leaseSeconds: int(object["lease"]), remoteHost: text(object["remote"])
        )
    }

    private static func profile(_ object: Object) -> LANProfile {
        LANProfile(
            upnp: (object["upnp"] as? Object).map {
                UPnPIdentity(
                    friendlyName: text($0["name"]), manufacturer: text($0["maker"]), model: text($0["model"]),
                    deviceType: text($0["type"]), server: text($0["server"])
                )
            },
            banners: objects(object["banners"]).map {
                HTTPBanner(
                    port: int($0["port"]) ?? 0, secure: bool($0["secure"]), status: int($0["status"]),
                    server: text($0["server"]), poweredBy: text($0["poweredBy"]), title: text($0["title"]), realm: text($0["realm"])
                )
            },
            rtsp: objects(object["rtsp"]).map {
                RTSPService(port: int($0["port"]) ?? 0, server: text($0["server"]), methods: text($0["methods"]))
            },
            windows: (object["windows"] as? Object).map {
                WindowsHost(
                    computerName: text($0["computer"]), domain: text($0["domain"]),
                    shares: $0["shares"] as? [String] ?? [], guestSession: bool($0["guest"])
                )
            },
            portForwarding: (object["forwarding"] as? Object).map {
                PortForwarding(
                    checkedAt: date($0["checkedAt"]) ?? .distantPast, supported: bool($0["supported"]),
                    externalIP: text($0["externalIP"]), mappings: objects($0["mappings"]).compactMap(mapping)
                )
            },
            inbound: objects(object["inbound"]).compactMap(mapping)
        )
    }

    // MARK: Bluetooth

    /// iOS never sees a hardware address, so the CoreBluetooth identifier stands in for it.
    private static func ble(_ saved: SavedBLE) -> Object {
        var object: Object = [
            "address": saved.id.uuidString,
            "kind": "Unknown",
            "transport": "LE",
            "payload": hex(saved.manufacturerPayload),
            "services": saved.services.map(\.uuid),
            "solicited": saved.solicitedServices.map(\.uuid),
            "serviceData": Dictionary(saved.serviceData.map { ($0.uuid, hex($0.data)) }, uniquingKeysWith: { a, _ in a }),
            "rssi": saved.rssi,
            "samples": saved.samples.map { [millis($0.date), $0.rssi] },
            "packets": saved.packetCount,
            "firstSeen": millis(saved.firstSeen),
            "lastSeen": millis(saved.lastSeen),
        ]
        object["localName"] = saved.localName
        object["peripheralName"] = saved.peripheralName
        object["companyId"] = saved.companyID.map { Int($0) }
        object["txPower"] = saved.txPower
        object["connectable"] = saved.isConnectable
        return object
    }

    /// Rebuilt through the advertisement parser, so names and Apple messages come back as they were read.
    private static func ble(_ object: Object) -> SavedBLE? {
        guard let address = text(object["address"]) else { return nil }
        let firstSeen = date(object["firstSeen"]) ?? .distantPast
        var manufacturer: Data?
        if let company = int(object["companyId"]) {
            manufacturer = Data([UInt8(company & 0xFF), UInt8((company >> 8) & 0xFF)]) + unhex(object["payload"] as? String ?? "")
        }
        let fields = BLEFields(
            localName: text(object["localName"]),
            manufacturerData: manufacturer,
            serviceUUIDs: object["services"] as? [String] ?? [],
            solicitedServiceUUIDs: object["solicited"] as? [String] ?? [],
            serviceData: ((object["serviceData"] as? [String: String]) ?? [:]).mapValues(unhex),
            txPower: int(object["txPower"]),
            isConnectable: (object["connectable"] as? NSNumber)?.boolValue
        )
        let rssi = int(object["rssi"]) ?? -100
        let device = BLEDevice(id: UUID(uuidString: address) ?? stableID("ble:" + address), rssi: rssi, seen: firstSeen)
        // 127 is CoreBluetooth's "no reading", so the parse adds no sample of its own.
        device.absorb(BLEAdvertisement.parse(fields), peripheralName: text(object["peripheralName"]), rssi: 127, at: firstSeen)
        var saved = SavedBLE(device)
        saved.rssi = rssi
        saved.samples = arrays(object["samples"]).compactMap { pair in
            guard pair.count == 2, let when = date(pair[0]), let value = int(pair[1]) else { return nil }
            return SavedBLE.Sample(date: when, rssi: value)
        }
        saved.packetCount = int(object["packets"]) ?? 0
        saved.lastSeen = date(object["lastSeen"]) ?? firstSeen
        return saved
    }

    // MARK: Connection checks

    private static func connection(_ saved: SavedConnection) -> Object? {
        let snapshot = saved.snapshot
        guard let status = snapshot.status else { return nil }
        var object: Object = [
            "checkedAt": millis(snapshot.checkedAt),
            "viaVpn": saved.viaVpn,
            "viaCellular": saved.viaCellular ?? false,
            "status": statusName(status),
            "speedFailed": snapshot.speedFailed,
            "regions": json(snapshot.regions) ?? [],
            "abroad": json(snapshot.abroad) ?? [],
            "abroadNote": snapshot.abroadNote.rawValue.uppercased(),
            "remoteTraces": json(snapshot.remoteTraces) ?? [],
            "traceNote": snapshot.traceNote.rawValue.uppercased(),
        ]
        object["detail"] = snapshot.detail
        object["gateway"] = json(snapshot.gateway)
        object["dns"] = json(snapshot.dns)
        object["web"] = json(snapshot.web)
        object["external"] = json(snapshot.external)
        object["speed"] = json(snapshot.speed)
        object["localTrace"] = json(snapshot.localTrace)
        object["tls"] = json(snapshot.tls)
        object["resolver"] = json(snapshot.resolver)
        object["exposure"] = json(snapshot.exposure)
        object["access"] = json(snapshot.access)
        return object
    }

    private static func connection(_ object: Object) -> SavedConnection? {
        guard let status = text(object["status"]).flatMap(status(named:)) else { return nil }
        var snapshot = LinkSnapshot()
        snapshot.checkedAt = date(object["checkedAt"]) ?? .distantPast
        snapshot.status = status
        snapshot.detail = text(object["detail"])
        snapshot.gateway = value(object["gateway"])
        snapshot.dns = value(object["dns"])
        snapshot.web = value(object["web"])
        snapshot.external = value(object["external"])
        snapshot.speed = value(object["speed"])
        snapshot.speedFailed = bool(object["speedFailed"])
        snapshot.regions = value(object["regions"]) ?? []
        snapshot.abroad = value(object["abroad"]) ?? []
        snapshot.abroadNote = text(object["abroadNote"]).flatMap { RemoteNote(rawValue: $0.lowercased()) } ?? .none
        snapshot.localTrace = value(object["localTrace"])
        snapshot.tls = value(object["tls"])
        snapshot.resolver = value(object["resolver"])
        snapshot.exposure = value(object["exposure"])
        snapshot.access = value(object["access"])
        snapshot.remoteTraces = value(object["remoteTraces"]) ?? []
        snapshot.traceNote = text(object["traceNote"]).flatMap { RemoteNote(rawValue: $0.lowercased()) } ?? .none
        return SavedConnection(viaVpn: bool(object["viaVpn"]), viaCellular: bool(object["viaCellular"]), snapshot: snapshot)
    }

    private static func statusName(_ status: LinkStatus) -> String {
        switch status {
        case .online: "ONLINE"
        case .captive: "CAPTIVE"
        case .lan: "LAN"
        case .offline: "OFFLINE"
        case .noNetwork: "NO_NETWORK"
        }
    }

    private static func status(named name: String) -> LinkStatus? {
        switch name {
        case "ONLINE": .online
        case "CAPTIVE": .captive
        case "LAN": .lan
        case "OFFLINE": .offline
        case "NO_NETWORK": .noNetwork
        default: nil
        }
    }

    /// The probe results keep the same field names on both platforms, so they go through `Codable`.
    private static func json<T: Encodable>(_ value: T?) -> Any? {
        guard let value, let data = try? JSONEncoder().encode(value) else { return nil }
        return try? JSONSerialization.jsonObject(with: data)
    }

    private static func value<T: Decodable>(_ object: Any?) -> T? {
        guard let object, JSONSerialization.isValidJSONObject(object),
              let data = try? JSONSerialization.data(withJSONObject: object) else { return nil }
        return try? JSONDecoder().decode(T.self, from: data)
    }

    // MARK: Helpers

    private static func millis(_ date: Date) -> Int64 { Int64((date.timeIntervalSince1970 * 1000).rounded()) }

    private static func date(_ value: Any?) -> Date? {
        double(value).map { Date(timeIntervalSince1970: $0 / 1000) }
    }

    private static func int(_ value: Any?) -> Int? {
        // `value is Bool` would also reject 0 and 1, since those bridge to
        // Bool; the Core Foundation type tells a real boolean apart.
        guard let number = value as? NSNumber, CFGetTypeID(number) != CFBooleanGetTypeID() else { return nil }
        return number.intValue
    }

    private static func double(_ value: Any?) -> Double? {
        // `value is Bool` would also reject 0 and 1, since those bridge to
        // Bool; the Core Foundation type tells a real boolean apart.
        guard let number = value as? NSNumber, CFGetTypeID(number) != CFBooleanGetTypeID() else { return nil }
        return number.doubleValue
    }

    private static func bool(_ value: Any?) -> Bool { (value as? NSNumber)?.boolValue ?? false }

    private static func text(_ value: Any?) -> String? {
        guard let string = value as? String, !string.isEmpty else { return nil }
        return string
    }

    private static func objects(_ value: Any?) -> [Object] { (value as? [Any])?.compactMap { $0 as? Object } ?? [] }

    private static func arrays(_ value: Any?) -> [[Any]] { (value as? [Any])?.compactMap { $0 as? [Any] } ?? [] }

    private static func hex(_ data: Data) -> String { data.map { String(format: "%02x", $0) }.joined() }

    private static func unhex(_ text: String) -> Data {
        let clean = Array(text.filter(\.isHexDigit))
        guard clean.count % 2 == 0 else { return Data() }
        return Data(stride(from: 0, to: clean.count, by: 2).map { UInt8(String(clean[$0...$0 + 1]), radix: 16) ?? 0 })
    }

    /// The same UUID every time for an id that is not one, so a second import is still a duplicate.
    private static func stableID(_ text: String) -> UUID {
        let b = Array(Insecure.MD5.hash(data: Data(text.utf8)))
        return UUID(uuid: (b[0], b[1], b[2], b[3], b[4], b[5], b[6], b[7], b[8], b[9], b[10], b[11], b[12], b[13], b[14], b[15]))
    }

    private static let fileStamp: DateFormatter = {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = "yyyyMMdd-HHmm"
        return formatter
    }()
}
