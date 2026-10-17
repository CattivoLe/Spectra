import Foundation
import Testing
@testable import Spectra

struct LANIdentityTests {
    @Test func ssdpResponseCarriesTheDescriptionURL() {
        let raw = """
        HTTP/1.1 200 OK\r
        CACHE-CONTROL: max-age=1800\r
        LOCATION: http://192.168.1.20:1400/xml/device_description.xml\r
        SERVER: Linux/3.14 UPnP/1.0 Sonos/1\r
        ST: urn:schemas-upnp-org:device:MediaRenderer:1\r
        \r\n
        """
        let parsed = LANIdentity.parseSSDP(Data(raw.utf8))
        #expect(parsed?.location == "http://192.168.1.20:1400/xml/device_description.xml")
        #expect(parsed?.server == "Linux/3.14 UPnP/1.0 Sonos/1")
        #expect(LANIdentity.locationIsOnHost(parsed?.location ?? "", ip: "192.168.1.20"))
        #expect(!LANIdentity.locationIsOnHost("http://evil.example/x", ip: "192.168.1.20"))
        #expect(!LANIdentity.locationIsOnHost("file:///etc/passwd", ip: "192.168.1.20"))
    }

    @Test func upnpDescriptionKeepsTheRootDevice() {
        let xml = """
        <root><device>
        <deviceType>urn:schemas-upnp-org:device:DigitalSecurityCamera:1</deviceType>
        <friendlyName>Front door</friendlyName>
        <manufacturer>Hikvision</manufacturer>
        <modelName>DS-2CD2043</modelName>
        <modelNumber>1.0</modelNumber>
        </device></root>
        """
        let device = LANIdentity.parseUPnPDescription(xml)
        #expect(device.friendlyName == "Front door")
        #expect(device.manufacturer == "Hikvision")
        #expect(device.model == "DS-2CD2043 1.0")
        #expect(device.deviceType == "DigitalSecurityCamera")
    }

    @Test func upnpNamesDecodeQuoteEntities() {
        let xml = """
        <root><device>
        <friendlyName>55&quot; Crystal UHD</friendlyName>
        <manufacturer>Samsung &amp; Co</manufacturer>
        <modelName>UE55&#34;AU</modelName>
        </device></root>
        """
        let device = LANIdentity.parseUPnPDescription(xml)
        #expect(device.friendlyName == "55\" Crystal UHD")
        #expect(device.manufacturer == "Samsung & Co")
        #expect(device.model == "UE55\"AU")
        #expect(LANIdentity.decodeXMLEntities("55&quot: Crystal UHD") == "55\" Crystal UHD")
        #expect(LANIdentity.decodeXMLEntities("&amp;quot;") == "&quot;")
    }

    @Test func anEmptyFriendlyNameDoesNotHideTheRealOne() {
        let xml = """
        <root><device>
        <friendlyName>&#0;</friendlyName>
        <friendlyName> </friendlyName>
        <friendlyName>55&quot; Crystal UHD</friendlyName>
        </device></root>
        """
        #expect(LANIdentity.parseUPnPDescription(xml).friendlyName == "55\" Crystal UHD")
    }

    @Test func aFailedProbeKeepsTheNameAlreadyLearned() {
        let known = LANProfile(upnp: UPnPIdentity(friendlyName: "55\" Crystal UHD", manufacturer: "Samsung", model: "UE55", deviceType: "MediaRenderer", server: "Samsung"))
        let missed = LANProfile()
        #expect(missed.filled(from: known) == known)

        let partial = LANProfile(upnp: UPnPIdentity(friendlyName: nil, manufacturer: nil, model: nil, deviceType: "MediaRenderer", server: nil))
        let merged = partial.filled(from: known)
        #expect(merged.upnp?.friendlyName == "55\" Crystal UHD")
        #expect(merged.upnp?.manufacturer == "Samsung")
        #expect(merged.upnp?.deviceType == "MediaRenderer")
    }

    @Test func aHeadRejectionLosesToThePage() {
        #expect(LANIdentity.httpMethodRejected(501))
        #expect(LANIdentity.httpPrefers(status: 200, title: "Keenetic", over: 501, otherTitle: nil))
        #expect(!LANIdentity.httpPrefers(status: 501, title: nil, over: 200, otherTitle: "Keenetic"))
    }

    @Test func anAppleModelIdNamesThePhone() {
        let service = ServiceAdvertisement(type: "_device-info._tcp", name: "Phone", model: "iPhone16,1")
        let identity = HostIdentity.recognize(names: ["Phone"], services: [service])
        #expect(identity.manufacturer == "Apple")
        #expect(identity.operatingSystem == "iOS")
        #expect(identity.model == "iPhone 15 Pro")
    }

    @Test func aFoldNameNamesSamsungAndroid() {
        let identity = HostIdentity.recognize(names: ["Galaxy Z Fold5"])
        #expect(identity.manufacturer == "Samsung")
        #expect(identity.operatingSystem == "Android")
        #expect(identity.model == "Galaxy Z Fold5")

        let coded = HostIdentity.recognize(names: ["SM-F946B"])
        #expect(coded.manufacturer == "Samsung")
        #expect(coded.operatingSystem == "Android")
        #expect(coded.model == "Galaxy Z Fold5")

        let fold4 = HostIdentity.recognize(names: ["SM-F936B"])
        #expect(fold4.manufacturer == "Samsung")
        #expect(fold4.operatingSystem == "Android")
        #expect(fold4.model == "Galaxy Z Fold4")

        let hyphenated = HostIdentity.recognize(names: ["Galaxy-Z-Fold4"])
        #expect(hyphenated.manufacturer == "Samsung")
        #expect(hyphenated.operatingSystem == "Android")
        #expect(hyphenated.model == "Galaxy Z Fold4")
    }

    @Test func airPlayDoesNotMakeASamsungTvOrEversoloIntoApple() {
        let streamer = HostIdentity.recognize(
            names: ["Eversolo DMP-A6"],
            services: [ServiceAdvertisement(type: "_airplay._tcp", name: "Eversolo DMP-A6")]
        )
        #expect(streamer.manufacturer == "Eversolo")
        #expect(streamer.operatingSystem == nil)

        let television = HostIdentity.recognize(
            names: ["Living Room"],
            services: [ServiceAdvertisement(type: "_raop._tcp", name: "Living Room")],
            upnpName: "55\" Crystal UHD",
            upnpMaker: "Samsung Electronics",
            upnpType: "MediaRenderer"
        )
        #expect(television.manufacturer == "Samsung")
        #expect(television.operatingSystem == "Tizen")
        #expect(television.model == "55\" Crystal UHD")

        let onlyAirPlay = HostIdentity.recognize(
            names: ["Living Room"],
            services: [ServiceAdvertisement(type: "_airplay._tcp", name: "Living Room")]
        )
        #expect(onlyAirPlay.manufacturer == nil)
    }

    @Test func anAppleSyncServiceNamesIos() {
        let phone = HostIdentity.recognize(
            names: ["Alex"],
            services: [ServiceAdvertisement(type: "_apple-mobdev2._tcp", name: "Alex")]
        )
        #expect(phone.manufacturer == "Apple")
        #expect(phone.operatingSystem == "iOS")
        #expect(phone.model == "iPhone")
    }

    @Test func aBlankHostnameDoesNotEraseTheTitle() {
        let blank = LANDevice(ipAddress: "192.168.1.20", hostnames: ["", " ", "."])
        #expect(blank.displayName == "192.168.1.20")

        let named = LANDevice(
            ipAddress: "192.168.1.20",
            hostnames: [""],
            profile: LANProfile(upnp: UPnPIdentity(friendlyName: "55\" Crystal UHD"))
        )
        #expect(named.displayName == "55\" Crystal UHD")
        #expect(LANDevice.usefulHostname("Living-Room.local", ip: "192.168.1.20") == "Living-Room")
        #expect(LANDevice.usefulHostname("192.168.1.20", ip: "192.168.1.20") == nil)

        let room = LANDevice(ipAddress: "192.168.1.20", hostnames: ["Living-Room.local"])
        #expect(room.displayName == "Living-Room")
        room.profile = LANProfile(upnp: UPnPIdentity(friendlyName: "\u{0}\u{200B}"))
        #expect(room.displayName == "Living-Room")
        room.profile = LANProfile(upnp: UPnPIdentity(friendlyName: "Unknown"))
        #expect(room.displayName == "Living-Room")
        room.profile = LANProfile(upnp: UPnPIdentity(friendlyName: "55\" Crystal UHD"))
        #expect(room.displayName == "55\" Crystal UHD")

        let again = LANDevice(ipAddress: "192.168.1.20", hostnames: ["\u{0}"])
        again.preservePinnedName(from: room)
        #expect(again.displayName == "55\" Crystal UHD")
    }

    @Test func httpBannerReadsServerTitleAndRealm() {
        let raw = """
        HTTP/1.0 200 OK\r
        Server: Webs\r
        WWW-Authenticate: Basic realm="DS-2CD2043"\r
        \r
        <html><title>Hikvision</title></html>
        """
        let parsed = LANIdentity.parseHTTP(Data(raw.utf8))
        #expect(parsed?.status == 200)
        #expect(parsed?.server == "Webs")
        #expect(parsed?.title == "Hikvision")
        #expect(parsed?.realm == "DS-2CD2043")
    }

    @Test func rtspOptionsConfirmAStreamer() {
        let raw = "RTSP/1.0 200 OK\r\nCSeq: 1\r\nServer: Hikvision streaming\r\nPublic: OPTIONS, DESCRIBE, SETUP, PLAY\r\n\r\n"
        let parsed = LANIdentity.parseRTSP(Data(raw.utf8))
        #expect(parsed?.server == "Hikvision streaming")
        #expect(parsed?.methods == "OPTIONS, DESCRIBE, SETUP, PLAY")
    }

    @Test func netbiosNodeStatusNamesTheComputerAndGroup() {
        var packet = Data(repeating: 0, count: 12)
        packet[2] = 0x84
        packet[5] = 1
        packet[7] = 1
        packet.append(0x20)
        packet.append(contentsOf: [UInt8](repeating: 0x41, count: 32))
        packet.append(contentsOf: [0x00, 0x00, 0x21, 0x00, 0x01])
        packet.append(contentsOf: [0xC0, 0x0C, 0x00, 0x21, 0x00, 0x01])
        packet.append(contentsOf: [UInt8](repeating: 0, count: 4))
        packet.append(contentsOf: [0x00, 37])
        packet.append(2)
        packet.append(contentsOf: padded("OFFICE-PC") + [0x00, 0x04, 0x00])
        packet.append(contentsOf: padded("HOME") + [0x00, 0x84, 0x00])

        let names = LANIdentity.parseNetBIOS(packet)
        #expect(names.contains { $0.name == "OFFICE-PC" && $0.suffix == 0 && !$0.group })
        #expect(names.contains { $0.name == "HOME" && $0.group })
    }

    @Test func ntlmChallengeNamesComeFromTheTargetInfo() {
        var info = Data()
        info.append(contentsOf: av(1, "OFFICE-PC"))
        info.append(contentsOf: av(2, "HOME"))
        info.append(contentsOf: [0, 0, 0, 0])

        var message = Data("NTLMSSP\0".utf8)
        message.append(contentsOf: [2, 0, 0, 0])
        message.append(contentsOf: [0, 0, 0, 0, 48, 0, 0, 0])
        message.append(contentsOf: [UInt8](repeating: 0, count: 4 + 8 + 8))
        message.append(contentsOf: UInt16(info.count).littleEndianBytes)
        message.append(contentsOf: UInt16(info.count).littleEndianBytes)
        message.append(contentsOf: [48, 0, 0, 0])
        message.append(info)

        let names = LANIdentity.parseNTLMChallenge(Data([0xFE, 0x53]) + message)
        #expect(names?.computer == "OFFICE-PC")
        #expect(names?.domain == "HOME")
    }

    @Test func camerasAndIoTAreFlaggedFromTheVendor() {
        #expect(NotableDevice.classify(manufacturer: "Hikvision", oui: "44:19:B6", upnpType: nil, hasRTSP: false) == .camera)
        #expect(NotableDevice.classify(manufacturer: "Espressif", oui: nil, upnpType: nil, hasRTSP: false) == .iot)
        #expect(NotableDevice.classify(manufacturer: "Apple", oui: nil, upnpType: nil, hasRTSP: false) == nil)
        #expect(NotableDevice.classify(manufacturer: "Apple", oui: nil, upnpType: nil, hasRTSP: true) == .camera)
        #expect(NotableDevice.classify(manufacturer: nil, oui: "44:19:B6", upnpType: nil, hasRTSP: false) == .camera)
        #expect(OUIDatabase.lookup("44:19:B6") == nil)
        #expect(SuspiciousOUI.names["44:19:B6"] == "Hikvision")
    }

    @Test func smbNegotiateIsAFramedSMB2Packet() {
        let framed = LANIdentity.smbNegotiate()
        #expect(framed[0] == 0)
        let payload = LANIdentity.smbPayload(framed)
        #expect(payload.prefix(4) == Data([0xFE, 0x53, 0x4D, 0x42]))
        #expect(LANIdentity.smbStatus(payload) == 0)
    }

    private func padded(_ name: String) -> [UInt8] {
        var bytes = Array(name.utf8.prefix(15))
        bytes.append(contentsOf: [UInt8](repeating: 0x20, count: 15 - bytes.count))
        return bytes
    }

    private func av(_ id: UInt16, _ text: String) -> [UInt8] {
        let encoded = utf16(text)
        return id.littleEndianBytes + UInt16(encoded.count).littleEndianBytes + encoded
    }

    private func utf16(_ text: String) -> [UInt8] {
        text.utf16.flatMap { [$0 & 0xFF, $0 >> 8] }.map(UInt8.init)
    }
}

private extension UInt16 {
    var littleEndianBytes: [UInt8] { [UInt8(self & 0xFF), UInt8(self >> 8)] }
}
