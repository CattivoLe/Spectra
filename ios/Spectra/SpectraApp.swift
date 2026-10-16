import SwiftUI

@main
struct SpectraApp: App {
    @State private var store: NetworkStore
    @State private var link: SensorLink

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
        }
    }
}

struct RootView: View {
    enum Tab: Hashable { case networks, channels, sensor }

    @State private var tab: Tab = .networks

    var body: some View {
        TabView(selection: $tab) {
            NetworksView(openSensor: { tab = .sensor })
                .tabItem { Label("Networks", systemImage: "wifi") }
                .tag(Tab.networks)
            ChannelsView()
                .tabItem { Label("Channels", systemImage: "waveform.path") }
                .tag(Tab.channels)
            SensorView()
                .tabItem { Label("Sensor", systemImage: "antenna.radiowaves.left.and.right") }
                .tag(Tab.sensor)
        }
    }
}
