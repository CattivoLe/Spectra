import Darwin
import Foundation

/// Xiaomi's miIO / Mi Home devices: air fryers, vacuums, purifiers, lamps. Most of them open no TCP port at all,
/// so a TCP sweep finds an address and nothing else. They do answer a 32-byte "hello" on UDP 54321, and many
/// announce `_miio._udp` over Bonjour with the model in the instance name: `careli-fryer-maf02_miio123456789`.
/// Mirrors Miio.kt. iOS sends the hello to each address in turn, never to the broadcast address.
nonisolated enum Miio {
    static let port = 54321
    static let serviceType = "_miio._udp"
    static let portName = "miIO (UDP)"

    /// The hello every miIO device answers: magic 0x2131, length 32, the rest 0xFF.
    static let hello: Data = {
        var bytes = [UInt8](repeating: 0xFF, count: 32)
        bytes[0] = 0x21
        bytes[1] = 0x31
        bytes[2] = 0x00
        bytes[3] = 0x20
        return Data(bytes)
    }()

    /// The device id in a hello reply, or `nil` when `data` is not one.
    static func parseHello(_ data: Data) -> UInt32? {
        let bytes = [UInt8](data)
        guard bytes.count >= 32, bytes[0] == 0x21, bytes[1] == 0x31 else { return nil }
        guard (Int(bytes[2]) << 8 | Int(bytes[3])) == 32 else { return nil }
        let id = bytes[8..<12].reduce(UInt32(0)) { $0 << 8 | UInt32($1) }
        // Our own hello coming back carries 0xFFFFFFFF.
        return id == 0xFFFF_FFFF ? nil : id
    }

    private static let instance = /^([a-z0-9]+)-([a-z0-9]+)-([a-z0-9-]+?)_miio(\d+)$/.ignoresCase()

    /// `careli-fryer-maf02_miio123456789` → `careli.fryer.maf02`; `nil` for any other name.
    static func modelFromInstance(_ name: String) -> String? {
        guard let match = name.trimmingCharacters(in: .whitespacesAndNewlines).wholeMatch(of: instance) else { return nil }
        return "\(match.1).\(match.2).\(match.3)".lowercased()
    }

    /// Whether `name` is a miIO instance name, which is an identifier rather than something to show.
    static func isInstanceName(_ name: String) -> Bool { modelFromInstance(name) != nil }

    /// What kind of appliance a model id such as `careli.fryer.maf02` is, in the user's language.
    static func kind(_ model: String) -> String? {
        let parts = model.lowercased().split(separator: ".")
        guard parts.count > 1 else { return nil }
        let type = parts[1]
        return kinds.first { type.hasPrefix($0.stem) }?.title
    }

    /// The kind of a Mi Home appliance that said nothing about its model.
    static var unknownKind: String { String(localized: "Mi Home device") }

    /// Longest stems first, so `airpurifier` is not read as `air…`.
    private static var kinds: [(stem: String, title: String)] {
        [
            ("airfryer", String(localized: "Air fryer")),
            ("fryer", String(localized: "Air fryer")),
            ("airpurifier", String(localized: "Air purifier")),
            ("airp", String(localized: "Air purifier")),
            ("airfresh", String(localized: "Fresh air system")),
            ("aircondition", String(localized: "Air conditioner")),
            ("acpartner", String(localized: "Air conditioner")),
            ("humidifier", String(localized: "Humidifier")),
            ("dehumidifier", String(localized: "Dehumidifier")),
            ("vacuum", String(localized: "Robot vacuum")),
            ("sweeper", String(localized: "Robot vacuum")),
            ("mop", String(localized: "Robot vacuum")),
            ("cooker", String(localized: "Multicooker")),
            ("ihcooker", String(localized: "Induction cooker")),
            ("kettle", String(localized: "Kettle")),
            ("oven", String(localized: "Oven")),
            ("microwave", String(localized: "Microwave")),
            ("dishwasher", String(localized: "Dishwasher")),
            ("washer", String(localized: "Washing machine")),
            ("fridge", String(localized: "Fridge")),
            ("heater", String(localized: "Heater")),
            ("fan", String(localized: "Fan")),
            ("light", String(localized: "Lamp")),
            ("lamp", String(localized: "Lamp")),
            ("bulb", String(localized: "Lamp")),
            ("ceiling", String(localized: "Lamp")),
            ("plug", String(localized: "Smart plug")),
            ("switch", String(localized: "Switch")),
            ("gateway", String(localized: "Smart home hub")),
            ("camera", String(localized: "Camera")),
            ("wifispeaker", String(localized: "Smart speaker")),
            ("speaker", String(localized: "Smart speaker")),
            ("tv", String(localized: "TV")),
            ("curtain", String(localized: "Curtain motor")),
            ("waterpuri", String(localized: "Water purifier")),
            ("feeder", String(localized: "Pet feeder")),
            ("toilet", String(localized: "Smart toilet")),
            ("lock", String(localized: "Door lock")),
        ].sorted { $0.0.count > $1.0.count }
    }

    /// The port a hello answer stands for on the host card.
    static var discoveredPort: DiscoveredPort { DiscoveredPort(number: port, service: portName) }

    /// Sends the hello to each address, one by one, and listens `wait` for answers. Returns the addresses that
    /// answered as a miIO device. Blocking: call it off the main actor.
    static func sayHello(to hosts: [String], wait: TimeInterval = 1.5) -> Set<String> {
        let sock = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP)
        guard sock >= 0 else { return [] }
        defer { close(sock) }
        let flags = fcntl(sock, F_GETFL, 0)
        _ = fcntl(sock, F_SETFL, flags | O_NONBLOCK)
        for host in hosts {
            guard let value = parseIP(host) else { continue }
            var address = sockaddr_in()
            address.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
            address.sin_family = sa_family_t(AF_INET)
            address.sin_port = UInt16(port).bigEndian
            address.sin_addr.s_addr = value.bigEndian
            _ = hello.withUnsafeBytes { buffer in
                withUnsafePointer(to: &address) { pointer in
                    pointer.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                        sendto(sock, buffer.baseAddress, buffer.count, 0, $0, socklen_t(MemoryLayout<sockaddr_in>.size))
                    }
                }
            }
        }
        var found: Set<String> = []
        let deadline = Date().addingTimeInterval(wait)
        while Date() < deadline {
            var poller = pollfd(fd: sock, events: Int16(POLLIN), revents: 0)
            let ready = poll(&poller, 1, Int32(max(1, deadline.timeIntervalSinceNow * 1000)))
            if ready <= 0 { break }
            while true {
                var buffer = [UInt8](repeating: 0, count: 256)
                var from = sockaddr_in()
                var length = socklen_t(MemoryLayout<sockaddr_in>.size)
                let count = withUnsafeMutablePointer(to: &from) { pointer in
                    pointer.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                        recvfrom(sock, &buffer, buffer.count, 0, $0, &length)
                    }
                }
                if count <= 0 { break }
                guard parseHello(Data(buffer.prefix(count))) != nil else { continue }
                let value = UInt32(bigEndian: from.sin_addr.s_addr)
                found.insert([24, 16, 8, 0].map { String(value >> $0 & 0xFF) }.joined(separator: "."))
            }
        }
        return found
    }
}
