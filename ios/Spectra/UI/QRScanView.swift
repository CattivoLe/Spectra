import AVFoundation
import PhotosUI
import SwiftUI
import VisionKit

/// Scans a QR code with the camera, or reads one out of a photo, and hands
/// the text back. Nothing is opened: the text goes to the link inspector.
struct QRScanView: View {
    var onFound: (String) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var cameraAccess = AVCaptureDevice.authorizationStatus(for: .video)
    @State private var pickedItem: PhotosPickerItem?
    @State private var photoMessage: String?
    @State private var reading = false

    private var cameraSupported: Bool {
        DataScannerViewController.isSupported && DataScannerViewController.isAvailable
    }

    var body: some View {
        NavigationStack {
            ZStack {
                Color.black.ignoresSafeArea()
                if cameraSupported, cameraAccess == .authorized {
                    QRScannerView { payload in
                        finish(payload)
                    }
                    .ignoresSafeArea()
                    VStack {
                        Spacer()
                        Text("Point the camera at a QR code")
                            .font(.callout)
                            .foregroundStyle(.white)
                            .padding(.horizontal, 14)
                            .padding(.vertical, 8)
                            .background(.black.opacity(0.55), in: .capsule)
                            .padding(.bottom, 24)
                    }
                } else {
                    cameraFallback
                }
            }
            .navigationTitle("Scan QR code")
            .navigationBarTitleDisplayMode(.inline)
            .toolbarColorScheme(.dark, for: .navigationBar)
            .toolbarBackground(.visible, for: .navigationBar)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    PhotosPicker(selection: $pickedItem, matching: .images) {
                        Label("Choose a photo", systemImage: "photo.on.rectangle")
                    }
                    .disabled(reading)
                }
            }
            .task {
                if cameraSupported, cameraAccess == .notDetermined {
                    let granted = await AVCaptureDevice.requestAccess(for: .video)
                    cameraAccess = granted ? .authorized : .denied
                }
            }
            .onChange(of: pickedItem) { _, item in
                guard let item else { return }
                Task { await readPhoto(item) }
            }
            .alert("No QR code", isPresented: Binding(get: { photoMessage != nil }, set: { if !$0 { photoMessage = nil } })) {
                Button("OK", role: .cancel) {}
            } message: {
                Text(photoMessage ?? "")
            }
        }
        .preferredColorScheme(.dark)
    }

    @ViewBuilder
    private var cameraFallback: some View {
        if !cameraSupported {
            ContentUnavailableView {
                Label("No camera scanning", systemImage: "camera.badge.ellipsis")
            } description: {
                Text("Camera scanning is not available on this device. A photo of the code can still be read.")
            } actions: {
                photoButton
            }
            .foregroundStyle(.white)
        } else if cameraAccess == .denied || cameraAccess == .restricted {
            ContentUnavailableView {
                Label("Camera access", systemImage: "camera.fill")
            } description: {
                Text("Allow camera access in Settings to scan codes, or read one from a photo.")
            } actions: {
                if let settings = URL(string: UIApplication.openSettingsURLString) {
                    Link("Open Settings", destination: settings)
                        .buttonStyle(.borderedProminent)
                }
                photoButton
            }
            .foregroundStyle(.white)
        } else {
            ProgressView()
                .tint(.white)
        }
    }

    private var photoButton: some View {
        PhotosPicker(selection: $pickedItem, matching: .images) {
            Label("Choose a photo", systemImage: "photo.on.rectangle")
        }
        .buttonStyle(.bordered)
        .disabled(reading)
    }

    private func readPhoto(_ item: PhotosPickerItem) async {
        reading = true
        defer { reading = false; pickedItem = nil }
        guard let data = try? await item.loadTransferable(type: Data.self),
              let image = UIImage(data: data)?.cgImage else {
            photoMessage = String(localized: "That photo could not be read.")
            return
        }
        let payloads = (try? QRDecoder.payloads(in: image)) ?? []
        guard let first = payloads.first else {
            photoMessage = String(localized: "No QR code found in that photo.")
            return
        }
        finish(first)
    }

    private func finish(_ payload: String) {
        dismiss()
        onFound(payload)
    }
}

/// The live camera view. `DataScannerViewController` draws its own
/// highlights and handles focus; this wrapper only listens for codes.
private struct QRScannerView: UIViewControllerRepresentable {
    var onFound: (String) -> Void

    func makeCoordinator() -> Coordinator { Coordinator(onFound: onFound) }

    func makeUIViewController(context: Context) -> DataScannerViewController {
        let controller = DataScannerViewController(
            recognizedDataTypes: [.barcode(symbologies: QRDecoder.symbologies)],
            qualityLevel: .balanced,
            recognizesMultipleItems: false,
            isHighFrameRateTrackingEnabled: false,
            isHighlightingEnabled: true
        )
        controller.delegate = context.coordinator
        return controller
    }

    func updateUIViewController(_ controller: DataScannerViewController, context: Context) {
        guard !controller.isScanning, !context.coordinator.finished else { return }
        try? controller.startScanning()
    }

    static func dismantleUIViewController(_ controller: DataScannerViewController, coordinator: Coordinator) {
        controller.stopScanning()
    }

    final class Coordinator: NSObject, DataScannerViewControllerDelegate {
        var onFound: (String) -> Void
        var finished = false

        init(onFound: @escaping (String) -> Void) {
            self.onFound = onFound
        }

        func dataScanner(_ scanner: DataScannerViewController, didAdd added: [RecognizedItem], allItems: [RecognizedItem]) {
            report(added, scanner: scanner)
        }

        func dataScanner(_ scanner: DataScannerViewController, didTapOn item: RecognizedItem) {
            report([item], scanner: scanner)
        }

        private func report(_ items: [RecognizedItem], scanner: DataScannerViewController) {
            guard !finished else { return }
            for case .barcode(let code) in items {
                guard let payload = code.payloadStringValue, !payload.isEmpty else { continue }
                finished = true
                scanner.stopScanning()
                onFound(payload)
                return
            }
        }
    }
}
