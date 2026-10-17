import MapKit
import SwiftUI

struct HistoryView: View {
    @Environment(HistoryStore.self) private var history
    @State private var confirmClear = false

    var body: some View {
        NavigationStack {
            List {
                ForEach(HistoryEntry.Kind.allCases, id: \.self) { kind in
                    let rows = history.entries.filter { $0.kind == kind }
                    if !rows.isEmpty {
                        Section(kind.title) {
                            ForEach(rows) { entry in
                                NavigationLink(value: entry.id) {
                                    VStack(alignment: .leading, spacing: 2) {
                                        Text("\(entry.itemCount) · \(entry.source)")
                                            .font(.body.weight(.semibold))
                                        Text(entry.savedAt, style: .relative)
                                            .font(.caption)
                                            .foregroundStyle(.secondary)
                                    }
                                }
                                .swipeActions {
                                    Button("Delete", role: .destructive) {
                                        history.delete(entry.id)
                                    }
                                }
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
                        description: Text("Save what Wi‑Fi, LAN or Bluetooth just found. The Cardputer's side button saves its own scan, and it arrives here when the phone connects.")
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
            .toolbar {
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
