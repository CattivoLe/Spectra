import Foundation
import Testing
@testable import Spectra

struct FirmwareUpdateTests {
    @Test func crc32MatchesTheZlibVector() {
        #expect(FirmwareUpdate.CRC32.hash(Data("123456789".utf8)) == 0xCBF43926)
    }

    @Test func crc32ChainsAcrossChunks() {
        let whole = Data("spectra-firmware".utf8)
        let split = FirmwareUpdate.CRC32.hash(whole.prefix(6))
        // Recompute the second half the same way hash() chains: feed the
        // running value back in, which undoes the final complement.
        func step(_ crc: UInt32, _ data: Data) -> UInt32 {
            var running = ~crc
            for byte in data {
                running ^= UInt32(byte)
                for _ in 0..<8 {
                    let mix: UInt32 = (running & 1) == 1 ? 0xEDB8_8320 : 0
                    running = (running >> 1) ^ mix
                }
            }
            return ~running
        }
        let chained = step(step(0, whole.prefix(6)), whole.dropFirst(6))
        #expect(chained == FirmwareUpdate.CRC32.hash(whole))
        #expect(split == FirmwareUpdate.CRC32.hash(whole.prefix(6)))
    }

    @Test func rejectsAMergedImageAndAcceptsAnAppImage() throws {
        #expect(throws: FirmwareUpdate.ImageError.notAnAppImage) {
            try FirmwareUpdate.validate(Data(repeating: 0xFF, count: 64))
        }
        #expect(throws: FirmwareUpdate.ImageError.tooSmall) {
            try FirmwareUpdate.validate(Data([0xE9, 0x00]))
        }
        var app = Data([FirmwareUpdate.magic])
        app.append(Data(repeating: 0x11, count: 31))
        try FirmwareUpdate.validate(app)
    }

    @Test func decodesAFailureLaidOutFromTheDocument() throws {
        let bytes: [UInt8] = [
            0x30, 0x04,             // update status, failed
            0x00, 0x10, 0x00, 0x00, // 4096 bytes accepted
            0x06,                   // checksum
        ]
        let status = try #require(FirmwareUpdate.Status.decode(Data(bytes)))
        #expect(status.phase == .failed)
        #expect(status.bytesAccepted == 4096)
        #expect(status.failure == .checksum)
        #expect(SensorProtocol.decode(Data(bytes)) == nil)
    }

    @Test func uploadReassemblesTheImageInOrder() throws {
        var image = Data([FirmwareUpdate.magic])
        image.append(Data(repeating: 0x5A, count: 249))
        var upload = FirmwareUpload(image: image, chunkSize: 100)
        let begin = upload.begin()
        #expect(begin[0] == 0x01)
        #expect(begin.count == 9)
        #expect(u32(begin, 1) == UInt32(image.count))
        #expect(u32(begin, 5) == FirmwareUpdate.CRC32.hash(image))

        var reassembled = Data()
        var packet = upload.handle(FirmwareUpdate.Status(phase: .ready, bytesAccepted: 0, failure: .none))
        var steps = 0
        while let current = packet {
            steps += 1
            #expect(steps < 8)
            if current[0] == 0x03 {
                #expect(upload.phase == .waitDone)
                #expect(reassembled == image)
                packet = nil
            } else {
                #expect(current[0] == 0x02)
                #expect(u32(current, 1) == UInt32(reassembled.count))
                reassembled.append(current.dropFirst(5))
                packet = upload.onWriteCompleted()
            }
        }
        #expect(reassembled == image)
        #expect(upload.handle(FirmwareUpdate.Status(phase: .done, bytesAccepted: UInt32(image.count), failure: .none)) == nil)
        #expect(upload.phase == .succeeded)
    }

    @Test func aBadOffsetFailsTheUpload() {
        var image = Data([FirmwareUpdate.magic])
        image.append(Data(repeating: 0x01, count: 40))
        var upload = FirmwareUpload(image: image, chunkSize: 16)
        _ = upload.begin()
        _ = upload.handle(FirmwareUpdate.Status(phase: .ready, bytesAccepted: 0, failure: .none))
        #expect(upload.handle(FirmwareUpdate.Status(phase: .failed, bytesAccepted: 0, failure: .badOffset)) == nil)
        #expect(upload.phase == .failed(.badOffset))
    }

    @Test func infoWithoutOtaDecodesAsUnsupported() throws {
        let json = #"{"proto":5,"fw":"0.5.0","chip":"ESP32-S3","bands":["2.4"],"interval":5}"#
        let info = try JSONDecoder().decode(SensorInfo.self, from: Data(json.utf8))
        #expect(info.ota == false)
        #expect(info.fw == "0.5.0")
    }

    @Test func infoWithOtaDecodes() throws {
        let json = #"{"proto":5,"fw":"0.6.0","chip":"ESP32-S3","bands":["2.4"],"interval":0,"ota":true}"#
        let info = try JSONDecoder().decode(SensorInfo.self, from: Data(json.utf8))
        #expect(info.ota)
        #expect(info.interval == 0)
    }

    private func u32(_ data: Data, _ offset: Int) -> UInt32 {
        let bytes = [UInt8](data)
        return UInt32(bytes[offset])
            | UInt32(bytes[offset + 1]) << 8
            | UInt32(bytes[offset + 2]) << 16
            | UInt32(bytes[offset + 3]) << 24
    }
}
