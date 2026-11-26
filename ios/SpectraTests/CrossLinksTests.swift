import Foundation
import Testing
@testable import Spectra

/// Ties between the app's screens: one device on the LAN scan, on the air, over Bluetooth, in the router's table and
/// in the phone's traffic. The same cases as CrossLinksTest.kt. The simulator may run in Russian: texts are compared
/// through `String(localized:)`, never as English literals.
struct CrossLinksTests {
    let now = Date(timeIntervalSince1970: 1_800_000_000)

    var router: AssistantSnapshot.Host {
        AssistantSnapshot.Host(ip: "192.168.1.1", mac: "50:FF:20:11:22:30", name: "Keenetic", maker: "Keenetic", isGateway: true)
    }
    var camera: AssistantSnapshot.Host {
        AssistantSnapshot.Host(ip: "192.168.1.64", mac: "44:19:B6:00:00:64", name: "IPC-Yard", maker: "Hikvision")
    }
    var speaker: AssistantSnapshot.Host {
        AssistantSnapshot.Host(ip: "192.168.1.30", mac: "00:1B:66:00:00:30", name: "Momentum-3", maker: "Sennheiser")
    }
    var phone: AssistantSnapshot.Host { AssistantSnapshot.Host(ip: "192.168.1.20", name: "This phone", isThisPhone: true) }

    func home(stations: [AssistantSnapshot.Station] = [], current: Bool = true) -> AssistantSnapshot.Wifi {
        AssistantSnapshot.Wifi(name: "Home", bssid: "52:FF:20:11:22:31", rssi: -48, channel: 36, band: "5", security: "WPA2",
                               isCurrent: current, clients: stations.count, stations: stations)
    }

    func snapshot(hosts: [AssistantSnapshot.Host] = [], wifi: [AssistantSnapshot.Wifi] = [], bluetooth: [AssistantSnapshot.Bluetooth] = []) -> AssistantSnapshot {
        var s = AssistantSnapshot()
        s.hosts = hosts
        s.wifi = wifi
        s.bluetooth = bluetooth
        s.wifiName = "Home"
        return s
    }

    func forwarding(_ host: AssistantSnapshot.Host, _ targets: [AssistantSnapshot.ForwardTarget]) -> AssistantSnapshot.Host {
        var gateway = host
        gateway.forwarding = .init(supported: true, rules: targets.map { "\($0.proto) \($0.externalPort) → \($0.client):\($0.internalPort)" }, targets: targets)
        return gateway
    }

    func exposure(_ ports: [Int], via: String? = "wifi") -> AssistantSnapshot.Link {
        var link = AssistantSnapshot.Link(checkedAt: now)
        link.status = "online"
        link.exposedPorts = ports
        link.exposureChecked = true
        link.via = via
        return link
    }

    func texts(_ links: [CrossLink]) -> [String] { links.compactMap { $0.finding?.text } }

    // MARK: - A host and its Wi‑Fi side

    @Test func aHostIsFoundOnTheAirByItsHardwareAddress() {
        // The sensor writes the address in its own case: the match must not care.
        let s = snapshot(hosts: [router, camera], wifi: [home(stations: [.init(mac: "44:19:b6:00:00:64", rssi: -57)])])
        let links = CrossLinks.host(s, camera, now: now)
        #expect(texts(links) == [String(localized: "On Wi‑Fi “\("Home")”, channel \(36); the sensor hears it at \(-57) dBm.")])
        #expect(links[0].line.contains("52:FF:20:11:22:31") && links[0].line.contains("WPA2"))
        // The router is the access point, not a client of it.
        #expect(CrossLinks.host(snapshot(hosts: [router], wifi: [home(stations: [.init(mac: router.mac!, rssi: -40)])]), router, now: now).isEmpty)
        // A host the scan got no address for cannot be matched.
        var bare = camera
        bare.mac = nil
        #expect(CrossLinks.host(s, bare, now: now).isEmpty)
    }

    @Test func aSteadySenderIsNamedOnItsOwnCardAndOnTheNetworks() {
        var s = snapshot(hosts: [router, camera], wifi: [home()])
        s.senders = [.init(mac: camera.mac!, bssid: "52:FF:20:11:22:31", bytesPerSecond: 150_000)]
        let own = CrossLinks.host(s, camera, now: now)
        #expect(own.first?.finding?.risk == .warning)
        #expect(own.first?.line.contains("1200 kbit/s") == true)
        let all = CrossLinks.lan(s, now: now)
        #expect(texts(all) == [String(localized: "\("IPC-Yard (192.168.1.64)") sends data over Wi‑Fi the whole time, as a camera or microphone would.")])
        // The card shows it, and the model reads it under the heading.
        let facts = AssistantCards.host(s, ip: camera.ip, now: now)
        #expect(facts.findings.contains { $0.risk == .warning && $0.text == own[0].finding?.text })
        #expect(facts.data.contains(CrossLinks.heading) && facts.data.contains("1200 kbit/s"))
        #expect(facts.deepData.contains("1200 kbit/s"))
    }

    // MARK: - Ports opened from the internet

    @Test func aForwardedPortIsTiedToTheDeviceItLeadsTo() {
        let gateway = forwarding(router, [.init(proto: "TCP", externalPort: 8080, client: camera.ip, internalPort: 80),
                                          .init(proto: "UDP", externalPort: 5000, client: "192.168.1.99", internalPort: 5000)])
        var s = snapshot(hosts: [gateway, camera])
        let quiet = CrossLinks.host(s, camera, now: now)
        #expect(texts(quiet) == [String(localized: "The router forwards ports from the internet to this device: \("TCP 8080 → 80").")])
        #expect(quiet[0].finding?.risk == .critical)
        // The router's own card already lists its table.
        #expect(CrossLinks.host(s, gateway, now: now).isEmpty)
        // The whole network: the rule is named for the model, and the one that leads nowhere is shown.
        let lan = CrossLinks.lan(s, now: now)
        #expect(lan.contains { $0.finding == nil && $0.line.contains("TCP 8080") && $0.line.contains("IPC-Yard (192.168.1.64)") })
        #expect(texts(lan) == [String(localized: "The router forwards port \("5000") to \("192.168.1.99"), which is not on the network now.")])

        // The connection check found the same port answering from outside.
        s.link = exposure([8080, 443])
        #expect(texts(CrossLinks.host(s, camera, now: now)).contains(String(localized: "Open from the internet right now: \("8080") — the router forwards it to this device.")))
        #expect(texts(CrossLinks.lan(s, now: now)).contains(String(localized: "\("IPC-Yard (192.168.1.64)"): open from the internet right now on port \("8080").")))
        let internet = CrossLinks.internet(s)
        #expect(texts(internet) == [
            String(localized: "Port \("8080") open from the internet leads to \("IPC-Yard (192.168.1.64)")."),
            String(localized: "Port \("443") answers from the internet but is not in the router's UPnP table: the router itself answers, or the port is forwarded by hand in its settings."),
        ])
        #expect(internet[0].finding?.risk == .critical && internet[1].finding?.risk == .info)
        #expect(!CrossLinks.asksForwarding(s))
        let card = InternetCards.internet(s, now: now)
        #expect(card.findings.contains { $0.text == internet[0].finding?.text })
        #expect(card.data.contains(CrossLinks.heading))
    }

    @Test func openPortsWithoutTheRoutersTableAskForIt() {
        var s = snapshot(hosts: [router, camera])
        s.link = exposure([8080])
        #expect(CrossLinks.asksForwarding(s))
        #expect(texts(CrossLinks.internet(s)).isEmpty)
        #expect(CrossLinks.internet(s).first?.line.contains("has not been read") == true)
        #expect(InternetCards.internet(s, now: now).steps.first?.tool == AssistantActions.Name.checkPortForwarding.rawValue)
        // Checked over the mobile network: the public address is the operator's, the router has nothing to do with it.
        s.link = exposure([8080], via: "cellular")
        #expect(CrossLinks.internet(s).isEmpty && !CrossLinks.asksForwarding(s))
        #expect(InternetCards.internet(s, now: now).steps.allSatisfy { $0.tool != AssistantActions.Name.checkPortForwarding.rawValue })
    }

    // MARK: - Bluetooth

    @Test func oneNameOnBothSidesTiesAHostToItsBluetoothSide() {
        let heard = AssistantSnapshot.Bluetooth(name: "MOMENTUM 3", company: "Sennheiser", rssi: -61, id: "B1")
        let s = snapshot(hosts: [router, speaker, phone], bluetooth: [heard])
        #expect(texts(CrossLinks.host(s, speaker, now: now)) == [String(localized: "Also heard over Bluetooth as “\("MOMENTUM 3")” (\(-61) dBm): probably the same device.")])
        #expect(texts(CrossLinks.bluetooth(s, heard, now: now)) == [String(localized: "Probably the same device as \("Momentum-3 (192.168.1.30)") on your local network.")])
        #expect(AssistantCards.bluetooth(s, id: "B1", now: now).data.contains("probably the same device as Momentum-3 (192.168.1.30)"))

        // A risky port on the LAN side is worth a warning on the Bluetooth card.
        var risky = speaker
        risky.portRisks = [.init(title: "Telnet: clear-text login", high: true)]
        let warned = CrossLinks.bluetooth(snapshot(hosts: [risky], bluetooth: [heard]), heard, now: now)
        #expect(warned.first?.finding?.risk == .warning && warned.first?.finding?.text.contains("Telnet") == true)
    }

    @Test func aNameIsNoTieWhenItIsNotTheDevicesOwnOrNotTheOnlyOne() {
        // Two hosts carry the name: which of them is heard is unknown.
        var second = speaker
        second.ip = "192.168.1.31"
        let heard = AssistantSnapshot.Bluetooth(name: "Momentum 3", rssi: -61, id: "B1")
        #expect(CrossLinks.lanTwin(snapshot(hosts: [speaker, second], bluetooth: [heard]), of: heard) == nil)
        // A name every phone of a kind carries.
        let iphone = AssistantSnapshot.Host(ip: "192.168.1.40", name: "iPhone", maker: "Apple")
        #expect(CrossLinks.bluetoothTwin(snapshot(hosts: [iphone], bluetooth: [.init(name: "iPhone", rssi: -50, id: "B2")]), of: iphone) == nil)
        // The Bluetooth name is the maker's, not the device's own.
        let nameless = AssistantSnapshot.Bluetooth(name: "Momentum 3", rssi: -61, id: "B3", ownName: false)
        #expect(CrossLinks.lanTwin(snapshot(hosts: [speaker], bluetooth: [nameless]), of: nameless) == nil)
        // A host named by its maker or its address alone.
        let plain = AssistantSnapshot.Host(ip: "192.168.1.50", name: "Sennheiser", maker: "Sennheiser")
        #expect(CrossLinks.bluetoothTwin(snapshot(hosts: [plain], bluetooth: [.init(name: "Sennheiser", rssi: -50, id: "B4")]), of: plain) == nil)
        // The same device heard under two identifiers is still one name.
        let twice = [AssistantSnapshot.Bluetooth(name: "Momentum 3", rssi: -70, id: "B5"), .init(name: "MOMENTUM 3", rssi: -55, id: "B6")]
        #expect(CrossLinks.bluetoothTwin(snapshot(hosts: [speaker], bluetooth: twice), of: speaker)?.id == "B6")
        // A longer name that holds the shorter one whole.
        #expect(CrossLinks.namesMatch("[LG] webOS TV OLED55C1", "LG webOS TV"))
        #expect(!CrossLinks.namesMatch("TV", "TV"))
    }

    @Test func aBluetoothDeviceAndANetworkOfOneNameAreTied() {
        let printer = AssistantSnapshot.Bluetooth(name: "DIRECT-7A-HP Laser", rssi: -58, id: "B1")
        let own = AssistantSnapshot.Wifi(name: "DIRECT-7A-HP Laser", bssid: "AA:BB:CC:00:00:01", rssi: -50, channel: 6, band: "2.4", security: "WPA2")
        let s = snapshot(wifi: [own, home()], bluetooth: [printer])
        #expect(texts(CrossLinks.bluetooth(s, printer, now: now)) == [String(localized: "A Wi‑Fi network with the same name is on air (\("WPA2")): probably this device's own access point.")])
        #expect(texts(CrossLinks.wifi(s, own, now: now)).contains(String(localized: "A Bluetooth device with the same name is nearby (“\("DIRECT-7A-HP Laser")”, \(-58) dBm): probably the one that broadcasts this network.")))
    }

    // MARK: - A Wi‑Fi network and the local network behind it

    @Test func theNetworkThePhoneIsOnShowsItsLocalNetwork() {
        var risky = camera
        risky.portRisks = [.init(title: "Telnet: clear-text login", high: true)]
        var s = snapshot(hosts: [router, risky, speaker, phone],
                         wifi: [home(stations: [.init(mac: camera.mac!, rssi: -57), .init(mac: "DE:AD:BE:EF:00:01", rssi: -70)])])
        s.link = exposure([])
        s.link?.intercepted = false
        let links = CrossLinks.wifi(s, s.wifi[0], now: now)
        #expect(texts(links) == [
            String(localized: "Your phone is on this network: the network scan found \(4) devices on it."),
            String(localized: "Devices on it that need attention: \("IPC-Yard (192.168.1.64)")."),
            String(localized: "The connection check on it: the internet works, HTTPS is not intercepted."),
            String(localized: "Most likely your router's access point: \("Keenetic (192.168.1.1)")."),
            String(localized: "Devices of your network on it: \("IPC-Yard (192.168.1.64)")."),
        ])
        let facts = AssistantCards.wifi(s, bssid: s.wifi[0].bssid, now: now)
        #expect(facts.findings.contains { $0.risk == .warning && $0.text.contains("IPC-Yard") })
        #expect(facts.data.contains("Telnet: clear-text login"))
        // The deep analysis reads the connection check made through this network.
        #expect(facts.deepData.contains("Latest connection check"))

        // Interception found by the connection check belongs on the network's card.
        s.link?.intercepted = true
        #expect(CrossLinks.wifi(s, s.wifi[0], now: now).contains { $0.finding?.risk == .critical })
        // Another network nearby gets none of it.
        let other = AssistantSnapshot.Wifi(name: "Cafe", bssid: "AA:BB:CC:00:00:02", rssi: -70, channel: 1, band: "2.4", security: "Open")
        #expect(CrossLinks.wifi(s, other, now: now).isEmpty)
    }

    @Test func stationsTheScanDidNotFindAreCounted() {
        let stations = [camera.mac!, "DE:AD:BE:EF:00:01", "DE:AD:BE:EF:00:02", "DE:AD:BE:EF:00:03"].map { AssistantSnapshot.Station(mac: $0, rssi: -60) }
        var s = snapshot(hosts: [router, camera, phone], wifi: [home(stations: stations)])
        // Three stations match no host; one of them may be the phone, which the scan lists without an address.
        #expect(CrossLinks.quietStations(s)?.extra == 2)
        #expect(texts(CrossLinks.lan(s, now: now)) == [String(localized: "The sensor hears \(2) more devices on your Wi‑Fi than the network scan found: asleep, or kept apart from the phone.")])
        // While the scan runs its list is not whole yet.
        s.lanScanning = true
        #expect(CrossLinks.quietStations(s) == nil)
        // Without any hardware address from the scan there is nothing to compare by.
        s.lanScanning = false
        s.hosts = [phone, AssistantSnapshot.Host(ip: "192.168.1.5", name: "192.168.1.5")]
        #expect(CrossLinks.quietStations(s) == nil)
        // A neighbour's network is not counted.
        #expect(CrossLinks.quietStations(snapshot(hosts: [router, camera], wifi: [home(stations: stations, current: false)].map {
            var ap = $0
            ap.name = "Neighbour"
            return ap
        })) == nil)
    }

    // MARK: - Traffic, captures, history

    @Test func recordedTrafficAndTheLastCaptureNameTheHost() {
        func flow(_ id: Int64, to destination: String, port: Int = 443, tls: Bool = true, request: String? = nil) -> TrafficFlow {
            TrafficFlow(id: id, proto: "TCP", source: "10.0.0.2", sourcePort: 50_000, destination: destination, destinationPort: port,
                        request: request, tls: tls, startedAt: 0, lastAt: 1000)
        }
        let flows = [flow(1, to: camera.ip, port: 80, tls: false, request: "GET /"), flow(2, to: camera.ip), flow(3, to: "93.184.216.34"),
                     flow(4, to: router.ip, port: 53, tls: false)]
        let peers = AssistantSources.localPeers(flows, hosts: [router.ip, camera.ip])
        // The name lookup the router answers is not a talk with the router.
        #expect(peers == [.init(address: camera.ip, connections: 2, cleartext: true)])
        var s = snapshot(hosts: [router, camera])
        s.localPeers = peers
        s.captureName = "Home.pcap"
        s.captureTalkers = [.init(address: "44:19:b6:00:00:64", packets: 310)]
        #expect(texts(CrossLinks.host(s, camera, now: now)) == [
            String(localized: "The phone talked to it in the recorded traffic: \(2) connections, some unencrypted."),
            String(localized: "In the last capture (\("Home.pcap")): \(310) packets from or to it."),
        ])
        // The recording's own card names the device behind the address.
        let card = AssistantCards.traffic(flows, name: "x", digest: "d", local: [camera.ip: "IPC-Yard · Hikvision"])
        #expect(card.findings.contains { $0.text == String(localized: "The phone talked to devices on your network: \("IPC-Yard · Hikvision (192.168.1.64)").") })
        #expect(card.data.contains("IPC-Yard · Hikvision (192.168.1.64) ×2, plain HTTP"))
        // One connection's card says the server is a device of the network.
        let context = TrafficCards.Context(session: nil, flows: flows, local: [camera.ip: "IPC-Yard · Hikvision"])
        let connection = TrafficCards.connection(flows[1], context)
        #expect(connection.findings.contains { $0.text == String(localized: "The server is a device on your network: \("IPC-Yard · Hikvision") (\(camera.ip)).") })
        #expect(connection.data.contains("found by the LAN scan: IPC-Yard · Hikvision (192.168.1.64)"))
        #expect(TrafficCards.localTie(flows[2], context) == nil)
    }

    @Test func aDeviceMetLongAgoSaysSinceWhen() {
        var old = camera
        old.firstSeen = now.addingTimeInterval(-40 * 24 * 3600)
        let day = old.firstSeen!.formatted(.dateTime.day().month().year())
        #expect(texts(CrossLinks.host(snapshot(hosts: [old]), old, now: now)) == [String(localized: "First seen on \(day).")])
        // Met today, or flagged as new: nothing to add.
        var fresh = camera
        fresh.firstSeen = now.addingTimeInterval(-3600)
        #expect(CrossLinks.host(snapshot(hosts: [fresh]), fresh, now: now).isEmpty)
        old.isNew = true
        #expect(CrossLinks.host(snapshot(hosts: [old]), old, now: now).isEmpty)
    }

    // MARK: - On a card

    @Test func aCardShowsAtMostFourTiesAndTheModelReadsThemAll() {
        let links = (1...6).map { CrossLink(finding: .init(risk: $0 == 6 ? .critical : .info, text: "t\($0)"), line: "l\($0)") }
        #expect(CrossLinks.findings(links).map(\.text) == ["t6", "t1", "t2", "t3"])
        #expect(CrossLinks.text(links).components(separatedBy: "\n").count == 7)
        #expect(CrossLinks.text([]).isEmpty && CrossLinks.block([]).isEmpty)
        // A card with nothing tied reads as before: no empty heading.
        #expect(!AssistantCards.host(snapshot(hosts: [camera]), ip: camera.ip, now: now).data.contains(CrossLinks.heading))
        // The model is told what the ties are.
        #expect(CardPrompts.system(.summary, kind: .host).contains("Ties to the app's other screens"))
    }

    @Test func aRoutersAccessPointIsKnownByItsAddress() {
        #expect(CrossLinks.near("50:FF:20:11:22:30", "52:FF:20:11:22:31"))
        #expect(CrossLinks.near("50:FF:20:11:22:30", "50:FF:20:11:22:30"))
        #expect(!CrossLinks.near("50:FF:20:11:22:30", "50:FF:20:11:23:30"))
        #expect(!CrossLinks.near("50:FF:20:11:22:30", "not an address"))
    }
}
