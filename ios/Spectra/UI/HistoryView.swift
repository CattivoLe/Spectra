import MapKit
import SwiftUI
import UniformTypeIdentifiers

/// How the saved sessions are grouped on screen.
enum HistoryGrouping: String, CaseIterable, Identifiable {
    case date, place
    var id: String { rawValue }
    var label: LocalizedStringKey { self == .date ? "By date" : "By place" }
    var systemImage: String { self == .date ? "calendar" : "mappin.and.ellipse" }
}

/// One tab's saved sessions: only the records of `area`, each session cut down to them. Pushed from the tab's
/// clock button; every tab has its own. A session is a section that opens onto its records, with no page between.
struct HistoryView: View {
    var area: HistoryArea
    @Environment(HistoryStore.self) private var history
    @AppStorage("historyGrouping") private var grouping: HistoryGrouping = .date
    @AppStorage("autosaveHistory") private var autosave = true
    @State private var confirmClear = false
    @State private var showSettings = false
    /// The sessions open in the list. The newest starts open; every other stays folded until tapped.
    @State private var expanded: Set<UUID> = []
    @State private var openedNewest = false
    /// A session opened from the map.
    @State private var opened: UUID?
    @State private var selectedPin: HistoryPin?
    @State private var showUnlocated = false
    @State private var camera: MapCameraPosition = .automatic
    @State private var shareItem: ShareItem?
    /// Shows the sessions imported from another phone instead of this iPhone's own.
    @State private var importedShelf = false
    @State private var importing = false
    @State private var importResult: String?
    // Live map is hidden for now.
    // @State private var showLive = false

    /// This tab's part of every saved session.
    private var all: [HistoryEntry] { history.entries.forArea(area) }

    private var hasImported: Bool { all.contains { $0.importedFrom != nil } }

    private var shown: [HistoryEntry] {
        let imported = importedShelf && hasImported
        return all.filter { ($0.importedFrom != nil) == imported }
    }

    /// The sessions in the order they are listed: newest first, or place by place.
    private var ordered: [HistoryEntry] {
        let newest = shown.sorted { $0.savedAt > $1.savedAt }
        guard grouping == .place else { return newest }
        var places: [String] = []
        for entry in newest where !places.contains(entry.placeName) { places.append(entry.placeName) }
        return places.flatMap { place in newest.filter { $0.placeName == place } }
    }

    /// Saved scans that carry a fix, one point per place (~55 m).
    private var pins: [HistoryPin] {
        let located = shown.filter { $0.latitude != nil && $0.longitude != nil }
        let buckets = Dictionary(grouping: located) { entry -> String in
            let scale = 2000.0
            let lat = Int(((entry.latitude ?? 0) * scale).rounded())
            let lon = Int(((entry.longitude ?? 0) * scale).rounded())
            return "\(lat)_\(lon)"
        }
        return buckets.map { key, entries in
            let newest = entries.max { $0.savedAt < $1.savedAt } ?? entries[0]
            let latitude = entries.compactMap(\.latitude).reduce(0, +) / Double(entries.count)
            let longitude = entries.compactMap(\.longitude).reduce(0, +) / Double(entries.count)
            return HistoryPin(
                id: key,
                coordinate: CLLocationCoordinate2D(latitude: latitude, longitude: longitude),
                label: newest.placeLabel ?? String(format: "%.4f, %.4f", latitude, longitude),
                entries: entries.sorted { $0.savedAt > $1.savedAt }
            )
        }
        .sorted { $0.id < $1.id }
    }

    private var unlocated: [HistoryEntry] {
        shown.filter { $0.latitude == nil || $0.longitude == nil }.sorted { $0.savedAt > $1.savedAt }
    }

    private var showsMap: Bool { grouping == .place && !pins.isEmpty }

    var body: some View {
        SwiftUI.Group {
            SwiftUI.Group {
                if showsMap {
                    placeMap
                } else {
                    sessionList
                }
            }
            .safeAreaInset(edge: .top) {
                if hasImported {
                    Picker("Shelf", selection: $importedShelf) {
                        Text("This iPhone").tag(false)
                        Text("Imported").tag(true)
                    }
                    .pickerStyle(.segmented)
                    .padding(.horizontal)
                    .padding(.bottom, 6)
                }
            }
            .navigationTitle("History")
            .modifier(HistorySubtitle(text: area.title))
            .fileImporter(isPresented: $importing, allowedContentTypes: [.json, .data]) { result in
                guard case .success(let url) = result else { return }
                importHistory(from: url)
            }
            .alert(
                "Import",
                isPresented: Binding(get: { importResult != nil }, set: { if !$0 { importResult = nil } })
            ) {
                Button("OK", role: .cancel) {}
            } message: {
                Text(importResult ?? "")
            }
            .navigationDestination(item: $opened) { id in
                HistoryDetailView(id: id, area: area)
            }
            .confirmationDialog("Clear history?", isPresented: $confirmClear, titleVisibility: .visible) {
                Button("Clear history", role: .destructive) {
                    history.clear(imported: importedShelf && hasImported, area: area)
                    if !hasImported { importedShelf = false }
                }
                Button("Cancel", role: .cancel) {}
            } message: {
                if importedShelf && hasImported {
                    Text("Imported \(area.title) records are removed. Scans on this iPhone and other tabs stay.")
                } else {
                    Text("Saved \(area.title) records are removed. Other tabs keep theirs.")
                }
            }
            .sheet(isPresented: $showSettings) {
                HistorySettingsView(autosave: $autosave)
            }
            .sheet(item: $selectedPin) { pin in
                pinSheet(title: pin.label, entries: pin.entries)
            }
            .sheet(isPresented: $showUnlocated) {
                pinSheet(title: String(localized: "No location"), entries: unlocated)
            }
            .shareSheet(item: $shareItem)
            // Live map is hidden for now.
            // .fullScreenCover(isPresented: $showLive) { LiveMapView() }
            .toolbar { historyToolbar }
        }
    }

    @ToolbarContentBuilder
    private var historyToolbar: some ToolbarContent {
        if !shown.isEmpty {
            ToolbarItem(placement: .topBarTrailing) {
                groupingButton(.date)
            }
            ToolbarItem(placement: .topBarTrailing) {
                groupingButton(.place)
            }
            if #available(iOS 26, *) {
                ToolbarSpacer(.fixed, placement: .topBarTrailing)
            }
        }
        ToolbarItem(placement: .topBarTrailing) {
            Menu {
                Button("Import…", systemImage: "square.and.arrow.down") { importing = true }
                if !shown.isEmpty {
                    HistoryExportMenu(entries: shown, shareItem: $shareItem)
                }
                Divider()
                Button("Settings…", systemImage: "gearshape") { showSettings = true }
                if !shown.isEmpty {
                    Divider()
                    Button("Clear history", systemImage: "trash", role: .destructive) {
                        confirmClear = true
                    }
                }
            } label: {
                Label("More", systemImage: "ellipsis.circle")
            }
        }
    }

    private func exportMenu(entries: [HistoryEntry]) -> some View {
        HistoryExportMenu(entries: entries, shareItem: $shareItem)
    }

    private func importHistory(from url: URL) {
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        guard let data = try? Data(contentsOf: url), let added = history.importDocument(data) else {
            importResult = String(localized: "Could not read that file.")
            return
        }
        if added > 0 { importedShelf = true }
        importResult = added == 0
            ? String(localized: "Nothing new in that file.")
            : String(localized: "Imported \(added) scans.")
    }

    private func groupingButton(_ mode: HistoryGrouping) -> some View {
        let selected = grouping == mode
        return Button { grouping = mode } label: {
            Label(mode.label, systemImage: mode.systemImage)
                .labelStyle(.titleAndIcon)
                .font(.subheadline.weight(selected ? .semibold : .regular))
                .padding(.horizontal, 10)
                .padding(.vertical, 5)
                .background(selected ? Color.accentColor.opacity(0.18) : .clear, in: Capsule())
                .foregroundStyle(selected ? Color.accentColor : .secondary)
        }
        .accessibilityAddTraits(selected ? .isSelected : [])
    }

    private var sessionList: some View {
        List {
            ForEach(ordered) { entry in
                let isExpanded = expanded.contains(entry.id)
                Section {
                    if isExpanded { HistoryRows(entry: entry) }
                } header: {
                    SessionHeader(
                        entry: entry,
                        placeFirst: grouping == .place,
                        isExpanded: isExpanded,
                        shareItem: $shareItem,
                        openPlace: entry.hasFix ? { opened = entry.id } : nil,
                        delete: { history.delete(entry.id, area: area) }
                    ) {
                        withAnimation(.easeInOut(duration: 0.2)) {
                            if isExpanded { expanded.remove(entry.id) } else { expanded.insert(entry.id) }
                        }
                    }
                } footer: {
                    if isExpanded { Text(entry.savedLine) }
                }
            }
        }
        .onAppear {
            guard !openedNewest else { return }
            openedNewest = true
            if let newest = ordered.first { expanded.insert(newest.id) }
        }
        .overlay {
            if shown.isEmpty {
                ContentUnavailableView(
                    "Nothing saved",
                    systemImage: "clock.arrow.circlepath",
                    description: Text(area.emptyHint)
                )
            }
        }
    }

    private var placeMap: some View {
        Map(position: $camera) {
            ForEach(pins) { pin in
                Annotation("", coordinate: pin.coordinate, anchor: .center) {
                    HistoryMapPin(pin: pin) { open(pin) }
                }
            }
        }
        .mapStyle(.standard(pointsOfInterest: .excludingAll))
        .onAppear { camera = .automatic }
        .onChange(of: pins.map(\.id)) { _, _ in camera = .automatic }
        .overlay(alignment: .bottom) {
            if !unlocated.isEmpty {
                Button {
                    showUnlocated = true
                } label: {
                    Text("\(unlocated.count) without a location")
                }
                .buttonStyle(.bordered)
                .padding(.bottom, 12)
            }
        }
    }

    private func open(_ pin: HistoryPin) {
        if pin.entries.count == 1, let entry = pin.entries.first {
            opened = entry.id
        } else {
            selectedPin = pin
        }
    }

    private func pinSheet(title: String, entries: [HistoryEntry]) -> some View {
        NavigationStack {
            List(entries) { entry in
                Button {
                    selectedPin = nil
                    showUnlocated = false
                    opened = entry.id
                } label: {
                    EntryRow(entry: entry)
                }
            }
            .navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
        }
        .presentationDetents([.medium, .large])
    }
}

/// The tab's name under the History title, where the system can show one.
private struct HistorySubtitle: ViewModifier {
    var text: String

    func body(content: Content) -> some View {
        if #available(iOS 26, *) {
            content.navigationSubtitle(text)
        } else {
            content
        }
    }
}

/// The clock in a tab's top bar: that tab's saved sessions, pushed onto the tab's own stack.
struct HistoryButton: ToolbarContent {
    var area: HistoryArea
    @Binding var isPresented: Bool

    var body: some ToolbarContent {
        ToolbarItem(placement: .topBarTrailing) {
            Button("History", systemImage: "clock.arrow.circlepath") { isPresented = true }
        }
    }
}

/// Scans that share a place, drawn as one point.
private struct HistoryPin: Identifiable {
    var id: String
    var coordinate: CLLocationCoordinate2D
    var label: String
    var entries: [HistoryEntry]

    var symbol: String {
        let symbols = Set(entries.map(\.rowSystemImage))
        return symbols.count == 1 ? symbols.first ?? "mappin" : "mappin.and.ellipse"
    }
}

private struct HistoryMapPin: View {
    var pin: HistoryPin
    var action: () -> Void

    var body: some View {
        Button(action: action) {
            VStack(spacing: 3) {
                ZStack(alignment: .topTrailing) {
                    Image(systemName: pin.symbol)
                        .font(.body.weight(.semibold))
                        .foregroundStyle(.white)
                        .frame(width: 36, height: 36)
                        .background(.tint, in: Circle())
                        .overlay(Circle().stroke(.white, lineWidth: 2))
                    if pin.entries.count > 1 {
                        Text("\(pin.entries.count)")
                            .font(.caption2.weight(.bold))
                            .foregroundStyle(.primary)
                            .padding(.horizontal, 5)
                            .padding(.vertical, 1)
                            .background(.white, in: Capsule())
                            .offset(x: 8, y: -6)
                    }
                }
                Text(pin.label)
                    .font(.caption2.weight(.semibold))
                    .lineLimit(1)
                    .frame(maxWidth: 140)
                    .padding(.horizontal, 6)
                    .padding(.vertical, 2)
                    .background(.ultraThinMaterial, in: Capsule())
            }
        }
        .buttonStyle(.plain)
        .accessibilityLabel(pin.label)
    }
}

extension HistoryEntry {
    /// The place's name; empty when the session has none.
    fileprivate var placeName: String { placeLabel ?? "" }

    fileprivate var hasFix: Bool { latitude != nil && longitude != nil }

    fileprivate var hasRecords: Bool {
        !networks.isEmpty || !probes.isEmpty || !deauths.isEmpty || !lan.isEmpty || !bluetooth.isEmpty || !connections.isEmpty
    }

    /// The phone's own scans are stored with an English name, the same in every language.
    private var sourceName: String {
        source == "This iPhone" ? String(localized: "This iPhone") : source
    }

    fileprivate var dayName: String {
        let calendar = Calendar.current
        if calendar.isDateInToday(savedAt) { return String(localized: "Today") }
        if calendar.isDateInYesterday(savedAt) { return String(localized: "Yesterday") }
        return savedAt.formatted(.dateTime.weekday(.wide).month().day())
    }

    fileprivate var savedLine: String {
        "\(sourceName) · \(savedAt.formatted(date: .abbreviated, time: .shortened))"
    }
}

/// A session in the list: its day and place, how many records it holds, and what can be done with it.
private struct SessionHeader: View {
    var entry: HistoryEntry
    var placeFirst: Bool
    var isExpanded: Bool
    @Binding var shareItem: ShareItem?
    /// Shows where the session was saved, when it carries a fix.
    var openPlace: (() -> Void)?
    var delete: () -> Void
    var toggle: () -> Void

    private var place: String { entry.placeName.isEmpty ? String(localized: "No location") : entry.placeName }
    private var time: String { entry.savedAt.formatted(date: .omitted, time: .shortened) }

    var body: some View {
        HStack(spacing: 12) {
            Button(action: toggle) {
                HStack(spacing: 8) {
                    VStack(alignment: .leading, spacing: 1) {
                        Text(placeFirst ? place : entry.dayName)
                            .font(.subheadline.weight(.semibold))
                            .foregroundStyle(.primary)
                        Text(verbatim: placeFirst ? "\(entry.dayName), \(time)" : entry.placeName.isEmpty ? time : "\(time) · \(entry.placeName)")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                            .lineLimit(1)
                    }
                    Spacer(minLength: 8)
                    Text("\(entry.itemCount)")
                        .font(.subheadline.monospacedDigit())
                        .foregroundStyle(.secondary)
                    Image(systemName: "chevron.right")
                        .font(.caption2.weight(.semibold))
                        .foregroundStyle(.secondary)
                        .rotationEffect(.degrees(isExpanded ? 90 : 0))
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            Menu {
                if let openPlace {
                    Button("Show where it was saved", systemImage: "map", action: openPlace)
                }
                HistoryExportMenu(entries: [entry], shareItem: $shareItem)
                Divider()
                Button("Delete", systemImage: "trash", role: .destructive, action: delete)
            } label: {
                Image(systemName: "ellipsis")
                    .frame(width: 28, height: 28)
                    .contentShape(Rectangle())
            }
            .accessibilityLabel("More")
        }
        .textCase(nil)
    }
}

/// One session of a place, as the map's sheet lists them.
private struct EntryRow: View {
    var entry: HistoryEntry

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text("\(entry.dayName) · \(entry.itemCount)")
                .font(.body.weight(.semibold))
                .foregroundStyle(.primary)
            Text(entry.savedLine)
                .font(.caption)
                .foregroundStyle(.secondary)
                .lineLimit(1)
        }
    }
}

private struct HistorySettingsView: View {
    @Binding var autosave: Bool
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Toggle("Save scans automatically", isOn: $autosave)
                } footer: {
                    Text("When on, Wi‑Fi, LAN and Bluetooth scans are kept in History on their own. Scans of the same kind, on the same day, in the same place are merged into one session instead of piling up.")
                }
            }
            .navigationTitle("History settings")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                }
            }
        }
        .presentationDetents([.medium, .large])
    }
}

struct HistoryDetailView: View {
    var id: UUID
    var area: HistoryArea
    @Environment(HistoryStore.self) private var history
    @Environment(\.dismiss) private var dismiss
    @State private var shareItem: ShareItem?

    /// The session as this tab shows it: another tab's records in it stay out of sight.
    private var entry: HistoryEntry? { history.entries.first { $0.id == id }?.only(area) }

    var body: some View {
        List {
            if let entry {
                locationSection(entry)
                Section {
                    HistoryRows(entry: entry)
                } footer: {
                    Text(entry.savedLine)
                }
            }
        }
        .navigationTitle(entry?.dayName ?? String(localized: "History"))
        .navigationBarTitleDisplayMode(.inline)
        .shareSheet(item: $shareItem)
        .toolbar {
            if let entry {
                ToolbarItem(placement: .topBarTrailing) {
                    HistoryExportMenu(entries: [entry], shareItem: $shareItem)
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Delete", systemImage: "trash", role: .destructive) {
                        history.delete(id, area: area)
                        dismiss()
                    }
                }
            }
        }
    }

    /// Where the phone was when it saved, for phone-side captures that carry a fix.
    @ViewBuilder
    private func locationSection(_ entry: HistoryEntry) -> some View {
        if let lat = entry.latitude, let lon = entry.longitude {
            let coordinate = CLLocationCoordinate2D(latitude: lat, longitude: lon)
            Section("Where") {
                Map(initialPosition: .region(MKCoordinateRegion(
                    center: coordinate,
                    span: MKCoordinateSpan(latitudeDelta: 0.01, longitudeDelta: 0.01)
                ))) {
                    Marker(entry.source, coordinate: coordinate)
                }
                .frame(height: 150)
                .allowsHitTesting(false)
                .listRowInsets(EdgeInsets())

                Button {
                    let item = MKMapItem(placemark: MKPlacemark(coordinate: coordinate))
                    item.name = entry.source
                    item.openInMaps()
                } label: {
                    Label(String(format: "%.5f, %.5f", lat, lon), systemImage: "location.fill")
                        .monospacedDigit()
                }
            }
        }
    }
}

/// Every record of a session, as rows that open the same cards as the live lists.
private struct HistoryRows: View {
    var entry: HistoryEntry

    var body: some View {
        ForEach(entry.deauths) { event in
            DeauthRow(event: event)
        }
        ForEach(entry.networks) { network in
            NavigationLink {
                NetworkDetailView(network: network)
            } label: {
                NetworkRow(network: network)
            }
        }
        if !entry.probes.isEmpty {
            Text("\(entry.probes.count) probing")
                .font(.footnote.weight(.semibold))
                .foregroundStyle(.secondary)
        }
        ForEach(entry.probes) { probe in
            NavigationLink {
                ClientDetailView(
                    client: WiFiClient(mac: probe.mac, apBSSID: nil, rssi: probe.rssi, isAssociated: false),
                    savedProbe: probe
                )
            } label: {
                ProbeDeviceRow(device: probe)
            }
        }
        ForEach(entry.connections) { saved in
            NavigationLink {
                LinkView(saved: saved)
            } label: {
                VStack(alignment: .leading, spacing: 2) {
                    Text(statusLabel(saved.snapshot.status))
                    Text(snapshotDetail(saved.snapshot, viaVpn: saved.viaVpn))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
        }
        ForEach(entry.lan) { saved in
            let device = saved.device()
            NavigationLink {
                DeviceDetailView(device: device)
            } label: {
                DeviceRow(device: device)
            }
        }
        ForEach(entry.bluetooth) { saved in
            let device = saved.device()
            NavigationLink {
                BLEDeviceDetailView(device: device)
            } label: {
                BLEDeviceRow(device: device, now: device.lastSeen)
            }
        }
        // A capture from the Cardputer stores summary lines, not the records.
        ForEach(entry.hasRecords ? [] : entry.lines) { line in
            HStack(alignment: .firstTextBaseline) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(line.title)
                    if !line.detail.isEmpty {
                        Text(line.detail)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
                Spacer(minLength: 8)
                if !line.trailing.isEmpty {
                    Text(line.trailing)
                        .font(.body.monospacedDigit())
                        .foregroundStyle(.secondary)
                }
            }
        }
    }
}

/// Export formats for saved sessions. "Spectra" is the full history file the
/// iPhone and Android apps import from each other; the rest are for other tools.
struct HistoryExportMenu: View {
    var entries: [HistoryEntry]
    @Binding var shareItem: ShareItem?

    var body: some View {
        Menu {
            Button("Spectra") { share(HistoryDocument.file(entries, from: origin)) }
            ForEach(Exporter.Format.allCases) { format in
                Button(format.label) { share(Exporter.file(format, from: entries)) }
            }
        } label: {
            Label("Export", systemImage: "square.and.arrow.up")
        }
    }

    /// Imported sessions from one phone keep that phone's name; anything else is this iPhone's.
    private var origin: String {
        let origins = Set(entries.map(\.importedFrom))
        if origins.count == 1, let only = origins.first ?? nil { return only }
        return UIDevice.current.name
    }

    private func share(_ file: Exporter.File) {
        if let url = writeExport(file) { shareItem = ShareItem(url: url) }
    }
}
