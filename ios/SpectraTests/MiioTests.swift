import Foundation
import Testing
@testable import Spectra

/// Xiaomi Mi Home appliances: the UDP hello and the model in the Bonjour name. Ported from MiioTest.kt.
/// The simulator may run in Russian: texts are compared through `String(localized:)`.
struct MiioTests {
    @Test func helloIsThirtyTwoBytesWithTheMagic() {
        let hello = [UInt8](Miio.hello)
        #expect(hello.count == 32)
        #expect(hello[0] == 0x21)
        #expect(hello[1] == 0x31)
        #expect(hello[3] == 0x20)
        // Our own hello, heard back, is not a device.
        #expect(Miio.parseHello(Miio.hello) == nil)
    }

    @Test func aReplyCarriesTheDeviceId() {
        var reply = [UInt8](repeating: 0, count: 32)
        reply[0] = 0x21; reply[1] = 0x31; reply[2] = 0x00; reply[3] = 0x20
        reply[8] = 0x12; reply[9] = 0x34; reply[10] = 0x56; reply[11] = 0x78
        #expect(Miio.parseHello(Data(reply)) == 0x1234_5678)
        #expect(Miio.parseHello(Data(reply.prefix(20))) == nil)
        #expect(Miio.parseHello(Data(count: 32)) == nil)
    }

    @Test func theModelComesFromTheInstanceName() {
        #expect(Miio.modelFromInstance("careli-fryer-maf02_miio123456789") == "careli.fryer.maf02")
        #expect(Miio.modelFromInstance("zhimi-airpurifier-ma2_miio98765") == "zhimi.airpurifier.ma2")
        #expect(Miio.modelFromInstance("Living Room TV") == nil)
        #expect(Miio.isInstanceName("careli-fryer-maf02_miio123456789"))
        #expect(Miio.kind("careli.fryer.maf02") == String(localized: "Air fryer"))
        #expect(Miio.kind("zhimi.airpurifier.ma2") == String(localized: "Air purifier"))
        #expect(Miio.kind("roborock.vacuum.s5") == String(localized: "Robot vacuum"))
        #expect(Miio.kind("xiaomi.unknownthing.v1") == nil)
    }

    @Test func aMiHomeApplianceIsNamedByWhatItIs() {
        let service = ServiceAdvertisement(type: Miio.serviceType, name: "Mi Home", model: "careli.fryer.maf02")
        let found = HostIdentity.recognize(names: [], services: [service])
        #expect(found.manufacturer == "Xiaomi")
        #expect(found.model == String(localized: "Air fryer"))
        #expect(service.kind == "Mi Home")
        // Only the hello answered (the user's air fryer): a Xiaomi appliance, kind unknown.
        let hello = HostIdentity.recognize(names: [], ports: [Miio.port])
        #expect(hello.manufacturer == "Xiaomi")
        #expect(hello.model == String(localized: "Mi Home device"))
    }

    @Test func theInstanceNameIsNeverShownAsTheHostName() {
        #expect(LANDevice.usefulHostname("careli-fryer-maf02_miio123456789", ip: "192.168.1.95") == nil)
        let device = LANDevice(ipAddress: "192.168.1.95",
                               services: [ServiceAdvertisement(type: Miio.serviceType, name: "Mi Home", model: "careli.fryer.maf02")])
        #expect(device.displayName == String(localized: "Air fryer"))
        #expect(!device.allPortsScanned)
    }

    @Test func aRecentFullScanKeepsItsPortsAcrossARescan() {
        let kept = [DiscoveredPort(number: 8888, service: "TCP"), DiscoveredPort(number: 80, service: "HTTP")]
        let fresh = [DiscoveredPort(number: 80, service: "HTTP"), Miio.discoveredPort]
        #expect(LANScanner.mergedPorts(fresh, kept: kept).map(\.number) == [80, 8888, 54321])
        #expect(LANScanner.fullScanKeep == 30 * 60)
    }
}
