import SwiftUI

@main
struct SpectraApp: App {
    @State private var store: NetworkStore
    @State private var history: HistoryStore
    @State private var link: SensorLink
    @State private var lanScanner = LANScanner()
    @State private var bleScanner = BLEScanner()
    @State private var location = LocationService()

    init() {
        let store = NetworkStore()
        let history = HistoryStore()
        _store = State(initialValue: store)
        _history = State(initialValue: history)
        _link = State(initialValue: SensorLink(store: store, history: history))
    }

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(store)
                .environment(history)
                .environment(link)
                .environment(lanScanner)
                .environment(bleScanner)
                .environment(location)
                .task {
                    history.locationProvider = { [weak location] in
                        location?.coordinate.map { ($0.latitude, $0.longitude) }
                    }
                    location.start()
                }
        }
    }
}

struct RootView: View {
    enum Tab: Hashable { case networks, lan, bluetooth, history, sensor }

    @Environment(HistoryStore.self) private var history
    @Environment(SensorLink.self) private var link
    @Environment(NetworkStore.self) private var store
    @Environment(LANScanner.self) private var lanScanner
    @Environment(BLEScanner.self) private var bleScanner
    @Environment(\.scenePhase) private var scenePhase
    @AppStorage("autosaveHistory") private var autosave = true
    @State private var tab: Tab = .networks
    @State private var notice: SaveNotice?

    /// Folds whatever each source has found into its History session. Each
    /// upsert is silent and no-ops when there is nothing to save, so it is safe
    /// to call often.
    private func autosaveNow() {
        guard autosave else { return }
        history.upsertNetworks(store, source: "This iPhone")
        history.upsertLAN(
            lanScanner.sortedDevices,
            networkKey: lanScanner.networkKey,
            networkLabel: lanScanner.networkLabel,
            source: "This iPhone"
        )
        history.upsertBluetooth(Array(bleScanner.devices.values), source: "This iPhone")
    }

    var body: some View {
        TabView(selection: $tab) {
            NetworksView(openSensor: { tab = .sensor })
                .tabItem { Label("Wi‑Fi", systemImage: "wifi") }
                .tag(Tab.networks)
            LANView()
                .tabItem { Label("LAN", systemImage: "network") }
                .tag(Tab.lan)
            BLEView()
                .tabItem { Label("Bluetooth", systemImage: "wave.3.right") }
                .tag(Tab.bluetooth)
            HistoryView()
                .tabItem { Label("History", systemImage: "clock.arrow.circlepath") }
                .tag(Tab.history)
            SensorView()
                .tabItem { Label("Sensor", systemImage: "antenna.radiowaves.left.and.right") }
                .tag(Tab.sensor)
        }
        .overlay(alignment: .top) {
            if let notice {
                SavedToast(notice: notice)
                    .padding(.top, 6)
                    .transition(.move(edge: .top).combined(with: .opacity))
                    .onTapGesture { withAnimation { self.notice = nil } }
            }
        }
        .sensoryFeedback(.success, trigger: history.lastSave?.id)
        .sensoryFeedback(trigger: link.isConnected) { _, connected in
            connected ? .success : .impact(weight: .light)
        }
        .task {
            // Enrich the open sessions while scanning; a hard flush also happens
            // when the app leaves the foreground.
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(15))
                autosaveNow()
            }
        }
        .onChange(of: scenePhase) { _, phase in
            if phase != .active { autosaveNow() }
        }
        .onChange(of: store.scanCount) { _, _ in autosaveNow() }
        .onChange(of: lanScanner.scanCount) { _, _ in autosaveNow() }
        .onChange(of: history.lastSave) { _, saved in
            guard let saved else { return }
            withAnimation(.spring(duration: 0.35)) { notice = saved }
            Task {
                try? await Task.sleep(for: .seconds(2.2))
                withAnimation(.easeOut(duration: 0.25)) {
                    if notice?.id == saved.id { notice = nil }
                }
            }
        }
    }
}

/// A short "saved" capsule at the top of the window.
struct SavedToast: View {
    var notice: SaveNotice

    var body: some View {
        HStack(spacing: 10) {
            Image(systemName: "checkmark.circle.fill")
                .font(.title3)
                .foregroundStyle(.green)
            VStack(alignment: .leading, spacing: 1) {
                Text(notice.fromSensor ? "Cardputer scan saved" : "Saved to History")
                    .font(.subheadline.weight(.semibold))
                Text("\(notice.kind.title) · \(notice.count)")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 10)
        .background(.regularMaterial, in: .capsule)
        .shadow(color: .black.opacity(0.15), radius: 12, y: 4)
        .accessibilityElement(children: .combine)
    }
}
