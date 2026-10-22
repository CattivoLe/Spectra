import SwiftUI
import UniformTypeIdentifiers

/// Imports a `.pcap`/`.pcapng` file and shows an approximate decode: a summary,
/// a protocol and application breakdown, the busiest endpoints and
/// conversations, and the first packets. Parsing runs off the main actor.
struct PcapView: View {
    /// A capture already in memory, such as one just taken from the sensor.
    struct Capture: Identifiable, Hashable {
        let id = UUID()
        var name: String
        var data: Data
    }

    var capture: Capture?
    /// A file picked before this view appeared (e.g. straight from History), decoded on appear.
    var fileURL: URL?

    @State private var picking = false
    @State private var phase: Phase = .idle
    @State private var shareItem: ShareItem?

    private func save(_ capture: Capture) {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(capture.name)
        do {
            try capture.data.write(to: url, options: .atomic)
            shareItem = ShareItem(url: url)
        } catch {
            // Writing to the temporary directory should not fail; nothing to show if it does.
        }
    }

    private enum Phase {
        case idle
        case loading(name: String)
        case loaded(name: String, analysis: CaptureAnalysis)
        case failed(message: String)
    }

    /// Result of reading and decoding a file off the main actor. `Result` is not
    /// usable here because its failure must be an `Error`, and ours is a message.
    private enum LoadOutcome: Sendable {
        case ok(CaptureAnalysis)
        case error(String)
    }

    var body: some View {
        List {
            switch phase {
            case .idle:
                if let fileURL, capture == nil {
                    decodingRow(name: fileURL.lastPathComponent)
                } else {
                    idle
                }
            case .loading(let name):
                decodingRow(name: name)
            case .loaded(let name, let analysis):
                results(name: name, analysis: analysis)
            case .failed(let message):
                Section {
                    Label(message, systemImage: "exclamationmark.triangle.fill")
                        .foregroundStyle(.orange)
                    chooseButton(title: "Choose a file…")
                }
            }
        }
        .navigationTitle("Decode pcap")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            if let capture {
                // A capture handed over in memory is saved from here; there is no file to swap.
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Save .pcap", systemImage: "square.and.arrow.up") { save(capture) }
                }
            } else if case .loaded = phase {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Open another", systemImage: "folder") { picking = true }
                }
            }
        }
        .shareSheet(item: $shareItem)
        .fileImporter(
            isPresented: $picking,
            allowedContentTypes: Self.captureFileTypes,
            allowsMultipleSelection: false
        ) { load($0) }
        .task(id: capture?.id) {
            guard let capture, case .idle = phase else { return }
            phase = .loading(name: capture.name)
            let data = capture.data
            switch await Task.detached(priority: .userInitiated) { Self.analyze(data) }.value {
            case .ok(let analysis): phase = .loaded(name: capture.name, analysis: analysis)
            case .error(let message): phase = .failed(message: message)
            }
        }
        .task(id: fileURL) {
            guard capture == nil, let fileURL, case .idle = phase else { return }
            beginLoading(fileURL)
        }
    }

    private func decodingRow(name: String) -> some View {
        Section {
            HStack(spacing: 10) {
                ProgressView()
                Text("Decoding \(name)…").foregroundStyle(.secondary)
            }
        }
    }

    private var idle: some View {
        Section {
            chooseButton(title: "Choose a capture…")
        } footer: {
            Text("Open a .pcap or .pcapng saved from Wireshark, tcpdump or the Cardputer. Spectra reads it on the phone and shows a rough decode: protocols, the busiest hosts and the first packets. Nothing leaves the device.")
        }
    }

    private func chooseButton(title: LocalizedStringKey) -> some View {
        Button(title, systemImage: "doc.badge.plus") { picking = true }
    }

    // MARK: Results

    @ViewBuilder
    private func results(name: String, analysis: CaptureAnalysis) -> some View {
        let summary = analysis.summary

        Section("Capture") {
            InfoRow("File", value: name)
            InfoRow("Format", value: analysis.format.uppercased())
            InfoRow("Link layer", value: summary.linkTypes.isEmpty ? "—" : summary.linkTypes.joined(separator: ", "))
            InfoRow("Packets", value: "\(summary.packetCount)")
            InfoRow("Captured", value: bytes(summary.capturedBytes))
            if summary.wireBytes != summary.capturedBytes {
                InfoRow("On the wire", value: bytes(summary.wireBytes))
            }
            if let duration = summary.duration {
                InfoRow("Duration", value: Duration.seconds(duration).formatted(.units(allowed: [.hours, .minutes, .seconds], width: .narrow)))
            }
            if let start = summary.start {
                InfoRow("Started") { Text(start, format: .dateTime.year().month().day().hour().minute().second()) }
            }
        }

        if summary.packetCount == 0 {
            Section {
                Text("The file parsed, but it holds no packets.").foregroundStyle(.secondary)
            }
        }

        if !summary.protocols.isEmpty {
            Section("Protocols") {
                ForEach(summary.protocols) { item in
                    CountBar(name: item.name, count: item.count, total: summary.packetCount)
                }
            }
        }

        if !summary.apps.isEmpty {
            Section {
                ForEach(summary.apps) { item in
                    CountBar(name: item.name, count: item.count, total: summary.packetCount)
                }
            } header: {
                Text("Applications")
            } footer: {
                Text("Guessed from well-known ports, so it is a hint, not proof.")
            }
        }

        if !summary.talkers.isEmpty {
            Section("Top hosts") {
                ForEach(summary.talkers) { talker in
                    HStack {
                        Text(talker.endpoint).font(.callout.monospaced()).lineLimit(1).truncationMode(.middle)
                        Spacer(minLength: 8)
                        Text("\(talker.packets) · \(bytes(talker.bytes))")
                            .font(.caption.monospacedDigit())
                            .foregroundStyle(.secondary)
                    }
                }
            }
        }

        if !summary.conversations.isEmpty {
            Section("Conversations") {
                ForEach(summary.conversations) { conversation in
                    VStack(alignment: .leading, spacing: 2) {
                        Text("\(conversation.a) ↔ \(conversation.b)")
                            .font(.callout.monospaced()).lineLimit(1).truncationMode(.middle)
                        Text("\(conversation.packets) packets · \(bytes(conversation.bytes))")
                            .font(.caption.monospacedDigit())
                            .foregroundStyle(.secondary)
                    }
                }
            }
        }

        if !analysis.rows.isEmpty {
            Section {
                ForEach(analysis.rows) { row in
                    PacketRowView(row: row, start: summary.start)
                }
            } header: {
                Text("Packets")
            } footer: {
                if analysis.rowsTruncated {
                    Text("Showing the first \(analysis.rows.count) packets. The summary above counts all \(summary.packetCount).")
                }
            }
        }
    }

    private func bytes(_ count: Int) -> String {
        Int64(count).formatted(.byteCount(style: .file))
    }

    // MARK: Loading

    static var captureFileTypes: [UTType] {
        var types: [UTType] = [.data]
        for ext in ["pcap", "pcapng", "cap"] {
            if let type = UTType(filenameExtension: ext) { types.append(type) }
        }
        return types
    }

    private func load(_ result: Result<[URL], Error>) {
        switch result {
        case .failure:
            phase = .failed(message: String(localized: "Could not open the file."))
        case .success(let urls):
            guard let url = urls.first else { return }
            beginLoading(url)
        }
    }

    private func beginLoading(_ url: URL) {
        let name = url.lastPathComponent
        phase = .loading(name: name)
        Task {
            let outcome = await Self.readAndAnalyze(url)
            switch outcome {
            case .ok(let analysis): phase = .loaded(name: name, analysis: analysis)
            case .error(let message): phase = .failed(message: message)
            }
        }
    }

    private static func readAndAnalyze(_ url: URL) async -> LoadOutcome {
        await Task.detached(priority: .userInitiated) { () -> LoadOutcome in
            let accessed = url.startAccessingSecurityScopedResource()
            defer { if accessed { url.stopAccessingSecurityScopedResource() } }
            guard let data = try? Data(contentsOf: url, options: .mappedIfSafe) else {
                return .error(String(localized: "Could not read the file."))
            }
            guard data.count <= 80_000_000 else {
                return .error(String(localized: "That capture is too large to open on the phone (over 80 MB)."))
            }
            return Self.analyze(data)
        }.value
    }

    nonisolated private static func analyze(_ data: Data) -> LoadOutcome {
        do {
            return .ok(try PcapAnalyzer.analyze(data))
        } catch PcapError.notACapture {
            return .error(String(localized: "This is not a pcap or pcapng file."))
        } catch PcapError.tooShort {
            return .error(String(localized: "This file is too short to be a capture."))
        } catch {
            return .error(String(localized: "Could not decode this capture."))
        }
    }
}

/// A name with a packet count and a proportional bar.
private struct CountBar: View {
    var name: String
    var count: Int
    var total: Int

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                Text(name).font(.callout)
                Spacer(minLength: 8)
                Text("\(count)").font(.caption.monospacedDigit()).foregroundStyle(.secondary)
            }
            GeometryReader { geometry in
                let fraction = total > 0 ? Double(count) / Double(total) : 0
                Capsule()
                    .fill(.tint)
                    .frame(width: max(2, geometry.size.width * fraction))
            }
            .frame(height: 4)
        }
    }
}

private struct PacketRowView: View {
    var row: PacketRow
    var start: Date?

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            HStack(alignment: .firstTextBaseline) {
                Text(offset).font(.caption.monospacedDigit()).foregroundStyle(.secondary)
                Text(row.transport).font(.caption.weight(.semibold))
                Spacer(minLength: 8)
                Text("\(row.length) B").font(.caption.monospacedDigit()).foregroundStyle(.secondary)
            }
            Text(row.info)
                .font(.footnote.monospaced())
                .lineLimit(2)
                .truncationMode(.middle)
        }
    }

    private var offset: String {
        guard let start, let time = row.timestamp else { return "—" }
        return String(format: "+%.3fs", time.timeIntervalSince(start))
    }
}
