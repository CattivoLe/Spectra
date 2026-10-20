import SwiftUI

enum LANRoute: Hashable {
    case device(String)
    case connection
}

/// Whether the connection check goes through an active VPN or around it, with
/// a line saying what that changes in the results.
struct VpnToggle: View {
    @Binding var viaVpn: Bool

    static let onText: LocalizedStringKey = "Speed, external address and countries are measured through the VPN tunnel."
    static let offText: LocalizedStringKey = "The VPN is bypassed: the check measures the Wi-Fi or Ethernet connection itself."

    var body: some View {
        Toggle(isOn: $viaVpn) {
            VStack(alignment: .leading, spacing: 2) {
                Text("Check through VPN")
                Text(viaVpn ? Self.onText : Self.offText)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
    }
}

struct LinkView: View {
    var saved: SavedConnection?
    @Environment(LinkProbe.self) private var probe
    @AppStorage("probeViaVpn") private var viaVpn = false

    var body: some View {
        if let saved {
            savedList(saved)
        } else {
            liveList
        }
    }

    @ViewBuilder
    private var liveList: some View {
        let report = probe.report
        List {
            Section {
                VpnToggle(viaVpn: $viaVpn)
                    .disabled(probe.running)
            }
            if probe.running {
                Section {
                    ProgressView(value: probe.fraction)
                        .tint(.teal)
                    Text(linkText(probe.phase.isEmpty ? "Checking…" : probe.phase))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
            if let report, report.status != nil {
                internetSection(report)
                if let address = report.external {
                    externalSection(address)
                }
                if report.speed != nil || report.speedFailed {
                    speedSection(report)
                }
                if !report.regions.isEmpty {
                    Section {
                        ForEach(report.regions) { region in
                            RegionRow(region: region)
                        }
                    } header: {
                        Text("Countries")
                    } footer: {
                        Text("A connection to port 443. A miss can be this network filtering the site, or the site being down.")
                    }
                }
                if !report.abroad.isEmpty || report.abroadNote != .none {
                    abroadSection(report)
                }
                if !report.remoteTraces.isEmpty || report.traceNote != .none {
                    remoteSection(report)
                }
            } else if !probe.running {
                Section {
                    ContentUnavailableView {
                        Label("Not checked yet", systemImage: "globe")
                    } description: {
                        Text("Run a check to see whether this network reaches the internet, how fast it is, which countries answer, and the path out.")
                    } actions: {
                        Button("Check connection", action: probe.check)
                            .buttonStyle(.borderedProminent)
                    }
                }
                .listRowBackground(Color.clear)
                .listRowSeparator(.hidden)
            }
        }
        .navigationTitle("Connection")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                if probe.running {
                    Button("Stop", systemImage: "stop.fill", action: probe.stop)
                } else {
                    Button("Check again", systemImage: "arrow.clockwise", action: probe.check)
                }
            }
        }
    }

    @ViewBuilder
    private func savedList(_ saved: SavedConnection) -> some View {
        let report = saved.snapshot
        List {
            Section {
                Text(saved.viaVpn ? VpnToggle.onText : VpnToggle.offText)
                    .foregroundStyle(.secondary)
            }
            if report.status != nil {
                internetSection(report)
                if let address = report.external {
                    externalSection(address)
                }
                if report.speed != nil || report.speedFailed {
                    speedSection(report)
                }
                if !report.regions.isEmpty {
                    Section {
                        ForEach(report.regions) { region in
                            RegionRow(region: region)
                        }
                    } header: {
                        Text("Countries")
                    } footer: {
                        Text("A connection to port 443. A miss can be this network filtering the site, or the site being down.")
                    }
                }
                if !report.abroad.isEmpty || report.abroadNote != .none {
                    abroadSection(report)
                }
                if !report.remoteTraces.isEmpty || report.traceNote != .none {
                    remoteSection(report)
                }
            }
        }
        .navigationTitle("Connection")
        .navigationBarTitleDisplayMode(.inline)
    }

    @ViewBuilder
    private func internetSection(_ report: LinkSnapshot) -> some View {
        Section {
            LabeledContent("Internet") {
                Text(statusLabel(report.status))
                    .foregroundStyle(statusColor(report.status))
            }
            if let gateway = report.gateway {
                probeRow("Gateway", gateway, down: "Unreachable")
            }
            if let dns = report.dns {
                probeRow("DNS", dns, down: "No reply")
            }
            if let web = report.web {
                probeRow("Secure web", web, down: "No reply")
            }
            if let detail = report.detail {
                Text(linkText(detail))
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
        } header: {
            Text("Internet")
        } footer: {
            Text(checked(report.checkedAt))
        }
    }

    private func externalSection(_ address: ExternalAddress) -> some View {
        let place = [address.city, address.countryCode.map(countryName)]
            .compactMap { $0?.isEmpty == false ? $0 : nil }
            .joined(separator: ", ")
        let provider = [address.asn, address.isp].compactMap { $0 }.filter { !$0.isEmpty }.joined(separator: " ")
        return Section("External address") {
            LabeledContent("External address") {
                Text(address.ip)
                    .monospaced()
                    .textSelection(.enabled)
            }
            if !place.isEmpty {
                LabeledContent("Place", value: place)
            }
            if !provider.isEmpty {
                LabeledContent("Provider", value: provider)
            }
            if let colo = address.colo {
                LabeledContent("Edge") {
                    Text(colo).monospaced()
                }
            }
            if address.warp {
                Text("WARP is on")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
        }
    }

    private func speedSection(_ report: LinkSnapshot) -> some View {
        Section {
            if let latency = report.speed?.latencyMs {
                LabeledContent("Latency", value: millis(latency))
            }
            if let down = report.speed?.downBps {
                LabeledContent("Download", value: rate(down))
            }
            if let up = report.speed?.upBps {
                LabeledContent("Upload", value: rate(up))
            }
            if report.speedFailed {
                Text("Could not measure the speed.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
        } header: {
            Text("Speed")
        } footer: {
            Text("A few seconds of traffic to Cloudflare. It is a snapshot, not a full test.")
        }
    }

    private func abroadSection(_ report: LinkSnapshot) -> some View {
        Section {
            ForEach(report.abroad) { view in
                AbroadRow(view: view)
            }
            if let text = note(report.abroadNote) {
                Text(text)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
        } header: {
            Text("From abroad")
        } footer: {
            Text("A phone behind a router usually does not answer pings from the internet. A reply means the public address answered.")
        }
    }

    private func remoteSection(_ report: LinkSnapshot) -> some View {
        Section {
            ForEach(report.remoteTraces) { trace in
                RemoteRow(trace: trace)
            }
            if let text = note(report.traceNote) {
                Text(text)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
        } header: {
            Text("Paths toward this address")
        } footer: {
            Text("Measured by servers in other countries, toward this network's public address. This phone cannot send the probes a traceroute needs.")
        }
    }

    private func probeRow(_ title: LocalizedStringKey, _ result: ProbeResult, down: String) -> some View {
        LabeledContent(title) {
            Text(result.ok ? probeValue(result) : linkText(down))
                .foregroundStyle(result.ok ? Color.secondary : Color.orange)
                .monospacedDigit()
        }
    }
}

struct RegionRow: View {
    var region: RegionReach

    var body: some View {
        LabeledContent {
            Text(region.ok ? millis(region.millis ?? 0) : linkText("No route"))
                .foregroundStyle(region.ok ? .green : .secondary)
                .monospacedDigit()
        } label: {
            VStack(alignment: .leading, spacing: 2) {
                Text(countryName(region.code))
                Text(region.host)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
    }
}

struct AbroadRow: View {
    var view: AbroadView

    var body: some View {
        LabeledContent(place(view.code, view.city)) {
            Text(view.ok ? millis(view.millis ?? 0) : linkText("No reply"))
                .foregroundStyle(view.ok ? .green : .secondary)
                .monospacedDigit()
        }
    }
}

struct RemoteRow: View {
    var trace: RemoteTrace

    var body: some View {
        VStack(alignment: .leading, spacing: 3) {
            Text(place(trace.code, trace.city))
            Text(outcome)
                .font(.caption)
                .foregroundStyle(.secondary)
            let path = LinkParse.compactPath(trace.hops)
            if !path.isEmpty {
                Text(path)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .monospaced()
            }
        }
        .padding(.vertical, 2)
    }

    private var outcome: String {
        if trace.reached { return linkText("Reaches the address") }
        if let last = trace.hops.last(where: { $0.address != nil })?.address {
            return String(format: linkText("Stops at %@"), last)
        }
        return linkText("No hops came back")
    }
}

func connectionTitle(_ probe: LinkProbe) -> String {
    switch probe.report?.status {
    case .online: return linkText("Online")
    case .captive: return linkText("Sign-in required")
    case .lan: return linkText("Local only")
    case .offline: return linkText("Offline")
    case .noNetwork: return linkText("No Wi-Fi")
    case nil: return probe.running ? linkText("Checking…") : linkText("Connection")
    }
}

func snapshotDetail(_ report: LinkSnapshot, viaVpn: Bool) -> String {
    var bits: [String] = []
    if let ip = report.external?.ip { bits.append(ip) }
    if let down = report.speed?.downBps { bits.append(rate(down)) }
    if viaVpn { bits.append(linkText("Through VPN")) }
    if !bits.isEmpty { return bits.joined(separator: " · ") }
    if let detail = report.detail { return linkText(detail) }
    return checked(report.checkedAt)
}

func connectionDetail(_ probe: LinkProbe) -> String {
    if probe.running, !probe.phase.isEmpty { return linkText(probe.phase) }
    guard let report = probe.report else {
        return linkText("Internet, speed, countries and the path out of this network.")
    }
    var bits: [String] = []
    if let ip = report.external?.ip { bits.append(ip) }
    if let down = report.speed?.downBps { bits.append(rate(down)) }
    if let city = report.external?.city, !city.isEmpty { bits.append(city) }
    if !bits.isEmpty { return bits.joined(separator: " · ") }
    if let detail = report.detail { return linkText(detail) }
    return checked(report.checkedAt)
}

private func probeValue(_ result: ProbeResult) -> String {
    if let millis = result.millis {
        return String(format: linkText("%@ · %lld ms"), result.name, Int64(millis))
    }
    return result.name
}

func statusLabel(_ status: LinkStatus?) -> String {
    switch status {
    case .online: return linkText("Online")
    case .captive: return linkText("Sign-in required")
    case .lan: return linkText("Local only")
    case .offline: return linkText("Offline")
    case .noNetwork: return linkText("No Wi-Fi")
    case nil: return linkText("Checking…")
    }
}

private func statusColor(_ status: LinkStatus?) -> Color {
    switch status {
    case .online: return .green
    case .captive, .lan: return .orange
    case .offline, .noNetwork: return .red
    case nil: return .secondary
    }
}

private func note(_ note: RemoteNote) -> String? {
    switch note {
    case .busy: return linkText("The lookup service is busy. Try again in a minute.")
    case .failed: return linkText("Could not ask the other countries.")
    case .none: return nil
    }
}

func countryName(_ code: String) -> String {
    guard !code.isEmpty else { return "" }
    return Locale.current.localizedString(forRegionCode: code.uppercased()) ?? code.uppercased()
}

func place(_ code: String, _ city: String) -> String {
    [countryName(code), city].filter { !$0.isEmpty }.joined(separator: " · ")
}

func millis(_ value: Int) -> String {
    String(format: linkText("%lld ms"), locale: .current, Int64(value))
}

func rate(_ bps: Double) -> String {
    if bps >= 1_000_000 {
        return String(format: linkText("%.1f Mbit/s"), locale: .current, bps / 1_000_000)
    }
    if bps >= 1_000 {
        return String(format: linkText("%.0f kbit/s"), locale: .current, bps / 1_000)
    }
    return String(format: linkText("%.0f bit/s"), locale: .current, bps)
}

private func checked(_ date: Date) -> String {
    String(format: linkText("Checked %@"), date.formatted(.relative(presentation: .named)))
}
