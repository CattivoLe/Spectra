import Foundation

/// HPACK (RFC 7541): the header compression of HTTP/2. One decoder per direction of a connection, fed every
/// header block in order, since each block can change the table the next one refers to. Port of Android
/// `core/Hpack.kt`.
nonisolated final class HpackDecoder {
    struct Failure: Error {}

    private struct Entry {
        let field: HttpField
        let size: Int
    }

    private var maxSize: Int
    /// Newest first.
    private var table: [Entry] = []
    private var size = 0
    private static let maxTable = 1 << 20

    init(maxSize: Int = 4096) { self.maxSize = maxSize }

    /// Decodes one complete header block. Throws on malformed input: the connection can no longer be read.
    func decode(_ block: [UInt8]) throws -> [HttpField] {
        var out: [HttpField] = []
        var reader = Reader(data: block)
        while reader.more {
            let b = reader.peek()
            if b & 0x80 != 0 {
                out.append(try field(reader.int(7)))
            } else if b & 0x40 != 0 {
                let (name, nameBytes) = try name(&reader, 6)
                let (value, valueBytes) = try reader.string()
                let field = HttpField(name: name, value: value)
                out.append(field)
                add(Entry(field: field, size: nameBytes + valueBytes + 32))
            } else if b & 0x20 != 0 {
                let next = try reader.int(5)
                if next > Self.maxTable { throw Failure() }
                maxSize = next
                evict(0)
            } else {
                let (name, _) = try name(&reader, 4)
                out.append(HttpField(name: name, value: try reader.string().0))
            }
        }
        return out
    }

    private func name(_ reader: inout Reader, _ prefix: Int) throws -> (String, Int) {
        let index = try reader.int(prefix)
        if index == 0 { return try reader.string() }
        let name = try field(index).name
        return (name, name.utf8.count)
    }

    private func field(_ index: Int) throws -> HttpField {
        if index <= 0 { throw Failure() }
        if index <= Self.staticTable.count { return Self.staticTable[index - 1] }
        let dynamic = index - Self.staticTable.count - 1
        guard dynamic < table.count else { throw Failure() }
        return table[dynamic].field
    }

    private func add(_ entry: Entry) {
        evict(entry.size)
        if entry.size > maxSize { return }
        table.insert(entry, at: 0)
        size += entry.size
    }

    private func evict(_ room: Int) {
        while !table.isEmpty && size + room > maxSize { size -= table.removeLast().size }
    }

    private struct Reader {
        let data: [UInt8]
        var at = 0

        var more: Bool { at < data.count }

        func peek() -> Int { Int(data[at]) }

        mutating func next() throws -> Int {
            guard at < data.count else { throw Failure() }
            defer { at += 1 }
            return Int(data[at])
        }

        mutating func int(_ prefix: Int) throws -> Int {
            let mask = (1 << prefix) - 1
            var value = try next() & mask
            if value < mask { return value }
            var shift = 0
            while true {
                let b = try next()
                value += (b & 0x7F) << shift
                if shift > 28 || value > Int(Int32.max) { throw Failure() }
                if b & 0x80 == 0 { return value }
                shift += 7
            }
        }

        /// The text and its length in octets, which is what the table counts.
        mutating func string() throws -> (String, Int) {
            let huffman = peek() & 0x80 != 0
            let length = try int(7)
            guard length <= data.count - at else { throw Failure() }
            let raw = Array(data[at..<at + length])
            at += length
            let bytes = huffman ? try Huffman.decode(raw) : raw
            return (String(decoding: bytes, as: UTF8.self), bytes.count)
        }
    }

    static let staticTable: [HttpField] = [
        (":authority", ""), (":method", "GET"), (":method", "POST"), (":path", "/"), (":path", "/index.html"),
        (":scheme", "http"), (":scheme", "https"), (":status", "200"), (":status", "204"), (":status", "206"),
        (":status", "304"), (":status", "400"), (":status", "404"), (":status", "500"), ("accept-charset", ""),
        ("accept-encoding", "gzip, deflate"), ("accept-language", ""), ("accept-ranges", ""), ("accept", ""),
        ("access-control-allow-origin", ""), ("age", ""), ("allow", ""), ("authorization", ""), ("cache-control", ""),
        ("content-disposition", ""), ("content-encoding", ""), ("content-language", ""), ("content-length", ""),
        ("content-location", ""), ("content-range", ""), ("content-type", ""), ("cookie", ""), ("date", ""),
        ("etag", ""), ("expect", ""), ("expires", ""), ("from", ""), ("host", ""), ("if-match", ""),
        ("if-modified-since", ""), ("if-none-match", ""), ("if-range", ""), ("if-unmodified-since", ""),
        ("last-modified", ""), ("link", ""), ("location", ""), ("max-forwards", ""), ("proxy-authenticate", ""),
        ("proxy-authorization", ""), ("range", ""), ("referer", ""), ("refresh", ""), ("retry-after", ""),
        ("server", ""), ("set-cookie", ""), ("strict-transport-security", ""), ("transfer-encoding", ""),
        ("user-agent", ""), ("vary", ""), ("via", ""), ("www-authenticate", ""),
    ].map { HttpField(name: $0.0, value: $0.1) }
}

/// The static Huffman code of HPACK. It is canonical: codes follow from the lengths alone, shortest first and
/// by symbol within a length, so only the lengths are listed (symbols 0–255, then end-of-string).
nonisolated enum Huffman {
    private static let lengths: [Int] = [
        13, 23, 28, 28, 28, 28, 28, 28, 28, 24, 30, 28, 28, 30, 28, 28, 28, 28, 28, 28, 28, 28, 30, 28, 28, 28, 28, 28, 28, 28, 28, 28,
        6, 10, 10, 12, 13, 6, 8, 11, 10, 10, 8, 11, 8, 6, 6, 6, 5, 5, 5, 6, 6, 6, 6, 6, 6, 6, 7, 8, 15, 6, 12, 10,
        13, 6, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 8, 7, 8, 13, 19, 13, 14, 6,
        15, 5, 6, 5, 6, 5, 6, 6, 6, 5, 7, 7, 6, 6, 6, 5, 6, 7, 6, 5, 5, 6, 7, 7, 7, 7, 7, 15, 11, 14, 13, 28,
        20, 22, 20, 20, 22, 22, 22, 23, 22, 23, 23, 23, 23, 23, 24, 23, 24, 24, 22, 23, 24, 23, 23, 23, 23, 21, 22, 23, 22, 23, 23, 24,
        22, 21, 20, 22, 22, 23, 23, 21, 23, 22, 22, 24, 21, 22, 23, 23, 21, 21, 22, 21, 23, 22, 23, 23, 20, 22, 22, 22, 23, 22, 22, 23,
        26, 26, 20, 19, 22, 23, 22, 25, 26, 26, 26, 27, 27, 26, 24, 25, 19, 21, 26, 27, 27, 26, 27, 24, 21, 21, 26, 26, 28, 27, 27, 27,
        20, 24, 20, 21, 22, 21, 21, 23, 22, 22, 25, 25, 24, 24, 26, 23, 26, 27, 26, 26, 27, 27, 27, 27, 27, 28, 27, 27, 27, 27, 27, 26,
        30,
    ]

    /// For each length: the first code, how many codes, and where its symbols start in `symbols`.
    private static let tables: (first: [Int], count: [Int], start: [Int], symbols: [Int]) = {
        let symbols = (0...256).sorted { (lengths[$0], $0) < (lengths[$1], $1) }
        var count = [Int](repeating: 0, count: 31)
        for length in lengths { count[length] += 1 }
        var first = [Int](repeating: 0, count: 31)
        var start = [Int](repeating: 0, count: 31)
        var code = 0, index = 0
        for length in 1...30 {
            first[length] = code
            start[length] = index
            code = (code + count[length]) << 1
            index += count[length]
        }
        return (first, count, start, symbols)
    }()

    static func decode(_ data: [UInt8]) throws -> [UInt8] {
        let t = tables
        var out: [UInt8] = []
        out.reserveCapacity(data.count * 8 / 5 + 1)
        var code = 0, length = 0
        for byte in data {
            for bit in stride(from: 7, through: 0, by: -1) {
                code = (code << 1) | ((Int(byte) >> bit) & 1)
                length += 1
                let offset = code - t.first[length]
                if offset >= 0 && offset < t.count[length] {
                    let symbol = t.symbols[t.start[length] + offset]
                    if symbol == 256 { throw HpackDecoder.Failure() }
                    out.append(UInt8(symbol))
                    code = 0
                    length = 0
                } else if length >= 30 {
                    throw HpackDecoder.Failure()
                }
            }
        }
        // What is left must be padding: fewer than eight bits, all ones.
        if length > 7 || code != (1 << length) - 1 { throw HpackDecoder.Failure() }
        return out
    }
}
