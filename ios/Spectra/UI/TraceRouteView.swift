import MapKit
import SwiftUI

/// A traceroute from this phone to a target the person picks, drawn on a map
/// where the hops' addresses can be placed.
struct TraceRouteView: View {
    @Environment(RouteTracer.self) private var tracer
    @AppStorage("traceTarget") private var target = RouteTrace.defaultTarget
    @FocusState private var editing: Bool
    @State private var camera: MapCameraPosition = .automatic

    private static let presets = ["1.1.1.1", "8.8.8.8", "google.com", "ya.ru"]

    var body: some View {
        List {
            Section {
                HStack(spacing: 10) {
                    Image(systemName: "scope").foregroundStyle(.secondary)
                    TextField("Site or IP address", text: $target)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .keyboardType(.URL)
                        .submitLabel(.go)
                        .focused($editing)
                        .onSubmit(start)
                }
                FlowLayout(spacing: 8, lineSpacing: 8) {
                    ForEach(Self.presets, id: \.self) { preset in
                        FilterChip(title: LocalizedStringKey(preset), systemImage: nil, isOn: target == preset) {
                            target = preset
                        }
                    }
                }
                .padding(.vertical, 2)
                if tracer.running {
                    HStack(spacing: 10) {
                        ProgressView()
                        Text(tracer.phase).foregroundStyle(.secondary)
                        Spacer()
                        Button("Stop", action: tracer.stop).buttonStyle(.borderless)
                    }
                } else {
                    Button(action: start) {
                        Label(tracer.trace == nil ? "Trace" : "Trace again", systemImage: "point.topleft.down.to.point.bottomright.curvepath")
                    }
                    .disabled(RouteTrace.target(from: target) == nil)
                }
                if let failure = tracer.failure {
                    Label(failure, systemImage: "exclamationmark.triangle.fill")
                        .foregroundStyle(.orange)
                }
            } header: {
                Text("Target")
            } footer: {
                VpnRouteNote()
            }

            if let trace = tracer.trace, !trace.hops.isEmpty {
                let path = RouteTrace.path(trace.hops, places: tracer.places)
                if !path.isEmpty {
                    Section {
                        map(path)
                            .frame(height: 300)
                            .listRowInsets(EdgeInsets())
                    } footer: {
                        Text("Hops are placed by ipwho.is from their addresses, which it sees. A router's registered place can be far from where it stands, so the map is a rough picture.")
                    }
                }
                Section {
                    ForEach(Array(trace.hops.enumerated()), id: \.offset) { index, hop in
                        HopRow(ttl: index + 1, hop: hop, place: hop.address.flatMap { tracer.places[$0] })
                    }
                } header: {
                    Text("Path to \(tracer.target ?? trace.target)")
                } footer: {
                    if let address = tracer.address, address != tracer.target {
                        Text("\(tracer.target ?? "") is \(address). One probe per hop; a hop that does not answer shows as an asterisk.")
                    } else {
                        Text("One probe per hop; a hop that does not answer shows as an asterisk.")
                    }
                }
            }
        }
        .navigationTitle("Traceroute")
        .summaryButton(isShown: !tracer.running && tracer.trace != nil, subject: { .internet })
        .scrollDismissesKeyboard(.interactively)
        .onChange(of: tracer.places) { _, _ in camera = .automatic }
    }

    private func start() {
        editing = false
        tracer.run(target)
    }

    private func map(_ path: [(ttl: Int, place: HopPlace)]) -> some View {
        Map(position: $camera) {
            MapPolyline(coordinates: path.map { CLLocationCoordinate2D(latitude: $0.place.latitude, longitude: $0.place.longitude) })
                .stroke(.tint, style: StrokeStyle(lineWidth: 3, lineCap: .round, lineJoin: .round))
            ForEach(path, id: \.ttl) { point in
                Annotation(point.place.city ?? "", coordinate: CLLocationCoordinate2D(latitude: point.place.latitude, longitude: point.place.longitude)) {
                    Text("\(point.ttl)")
                        .font(.caption2.weight(.bold).monospacedDigit())
                        .foregroundStyle(.white)
                        .frame(minWidth: 22, minHeight: 22)
                        .background(Circle().fill(point.ttl == path.last?.ttl ? Color.green : Color.accentColor))
                        .overlay(Circle().stroke(.white, lineWidth: 1.5))
                }
            }
        }
        .mapStyle(.standard(pointsOfInterest: .excludingAll))
    }
}

/// Which connection the trace goes out through, as the Checks setting says.
private struct VpnRouteNote: View {
    @AppStorage(ProbeRoute.vpnKey) private var viaVpn = false
    @AppStorage(ProbeRoute.cellularKey) private var cellular = false

    var body: some View {
        Text(VpnToggle.text(viaVpn: viaVpn, cellular: cellular))
    }
}

private struct HopRow: View {
    var ttl: Int
    var hop: TraceHop
    var place: HopPlace?

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 12) {
            Text("\(ttl)")
                .font(.callout.monospacedDigit().weight(.semibold))
                .foregroundStyle(.secondary)
                .frame(width: 24, alignment: .trailing)
            VStack(alignment: .leading, spacing: 2) {
                Text(hop.address ?? "*")
                    .font(.callout.monospaced())
                    .textSelection(.enabled)
                if let name = hop.name {
                    Text(name).font(.caption).foregroundStyle(.secondary).lineLimit(1).truncationMode(.middle)
                }
                if let place {
                    Text(placeText(place)).font(.caption).foregroundStyle(.secondary).lineLimit(2)
                } else if let address = hop.address, !RouteTrace.isPublic(address) {
                    Text("Private network").font(.caption).foregroundStyle(.secondary)
                }
            }
            Spacer(minLength: 8)
            if let ms = hop.millis {
                Text(millis(ms)).font(.callout.monospacedDigit()).foregroundStyle(.secondary)
            }
        }
    }

    private func placeText(_ place: HopPlace) -> String {
        let region = place.countryCode.flatMap { Locale.current.localizedString(forRegionCode: $0) }
        return [[place.city, region].compactMap { $0 }.joined(separator: ", "), place.isp]
            .compactMap { $0?.isEmpty == false ? $0 : nil }
            .joined(separator: " · ")
    }
}
