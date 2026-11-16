import Foundation
import Testing
@testable import Spectra

struct ReviewFixesTests {
    @Test func serviceNamesLoseTheirHexIDs() {
        let cast = ServiceBlock.splitIdentifier("U8000F-66f11bc9458a5a04c86daa67f6b4c556")
        #expect(cast.name == "U8000F")
        #expect(cast.identifier == "66f11bc9458a5a04c86daa67f6b4c556")
        let spotify = ServiceBlock.splitIdentifier("1b067baf05912b622b215c7a35a9675c9717408d")
        #expect(spotify.name == nil)
        #expect(spotify.identifier == "1b067baf05912b622b215c7a35a9675c9717408d")
        let plain = ServiceBlock.splitIdentifier("MacBook M2 Pro 16")
        #expect(plain.name == "MacBook M2 Pro 16")
        #expect(plain.identifier == nil)
        // A short hex-looking suffix is part of the name.
        #expect(ServiceBlock.splitIdentifier("Living-Room-TV-ab12").identifier == nil)
    }

    @Test func speedServerChoiceRoundTrips() {
        for choice in [SpeedServerChoice.cloudflare, .mlab(machine: nil), .mlab(machine: "mlab1-beg01.mlab-oti.measurement-lab.org")] {
            #expect(SpeedServerChoice(raw: choice.raw) == choice)
        }
        #expect(SpeedServerChoice(raw: "garbage") == .cloudflare)
    }

    @Test func mlabLocateIsParsed() throws {
        let json = """
        {"results":[{"machine":"mlab1-beg01.mlab-oti.measurement-lab.org",
          "location":{"city":"Belgrade","country":"RS"},
          "urls":{"wss:///ndt/v7/download":"wss://ndt-mlab1-beg01.mlab-oti.measurement-lab.org/ndt/v7/download?access_token=a",
                  "wss:///ndt/v7/upload":"wss://ndt-mlab1-beg01.mlab-oti.measurement-lab.org/ndt/v7/upload?access_token=b"}},
          {"machine":"broken","urls":{}}]}
        """
        let servers = MLab.parseLocate(Data(json.utf8))
        #expect(servers.count == 1)
        let server = try #require(servers.first)
        #expect(server.city == "Belgrade")
        #expect(server.country == "RS")
        #expect(server.shortName == "mlab1-beg01")
        #expect(server.host == "ndt-mlab1-beg01.mlab-oti.measurement-lab.org")
        #expect(server.uploadURL.query == "access_token=b")
    }

    @Test func finalAddressComparison() throws {
        let a = try #require(URL(string: "https://GitHub.com/"))
        let b = try #require(URL(string: "https://github.com"))
        let c = try #require(URL(string: "https://github.com/login"))
        #expect(LinkInspectionView.sameAddress(a, b))
        #expect(!LinkInspectionView.sameAddress(b, c))
    }

    @Test func characteristicNamesComeFromTheCatalog() {
        #expect(BLENumbers.characteristicName(uuid: "2A50") == "PnP ID")
        #expect(BLENumbers.characteristicName(uuid: "8667556C-9A37-4C91-84ED-54EE27D90049") == "Apple Continuity")
        #expect(BLENumbers.serviceName(uuid: "D0611E78-BBB4-4591-A5F8-487910AE4366") == "Apple Continuity Service")
    }
}

struct LocalTracerTests {
    /// A 20-byte IPv4 header stub; only its length nibble matters to the parser.
    private let ip: [UInt8] = [0x45] + [UInt8](repeating: 0, count: 19)

    @Test func echoRequestChecksumVerifies() {
        let packet = LocalTracer.echoRequest(identifier: 0x1234, sequence: 7)
        #expect(packet[0] == 8)
        #expect(LocalTracer.checksum(packet) == 0)
    }

    @Test func echoReplyIsTheDestination() throws {
        let reply = ip + [0, 0, 0, 0, 0x12, 0x34, 0, 9]
        let parsed = try #require(LocalTracer.parse(reply, identifier: 0x1234))
        #expect(parsed.sequence == 9)
        #expect(parsed.final)
        #expect(LocalTracer.parse(reply, identifier: 0x9999) == nil)
    }

    @Test func timeExceededNamesTheQuotedProbe() throws {
        let quoted = ip + [8, 0, 0, 0, 0x12, 0x34, 0, 3]
        let reply = ip + [11, 0, 0, 0, 0, 0, 0, 0] + quoted
        let parsed = try #require(LocalTracer.parse(reply, identifier: 0x1234))
        #expect(parsed.sequence == 3)
        #expect(!parsed.final)
    }
}


struct LocalTracerV6Tests {
    @Test func echoReplyIsTheDestination() throws {
        let reply: [UInt8] = [129, 0, 0, 0, 0x12, 0x34, 0, 9] + Array(repeating: 0x41, count: 8)
        let parsed = try #require(LocalTracer.parseV6(reply, identifier: 0x1234))
        #expect(parsed.sequence == 9)
        #expect(parsed.final)
        // Our own request echoed back on loopback is not a reply.
        let request = LocalTracer.echoRequestV6(identifier: 0x1234, sequence: 9)
        #expect(LocalTracer.parseV6(request, identifier: 0x1234) == nil)
    }

    @Test func timeExceededQuotesTheProbe() throws {
        let ipv6Header = [UInt8](repeating: 0, count: 40)
        let quoted: [UInt8] = [128, 0, 0, 0, 0x12, 0x34, 0, 4]
        let reply: [UInt8] = [3, 0, 0, 0, 0, 0, 0, 0] + ipv6Header + quoted
        let parsed = try #require(LocalTracer.parseV6(reply, identifier: 0x1234))
        #expect(parsed.sequence == 4)
        #expect(!parsed.final)
        #expect(LocalTracer.parseV6(reply, identifier: 0x9999) == nil)
    }
}
