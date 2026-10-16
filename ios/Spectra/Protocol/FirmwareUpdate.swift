import Foundation

/// Delivery of an ESP32 application image over the Firmware characteristic.
/// docs/protocol.md is the source of truth.
nonisolated enum FirmwareUpdate {
    static let maxImageBytes = 0x330000
    static let maxChunkBytes = 500
    /// ESP32 application images start with this byte. Merged flash images do not.
    static let magic: UInt8 = 0xE9

    enum Command: Equatable {
        case begin(size: UInt32, crc: UInt32)
        case chunk(offset: UInt32, bytes: Data)
        case finish
        case abort

        var data: Data {
            switch self {
            case .begin(let size, let crc):
                var bytes: [UInt8] = [0x01]
                bytes.append(contentsOf: Self.le32(size))
                bytes.append(contentsOf: Self.le32(crc))
                return Data(bytes)
            case .chunk(let offset, let payload):
                var bytes: [UInt8] = [0x02]
                bytes.append(contentsOf: Self.le32(offset))
                bytes.append(contentsOf: payload)
                return Data(bytes)
            case .finish:
                return Data([0x03])
            case .abort:
                return Data([0x04])
            }
        }

        private static func le32(_ value: UInt32) -> [UInt8] {
            [
                UInt8(value & 0xFF),
                UInt8((value >> 8) & 0xFF),
                UInt8((value >> 16) & 0xFF),
                UInt8((value >> 24) & 0xFF),
            ]
        }
    }

    struct Status: Equatable {
        enum Phase: UInt8 {
            case ready = 1
            case progress = 2
            case done = 3
            case failed = 4
        }

        enum Failure: UInt8 {
            case none = 0
            case busy = 1
            case tooLarge = 2
            case beginFailed = 3
            case badOffset = 4
            case writeFailed = 5
            case checksum = 6
            case finalizeFailed = 7
            case aborted = 8
            case badImage = 9

            var message: String {
                switch self {
                case .none, .finalizeFailed:
                    String(localized: "The sensor could not finish the update.")
                case .busy:
                    String(localized: "The sensor is still scanning. Wait for the sweep to finish and try again.")
                case .tooLarge:
                    String(localized: "This image does not fit in the sensor's update slot.")
                case .beginFailed:
                    String(localized: "The sensor could not start the update. Flash it once over USB so the OTA slots exist, then try again.")
                case .badOffset:
                    String(localized: "The sensor lost its place in the image.")
                case .writeFailed:
                    String(localized: "The sensor could not write the image.")
                case .checksum:
                    String(localized: "The image failed its checksum. Transfer it again.")
                case .aborted:
                    String(localized: "Update cancelled.")
                case .badImage:
                    String(localized: "The sensor rejected the image header.")
                }
            }
        }

        var phase: Phase
        var bytesAccepted: UInt32
        var failure: Failure

        static func decode(_ data: Data) -> Status? {
            let bytes = [UInt8](data)
            guard bytes.count >= 7, bytes[0] == 0x30, let phase = Phase(rawValue: bytes[1]) else {
                return nil
            }
            let accepted = UInt32(bytes[2])
                | UInt32(bytes[3]) << 8
                | UInt32(bytes[4]) << 16
                | UInt32(bytes[5]) << 24
            let failure = Failure(rawValue: bytes[6]) ?? .finalizeFailed
            return Status(phase: phase, bytesAccepted: accepted, failure: failure)
        }
    }

    enum ImageError: Error, Equatable {
        case tooSmall
        case tooLarge
        case notAnAppImage

        var message: String {
            switch self {
            case .tooSmall, .notAnAppImage:
                String(localized: "This is not an ESP32 application image. Choose firmware.bin from pio run, not a merged flash image.")
            case .tooLarge:
                String(localized: "This image does not fit in the sensor's update slot.")
            }
        }
    }

    enum TransferError: Error {
        case notConnected
        case timedOut
        case cancelled
        case linkLost

        var message: String {
            switch self {
            case .notConnected:
                String(localized: "The sensor is not connected.")
            case .timedOut:
                String(localized: "The update timed out. Keep the Cardputer close and try again.")
            case .cancelled:
                String(localized: "Update cancelled.")
            case .linkLost:
                String(localized: "The link to the sensor dropped.")
            }
        }
    }

    static func validate(_ image: Data) throws {
        guard image.count >= 32 else { throw ImageError.tooSmall }
        guard image.count <= maxImageBytes else { throw ImageError.tooLarge }
        guard image[image.startIndex] == magic else { throw ImageError.notAnAppImage }
    }

    /// ISO-HDLC CRC-32, the same value as zlib. `123456789` hashes to `0xCBF43926`.
    enum CRC32 {
        static func hash(_ data: Data) -> UInt32 {
            var crc: UInt32 = 0xFFFF_FFFF
            for byte in data {
                crc ^= UInt32(byte)
                for _ in 0..<8 {
                    let mix: UInt32 = (crc & 1) == 1 ? 0xEDB8_8320 : 0
                    crc = (crc >> 1) ^ mix
                }
            }
            return ~crc
        }
    }
}

/// What the Sensor tab shows while an image is on its way to the Cardputer.
struct FirmwareUpdateProgress: Equatable {
    enum Stage: Equatable {
        case preparing
        case uploading(sent: Int, total: Int)
        case finishing
        case rebooting
        case succeeded(version: String)
        case failed(String)

        var isRunning: Bool {
            switch self {
            case .preparing, .uploading, .finishing, .rebooting: true
            case .succeeded, .failed: false
            }
        }
    }

    var stage: Stage
    var isRunning: Bool { stage.isRunning }
}

/// Orders Begin, data chunks, and Finish. The link waits for a GATT response
/// before asking for the next packet, so the device never sees a gap.
nonisolated struct FirmwareUpload {
    enum Phase: Equatable {
        case idle
        case waitReady
        case sending
        case waitDone
        case succeeded
        case failed(FirmwareUpdate.Status.Failure)
    }

    let image: Data
    let crc: UInt32
    let chunkSize: Int
    private(set) var offset = 0
    private(set) var phase: Phase = .idle

    init(image: Data, chunkSize: Int) {
        self.image = image
        self.crc = FirmwareUpdate.CRC32.hash(image)
        let capped = min(FirmwareUpdate.maxChunkBytes, max(1, chunkSize))
        self.chunkSize = capped
    }

    mutating func begin() -> Data {
        phase = .waitReady
        offset = 0
        return FirmwareUpdate.Command.begin(size: UInt32(image.count), crc: crc).data
    }

    /// A status notification arrived. The first chunk is returned once the
    /// device says it is ready.
    mutating func handle(_ status: FirmwareUpdate.Status) -> Data? {
        switch status.phase {
        case .failed:
            phase = .failed(status.failure == .none ? .finalizeFailed : status.failure)
            return nil
        case .done:
            phase = .succeeded
            return nil
        case .ready:
            guard phase == .waitReady else { return nil }
            phase = .sending
            return nextChunk()
        case .progress:
            return nil
        }
    }

    /// The previous write on the Firmware characteristic completed.
    mutating func onWriteCompleted() -> Data? {
        guard phase == .sending else { return nil }
        if offset >= image.count {
            phase = .waitDone
            return FirmwareUpdate.Command.finish.data
        }
        return nextChunk()
    }

    var abortCommand: Data { FirmwareUpdate.Command.abort.data }

    private mutating func nextChunk() -> Data {
        let end = min(offset + chunkSize, image.count)
        let payload = image.subdata(in: offset..<end)
        let packet = FirmwareUpdate.Command.chunk(offset: UInt32(offset), bytes: payload).data
        offset = end
        return packet
    }
}
