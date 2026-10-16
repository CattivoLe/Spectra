import SwiftUI

@main
struct SpectraApp: App {
    @State private var store: NetworkStore
    @State private var link: SensorLink
    @State private var lanScanner = LANScanner()

    init() {
        let store = NetworkStore()
        _store = State(initialValue: store)
        _link = State(initialValue: SensorLink(store: store))
    }

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(store)
                .environment(link)
                .environment(lanScanner)
        }
    }
}

struct RootView: View {
    enum Tab: Hashable { case networks, channels, lan, sensor }

    @State private var tab: Tab = .networks

    var body: some View {
        TabView(selection: $tab) {
            NetworksView(openSensor: { tab = .sensor })
                .tabItem { Label("Networks", systemImage: "wifi") }
                .tag(Tab.networks)
            ChannelsView()
                .tabItem { Label("Channels", systemImage: "waveform.path") }
                .tag(Tab.channels)
            LANView()
                .tabItem { Label("LAN", systemImage: "network") }
                .tag(Tab.lan)
            SensorView()
                .tabItem { Label("Sensor", systemImage: "antenna.radiowaves.left.and.right") }
                .tag(Tab.sensor)
        }
    }
}
