import SwiftUI
import UIKit

/// A file to hand to the system share sheet. `Identifiable` so it drives
/// `.sheet(item:)`.
struct ShareItem: Identifiable {
    let id = UUID()
    let url: URL
}

/// Wraps `UIActivityViewController` so SwiftUI can present a share sheet for a
/// file on disk (AirDrop, Files, Mail, …).
struct ShareSheet: UIViewControllerRepresentable {
    let url: URL

    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: [url], applicationActivities: nil)
    }

    func updateUIViewController(_ controller: UIActivityViewController, context: Context) {}
}

extension View {
    /// Presents a share sheet for the given file item.
    func shareSheet(item: Binding<ShareItem?>) -> some View {
        sheet(item: item) { ShareSheet(url: $0.url) }
    }
}

/// Writes an exported file to a temporary URL for sharing, replacing any
/// previous file of the same name.
func writeExport(_ file: Exporter.File) -> URL? {
    let url = FileManager.default.temporaryDirectory.appendingPathComponent(file.name)
    do {
        try file.contents.data(using: .utf8)?.write(to: url, options: .atomic)
        return url
    } catch {
        return nil
    }
}
