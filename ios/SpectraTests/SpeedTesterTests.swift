import Foundation
import Testing
@testable import Spectra

struct SpeedTesterTests {
    @Test func medianAndJitter() {
        #expect(SpeedMath.median([30, 10, 20]) == 20)
        #expect(SpeedMath.median([]) == nil)
        #expect(SpeedMath.jitter([10, 14, 12, 20]) == (4 + 2 + 8) / 3)
        #expect(SpeedMath.jitter([10]) == nil)
    }

    @Test func bufferbloatGrades() {
        #expect(SpeedResult.grade(added: 5) == "A")
        #expect(SpeedResult.grade(added: 45) == "B")
        #expect(SpeedResult.grade(added: 150) == "C")
        #expect(SpeedResult.grade(added: 300) == "D")
        #expect(SpeedResult.grade(added: 900) == "F")
        var result = SpeedResult(measuredAt: .now, streams: 4)
        #expect(result.bufferbloatGrade == nil)
        result.idleLatencyMs = 20
        result.loadedDownLatencyMs = 40
        result.loadedUpLatencyMs = 250
        #expect(result.bufferbloatGrade == "D", "the worse direction counts")
    }

    @Test func resultBecomesAFullSample() throws {
        var result = SpeedResult(measuredAt: .now, streams: 4, server: "BEG")
        result.downBps = 95_000_000
        result.upBps = 20_000_000
        result.idleLatencyMs = 12
        result.jitterMs = 3
        result.loadedDownLatencyMs = 30
        result.downSamples = [1, 2, 3]
        let sample = result.sample
        #expect(sample.isFull)
        #expect(sample.loadedLatencyMs == 30)
        #expect(sample.downSamples == [1, 2, 3])
        #expect(sample.server == "BEG")

        // Round trip through the history file, and a quick sample stays quick.
        var snapshot = LinkSnapshot()
        snapshot.status = .online
        snapshot.speed = sample
        let entry = HistoryEntry(kind: .lan, savedAt: .now, source: "Test", connections: [SavedConnection(viaVpn: false, snapshot: snapshot)])
        let file = HistoryDocument.file([entry], from: "Test")
        let (_, decoded) = try #require(HistoryDocument.decode(Data(file.contents.utf8)))
        #expect(decoded.first?.connections.first?.snapshot.speed == sample)
        let quick = try JSONDecoder().decode(SpeedSample.self, from: Data(#"{"latencyMs":10,"downBps":1000}"#.utf8))
        #expect(!quick.isFull)
    }

    @Test func counterSumsAcrossThreads() async {
        let counter = ByteCounter()
        await withTaskGroup(of: Void.self) { group in
            for _ in 0..<8 {
                group.addTask { for _ in 0..<1000 { counter.add(3) } }
            }
        }
        #expect(counter.total == 24_000)
    }

    @Test func refusedDownloadsAskForLess() {
        let counter = ByteCounter()
        #expect(counter.downloadChunk == ByteCounter.chunkStart)
        #expect(!counter.wasLimited)
        counter.throttle(shrink: true)
        #expect(counter.downloadChunk == ByteCounter.chunkStart / 2)
        #expect(counter.wasLimited)
        for _ in 0..<20 { counter.throttle(shrink: true) }
        #expect(counter.downloadChunk == ByteCounter.chunkFloor)
        let upload = ByteCounter()
        upload.throttle(shrink: false)
        #expect(upload.downloadChunk == ByteCounter.chunkStart)
    }

    @Test func theStatusComesFromTheFirstLine() {
        #expect(SpeedProbe.statusCode(Data("HTTP/1.1 429 Too Many Requests\r\nServer: cloudflare".utf8)) == 429)
        #expect(SpeedProbe.statusCode(Data("HTTP/1.1 200 OK".utf8)) == 200)
        #expect(SpeedProbe.statusCode(Data("garbage".utf8)) == nil)
    }
}
