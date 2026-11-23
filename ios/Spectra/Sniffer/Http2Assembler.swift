import Foundation

/// Reads one connection's cleartext into exchanges: HTTP/1.1 (`HttpAssembler`) or HTTP/2 (`Http2Assembler`).
nonisolated protocol HttpStream: AnyObject {
    func client(_ data: [UInt8])
    func server(_ data: [UInt8])
    func serverClosed()
    func exchanges() -> [HttpExchange]
}

/// Reads an HTTP/2 connection (RFC 9113) into exchanges, one per stream: the frames of both directions, header
/// blocks through `HpackDecoder`, bodies from DATA frames. Times and sizes are kept as for HTTP/1.1; header
/// sizes count the compressed blocks and their frame headers. Trailers (gRPC's status lives there) are added to
/// the response headers. Port of Android `core/Http2Assembler.kt`.
nonisolated final class Http2Assembler: HttpStream {
    static let version = "HTTP/2"
    /// Streams listed for one connection; later ones are read but not kept.
    static let maxStreams = 200
    static let preface = Array("PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".utf8)
    private static let maxFrame = 16 * 1024 * 1024

    private let sink: HttpAssembler.Sink
    private let clock: () -> Int64
    private var clientSide: Direction!
    private var serverSide: Direction!
    private var streams: [Int: Stream] = [:]
    private var ready: [HttpExchange] = []
    private var broken = false

    init(sink: @escaping HttpAssembler.Sink = { _, _, _ in false },
         clock: @escaping () -> Int64 = { Int64(Date().timeIntervalSince1970 * 1000) }) {
        self.sink = sink
        self.clock = clock
        clientSide = Direction(owner: self, fromClient: true)
        serverSide = Direction(owner: self, fromClient: false)
    }

    func exchanges() -> [HttpExchange] { ready }
    func client(_ data: [UInt8]) { clientSide.feed(data) }
    func server(_ data: [UInt8]) { serverSide.feed(data) }

    func serverClosed() {
        let now = clock()
        for stream in streams.values where !stream.responseDone && stream.responseStartAt != nil {
            finish(stream, request: false, now)
        }
    }

    private struct Body {
        let cap: Int
        var kept: [UInt8] = []
        var wire: Int64 = 0
        var cut = false

        mutating func add(_ data: ArraySlice<UInt8>) {
            wire += Int64(data.count)
            let room = cap - kept.count
            if data.count > room {
                if room > 0 { kept += data.prefix(room) }
                cut = true
            } else {
                kept += data
            }
        }
    }

    private final class Stream {
        let id: Int, index: Int, startedAt: Int64
        var requestHeaders: [HttpField] = []
        var responseHeaders: [HttpField] = []
        var status: Int?
        var requestHeaderBytes: Int64 = 0
        var responseHeaderBytes: Int64 = 0
        var requestBody = Body(cap: HttpAssembler.requestCap)
        var responseBody = Body(cap: HttpAssembler.responseCap)
        var requestEndAt: Int64?
        var responseStartAt: Int64?
        var endedAt: Int64?
        var requestDone = false, responseDone = false
        var requestStored = false, requestCut = false
        var responseStored = false, responseCut = false
        var parameters: [HttpField] = []

        init(id: Int, index: Int, startedAt: Int64) { self.id = id; self.index = index; self.startedAt = startedAt }
    }

    /// A header block spread over HEADERS (or PUSH_PROMISE) and CONTINUATION frames.
    private struct Block {
        let stream: Int
        let endStream: Bool
        let promised: Int?
        var bytes: [UInt8] = []
        var frames = 1
    }

    private struct Malformed: Error {}

    private final class Direction {
        unowned let owner: Http2Assembler
        let fromClient: Bool
        let hpack = HpackDecoder()
        var data: [UInt8] = []
        var head = 0
        var preface: Bool
        var block: Block?

        init(owner: Http2Assembler, fromClient: Bool) {
            self.owner = owner
            self.fromClient = fromClient
            preface = fromClient
        }

        func feed(_ bytes: [UInt8]) {
            if owner.broken || bytes.isEmpty { return }
            data += bytes
            do { try pump() } catch { owner.broken = true }
        }

        private func pump() throws {
            if preface {
                guard data.count - head >= Http2Assembler.preface.count else { return }
                guard Array(data[head..<head + Http2Assembler.preface.count]) == Http2Assembler.preface else { throw Malformed() }
                head += Http2Assembler.preface.count
                preface = false
            }
            while data.count - head >= 9 {
                let length = Int(data[head]) << 16 | Int(data[head + 1]) << 8 | Int(data[head + 2])
                if length > Http2Assembler.maxFrame { throw Malformed() }
                guard data.count - head >= 9 + length else { break }
                let type = Int(data[head + 3]), flags = Int(data[head + 4])
                let id = u31(head + 5)
                let start = head + 9
                head = start + length
                try frame(type, flags, id, start, start + length)
            }
            if head > 0 {
                data.removeFirst(head)
                head = 0
            }
        }

        private func u31(_ at: Int) -> Int {
            Int(data[at] & 0x7F) << 24 | Int(data[at + 1]) << 16 | Int(data[at + 2]) << 8 | Int(data[at + 3])
        }

        private func frame(_ type: Int, _ flags: Int, _ id: Int, _ start: Int, _ end: Int) throws {
            let now = owner.clock()
            switch type {
            case 0: // DATA
                guard let stream = owner.streams[id] else { return }
                let (from, to) = try unpad(flags, start, end)
                if stream.index >= 0 {
                    if fromClient { stream.requestBody.add(data[from..<to]) } else { stream.responseBody.add(data[from..<to]) }
                } else if fromClient {
                    stream.requestBody.wire += Int64(to - from)
                } else {
                    stream.responseBody.wire += Int64(to - from)
                }
                if flags & 0x1 != 0 { owner.finish(stream, request: fromClient, now) }
            case 1: // HEADERS
                var (from, to) = try unpad(flags, start, end)
                if flags & 0x20 != 0 { from += 5 }
                try open(Block(stream: id, endStream: flags & 0x1 != 0, promised: nil), flags, from, to, now)
            case 5: // PUSH_PROMISE
                let (from, to) = try unpad(flags, start, end)
                guard to - from >= 4 else { throw Malformed() }
                try open(Block(stream: id, endStream: false, promised: u31(from)), flags, from + 4, to, now)
            case 9: // CONTINUATION
                guard var current = block else { throw Malformed() }
                current.bytes += data[start..<end]
                current.frames += 1
                if flags & 0x4 != 0 { block = nil; try close(current, now) } else { block = current }
            case 3: // RST_STREAM
                if let stream = owner.streams[id] {
                    if !stream.requestDone { owner.finish(stream, request: true, now) }
                    if !stream.responseDone { owner.finish(stream, request: false, now) }
                }
            default:
                break
            }
        }

        private func open(_ next: Block, _ flags: Int, _ from: Int, _ to: Int, _ now: Int64) throws {
            guard to >= from else { throw Malformed() }
            var block = next
            block.bytes = Array(data[from..<to])
            if flags & 0x4 != 0 { try close(block, now) } else { self.block = block }
        }

        private func unpad(_ flags: Int, _ start: Int, _ end: Int) throws -> (Int, Int) {
            guard flags & 0x8 != 0 else { return (start, end) }
            guard start < end else { throw Malformed() }
            let pad = Int(data[start])
            guard start + 1 + pad <= end else { throw Malformed() }
            return (start + 1, end - pad)
        }

        /// Decodes a finished header block; every block is decoded, so the table stays in step.
        private func close(_ done: Block, _ now: Int64) throws {
            let fields = try hpack.decode(done.bytes)
            let bytes = Int64(done.bytes.count + 9 * done.frames)
            if let promised = done.promised {
                let stream = owner.start(promised, now)
                stream.requestHeaders = fields
                stream.requestHeaderBytes = bytes
                owner.finish(stream, request: true, now)
                return
            }
            if fromClient {
                if let stream = owner.streams[done.stream] {
                    stream.requestHeaders += fields
                    stream.requestHeaderBytes += bytes
                    if done.endStream { owner.finish(stream, request: true, now) } else { owner.publish(stream) }
                } else {
                    let stream = owner.start(done.stream, now)
                    stream.requestHeaders = fields
                    stream.requestHeaderBytes = bytes
                    if done.endStream { owner.finish(stream, request: true, now) } else { owner.publish(stream) }
                }
            } else {
                guard let stream = owner.streams[done.stream] else { return }
                if stream.responseStartAt == nil { stream.responseStartAt = now }
                stream.responseHeaderBytes += bytes
                let status = fields.first { $0.name == ":status" }.flatMap { Int($0.value) }
                if let status, (100...199).contains(status) {
                    // Interim response: counted, not shown.
                } else if stream.status == nil, let status {
                    stream.status = status
                    stream.responseHeaders = fields
                } else {
                    stream.responseHeaders += fields
                }
                if done.endStream { owner.finish(stream, request: false, now) } else { owner.publish(stream) }
            }
        }
    }

    private func start(_ id: Int, _ now: Int64) -> Stream {
        let index = ready.count < Self.maxStreams ? ready.count : -1
        let stream = Stream(id: id, index: index, startedAt: now)
        streams[id] = stream
        if index >= 0 {
            ready.append(HttpExchange(method: "", path: "", query: [], requestHeaders: [], parameters: [], status: nil, responseHeaders: []))
        }
        return stream
    }

    private func finish(_ stream: Stream, request: Bool, _ now: Int64) {
        if request {
            if stream.requestDone { return }
            stream.requestDone = true
            stream.requestEndAt = now
            if stream.index >= 0 {
                let (decoded, decodeCut) = decodeBody(stream.requestBody.kept, headers: stream.requestHeaders, cap: HttpAssembler.requestCap)
                stream.requestCut = stream.requestBody.cut || decodeCut
                if !stream.requestCut {
                    var fields: [HttpField] = []
                    if isForm(stream.requestHeaders) { fields += formFields(decoded) }
                    if isJSONType(stream.requestHeaders) { fields += jsonFields(decoded) }
                    stream.parameters = Array(fields.prefix(40))
                }
                stream.requestStored = !decoded.isEmpty && sink(stream.index, true, decoded)
                stream.requestBody.kept = []
            }
        } else {
            if stream.responseDone { return }
            stream.responseDone = true
            stream.endedAt = now
            if stream.index >= 0 {
                let (decoded, decodeCut) = decodeBody(stream.responseBody.kept, headers: stream.responseHeaders, cap: HttpAssembler.responseCap)
                stream.responseCut = stream.responseBody.cut || decodeCut
                stream.responseStored = !decoded.isEmpty && sink(stream.index, false, decoded)
                stream.responseBody.kept = []
            }
        }
        publish(stream)
        if stream.requestDone && stream.responseDone { streams.removeValue(forKey: stream.id) }
    }

    private func publish(_ stream: Stream) {
        guard stream.index >= 0 else { return }
        func pseudo(_ name: String) -> String? { stream.requestHeaders.first { $0.name == name }?.value }
        let target = pseudo(":path") ?? "/"
        let responded = stream.responseStartAt != nil
        let path = target.components(separatedBy: "?").first.flatMap { $0.isEmpty ? nil : $0 } ?? "/"
        ready[stream.index] = HttpExchange(
            method: pseudo(":method") ?? "", path: path, query: queryFields(target),
            requestHeaders: stream.requestHeaders, parameters: stream.parameters, status: stream.status,
            responseHeaders: stream.responseHeaders, responseCut: stream.responseCut, requestCut: stream.requestCut,
            requestBodyStored: stream.requestStored, responseBodyStored: stream.responseStored,
            httpVersion: Self.version, target: target,
            startedAt: stream.startedAt, requestEndAt: stream.requestEndAt,
            responseStartAt: stream.responseStartAt, endedAt: stream.endedAt,
            requestHeaderBytes: stream.requestHeaderBytes, requestBodyBytes: stream.requestBody.wire,
            responseHeaderBytes: responded ? stream.responseHeaderBytes : nil,
            responseBodyBytes: responded ? stream.responseBody.wire : nil
        )
    }
}
