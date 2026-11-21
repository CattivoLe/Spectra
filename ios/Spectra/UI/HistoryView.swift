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

struct HistoryView: View {
    @Environment(HistoryStore.self) private var history
    @AppStorage("historyGrouping") private var grouping: HistoryGrouping = .date
    @AppStorage("autosaveHistory") private var autosave = true
    @State private var confirmClear = false
    @State private var showSettings = false
    @State private var kindFilter: HistoryEntry.Kind?
    /// Expanded group keys. Today starts open; every other group stays folded until tapped.
    @State private var expanded: Set<String> = [Self.dateGroupID(Calendar.current.startOfDay(for: .now))]
    @State private var path = NavigationPath()
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

    private var hasImported: Bool { history.entries.contains { $0.importedFrom != nil } }

    private var shown: [HistoryEntry] {
        let imported = importedShelf && hasImported
        let shelf = history.entries.filter { ($0.importedFrom != nil) == imported }
        guard let kind = kindFilter else { return shelf }
        return shelf.filter { $0.kind == kind }
    }

    private var availableKinds: [HistoryEntry.Kind] {
        var seen: Set<HistoryEntry.Kind> = []
        let imported = importedShelf && hasImported
        for entry in history.entries where (entry.importedFrom != nil) == imported {
            seen.insert(entry.kind)
        }
        return HistoryEntry.Kind.allCases.filter(seen.contains)
    }

    /// One collapsible group of sessions: a day, or a place.
    private struct Group: Identifiable {
        var id: String
        var label: String
        var entries: [HistoryEntry]
        /// Which scan types this group holds, for the icon row on the header.
        var kinds: [HistoryEntry.Kind] {
            var seen: Set<HistoryEntry.Kind> = []
            return entries.compactMap { seen.insert($0.kind).inserted ? $0.kind : nil }
        }
        var itemCount: Int { entries.reduce(0) { $0 + $1.itemCount } }
    }

    private var groups: [Group] {
        switch grouping {
        case .date: return byDate()
        case .place: return byPlace()
        }
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
        NavigationStack(path: $path) {
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
            .navigationDestination(for: UUID.self) { id in
                HistoryDetailView(id: id)
            }
            .confirmationDialog("Clear history?", isPresented: $confirmClear, titleVisibility: .visible) {
                Button("Clear history", role: .destructive) {
                    history.clear(imported: importedShelf && hasImported)
                    if !hasImported { importedShelf = false }
                }
                Button("Cancel", role: .cancel) {}
            } message: {
                if importedShelf && hasImported {
                    Text("Imported scans are removed. Scans on this iPhone stay.")
                } else {
                    Text("Saved scans from this iPhone and the Cardputer are removed.")
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

    private var kindFilterChips: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(availableKinds, id: \.self) { kind in
                    FilterChip(title: LocalizedStringKey(kind.title), systemImage: kind.systemImage, isOn: kindFilter == kind) {
                        kindFilter = kindFilter == kind ? nil : kind
                    }
                }
            }
            .padding(.horizontal, 20)
            .padding(.vertical, 2)
        }
    }

    private var sessionList: some View {
        List {
            if availableKinds.count > 1 {
                Section {
                    kindFilterChips
                        .listRowInsets(EdgeInsets())
                        .listRowBackground(Color.clear)
                }
                .listSectionSpacing(.compact)
            }
            ForEach(groups) { group in
                let isExpanded = expanded.contains(group.id)
                Section {
                    if isExpanded {
                        ForEach(group.entries) { entry in
                            NavigationLink(value: entry.id) {
                                EntryRow(entry: entry, showPlace: grouping == .date)
                            }
                            .swipeActions {
                                Button("Delete", role: .destructive) {
                                    history.delete(entry.id)
                                }
                            }
                        }
                    }
                } header: {
                    GroupHeader(
                        label: group.label,
                        kinds: group.kinds,
                        count: group.itemCount,
                        isExpanded: isExpanded
                    ) {
                        withAnimation(.easeInOut(duration: 0.2)) {
                            if isExpanded { expanded.remove(group.id) } else { expanded.insert(group.id) }
                        }
                    }
                }
            }
        }
        .overlay {
            if shown.isEmpty {
                ContentUnavailableView(
                    "Nothing saved",
                    systemImage: "clock.arrow.circlepath",
                    description: Text("Scans are saved here automatically. The Cardputer's side button saves its own scan, and it arrives here when the phone connects.")
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
            path.append(entry.id)
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
                    path.append(entry.id)
                } label: {
                    EntryRow(entry: entry, showPlace: false)
                }
            }
            .navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
        }
        .presentationDetents([.medium, .large])
    }

    private func byDate() -> [Group] {
        let calendar = Calendar.current
        let buckets = Dictionary(grouping: shown) { calendar.startOfDay(for: $0.savedAt) }
        return buckets.keys.sorted(by: >).map { day in
            Group(
                id: Self.dateGroupID(day),
                label: Self.dayLabel(day),
                entries: (buckets[day] ?? []).sorted { $0.savedAt > $1.savedAt }
            )
        }
    }

    private func byPlace() -> [Group] {
        let buckets = Dictionary(grouping: shown) { entry in
            entry.placeLabel ?? entry.placeKey ?? ""
        }
        return buckets
            .map { label, entries in
                Group(
                    id: "place:\(label)",
                    label: label.isEmpty ? String(localized: "No location") : label,
                    entries: entries.sorted { $0.savedAt > $1.savedAt }
                )
            }
            .sorted { ($0.entries.first?.savedAt ?? .distantPast) > ($1.entries.first?.savedAt ?? .distantPast) }
    }

    private static func dateGroupID(_ day: Date) -> String { "date:\(day.timeIntervalSince1970)" }

    private static func dayLabel(_ day: Date) -> String {
        let calendar = Calendar.current
        if calendar.isDateInToday(day) { return String(localized: "Today") }
        if calendar.isDateInYesterday(day) { return String(localized: "Yesterday") }
        return day.formatted(.dateTime.weekday(.wide).month().day())
    }
}

/// Scans that share a place, drawn as one point.
private struct HistoryPin: Identifiable {
    var id: String
    var coordinate: CLLocationCoordinate2D
    var label: String
    var entries: [HistoryEntry]

    var symbol: String {
        let kinds = Set(entries.map(\.kind))
        return kinds.count == 1 ? kinds.first?.systemImage ?? "mappin" : "mappin.and.ellipse"
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

/// A collapsible section header with the group's name and the icons of the scan
/// types inside it.
private struct GroupHeader: View {
    var label: String
    var kinds: [HistoryEntry.Kind]
    var count: Int
    var isExpanded: Bool
    var toggle: () -> Void

    var body: some View {
        Button(action: toggle) {
            HStack(spacing: 8) {
                Text(label)
                HStack(spacing: 4) {
                    ForEach(kinds, id: \.self) { kind in
                        Image(systemName: kind.systemImage)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
                Spacer()
                Text("\(count)")
                    .foregroundStyle(.secondary)
                Image(systemName: isExpanded ? "chevron.down" : "chevron.right")
                    .font(.caption2.weight(.semibold))
                    .foregroundStyle(.secondary)
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

/// One saved session row.
private struct EntryRow: View {
    var entry: HistoryEntry
    var showPlace: Bool

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: entry.kind.systemImage)
                .font(.title3)
                .frame(width: 26)
                .foregroundStyle(.tint)
            VStack(alignment: .leading, spacing: 2) {
                Text("\(entry.kind.title) · \(entry.itemCount)")
                    .font(.body.weight(.semibold))
                HStack(spacing: 4) {
                    Text(entry.savedAt, style: .relative)
                    if showPlace, let place = entry.placeLabel {
                        Text("· \(place)")
                    } else {
                        Text("· \(entry.source)")
                    }
                }
                .font(.caption)
                .foregroundStyle(.secondary)
                .lineLimit(1)
            }
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
                Section {
                    NavigationLink {
                        LicensesView()
                    } label: {
                        Label("Licenses", systemImage: "doc.text")
                    }
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
    @Environment(HistoryStore.self) private var history
    @State private var shareItem: ShareItem?

    private var entry: HistoryEntry? { history.entries.first { $0.id == id } }

    var body: some View {
        List {
            if let entry {
                locationSection(entry)
                if entry.networks.isEmpty && entry.probes.isEmpty && entry.deauths.isEmpty
                    && entry.lan.isEmpty && entry.bluetooth.isEmpty && entry.connections.isEmpty {
                    Section {
                        ForEach(entry.lines) { line in
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
                    } footer: {
                        savedFooter(entry)
                    }
                } else {
                    savedRecords(entry)
                }
            }
        }
        .navigationTitle(entry?.kind.title ?? "History")
        .shareSheet(item: $shareItem)
        .toolbar {
            if let entry {
                ToolbarItem(placement: .topBarTrailing) {
                    HistoryExportMenu(entries: [entry], shareItem: $shareItem)
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Delete", systemImage: "trash", role: .destructive) {
                        history.delete(id)
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

    @ViewBuilder
    private func savedRecords(_ entry: HistoryEntry) -> some View {
        if !entry.deauths.isEmpty {
            Section {
                ForEach(entry.deauths) { event in
                    DeauthRow(event: event)
                }
            } header: {
                Label("Deauth detected", systemImage: "exclamationmark.shield.fill")
                    .foregroundStyle(.red)
                    .font(.subheadline.weight(.semibold))
            } footer: {
                if entry.networks.isEmpty && entry.probes.isEmpty {
                    savedFooter(entry)
                }
            }
        }
        if !entry.networks.isEmpty {
            Section {
                ForEach(entry.networks) { network in
                    NavigationLink {
                        NetworkDetailView(network: network)
                    } label: {
                        NetworkRow(network: network)
                    }
                }
            } footer: {
                if entry.probes.isEmpty && entry.lan.isEmpty && entry.bluetooth.isEmpty {
                    savedFooter(entry)
                }
            }
        }
        if !entry.probes.isEmpty {
            Section {
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
            } header: {
                Text("\(entry.probes.count) probing")
            } footer: {
                savedFooter(entry)
            }
        }
        if !entry.connections.isEmpty {
            Section {
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
            } header: {
                Text("Connection")
            } footer: {
                if entry.lan.isEmpty && entry.bluetooth.isEmpty && entry.networks.isEmpty && entry.probes.isEmpty && entry.deauths.isEmpty {
                    savedFooter(entry)
                }
            }
        }
        if !entry.lan.isEmpty {
            Section {
                ForEach(entry.lan) { saved in
                    let device = saved.device()
                    NavigationLink {
                        DeviceDetailView(device: device)
                    } label: {
                        DeviceRow(device: device)
                    }
                }
            } footer: {
                savedFooter(entry)
            }
        }
        if !entry.bluetooth.isEmpty {
            Section {
                ForEach(entry.bluetooth) { saved in
                    let device = saved.device()
                    NavigationLink {
                        BLEDeviceDetailView(device: device)
                    } label: {
                        BLEDeviceRow(device: device, now: device.lastSeen)
                    }
                }
            } footer: {
                savedFooter(entry)
            }
        }
    }

    private func savedFooter(_ entry: HistoryEntry) -> some View {
        Text("\(entry.source) · \(entry.savedAt.formatted(date: .abbreviated, time: .shortened))")
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
