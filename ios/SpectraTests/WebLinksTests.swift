import Foundation
import Testing
import UIKit
@testable import Spectra

struct WebLinksTests {
    private let home = WebLinkNetwork(key: "lan:ssid:Home", keys: ["lan:ssid:Home", "lan:net:192.168.1.0/24"], label: "Home")

    private func fresh() -> (WebLinks, UserDefaults) {
        let defaults = UserDefaults(suiteName: "weblinks-\(UUID().uuidString)")!
        return (WebLinks(defaults: defaults), defaults)
    }

    private func host(_ ip: String, mac: String? = nil, name: String? = nil, ports: [Int] = [80]) -> LANDevice {
        LANDevice(
            ipAddress: ip, macAddress: mac.flatMap(MACAddress.init(string:)), hostnames: name.map { [$0] } ?? [],
            openPorts: ports.map { DiscoveredPort(number: $0, service: DiscoveredPort.serviceName(for: $0)) }, portsScanned: true
        )
    }

    @Test func keptAndDroppedAndPersisted() throws {
        let (links, defaults) = fresh()
        let nas = host("192.168.1.10", mac: "AA:BB:CC:00:00:01", name: "nas.local", ports: [443, 5000])
        links.toggle(nas, port: 5000, network: home)
        links.toggle(nas, port: 443, network: home)
        #expect(links.links.map(\.address) == ["http://192.168.1.10:5000", "https://192.168.1.10"])
        #expect(links.links.first?.title == "nas")
        #expect(links.titles == ["nas"])

        let again = WebLinks(defaults: defaults)
        #expect(again.links == links.links)
        again.toggle(nas, port: 5000, network: home)
        #expect(again.links.map(\.port) == [443])
        #expect(again.link(for: nas, port: 443, network: home) != nil)
    }

    @Test func portWithoutAWebPageIsNotKept() {
        let (links, _) = fresh()
        links.toggle(host("192.168.1.10", ports: [22]), port: 22, network: home)
        #expect(links.links.isEmpty)
    }

    @Test func followsTheDeviceToANewAddress() throws {
        let (links, _) = fresh()
        let nas = host("192.168.1.10", mac: "AA:BB:CC:00:00:01", name: "nas.local", ports: [5000])
        links.toggle(nas, port: 5000, network: home)
        let id = try #require(links.links.first?.id)
        links.edit(id, name: "", address: "http://192.168.1.10:5000/admin?x=1", icon: nil, color: nil)
        let other = host("192.168.1.10", mac: "AA:BB:CC:00:00:02", name: "tv.local")
        let moved = host("192.168.1.77", mac: "AA:BB:CC:00:00:01", name: "nas.local", ports: [5000])
        links.follow([other, moved], network: home)
        #expect(links.links.first?.address == "http://192.168.1.77:5000/admin?x=1")
        #expect(links.link(for: moved, port: 5000, network: home) != nil)
        #expect(links.link(for: other, port: 5000, network: home) == nil)
    }

    @Test func twoDevicesSwapAddresses() {
        let (links, _) = fresh()
        links.toggle(host("192.168.1.10", name: "nas.local"), port: 80, network: home)
        links.toggle(host("192.168.1.20", name: "camera.local"), port: 80, network: home)
        links.follow([host("192.168.1.20", name: "nas.local"), host("192.168.1.10", name: "camera.local")], network: home)
        #expect(links.links.map(\.address) == ["http://192.168.1.20", "http://192.168.1.10"])
    }

    @Test func namesakeWithItsOwnLinkDoesNotTakeOver() {
        let (links, _) = fresh()
        links.toggle(host("192.168.1.10", name: "plug.local"), port: 80, network: home)
        links.toggle(host("192.168.1.11", name: "plug.local"), port: 80, network: home)
        links.follow([host("192.168.1.11", name: "plug.local")], network: home)
        #expect(links.links.map(\.ip) == ["192.168.1.10", "192.168.1.11"])
    }

    @Test func anotherNetworkLeavesTheLinkAlone() {
        let (links, _) = fresh()
        links.toggle(host("192.168.1.1", name: "router.lan"), port: 80, network: home)
        let elsewhere = WebLinkNetwork(key: "lan:ssid:Office", keys: ["lan:ssid:Office"], label: "Office")
        let router = host("192.168.1.1", name: "router.lan")
        #expect(links.link(for: router, port: 80, network: elsewhere) == nil)
        links.follow([host("10.0.0.1", name: "router.lan")], network: elsewhere)
        #expect(links.links.first?.address == "http://192.168.1.1")
        #expect(links.links.first?.networkLabel == "Home")
    }

    @Test func keepsTheUsersNameAndTakesTheDevices() throws {
        let (links, _) = fresh()
        let device = host("192.168.1.10", name: "esp.local")
        links.toggle(device, port: 80, network: home)
        device.customName = "Thermostat"
        links.follow([device], network: home)
        #expect(links.links.first?.title == "Thermostat")
        let id = try #require(links.links.first?.id)
        links.edit(id, name: " Heating ", address: "thermo.local/status", icon: nil, color: nil)
        links.follow([host("192.168.1.30", name: "esp.local")], network: home)
        #expect(links.links.first?.title == "Heating")
        #expect(links.links.first?.address == "http://thermo.local/status", "an address the user gave a name in is left alone")
    }

    @Test func pickedIconStaysAndAutoBringsTheDevicesBack() throws {
        let (links, defaults) = fresh()
        let nas = host("192.168.1.10", name: "nas.local")
        links.toggle(nas, port: 80, network: home)
        let link = try #require(links.links.first)
        links.edit(link.id, name: "", address: link.address, icon: "heart", color: .pink)
        links.follow([nas], network: home)
        #expect(WebLinks(defaults: defaults).links.first?.shownSymbol == "heart")
        #expect(WebLinks(defaults: defaults).links.first?.color == "pink")
        links.edit(link.id, name: "", address: link.address, icon: nil, color: nil)
        #expect(links.links.first?.shownSymbol == nas.deviceIcon)
    }

    @Test func orderChangesWithinANetworkOnly() {
        let (links, defaults) = fresh()
        let office = WebLinkNetwork(key: "lan:ssid:Office", keys: ["lan:ssid:Office"], label: "Office")
        links.toggle(host("192.168.1.10", name: "nas.local"), port: 80, network: home)
        links.toggle(host("10.0.0.1", name: "printer.local"), port: 80, network: office)
        links.toggle(host("192.168.1.20", name: "camera.local"), port: 80, network: home)
        links.toggle(host("192.168.1.30", name: "tv.local"), port: 80, network: home)
        let ids = links.links.map(\.id)
        links.reorder([ids[3], ids[0], ids[2]])
        #expect(links.links.map(\.title) == ["tv", "printer", "nas", "camera"])
        #expect(WebLinks(defaults: defaults).links.map(\.id) == links.links.map(\.id))
        links.reorder([UUID(), ids[0]])
        #expect(links.links.map(\.title) == ["tv", "printer", "nas", "camera"], "an unknown link changes nothing")
    }

    @Test func oneNetworkIsOneGroupWhateverItIsCalled() {
        let (links, _) = fresh()
        let unnamed = WebLinkNetwork(key: "lan:gw:1", keys: ["lan:gw:1", "lan:net:192.168.1.0/24"], label: "192.168.1.0/24")
        let named = WebLinkNetwork(key: "lan:gw:1", keys: unnamed.keys, label: "Home")
        let office = WebLinkNetwork(key: "lan:gw:2", keys: ["lan:gw:2"], label: "Office")
        links.toggle(host("10.0.0.1", name: "printer.local"), port: 80, network: office)
        links.toggle(host("192.168.1.10", name: "nas.local"), port: 80, network: named)
        links.toggle(host("192.168.1.20", name: "camera.local"), port: 80, network: unnamed)
        let groups = links.groups(current: unnamed)
        #expect(groups.map(\.label) == ["Home", "Office"], "the network the phone is on comes first")
        #expect(groups.first?.links.map(\.title) == ["nas", "camera"])
        links.follow([host("192.168.1.10", name: "nas.local")], network: unnamed)
        #expect(links.links[1].networkLabel == "Home", "a subnet does not replace a Wi-Fi name")
        let away = WebLinkNetwork(key: nil, keys: [], label: nil)
        #expect(links.groups(current: away).map(\.label) == ["Office", "Home"])
    }

    @Test func iconsAreSuggestedByNameThenHintsThenPort() {
        func ids(_ texts: [String], port: Int = 80, own: String? = nil) -> [String] {
            WebLinkIcon.suggestions(texts: texts, port: port, own: own).map(\.id)
        }
        #expect(ids(["", "DiskStation"]) == ["storage", "server", "media", "download"])
        #expect(ids(["Камера во дворе", "nas"]).prefix(2) == ["camera", "storage"])
        #expect(ids(["", "unknown", "Network Camera"]).first == "camera")
        #expect(ids(["", "host"], port: 8123).first == "smartHome")
        #expect(ids(["cam2"]).first == "camera", "a number may follow a short word")
        #expect(ids(["MacBook-Air pixel activity"]) == ["laptop", "computer", "terminal"], "a short word is not looked for inside others")
        #expect(ids(["Home Assistant"]).first == "smartHome")
        #expect(ids(["Pi-hole"]).contains("shield"))
        #expect(ids(["nas"], own: "storage") == ["server", "media", "download"])
        #expect(ids(["host"], own: "board") == ["sensor", "plug", "smartHome"])
        #expect(ids(["host"]) == ["smartHome", "server", "web"])
        #expect(ids(["router nas camera lock light plug fan water"]).count == 5)
    }

    @Test func everyIconExists() {
        #expect(Set(WebLinkIcon.all.map(\.id)).count == WebLinkIcon.all.count)
        for icon in WebLinkIcon.all {
            #expect(icon.related.allSatisfy { WebLinkIcon.with(id: $0) != nil })
            #expect(UIImage(systemName: icon.symbol) != nil, "\(icon.symbol)")
        }
    }

    @Test func linksSavedBeforeIconsStillLoad() throws {
        let defaults = UserDefaults(suiteName: "weblinks-\(UUID().uuidString)")!
        let old = """
        [{"id":"6E1F0A2C-0000-4000-8000-000000000001","deviceName":"nas","address":"http://192.168.1.10","ip":"192.168.1.10","port":80,"prints":[],"symbol":"externaldrive"}]
        """
        defaults.set(Data(old.utf8), forKey: "webLinks")
        let link = try #require(WebLinks(defaults: defaults).links.first)
        #expect(link.shownSymbol == "externaldrive")
    }

    @Test func typedAddresses() {
        #expect(WebLink.normalized(" 192.168.1.1:8080/ui ") == "http://192.168.1.1:8080/ui")
        #expect(WebLink.normalized("https://nas.local") == "https://nas.local")
        #expect(WebLink.normalized("ftp://nas.local") == nil)
        #expect(WebLink.normalized("javascript:alert(1)") == nil)
        #expect(WebLink.normalized("http://") == nil)
        #expect(WebLink.normalized("my nas") == nil)
        #expect(WebLink.normalized("") == nil)
    }
}
