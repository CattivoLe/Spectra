import SwiftUI
import UniformTypeIdentifiers
import LocalChatKit

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
    @State private var filter = PacketFilter()
    @Environment(CaptureShelf.self) private var captures

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
        // Opens a summary of this capture right here: it is kept on the shelf the card reads first.
        .summaryButton(isShown: loadedName != nil,
                       subject: { .capture },
                       prepare: {
                          if case .loaded(let name, let analysis) = phase {
                              captures.keep(name: name, analysis: analysis, fromSensor: capture != nil)
                          }
                      })
        .onChange(of: loadedName) {
            // Whatever was decoded last is what the assistant talks about when asked about "the capture".
            if case .loaded(let name, let analysis) = phase {
                captures.keep(name: name, analysis: analysis, fromSensor: capture != nil)
            }
        }
        .fileImporter(
            isPresented: $picking,
            allowedContentTypes: Self.captureFileTypes,
            allowsMultipleSelection: false
        ) { load($0) }
        .task(id: capture?.id) {
            guard let capture, case .idle = phase else { return }
            phase = .loading(name: capture.name)
            filter = PacketFilter()
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

    /// Changes once per decoded capture, to notice a new one.
    private var loadedName: String? {
        if case .loaded(let name, _) = phase { return name }
        return nil
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
            Section {
                ForEach(summary.talkers) { talker in
                    Button {
                        filter.endpoints = filter.endpoints == [talker.endpoint] ? [] : [talker.endpoint]
                    } label: {
                        HStack {
                            Text(talker.endpoint).font(.callout.monospaced()).lineLimit(1).truncationMode(.middle)
                            Spacer(minLength: 8)
                            Text("\(talker.packets) · \(bytes(talker.bytes))")
                                .font(.caption.monospacedDigit())
                                .foregroundStyle(.secondary)
                            if filter.endpoints == [talker.endpoint] {
                                Image(systemName: "line.3.horizontal.decrease.circle.fill").foregroundStyle(.tint)
                            }
                        }
                    }
                    .buttonStyle(.plain)
                }
            } header: {
                Text("Top hosts")
            } footer: {
                Text("Tap a host or a conversation to show only its packets.")
            }
        }

        if !summary.conversations.isEmpty {
            Section("Conversations") {
                ForEach(summary.conversations) { conversation in
                    let pair = [conversation.a, conversation.b]
                    Button {
                        filter.endpoints = filter.endpoints == pair ? [] : pair
                    } label: {
                        HStack {
                            VStack(alignment: .leading, spacing: 2) {
                                Text("\(conversation.a) ↔ \(conversation.b)")
                                    .font(.callout.monospaced()).lineLimit(1).truncationMode(.middle)
                                Text("\(conversation.packets) packets · \(bytes(conversation.bytes))")
                                    .font(.caption.monospacedDigit())
                                    .foregroundStyle(.secondary)
                            }
                            Spacer(minLength: 8)
                            if filter.endpoints == pair {
                                Image(systemName: "line.3.horizontal.decrease.circle.fill").foregroundStyle(.tint)
                            }
                        }
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                }
            }
        }

        if !analysis.rows.isEmpty {
            packetFilters(summary)
            let matching = filter.isActive ? analysis.rows.filter(filter.matches) : analysis.rows
            let shown = matching.prefix(CaptureAnalysis.shownRows)
            Section {
                ForEach(shown) { row in
                    PacketRowView(row: row, start: summary.start)
                }
                if matching.isEmpty {
                    Text("No packet matches these filters.")
                        .foregroundStyle(.secondary)
                }
            } header: {
                if filter.isActive {
                    Text("Packets: \(matching.count) of \(analysis.rows.count)")
                } else {
                    Text("Packets")
                }
            } footer: {
                if shown.count < matching.count {
                    Text("Showing the first \(shown.count) of \(matching.count). Narrow the filters to see the rest.")
                } else if analysis.rowsTruncated {
                    Text("The list holds the first \(analysis.rows.count) packets. The summary above counts all \(summary.packetCount).")
                }
            }
        }
    }

    /// Search, protocol and application chips, and the host picked above.
    @ViewBuilder
    private func packetFilters(_ summary: CaptureSummary) -> some View {
        Section {
            HStack(spacing: 8) {
                Image(systemName: "magnifyingglass").foregroundStyle(.secondary)
                TextField("Address, port or text", text: $filter.query)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                if !filter.query.isEmpty {
                    Button("Clear", systemImage: "xmark.circle.fill") { filter.query = "" }
                        .labelStyle(.iconOnly)
                        .foregroundStyle(.secondary)
                        .buttonStyle(.borderless)
                }
            }
            if summary.protocols.count > 1 {
                FlowLayout(spacing: 8, lineSpacing: 8) {
                    ForEach(summary.protocols) { item in
                        FilterChip(title: LocalizedStringKey(item.name), systemImage: nil, isOn: filter.transports.contains(item.name)) {
                            filter.toggle(transport: item.name)
                        }
                    }
                }
                .padding(.vertical, 2)
            }
            if !summary.apps.isEmpty {
                FlowLayout(spacing: 8, lineSpacing: 8) {
                    ForEach(summary.apps) { item in
                        FilterChip(title: LocalizedStringKey(item.name), systemImage: nil, isOn: filter.app == item.name) {
                            filter.app = filter.app == item.name ? nil : item.name
                        }
                    }
                }
                .padding(.vertical, 2)
            }
            if !filter.endpoints.isEmpty {
                HStack(spacing: 8) {
                    Image(systemName: "network").foregroundStyle(.tint)
                    Text(filter.endpoints.joined(separator: " ↔ "))
                        .font(.callout.monospaced())
                        .lineLimit(1)
                        .truncationMode(.middle)
                    Spacer(minLength: 8)
                    Button("Remove", systemImage: "xmark.circle.fill") { filter.endpoints = [] }
                        .labelStyle(.iconOnly)
                        .foregroundStyle(.secondary)
                        .buttonStyle(.borderless)
                }
            }
            if filter.isActive {
                Button("Reset filters", systemImage: "arrow.counterclockwise") { filter = PacketFilter() }
                    .buttonStyle(.borderless)
            }
        } header: {
            Text("Filter")
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
        filter = PacketFilter()
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
