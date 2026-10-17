import SwiftUI

@main
struct SpectraApp: App {
    @State private var store: NetworkStore
    @State private var history: HistoryStore
    @State private var link: SensorLink
    @State private var lanScanner = LANScanner()
    @State private var bleScanner = BLEScanner()

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
        }
    }
}

struct RootView: View {
    enum Tab: Hashable { case networks, lan, bluetooth, history, sensor }

    @State private var tab: Tab = .networks

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
    }
}
