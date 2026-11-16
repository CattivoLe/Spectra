import Foundation
import Testing
@testable import Spectra

struct PortForwardingTests {
    private let description = """
    <?xml version="1.0"?><root xmlns="urn:schemas-upnp-org:device-1-0"><URLBase>http://192.168.1.1:49152/</URLBase>
    <device><deviceType>urn:schemas-upnp-org:device:InternetGatewayDevice:1</deviceType><friendlyName>Router</friendlyName>
    <serviceList><service><serviceType>urn:schemas-upnp-org:service:Layer3Forwarding:1</serviceType><controlURL>/l3f</controlURL></service></serviceList>
    <deviceList><device><deviceType>urn:schemas-upnp-org:device:WANDevice:1</deviceType><deviceList><device>
    <deviceType>urn:schemas-upnp-org:device:WANConnectionDevice:1</deviceType><serviceList>
    <service><serviceType>urn:schemas-upnp-org:service:WANIPConnection:1</serviceType><controlURL>/ctl/IPConn</controlURL></service>
    <service><serviceType>urn:schemas-upnp-org:service:WANPPPConnection:1</serviceType><controlURL>http://192.168.1.1:49152/ctl/PPP</controlURL></service>
    </serviceList></device></deviceList></device></deviceList></device></root>
    """

    @Test func findsWANConnectionServicesOnly() {
        let services = LANIdentity.igdServices(in: description)
        #expect(services.map(\.controlURL) == ["/ctl/IPConn", "http://192.168.1.1:49152/ctl/PPP"])
        #expect(services.first?.serviceType.contains("WANIPConnection") == true)
    }

    @Test func controlURLResolvesAgainstURLBaseOrLocation() {
        let location = "http://192.168.1.1:1900/rootDesc.xml"
        let relative = LANIdentity.resolveControlURL("/ctl/IPConn", location: location, xml: description)
        #expect(relative?.absoluteString == "http://192.168.1.1:49152/ctl/IPConn")
        let noBase = LANIdentity.resolveControlURL("/ctl/IPConn", location: location, xml: "<root/>")
        #expect(noBase?.absoluteString == "http://192.168.1.1:1900/ctl/IPConn")
        let absolute = LANIdentity.resolveControlURL("http://192.168.1.1:49152/ctl/PPP", location: location, xml: description)
        #expect(absolute?.absoluteString == "http://192.168.1.1:49152/ctl/PPP")
    }

    @Test func soapRequestCarriesActionAndArguments() throws {
        let url = try #require(URL(string: "http://192.168.1.1:49152/ctl/IPConn"))
        let data = LANIdentity.soapRequest(url: url, serviceType: "urn:schemas-upnp-org:service:WANIPConnection:1", action: "GetGenericPortMappingEntry", arguments: [("NewPortMappingIndex", "3")])
        let text = try #require(String(data: data, encoding: .utf8))
        #expect(text.hasPrefix("POST /ctl/IPConn HTTP/1.1\r\nHost: 192.168.1.1:49152\r\n"))
        #expect(text.contains("SOAPAction: \"urn:schemas-upnp-org:service:WANIPConnection:1#GetGenericPortMappingEntry\""))
        #expect(text.contains("<NewPortMappingIndex>3</NewPortMappingIndex>"))
        let body = text.components(separatedBy: "\r\n\r\n")[1]
        #expect(text.contains("Content-Length: \(body.utf8.count)\r\n"))
    }

    @Test func soapOutcomeReadsStatusAndErrorCode() throws {
        let fault = Data("HTTP/1.1 500 Internal Server Error\r\nContent-Type: text/xml\r\n\r\n<s:Envelope><s:Body><s:Fault><detail><UPnPError><errorCode>713</errorCode><errorDescription>SpecifiedArrayIndexInvalid</errorDescription></UPnPError></detail></s:Fault></s:Body></s:Envelope>".utf8)
        let outcome = try #require(LANIdentity.soapOutcome(fault))
        #expect(outcome.status == 500)
        #expect(outcome.errorCode == LANIdentity.soapIndexInvalid)
    }

    @Test func parsesAMappingEntry() throws {
        let body = """
        <s:Envelope><s:Body><u:GetGenericPortMappingEntryResponse xmlns:u="urn:schemas-upnp-org:service:WANIPConnection:1">
        <NewRemoteHost></NewRemoteHost><NewExternalPort>8080</NewExternalPort><NewProtocol>TCP</NewProtocol>
        <NewInternalPort>80</NewInternalPort><NewInternalClient>192.168.1.60</NewInternalClient><NewEnabled>1</NewEnabled>
        <NewPortMappingDescription>Camera &amp; door</NewPortMappingDescription><NewLeaseDuration>0</NewLeaseDuration>
        </u:GetGenericPortMappingEntryResponse></s:Body></s:Envelope>
        """
        let mapping = try #require(LANIdentity.parsePortMapping(body))
        #expect(mapping.externalPort == 8080)
        #expect(mapping.internalPort == 80)
        #expect(mapping.internalClient == "192.168.1.60")
        #expect(mapping.proto == "TCP")
        #expect(mapping.enabled)
        #expect(mapping.description == "Camera & door")
        #expect(mapping.remoteHost == nil)
        #expect(LANIdentity.parsePortMapping("<s:Envelope><s:Body/></s:Envelope>") == nil)
    }

    @Test func groupsActiveRulesByHost() {
        let table = PortForwarding(checkedAt: .now, supported: true, externalIP: "203.0.113.17", mappings: [
            PortMapping(externalPort: 8080, internalPort: 80, internalClient: "192.168.1.60", proto: "TCP", description: nil, enabled: true, leaseSeconds: 0, remoteHost: nil),
            PortMapping(externalPort: 3389, internalPort: 3389, internalClient: "192.168.1.70", proto: "TCP", description: nil, enabled: true, leaseSeconds: 0, remoteHost: nil),
            PortMapping(externalPort: 554, internalPort: 554, internalClient: "192.168.1.60", proto: "TCP", description: nil, enabled: true, leaseSeconds: 0, remoteHost: nil),
            PortMapping(externalPort: 51413, internalPort: 51413, internalClient: "192.168.1.10", proto: "TCP", description: nil, enabled: false, leaseSeconds: 0, remoteHost: nil),
        ])
        let groups = table.byHost
        #expect(groups.map(\.host) == ["192.168.1.60", "192.168.1.70"])
        #expect(groups[0].mappings.map(\.externalPort) == [8080, 554])
    }

    @Test func exposedHostsAndTheRouterGetFindings() {
        let camera = LANDevice(ipAddress: "192.168.1.60", openPorts: [DiscoveredPort(number: 80, service: "HTTP")], portsScanned: true,
                               profile: LANProfile(inbound: [PortMapping(externalPort: 8080, internalPort: 80, internalClient: "192.168.1.60", proto: "TCP", description: nil, enabled: true, leaseSeconds: 0, remoteHost: nil)]))
        #expect(camera.riskLevel == .high)
        #expect(camera.riskFindings.first?.ports == [80])

        let table = PortForwarding(checkedAt: .now, supported: true, externalIP: nil, mappings: camera.profile.inbound)
        let router = LANDevice(ipAddress: "192.168.1.1", openPorts: [DiscoveredPort(number: 1900, service: "UPnP")], portsScanned: true, isGateway: true,
                               profile: LANProfile(portForwarding: table))
        let titles = router.riskFindings.map(\.level)
        #expect(titles == [.medium, .low])

        let quiet = LANDevice(ipAddress: "192.168.1.1", portsScanned: true, isGateway: true,
                              profile: LANProfile(portForwarding: PortForwarding(checkedAt: .now, supported: false, externalIP: nil, mappings: [])))
        #expect(quiet.riskFindings.isEmpty)
    }

    @Test func olderProfilesDecodeWithoutTheNewFields() throws {
        let old = Data(#"{"banners":[],"rtsp":[]}"#.utf8)
        let profile = try JSONDecoder().decode(LANProfile.self, from: old)
        #expect(profile.portForwarding == nil)
        #expect(profile.inbound.isEmpty)
        #expect(profile.isEmpty)
    }

    @Test func historyFileKeepsForwardings() throws {
        let table = PortForwarding(checkedAt: Date(timeIntervalSince1970: 1_700_000_000), supported: true, externalIP: "203.0.113.17", mappings: [
            PortMapping(externalPort: 8080, internalPort: 80, internalClient: "192.168.1.60", proto: "TCP", description: "Camera", enabled: true, leaseSeconds: 0, remoteHost: nil),
        ])
        let router = LANDevice(ipAddress: "192.168.1.1", portsScanned: true, isGateway: true, profile: LANProfile(portForwarding: table))
        let camera = LANDevice(ipAddress: "192.168.1.60", portsScanned: true, profile: LANProfile(inbound: table.mappings))
        let entry = HistoryEntry(kind: .lan, savedAt: .now, source: "Test", lan: [SavedLAN(router), SavedLAN(camera)])
        let file = HistoryDocument.file([entry], from: "Test")
        let (_, decoded) = try #require(HistoryDocument.decode(Data(file.contents.utf8)))
        #expect(decoded.first?.lan.first?.profile.portForwarding == table)
        #expect(decoded.first?.lan.last?.profile.inbound == table.mappings)
    }
}
