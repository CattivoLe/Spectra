import Foundation
import Compression

/// One WebSocket message after an upgrade: a whole text or binary message (its fragments joined), or a control
/// frame. `size` is the payload as sent; `preview` the start of a text message. The payload itself goes to the
/// body folder like an HTTP body, when `stored`. Port of Android `WsMessage`.
nonisolated struct WsMessage: Codable, Equatable, Sendable {
    var fromClient: Bool
    var opcode: Int
    var at: Int64
    var size: Int64
    var preview: String?
    var stored: Bool
    var cut = false
    var compressed = false

    static let text = 1, binary = 2, close = 8, ping = 9, pong = 10
    static let previewLength = 200

    var kind: String {
        switch opcode {
        case Self.text: "text"
        case Self.binary: "binary"
        case Self.close: "close"
        case Self.ping: "ping"
        case Self.pong: "pong"
        default: "opcode \(opcode)"
        }
    }

    init(fromClient: Bool, opcode: Int, at: Int64, size: Int64, preview: String?, stored: Bool, cut: Bool = false, compressed: Bool = false) {
        self.fromClient = fromClient; self.opcode = opcode; self.at = at; self.size = size
        self.preview = preview; self.stored = stored; self.cut = cut; self.compressed = compressed
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        fromClient = try c.decode(Bool.self, forKey: .fromClient)
        opcode = try c.decode(Int.self, forKey: .opcode)
        at = try c.decode(Int64.self, forKey: .at)
        size = try c.decodeIfPresent(Int64.self, forKey: .size) ?? 0
        preview = try c.decodeIfPresent(String.self, forKey: .preview)
        stored = try c.decodeIfPresent(Bool.self, forKey: .stored) ?? false
        cut = try c.decodeIfPresent(Bool.self, forKey: .cut) ?? false
        compressed = try c.decodeIfPresent(Bool.self, forKey: .compressed) ?? false
    }
}

/// Reads the frames of one WebSocket connection in both directions (RFC 6455), joining fragments and undoing
/// permessage-deflate (RFC 7692). `onMessage` gets each finished message and its payload, cut to `cap`.
/// Port of Android `WsReader`.
nonisolated final class WsReader {
    private let clock: () -> Int64
    private let onMessage: (WsMessage, [UInt8]) -> Void
    private let cap: Int
    private let deflate: Bool
    private let client: Side
    private let server: Side
    private(set) var broken = false

    /// Frames are held whole before they are read; a larger one ends the reading, not the connection.
    static let maxFrame = 4 * 1024 * 1024

    init(extensions: String?, clock: @escaping () -> Int64, cap: Int = 1024 * 1024,
         onMessage: @escaping (WsMessage, [UInt8]) -> Void) {
        let ext = extensions?.lowercased() ?? ""
        self.clock = clock
        self.onMessage = onMessage
        self.cap = cap
        deflate = ext.contains("permessage-deflate")
        client = Side(fromClient: true, reset: ext.contains("client_no_context_takeover"))
        server = Side(fromClient: false, reset: ext.contains("server_no_context_takeover"))
    }

    func client(_ data: [UInt8]) { feed(client, data) }
    func server(_ data: [UInt8]) { feed(server, data) }

    private func feed(_ side: Side, _ data: [UInt8]) {
        if broken || data.isEmpty { return }
        side.pending.append(contentsOf: data)
        do { try pump(side) } catch { broken = true }
    }

    private final class Side {
        let fromClient: Bool
        let reset: Bool
        var pending: [UInt8] = []
        var head = 0
        var inflater: StreamInflater?
        var inflaterBroken = false
        var opcode = 0
        var startedAt: Int64 = 0
        var compressed = false
        var payload: [UInt8] = []
        var wire: Int64 = 0
        var cut = false

        init(fromClient: Bool, reset: Bool) { self.fromClient = fromClient; self.reset = reset }
    }

    private struct Frame {
        let fin: Bool, rsv1: Bool, opcode: Int, start: Int, length: Int, mask: [UInt8]?
    }

    private struct TooLarge: Error {}

    private func pump(_ side: Side) throws {
        while let frame = try header(side.pending, side.head) {
            guard side.pending.count - frame.start >= frame.length else { break }
            var body = Array(side.pending[frame.start..<frame.start + frame.length])
            if let mask = frame.mask { for i in body.indices { body[i] ^= mask[i & 3] } }
            side.head = frame.start + frame.length
            handle(side, frame, body)
        }
        // Drop what was read; done in one go per feed, so a large frame is not copied piece by piece.
        if side.head > 0 {
            side.pending.removeFirst(side.head)
            side.head = 0
        }
        if side.pending.count > Self.maxFrame + 14 { throw TooLarge() }
    }

    private func handle(_ side: Side, _ frame: Frame, _ body: [UInt8]) {
        let now = clock()
        if frame.opcode >= 8 {
            var text: String?
            if frame.opcode == WsMessage.close && body.count >= 2 {
                let code = Int(body[0]) << 8 | Int(body[1])
                let reason = String(decoding: body[2...], as: UTF8.self)
                text = reason.isEmpty ? "\(code)" : "\(code) \(reason)"
            } else if !body.isEmpty && frame.opcode != WsMessage.close {
                text = String(decoding: body, as: UTF8.self)
            }
            emit(side, frame.opcode, now, Int64(body.count), body, text.map { String($0.prefix(WsMessage.previewLength)) }, cut: false, compressed: false)
            return
        }
        if frame.opcode != 0 {
            side.opcode = frame.opcode
            side.startedAt = now
            side.compressed = deflate && frame.rsv1
            side.payload = []
            side.wire = 0
            side.cut = false
        }
        side.wire += Int64(body.count)
        let room = (side.compressed ? Self.maxFrame : cap) - side.payload.count
        if body.count > room {
            if room > 0 { side.payload += body.prefix(room) }
            side.cut = true
        } else {
            side.payload += body
        }
        guard frame.fin else { return }
        var bytes = side.payload
        var messageCut = side.cut
        if side.compressed {
            if let inflated = inflate(side, bytes, complete: !side.cut) {
                bytes = inflated.0
                messageCut = messageCut || inflated.1
            } else {
                messageCut = true
            }
        }
        let preview = side.opcode == WsMessage.text
            ? String(String(decoding: bytes.prefix(WsMessage.previewLength * 4), as: UTF8.self).prefix(WsMessage.previewLength))
            : nil
        emit(side, side.opcode, side.startedAt, side.wire, bytes, preview, cut: messageCut, compressed: side.compressed)
        side.payload = []
    }

    private func emit(_ side: Side, _ opcode: Int, _ at: Int64, _ size: Int64, _ body: [UInt8], _ preview: String?,
                      cut: Bool, compressed: Bool) {
        let shown = body.count > cap ? Array(body.prefix(cap)) : body
        onMessage(WsMessage(fromClient: side.fromClient, opcode: opcode, at: at, size: size, preview: preview,
                            stored: false, cut: cut || body.count > cap, compressed: compressed), shown)
    }

    /// The message as sent, undoing permessage-deflate. Nil once the shared window was lost.
    private func inflate(_ side: Side, _ data: [UInt8], complete: Bool) -> ([UInt8], Bool)? {
        if side.inflaterBroken { return nil }
        guard complete else {
            // Part of the stream was not kept, so later messages that refer back to it cannot be read.
            if !side.reset { side.inflaterBroken = true }
            return nil
        }
        guard let engine = side.inflater ?? StreamInflater() else { side.inflaterBroken = true; return nil }
        side.inflater = engine
        guard let out = engine.inflate(data + [0, 0, 0xFF, 0xFF], cap: cap) else {
            side.inflaterBroken = true
            side.inflater = nil
            return nil
        }
        if side.reset { side.inflater = nil }
        return out
    }

    private func header(_ data: [UInt8], _ at: Int) throws -> Frame? {
        guard data.count - at >= 2 else { return nil }
        let b0 = Int(data[at]), b1 = Int(data[at + 1])
        var length = b1 & 0x7F
        var index = at + 2
        if length == 126 {
            guard data.count - index >= 2 else { return nil }
            length = Int(data[index]) << 8 | Int(data[index + 1])
            index += 2
        } else if length == 127 {
            guard data.count - index >= 8 else { return nil }
            var value: UInt64 = 0
            for i in 0..<8 { value = value << 8 | UInt64(data[index + i]) }
            guard value <= UInt64(Self.maxFrame) else { throw TooLarge() }
            length = Int(value)
            index += 8
        }
        if length > Self.maxFrame { throw TooLarge() }
        var mask: [UInt8]?
        if b1 & 0x80 != 0 {
            guard data.count - index >= 4 else { return nil }
            mask = Array(data[index..<index + 4])
            index += 4
        }
        return Frame(fin: b0 & 0x80 != 0, rsv1: b0 & 0x40 != 0, opcode: b0 & 0x0F, start: index, length: length, mask: mask)
    }
}

/// A raw-DEFLATE decoder that keeps its window between calls, as permessage-deflate needs.
private nonisolated final class StreamInflater {
    private let stream: UnsafeMutablePointer<compression_stream>
    private let chunk = 64 * 1024
    private let buffer: UnsafeMutablePointer<UInt8>

    init?() {
        stream = .allocate(capacity: 1)
        buffer = .allocate(capacity: chunk)
        stream.initialize(to: compression_stream(dst_ptr: buffer, dst_size: 0, src_ptr: UnsafePointer(buffer), src_size: 0, state: nil))
        guard compression_stream_init(stream, COMPRESSION_STREAM_DECODE, COMPRESSION_ZLIB) == COMPRESSION_STATUS_OK else {
            stream.deallocate()
            buffer.deallocate()
            return nil
        }
    }

    deinit {
        compression_stream_destroy(stream)
        stream.deallocate()
        buffer.deallocate()
    }

    /// Everything `input` decodes to, keeping at most `cap` bytes (the flag says more came). Nil on an error.
    func inflate(_ input: [UInt8], cap: Int) -> ([UInt8], Bool)? {
        var out: [UInt8] = []
        var over = false
        let ok = input.withUnsafeBufferPointer { src -> Bool in
            stream.pointee.src_ptr = src.baseAddress!
            stream.pointee.src_size = src.count
            var idle = 0
            while true {
                stream.pointee.dst_ptr = buffer
                stream.pointee.dst_size = chunk
                let status = compression_stream_process(stream, 0)
                let produced = chunk - stream.pointee.dst_size
                if produced > 0 {
                    let room = cap - out.count
                    if produced > room {
                        if room > 0 { out.append(contentsOf: UnsafeBufferPointer(start: buffer, count: room)) }
                        over = true
                    } else {
                        out.append(contentsOf: UnsafeBufferPointer(start: buffer, count: produced))
                    }
                }
                if status == COMPRESSION_STATUS_ERROR { return false }
                if status == COMPRESSION_STATUS_END { return true }
                if stream.pointee.src_size == 0 && produced < chunk { return true }
                // No progress on what is left: stop rather than spin.
                idle = produced == 0 ? idle + 1 : 0
                if idle > 2 { return true }
            }
        }
        return ok ? (out, over) : nil
    }
}
