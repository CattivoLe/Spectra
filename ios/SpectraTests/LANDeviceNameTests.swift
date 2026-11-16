import Foundation
import Testing
@testable import Spectra

struct LANDeviceNameTests {
    @Test func identifiersAreNotNames() {
        #expect(LANDevice.isWeakHostname("1b067baf05912b622b215c7a9d3e4f01"))
        #expect(LANDevice.isWeakHostname("3fa85f64-5717-4562-b3fc-2c963f66afa6"))
        #expect(!LANDevice.isWeakHostname("living-room-tv"))
        #expect(!LANDevice.isWeakHostname("MacBook-Pro"))
        #expect(!LANDevice.isWeakHostname("cafe"))
    }

    @Test func hexHostnameGivesWayToTheMaker() throws {
        let device = LANDevice(
            ipAddress: "192.168.1.117",
            macAddress: MACAddress(bytes: [0x00, 0x07, 0xAB, 0x01, 0x02, 0x03]),
            hostnames: ["1b067baf05912b622b215c7a9d3e4f01.local"],
            firstSeen: .now,
            lastSeen: .now
        )
        let maker = try #require(device.manufacturer)
        #expect(device.displayName == maker)
    }
}
