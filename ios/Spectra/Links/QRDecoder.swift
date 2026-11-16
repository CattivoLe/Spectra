import CoreGraphics
import CoreImage
import Foundation
import Vision

/// Finds codes in a still image: a screenshot of a message, a photo of a
/// poster. The camera path lives in `QRScannerView`; this one also works
/// where there is no camera.
nonisolated enum QRDecoder {
    static let symbologies: [VNBarcodeSymbology] = [.qr, .microQR, .aztec, .dataMatrix]

    /// The text of every code found, largest first. Vision reads QR, Aztec
    /// and Data Matrix; where it cannot run (the simulator, some older
    /// devices) Core Image's QR detector takes over.
    static func payloads(in image: CGImage) throws -> [String] {
        do {
            return try visionPayloads(in: image)
        } catch {
            return coreImagePayloads(in: image)
        }
    }

    private static func visionPayloads(in image: CGImage) throws -> [String] {
        let request = VNDetectBarcodesRequest()
        request.symbologies = symbologies
        let handler = VNImageRequestHandler(cgImage: image, options: [:])
        try handler.perform([request])
        let found = request.results ?? []
        return found
            .sorted { $0.boundingBox.width * $0.boundingBox.height > $1.boundingBox.width * $1.boundingBox.height }
            .compactMap(\.payloadStringValue)
    }

    private static func coreImagePayloads(in image: CGImage) -> [String] {
        guard let detector = CIDetector(ofType: CIDetectorTypeQRCode, context: nil, options: [CIDetectorAccuracy: CIDetectorAccuracyHigh]) else {
            return []
        }
        let features = detector.features(in: CIImage(cgImage: image))
        return features
            .compactMap { $0 as? CIQRCodeFeature }
            .sorted { $0.bounds.width * $0.bounds.height > $1.bounds.width * $1.bounds.height }
            .compactMap(\.messageString)
    }
}
