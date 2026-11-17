import SwiftUI
import UniformTypeIdentifiers
import LocalChatEngine
import LocalChatKit

/// Model management: what is installed, what can be downloaded, import from Files.
struct AssistantModelsView: View {
    @Environment(AssistantModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @Environment(\.openURL) private var openURL

    @State private var pending: CatalogModel?
    @State private var showImporter = false
    @State private var errorText: String?

    private var library: ModelLibrary { model.library }

    private var available: [CatalogModel] {
        library.catalog.filter { !library.isInstalled($0) }
    }

    var body: some View {
        NavigationStack {
            List {
                Section("Installed") {
                    if library.installed.isEmpty { Text("Nothing yet").foregroundStyle(.secondary) }
                    ForEach(library.installed) { item in
                        Button {
                            // The embedding model only helps the web search: there is nothing to chat with.
                            if !item.isEmbedding { Task { await model.select(item.fileName) } }
                        } label: {
                            HStack {
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(item.displayName).foregroundStyle(.primary)
                                    Text(item.isEmbedding
                                         ? String(localized: "\(Self.size(item.sizeBytes)) · for search by meaning")
                                         : Self.size(item.sizeBytes))
                                        .font(.footnote).foregroundStyle(.secondary)
                                }
                                Spacer()
                                if item.fileName == model.selectedModel { Image(systemName: "checkmark").foregroundStyle(.tint) }
                            }
                        }
                        .swipeActions {
                            Button("Delete", role: .destructive) { remove(item) }
                        }
                    }
                    if let name = library.importing {
                        HStack { ProgressView(); Text("Copying \(name)…").font(.footnote) }
                    }
                }

                Section {
                    ForEach(available) { item in
                        AssistantCatalogRow(item: item, state: library.downloads[item.id], onDownload: { pending = item })
                    }
                    if available.isEmpty { Text("Everything in the catalog is installed").foregroundStyle(.secondary) }
                } header: {
                    Text("Catalog")
                } footer: {
                    Text("Models are downloaded once and then work without the internet. Each file is checked against its checksum.")
                }

                Section {
                    Toggle("Download over Wi‑Fi only", isOn: Binding(get: { library.wifiOnly }, set: { model.setWifiOnly($0) }))
                    Button("Import from Files…", systemImage: "folder") { showImporter = true }
                } footer: {
                    Text("Any file in GGUF format will do.")
                }
            }
            .navigationTitle("Models")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
            .fileImporter(isPresented: $showImporter, allowedContentTypes: [.data]) { result in
                guard case .success(let url) = result else { return }
                Task {
                    do { try await library.importFile(from: url) } catch { errorText = error.localizedDescription }
                }
            }
            .confirmationDialog(
                "License: \(pending?.licenseName ?? "")",
                isPresented: Binding(get: { pending != nil }, set: { if !$0 { pending = nil } }),
                titleVisibility: .visible,
                presenting: pending
            ) { item in
                Button("Accept and download (\(Self.size(item.sizeBytes)))") { library.download(item) }
                Button("Read the license") { openURL(item.licenseURL) }
            } message: { item in
                Text("“\(item.name)” is distributed under this license. The download starts at once and continues even if you close the app.")
            }
            .alert("Error", isPresented: Binding(get: { errorText != nil }, set: { if !$0 { errorText = nil } })) {
                Button("OK") { errorText = nil }
            } message: { Text(errorText ?? "") }
        }
    }

    private func remove(_ item: InstalledModel) {
        Task {
            do { try await model.delete(item) } catch { errorText = error.localizedDescription }
        }
    }

    static func size(_ bytes: Int64) -> String {
        ByteCountFormatter.string(fromByteCount: bytes, countStyle: .file)
    }
}

private struct AssistantCatalogRow: View {
    @Environment(AssistantModel.self) private var model
    let item: CatalogModel
    let state: DownloadState?
    let onDownload: () -> Void

    private var fit: ModelFit {
        ModelCatalog.fit(fileSizeBytes: item.sizeBytes, physicalMemoryBytes: DeviceMetrics.physicalMemoryBytes)
    }

    /// Collapsed: one line with the name, size and download state. Tapping the row opens the rest — what the model
    /// is good at, whether it fits this phone, its warning and license — and only then offers to download it.
    @State private var expanded = false

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Button {
                withAnimation(.snappy) { expanded.toggle() }
            } label: {
                HStack(spacing: 6) {
                    Text(item.name).font(.headline).foregroundStyle(.primary).lineLimit(1)
                    if item.recommended {
                        Text("recommended").font(.caption2).foregroundStyle(.primary)
                            .padding(.horizontal, 6).padding(.vertical, 2).background(.tint.opacity(0.2), in: Capsule())
                    }
                    if item.notice != nil || fit != .comfortable {
                        Image(systemName: fit == .tooBig ? "xmark.octagon" : "exclamationmark.triangle")
                            .font(.caption).foregroundStyle(fit == .tooBig ? .red : .orange)
                    }
                    Spacer(minLength: 4)
                    Text(AssistantModelsView.size(item.sizeBytes)).font(.footnote).foregroundStyle(.secondary)
                    Image(systemName: "chevron.right")
                        .font(.footnote.weight(.semibold)).foregroundStyle(.tertiary)
                        .rotationEffect(.degrees(expanded ? 90 : 0))
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityHint(expanded ? "Hides the details" : "Shows the details")

            if expanded {
                Text(LocalizedStringKey(item.summary)).font(.footnote).foregroundStyle(.secondary)
                switch fit {
                case .comfortable: EmptyView()
                case .tight: Label("This phone will be tight on memory: crashes are possible", systemImage: "exclamationmark.triangle").font(.caption).foregroundStyle(.orange)
                case .tooBig: Label("Will not fit in this phone's memory", systemImage: "xmark.octagon").font(.caption).foregroundStyle(.red)
                }
                if let notice = item.notice {
                    Label(LocalizedStringKey(notice), systemImage: "exclamationmark.triangle").font(.caption).foregroundStyle(.orange)
                }
                Text("License: \(item.licenseName)").font(.caption).foregroundStyle(.tertiary)
            }
            // A download in progress always shows; the button to start one only once the details are open.
            if state != nil || expanded { action }
        }
        .padding(.vertical, 2)
    }

    @ViewBuilder
    private var action: some View {
        let library = model.library
        switch state {
        case nil:
            Button("Download", systemImage: "arrow.down.circle", action: onDownload)
                .buttonStyle(.bordered).disabled(fit == .tooBig)

        case .downloading(let received, let total):
            VStack(alignment: .leading, spacing: 4) {
                ProgressView(value: Double(received), total: Double(max(total, 1)))
                HStack {
                    Text("\(AssistantModelsView.size(received)) of \(AssistantModelsView.size(total))").font(.caption).foregroundStyle(.secondary)
                    Spacer()
                    Button("Pause", systemImage: "pause.circle") { library.pause(item) }.labelStyle(.iconOnly)
                    Button("Cancel", systemImage: "xmark.circle") { library.cancel(item) }.labelStyle(.iconOnly)
                }
                .buttonStyle(.borderless)
            }

        case .paused:
            HStack {
                Text("Paused").font(.caption).foregroundStyle(.secondary)
                Spacer()
                Button("Resume", systemImage: "play.circle") { library.download(item) }
                Button("Cancel", systemImage: "xmark.circle") { library.cancel(item) }.labelStyle(.iconOnly)
            }
            .buttonStyle(.borderless)

        case .verifying:
            HStack { ProgressView(); Text("Checking the file…").font(.caption).foregroundStyle(.secondary) }

        case .failed(let message):
            VStack(alignment: .leading, spacing: 4) {
                Text(message).font(.caption).foregroundStyle(.red)
                HStack {
                    Button("Try again", systemImage: "arrow.clockwise") { library.download(item) }
                    Button("Reset", systemImage: "xmark.circle") { library.cancel(item) }
                }
                .buttonStyle(.borderless)
            }
        }
    }
}
