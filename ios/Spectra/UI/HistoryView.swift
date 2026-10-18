import MapKit
import SwiftUI

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
    /// Expanded group keys. Empty means every group starts collapsed.
    @State private var expanded: Set<String> = []

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

    var body: some View {
        NavigationStack {
            List {
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
                if history.entries.isEmpty {
                    ContentUnavailableView(
                        "Nothing saved",
                        systemImage: "clock.arrow.circlepath",
                        description: Text("Scans are saved here automatically. The Cardputer's side button saves its own scan, and it arrives here when the phone connects.")
                    )
                }
            }
            .navigationTitle("History")
            .navigationDestination(for: UUID.self) { id in
                HistoryDetailView(id: id)
            }
            .confirmationDialog("Clear history?", isPresented: $confirmClear, titleVisibility: .visible) {
                Button("Clear history", role: .destructive) {
                    history.clear()
                }
                Button("Cancel", role: .cancel) {}
            } message: {
                Text("Saved scans from this iPhone and the Cardputer are removed.")
            }
            .sheet(isPresented: $showSettings) {
                HistorySettingsView(autosave: $autosave)
            }
            .toolbar {
                if !history.entries.isEmpty {
                    ToolbarItem(placement: .principal) {
                        Picker("Group by", selection: $grouping) {
                            ForEach(HistoryGrouping.allCases) { mode in
                                Label(mode.label, systemImage: mode.systemImage).tag(mode)
                            }
                        }
                        .pickerStyle(.segmented)
                        .fixedSize()
                    }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Settings", systemImage: "gearshape") { showSettings = true }
                }
                if !history.entries.isEmpty {
                    ToolbarItem(placement: .topBarTrailing) {
                        Button("Clear", systemImage: "trash", role: .destructive) {
                            confirmClear = true
                        }
                    }
                }
            }
        }
    }

    private func byDate() -> [Group] {
        let calendar = Calendar.current
        let buckets = Dictionary(grouping: history.entries) { calendar.startOfDay(for: $0.savedAt) }
        return buckets.keys.sorted(by: >).map { day in
            Group(
                id: "date:\(day.timeIntervalSince1970)",
                label: Self.dayLabel(day),
                entries: (buckets[day] ?? []).sorted { $0.savedAt > $1.savedAt }
            )
        }
    }

    private func byPlace() -> [Group] {
        let buckets = Dictionary(grouping: history.entries) { entry in
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

    private static func dayLabel(_ day: Date) -> String {
        let calendar = Calendar.current
        if calendar.isDateInToday(day) { return String(localized: "Today") }
        if calendar.isDateInYesterday(day) { return String(localized: "Yesterday") }
        return day.formatted(.dateTime.weekday(.wide).month().day())
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
            }
            .navigationTitle("History settings")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                }
            }
        }
        .presentationDetents([.medium])
    }
}

struct HistoryDetailView: View {
    var id: UUID
    @Environment(HistoryStore.self) private var history

    private var entry: HistoryEntry? { history.entries.first { $0.id == id } }

    var body: some View {
        List {
            if let entry {
                locationSection(entry)
                if entry.networks.isEmpty && entry.probes.isEmpty && entry.deauths.isEmpty
                    && entry.lan.isEmpty && entry.bluetooth.isEmpty {
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
        .toolbar {
            if entry != nil {
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
