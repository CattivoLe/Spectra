import Foundation

/// One rule in the router's port-forwarding table: a port on the public
/// address that leads to a host on this network.
nonisolated struct PortMapping: Equatable, Codable, Sendable, Identifiable {
    var externalPort: Int
    var internalPort: Int
    var internalClient: String
    /// "TCP" or "UDP".
    var proto: String
    var description: String?
    var enabled: Bool
    /// Seconds left on the rule; 0 means it stays until removed.
    var leaseSeconds: Int?
    /// Only this remote address may use the rule, when set.
    var remoteHost: String?

    var id: String { "\(proto):\(externalPort):\(internalClient):\(internalPort)" }
}

/// What the router said when asked for its forwardings over UPnP.
nonisolated struct PortForwarding: Equatable, Codable, Sendable {
    var checkedAt: Date
    /// The router answered UPnP and exposed a WAN connection service.
    var supported: Bool
    var externalIP: String?
    var mappings: [PortMapping]

    /// Rules that are on and lead to a host on the network, grouped by host.
    var byHost: [(host: String, mappings: [PortMapping])] {
        let active = mappings.filter(\.enabled)
        var order: [String] = []
        var groups: [String: [PortMapping]] = [:]
        for mapping in active {
            if groups[mapping.internalClient] == nil { order.append(mapping.internalClient) }
            groups[mapping.internalClient, default: []].append(mapping)
        }
        return order.map { ($0, groups[$0] ?? []) }
    }
}

// MARK: - Protocol

nonisolated extension LANIdentity {
    /// A WAN connection service found in a gateway description: the SOAP
    /// service type and where to send actions.
    struct IGDService: Equatable {
        var serviceType: String
        var controlURL: String
    }

    /// M-SEARCH for a gateway only, so a router that answers ssdp:all with a
    /// dozen replies gives the one that matters.
    static func ssdpGatewaySearch(host: String) -> Data {
        Data(("M-SEARCH * HTTP/1.1\r\nHOST: \(host):1900\r\nMAN: \"ssdp:discover\"\r\nMX: 1\r\nST: urn:schemas-upnp-org:device:InternetGatewayDevice:1\r\n\r\n").utf8)
    }

    /// WANIPConnection and WANPPPConnection services in a device description,
    /// in document order. The first is normally the live one.
    static func igdServices(in xml: String) -> [IGDService] {
        var found: [IGDService] = []
        for block in xml.components(separatedBy: "<service>").dropFirst() {
            let service = block.components(separatedBy: "</service>").first ?? block
            guard let type = tag("serviceType", in: service),
                  type.contains("WANIPConnection") || type.contains("WANPPPConnection"),
                  let control = tag("controlURL", in: service) else { continue }
            found.append(IGDService(serviceType: type, controlURL: control))
        }
        return found
    }

    /// The control URL made absolute against the description's location, or
    /// the document's URLBase when it names one.
    static func resolveControlURL(_ control: String, location: String, xml: String) -> URL? {
        if let absolute = URL(string: control), absolute.scheme != nil { return absolute }
        let base = tag("URLBase", in: xml).flatMap(URL.init(string:)) ?? URL(string: location)
        guard let base else { return nil }
        return URL(string: control, relativeTo: base)?.absoluteURL
    }

    /// A SOAP action for a WAN connection service, as the bytes to send.
    static func soapRequest(url: URL, serviceType: String, action: String, arguments: [(String, String)] = []) -> Data {
        let body = """
        <?xml version="1.0"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body><u:\(action) xmlns:u="\(serviceType)">\(arguments.map { "<\($0.0)>\($0.1)</\($0.0)>" }.joined())</u:\(action)></s:Body></s:Envelope>
        """
        let host = url.host ?? ""
        let port = url.port.map { ":\($0)" } ?? ""
        let path = url.path.isEmpty ? "/" : url.path + (url.query.map { "?\($0)" } ?? "")
        let head = "POST \(path) HTTP/1.1\r\nHost: \(host)\(port)\r\nUser-Agent: Spectra\r\nContent-Type: text/xml; charset=\"utf-8\"\r\nSOAPAction: \"\(serviceType)#\(action)\"\r\nContent-Length: \(body.utf8.count)\r\nConnection: close\r\n\r\n"
        return Data((head + body).utf8)
    }

    /// The HTTP status of a SOAP reply and its UPnP error code, when it has one.
    static func soapOutcome(_ data: Data) -> (status: Int?, errorCode: Int?, body: String)? {
        guard let text = String(data: data, encoding: .utf8) ?? String(data: data, encoding: .isoLatin1) else { return nil }
        let message = HeaderText.parse(text)
        let parts = message.start.split(separator: " ")
        let status = parts.count > 1 ? Int(parts[1]) : nil
        let error = tag("errorCode", in: message.body).flatMap { Int($0) }
        return (status, error, message.body)
    }

    /// One GetGenericPortMappingEntry reply, or nil when it is not one.
    static func parsePortMapping(_ body: String) -> PortMapping? {
        guard let external = tag("NewExternalPort", in: body).flatMap({ Int($0) }),
              let client = tag("NewInternalClient", in: body) else { return nil }
        return PortMapping(
            externalPort: external,
            internalPort: tag("NewInternalPort", in: body).flatMap { Int($0) } ?? external,
            internalClient: client,
            proto: tag("NewProtocol", in: body)?.uppercased() ?? "TCP",
            description: tag("NewPortMappingDescription", in: body),
            enabled: tag("NewEnabled", in: body).map { $0 != "0" && $0.lowercased() != "false" } ?? true,
            leaseSeconds: tag("NewLeaseDuration", in: body).flatMap { Int($0) },
            remoteHost: tag("NewRemoteHost", in: body)
        )
    }

    /// UPnP's "no such entry": the end of the table.
    static let soapIndexInvalid = 713
}

// MARK: - Probe

nonisolated extension LANProbes {
    /// Asks the gateway for its port-forwarding table over UPnP. Blocking;
    /// call off the main thread. Nil when the router does not speak UPnP at all.
    static func portForwarding(gateway ip: String, now: Date = .now) -> PortForwarding? {
        let replies = exchangeUDP(ip: ip, port: 1900, payload: LANIdentity.ssdpGatewaySearch(host: ip), timeout: .milliseconds(1200))
            + exchangeUDP(ip: ip, port: 1900, payload: LANIdentity.ssdpSearch(host: ip), timeout: .milliseconds(800))
        let locations = replies.compactMap(LANIdentity.parseSSDP).compactMap(\.location)
            .filter { LANIdentity.locationIsOnHost($0, ip: ip) }
        guard !locations.isEmpty else { return nil }

        var seen: Set<String> = []
        for location in locations where seen.insert(location).inserted {
            guard let xml = fetchText(location, ip: ip) else { continue }
            for service in LANIdentity.igdServices(in: xml) {
                guard let control = LANIdentity.resolveControlURL(service.controlURL, location: location, xml: xml),
                      LANIdentity.locationIsOnHost(control.absoluteString, ip: ip) else { continue }
                if let table = readMappings(control: control, serviceType: service.serviceType, ip: ip, now: now) {
                    return table
                }
            }
        }
        return PortForwarding(checkedAt: now, supported: false, externalIP: nil, mappings: [])
    }

    private static func readMappings(control: URL, serviceType: String, ip: String, now: Date) -> PortForwarding? {
        let port = UInt16(control.port ?? 80)
        func call(_ action: String, _ arguments: [(String, String)] = []) -> (status: Int?, errorCode: Int?, body: String)? {
            let request = LANIdentity.soapRequest(url: control, serviceType: serviceType, action: action, arguments: arguments)
            return exchangeTCP(ip: ip, port: port, payload: request, timeout: .seconds(2), limit: 65_536)
                .flatMap(LANIdentity.soapOutcome)
        }

        // The external address doubles as the check that the service is live.
        guard let first = call("GetExternalIPAddress") else { return nil }
        let externalIP = LANIdentity.tag("NewExternalIPAddress", in: first.body)
        guard first.status == 200 || first.errorCode != nil else { return nil }

        var mappings: [PortMapping] = []
        for index in 0..<256 {
            guard let reply = call("GetGenericPortMappingEntry", [("NewPortMappingIndex", String(index))]) else { break }
            if reply.errorCode == LANIdentity.soapIndexInvalid { break }
            guard reply.status == 200, let mapping = LANIdentity.parsePortMapping(reply.body) else { break }
            mappings.append(mapping)
        }
        return PortForwarding(checkedAt: now, supported: true, externalIP: externalIP.flatMap { $0.isEmpty || $0 == "0.0.0.0" ? nil : $0 }, mappings: mappings)
    }

    /// GET of a description document on the host, body only.
    private static func fetchText(_ location: String, ip: String) -> String? {
        guard let url = URL(string: location), let host = url.host, host == ip || host == "[\(ip)]", url.scheme == "http" else { return nil }
        let port = UInt16(url.port ?? 80)
        let path = url.path.isEmpty ? "/" : url.path + (url.query.map { "?\($0)" } ?? "")
        let request = Data("GET \(path) HTTP/1.0\r\nHost: \(ip)\r\nUser-Agent: Spectra\r\nConnection: close\r\n\r\n".utf8)
        guard let data = exchangeTCP(ip: ip, port: port, payload: request, timeout: .seconds(2), limit: 131_072),
              let text = String(data: data, encoding: .utf8) ?? String(data: data, encoding: .isoLatin1) else { return nil }
        return HeaderText.parse(text).body
    }
}
