import SwiftUI
import UserNotifications

@main
struct SpectraApp: App {
    @UIApplicationDelegateAdaptor(SpectraAppDelegate.self) private var appDelegate
    @State private var store: NetworkStore
    @State private var history: HistoryStore
    @State private var link: SensorLink
    @State private var lanScanner = LANScanner()
    @State private var linkProbe = LinkProbe()
    @State private var bleScanner = BLEScanner()
    @State private var location = LocationService()
    @State private var watchList = WatchList()
    @State private var joiner = WiFiJoiner()
    @State private var sniffer = TrafficSniffer()
    @State private var speedTester = SpeedTester()
    @State private var resourceWatch = ResourceWatch()
    @State private var routeTracer = RouteTracer()
    @State private var assistant = AssistantModel()
    /// The capture last decoded, for the assistant to talk about.
    @State private var captures = CaptureShelf()
    @State private var assistantMemory = AssistantMemory()

    init() {
        let store = NetworkStore()
        let history = HistoryStore()
        _store = State(initialValue: store)
        _history = State(initialValue: history)
        _link = State(initialValue: SensorLink(store: store, history: history))
        UNUserNotificationCenter.current().delegate = NewDeviceNotifier.presenter
    }

    var body: some Scene {
        WindowGroup {
            RootView()
                .assistantLock(assistant)
                .environment(assistant)
                .environment(captures)
                .environment(store)
                .environment(history)
                .environment(link)
                .environment(lanScanner)
                .environment(lanScanner.knownHosts)
                .environment(lanScanner.labels)
                .environment(lanScanner.wifi)
                .environment(linkProbe)
                .environment(speedTester)
                .environment(resourceWatch)
                .environment(routeTracer)
                .environment(bleScanner)
                .environment(location)
                .environment(watchList)
                .environment(joiner)
                .environment(sniffer)
                .onChange(of: watchList.ids, initial: true) { _, ids in
                    link.setWatched(ids)
                }
                .task {
                    joiner.onJoined = { [weak link] ssid, password in
                        link?.rememberJoined(ssid: ssid, password: password)
                    }
                    history.locationProvider = { [weak location] in
                        location?.coordinate.map { ($0.latitude, $0.longitude) }
                    }
                    location.start()
                    let sources = AssistantSources(
                        store: store, lan: lanScanner, ble: bleScanner, history: history,
                        link: link, probe: linkProbe, joiner: joiner, speed: speedTester, captures: captures,
                        memory: assistantMemory, resources: resourceWatch, tracer: routeTracer
                    )
                    captures.recordedTraffic = { [weak sniffer] in sniffer?.assistantRecording }
                    assistant.dataSource = { question in sources.attachment(for: question) }
                    assistant.memory = assistantMemory
                    let actions = AssistantActions(sources: sources, memory: assistantMemory)
                    assistant.actions = actions
                    assistant.cards = AssistantCardRunner(assistant: assistant, sources: sources, actions: actions)
                }
        }
    }
}

struct RootView: View {
    enum Tab: Hashable { case networks, lan, bluetooth, history, assistant }

    @Environment(HistoryStore.self) private var history
    @Environment(LinkProbe.self) private var linkProbe
    @Environment(SensorLink.self) private var link
    @Environment(NetworkStore.self) private var store
    @Environment(LANScanner.self) private var lanScanner
    @Environment(BLEScanner.self) private var bleScanner
    @Environment(AssistantModel.self) private var assistant
    @Environment(\.scenePhase) private var scenePhase
    @AppStorage("autosaveHistory") private var autosave = true
    @AppStorage("notifyNewDevices") private var notifyNewDevices = true
    @State private var tab: Tab = .networks
    @State private var showSensor = false
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
        // The same tick teaches the assistant what is usual for each device, so "is this normal for it?" has
        // something to compare against later.
        assistant.actions?.sources.recordBaselines()
    }

    /// One notice per finished scan, for the hosts that scan marked new.
    private func announceNewDevices() {
        guard notifyNewDevices, let verdict = lanScanner.lastVerdict, !verdict.newIPs.isEmpty else { return }
        let found = verdict.newIPs.compactMap { lanScanner.devices[$0] }
        let label = lanScanner.networkLabel
        Task { await NewDeviceNotifier.notify(found, networkLabel: label) }
    }

    private func keepConnection(_ snapshot: LinkSnapshot, viaVpn: Bool) {
        guard autosave, snapshot.status != nil, snapshot.status != .noNetwork else { return }
        history.upsertConnection(
            SavedConnection(viaVpn: viaVpn, viaCellular: ProbeRoute.current() == .cellular, snapshot: snapshot),
            networkKey: lanScanner.networkKey,
            networkLabel: lanScanner.networkLabel,
            source: "This iPhone"
        )
    }

    var body: some View {
        TabView(selection: $tab) {
            NetworksView(openSensor: { showSensor = true })
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
            AssistantView()
                .tabItem { Label("Assistant", systemImage: "sparkles") }
                .tag(Tab.assistant)
        }
        .sheet(isPresented: $showSensor) {
            SensorView()
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
        .onAppear { linkProbe.onFinished = keepConnection }
        .onChange(of: scenePhase) { _, phase in
            if phase != .active { autosaveNow() }
        }
        .onChange(of: store.scanCount) { _, _ in autosaveNow() }
        .onChange(of: lanScanner.scanCount) { _, _ in
            autosaveNow()
            announceNewDevices()
        }
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
