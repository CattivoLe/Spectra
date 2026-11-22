import SwiftUI

/// Whether the connection check goes through an active VPN or around it, with
/// a line saying what that changes in the results.
struct VpnToggle: View {
    @Binding var viaVpn: Bool

    @Environment(WiFiJoiner.self) private var joiner
    @AppStorage(ProbeRoute.cellularKey) private var cellular = false

    static let onText: LocalizedStringKey = "Speed, external address and countries are measured through the VPN tunnel."
    static let offText: LocalizedStringKey = "The VPN is bypassed: the check measures the Wi-Fi or Ethernet connection itself."
    static let cellularText: LocalizedStringKey = "The check goes out through the mobile network, even while Wi-Fi is joined. It uses mobile data."

    static func text(viaVpn: Bool, cellular: Bool) -> LocalizedStringKey {
        viaVpn ? onText : (cellular ? cellularText : offText)
    }

    private var route: Binding<ProbeRoute> {
        Binding(
            get: { ProbeRoute(viaVpn: viaVpn, cellular: cellular) },
            set: { chosen in
                viaVpn = chosen == .vpn
                cellular = chosen == .cellular
            }
        )
    }

    @State private var autoSwitchedToCellular = false

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Picker("Check through", selection: route) {
                Text("Wi-Fi").tag(ProbeRoute.wifi)
                Text("Cellular").tag(ProbeRoute.cellular)
                Text("VPN").tag(ProbeRoute.vpn)
            }
            .pickerStyle(.segmented)
            Text(Self.text(viaVpn: viaVpn, cellular: cellular))
                .font(.caption)
                .foregroundStyle(.secondary)
        }
        .padding(.vertical, 2)
        .onAppear { syncWithWifi() }
        .onChange(of: joiner.currentSSID) { syncWithWifi() }
    }

    private func syncWithWifi() {
        let hasWifi = joiner.currentSSID != nil
        if !hasWifi && !viaVpn && !cellular {
            cellular = true
            autoSwitchedToCellular = true
        } else if hasWifi && autoSwitchedToCellular && cellular {
            cellular = false
            autoSwitchedToCellular = false
        }
    }
}

struct LinkView: View {
    var saved: SavedConnection?
    @Environment(LinkProbe.self) private var probe
    @AppStorage("probeViaVpn") private var viaVpn = false
    @AppStorage("autosaveHistory") private var autosave = true
    @Environment(HistoryStore.self) private var history
    @Environment(LANScanner.self) private var lanScanner
    @State private var extraPort = ""
    @FocusState private var editingPort: Bool

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
                    // One row: a separator between the bar and its caption looked like a second bar.
                    VStack(alignment: .leading, spacing: 8) {
                        ProgressView(value: probe.fraction)
                            .tint(.teal)
                        Text(linkText(probe.phase.isEmpty ? "Checking…" : probe.phase))
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                    .padding(.vertical, 4)
                }
            }
            if let report, report.status != nil {
                if let access = report.access {
                    AccessSection(report: access)
                }
                internetSection(report)
                if let address = report.external {
                    externalSection(address)
                    exposureSection(report, live: true)
                }
                if let tls = report.tls {
                    tlsSection(tls)
                }
                if let resolver = report.resolver {
                    resolverSection(resolver, publicIP: report.external?.ip)
                }
                if report.speed != nil || report.speedFailed {
                    speedSection(report)
                }
                if !report.regions.isEmpty {
                    Section {
                        ForEach(report.regions) { region in
                            Button {
                                probe.repingRegion(region.code)
                            } label: {
                                RegionRow(region: region, busy: probe.repinging.contains("region-" + region.code))
                            }
                            .buttonStyle(.plain)
                            .disabled(probe.running)
                        }
                    } header: {
                        Text("Countries")
                    } footer: {
                        Text("A connection to port 443. A miss can be this network filtering the site, or the site being down. Tap a row to measure it again.")
                    }
                }
                if !report.abroad.isEmpty || report.abroadNote != .none {
                    abroadSection(report, live: true)
                }
                if let trace = report.localTrace {
                    localTraceSection(trace)
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
        .summaryButton(isShown: !probe.running && probe.report?.status != nil, subject: { .internet })
        .toolbar {
            if !autosave, !probe.running, let report, report.status != nil, report.status != .noNetwork {
                ToolbarItem(placement: .topBarTrailing) {
                    SaveToHistoryButton {
                        history.upsertConnection(SavedConnection(viaVpn: viaVpn, viaCellular: ProbeRoute.current() == .cellular, snapshot: report),
                                                 networkKey: lanScanner.networkKey,
                                                 networkLabel: lanScanner.networkLabel, source: "This iPhone")
                    }
                }
            }
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
                Text(VpnToggle.text(viaVpn: saved.viaVpn, cellular: saved.viaCellular == true))
                    .foregroundStyle(.secondary)
            }
            if report.status != nil {
                if let access = report.access {
                    AccessSection(report: access)
                }
                internetSection(report)
                if let address = report.external {
                    externalSection(address)
                    if report.exposure != nil {
                        exposureSection(report, live: false)
                    }
                }
                if let tls = report.tls {
                    tlsSection(tls)
                }
                if let resolver = report.resolver {
                    resolverSection(resolver, publicIP: report.external?.ip)
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
                    abroadSection(report, live: false)
                }
                if let trace = report.localTrace {
                    localTraceSection(trace)
                }
                if !report.remoteTraces.isEmpty || report.traceNote != .none {
                    remoteSection(report)
                }
            }
        }
        .navigationTitle("Connection")
        .navigationBarTitleDisplayMode(.inline)
    }

    // MARK: Certificates, DNS, exposure

    private func tlsSection(_ check: TLSCheck) -> some View {
        Section {
            ForEach(check.observations) { observation in
                NavigationLink {
                    CertificateDetailView(observation: observation)
                } label: {
                    HStack(spacing: 12) {
                        Image(systemName: observationSymbol(observation))
                            .foregroundStyle(observationColor(observation))
                            .frame(width: 22)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(observation.host)
                            Text(observation.issuer ?? observation.error ?? "")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                    }
                }
            }
            Text(tlsVerdict(check))
                .font(.footnote)
                .foregroundStyle(check.intercepted ? Color.red : Color.secondary)
        } header: {
            Text("HTTPS certificates")
        } footer: {
            Text("Four well-known sites are asked for their certificates without loading a page. A proxy, an antivirus or an attacker on the network has to sign them itself, and that shows here.")
        }
    }

    private func observationSymbol(_ observation: TLSObservation) -> String {
        if observation.error != nil { return "questionmark.circle" }
        return observation.trusted && observation.expected ? "checkmark.seal.fill" : "xmark.seal.fill"
    }

    private func observationColor(_ observation: TLSObservation) -> Color {
        if observation.error != nil { return .secondary }
        return observation.trusted && observation.expected ? .green : .red
    }

    private func tlsVerdict(_ check: TLSCheck) -> String {
        if check.reachable == 0 { return linkText("Could not reach the reference sites to compare certificates.") }
        if check.intercepted {
            return linkText("Certificates do not come from the expected authorities: something between you and the internet re-signs HTTPS, such as a corporate proxy, an antivirus or an attacker on the Wi-Fi.")
        }
        return linkText("Certificates come from the expected authorities: no sign of HTTPS interception.")
    }

    private func resolverSection(_ resolver: DNSReport, publicIP: String?) -> some View {
        Section {
            if !resolver.configured.isEmpty {
                LabeledContent("Configured") {
                    Text(resolver.configured.prefix(2).joined(separator: ", "))
                        .monospacedDigit()
                        .multilineTextAlignment(.trailing)
                }
            }
            if let ip = resolver.resolverIP {
                LabeledContent("Answers from") {
                    VStack(alignment: .trailing, spacing: 2) {
                        Text(resolver.resolverName ?? ip)
                            .multilineTextAlignment(.trailing)
                        if resolver.resolverName != nil {
                            Text([ip, resolver.resolverCountry.map(countryName)].compactMap { $0 }.joined(separator: " · "))
                                .font(.caption)
                        }
                    }
                }
            }
            Text(pathText(resolver.path(publicIP: publicIP)))
                .font(.footnote)
                .foregroundStyle(.secondary)
            if let lies = resolver.liesAboutMissingNames {
                Label {
                    Text(lies ? "Invents addresses for names that do not exist" : "Does not invent addresses for missing names")
                } icon: {
                    Image(systemName: lies ? "exclamationmark.triangle.fill" : "checkmark.circle")
                        .foregroundStyle(lies ? .orange : .secondary)
                }
                .font(.subheadline)
            }
            if let doh = resolver.dohReachable {
                LabeledContent("DNS over HTTPS") {
                    Text(doh ? "Reachable" : "Blocked").foregroundStyle(doh ? Color.secondary : Color.orange)
                }
            }
            if let dot = resolver.dotReachable {
                LabeledContent("DNS over TLS") {
                    Text(dot ? "Reachable" : "Blocked").foregroundStyle(dot ? Color.secondary : Color.orange)
                }
            }
        } header: {
            Text("DNS")
        } footer: {
            Text("The address the lookups come from is learned by resolving whoami.akamai.net; ipwho.is names its owner. Encrypted DNS is tried against Cloudflare over HTTPS and over TLS on port 853.")
        }
    }

    private func pathText(_ path: DNSReport.Path) -> String {
        switch path {
        case .local: linkText("This network resolves names itself: the lookups leave from your own public address.")
        case .forwarded: linkText("The router takes the lookups and forwards them: the world sees them come from the resolver above.")
        case .direct: linkText("The phone asks a public resolver directly.")
        case .unknown: linkText("Could not learn where the lookups go.")
        }
    }

    private func exposureSection(_ report: LinkSnapshot, live: Bool) -> some View {
        Section {
            if let exposure = report.exposure {
                ForEach(exposure.ports) { port in
                    HStack(spacing: 12) {
                        Text(port.port, format: .number.grouping(.never))
                            .font(.body.monospacedDigit().weight(.semibold))
                            .frame(width: 52, alignment: .leading)
                        Text(DiscoveredPort.serviceName(for: port.port))
                            .foregroundStyle(.secondary)
                        Spacer(minLength: 8)
                        Text(port.open ? "Open" : "Closed")
                            .font(.subheadline.weight(port.open ? .semibold : .regular))
                            .foregroundStyle(port.open ? Color.red : Color.secondary)
                    }
                }
                if !exposure.ports.isEmpty {
                    Text(exposureSummary(exposure))
                        .font(.footnote)
                        .foregroundStyle(exposure.openPorts.isEmpty ? Color.secondary : Color.red)
                }
                if exposure.note != .none {
                    Text(exposure.note == .busy
                         ? linkText("The lookup service is busy. Try again in a minute.")
                         : linkText("check-host.net could not be asked about some ports."))
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
            }
            if live {
                if probe.checkingExposure {
                    HStack(spacing: 10) {
                        ProgressView()
                        Text("Asking check-host.net…")
                            .foregroundStyle(.secondary)
                    }
                } else if viaVpn {
                    Text("Through a VPN the public address belongs to the VPN server, so its ports say nothing about your router.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                } else {
                    Button(report.exposure == nil ? "Check ports from the internet" : "Check again", systemImage: "globe") {
                        probe.checkExposure()
                    }
                    .disabled(probe.running)
                    HStack(spacing: 10) {
                        Image(systemName: "number")
                            .foregroundStyle(.secondary)
                            .frame(width: 22)
                        TextField("Another port", text: $extraPort)
                            .keyboardType(.numberPad)
                            .monospacedDigit()
                            .focused($editingPort)
                            .onChange(of: extraPort) { _, value in
                                let digits = String(value.filter(\.isNumber).prefix(5))
                                if digits != value { extraPort = digits }
                            }
                        Button("Check") {
                            if let port = customPort {
                                editingPort = false
                                extraPort = ""
                                probe.checkExposure(port: port)
                            }
                        }
                        .buttonStyle(.borderless)
                        .disabled(customPort == nil || probe.running)
                    }
                }
            }
        } header: {
            Text("Ports from the internet")
        } footer: {
            Text("check-host.net tries to connect to your public address on ten common ports, plus any you add, from one of its servers. An open port means your router forwards it to something inside.")
        }
    }

    private var customPort: Int? {
        guard let port = Int(extraPort), (1...65535).contains(port) else { return nil }
        return port
    }

    private func exposureSummary(_ exposure: ExposureReport) -> String {
        let open = exposure.openPorts.count
        if open == 0 { return linkText("No tested port answers from the internet: the router does not let these in.") }
        return String(format: linkText("%lld ports answer from the internet. Anyone online can reach what is behind them."), Int64(open))
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
            if let speed = report.speed, speed.isFull {
                if let jitter = speed.jitterMs {
                    LabeledContent("Jitter", value: millis(jitter))
                }
                if let loaded = speed.loadedLatencyMs {
                    LabeledContent("Latency under load", value: millis(loaded))
                }
                if let loaded = speed.loadedLatencyMs, let idle = speed.latencyMs {
                    LabeledContent("Bufferbloat", value: SpeedResult.grade(added: loaded - idle))
                }
                if let down = speed.downSamples, let up = speed.upSamples, !down.isEmpty || !up.isEmpty {
                    SpeedChart(points: savedPoints(down: down, up: up))
                        .frame(height: 120)
                }
            }
            if report.speedFailed {
                Text("Could not measure the speed.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
            if saved == nil {
                NavigationLink {
                    SpeedTestView()
                } label: {
                    Label("Full speed test", systemImage: "speedometer")
                }
            }
        } header: {
            Text("Speed")
        } footer: {
            if report.speed?.isFull == true {
                Text("From the full speed test: several streams for eight seconds each way.")
            } else {
                Text("A few seconds of traffic to Cloudflare. It is a snapshot, not a full test.")
            }
        }
    }

    /// Quarter-second samples back into chart points, both from 0 s.
    private func savedPoints(down: [Double], up: [Double]) -> [SpeedPoint] {
        var points: [SpeedPoint] = []
        for (index, bps) in down.enumerated() {
            points.append(SpeedPoint(id: index, direction: .down, seconds: Double(index + 1) * 0.25, bps: bps))
        }
        for (index, bps) in up.enumerated() {
            points.append(SpeedPoint(id: down.count + index, direction: .up, seconds: Double(index + 1) * 0.25, bps: bps))
        }
        return points
    }

    private func abroadSection(_ report: LinkSnapshot, live: Bool) -> some View {
        Section {
            ForEach(report.abroad) { view in
                if live, view.node != nil {
                    Button {
                        probe.repingAbroad(view.id)
                    } label: {
                        AbroadRow(view: view, busy: probe.repinging.contains("abroad-" + view.id))
                    }
                    .buttonStyle(.plain)
                    .disabled(probe.running)
                } else {
                    AbroadRow(view: view)
                }
            }
            if let text = note(report.abroadNote) {
                Text(text)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
        } header: {
            Text("From abroad")
        } footer: {
            Text(live
                 ? "A phone behind a router usually does not answer pings from the internet. A reply means the public address answered. Tap a row to ask that server again."
                 : "A phone behind a router usually does not answer pings from the internet. A reply means the public address answered.")
        }
    }

    /// Every hop from this phone to the trace target, like a traceroute.
    private func localTraceSection(_ trace: LocalTrace) -> some View {
        Section {
            if trace.unavailable {
                Text("This device would not send the trace probes.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            } else if trace.hops.isEmpty {
                Text("No hop answered.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            } else {
                ForEach(Array(trace.hops.enumerated()), id: \.offset) { index, hop in
                    TraceHopRow(number: index + 1, hop: hop, isTarget: hop.address == trace.target)
                }
            }
        } header: {
            Text("Path from here to \(trace.target)")
        } footer: {
            Text("Each router on the way answers when a probe runs out of hops, which names it. A star is a router that stays silent; it still forwards traffic.")
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
            Text("Measured by servers in other countries, toward this network's public address: the way in, while the section above is the way out. Tap a server to see every hop.")
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
    var busy = false

    var body: some View {
        HStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 2) {
                Text(countryName(region.code))
                    .fixedSize(horizontal: false, vertical: true)
                Text(region.host)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            Spacer(minLength: 8)
            PingValue(text: region.ok ? millis(region.millis ?? 0) : linkText("No route"), ok: region.ok, busy: busy)
        }
        .contentShape(Rectangle())
    }
}

struct AbroadRow: View {
    var view: AbroadView
    var busy = false

    var body: some View {
        HStack(spacing: 12) {
            Text(place(view.code, view.city))
                .fixedSize(horizontal: false, vertical: true)
            Spacer(minLength: 8)
            PingValue(text: view.ok ? millis(view.millis ?? 0) : linkText("No reply"), ok: view.ok, busy: busy)
        }
        .contentShape(Rectangle())
    }
}

/// A round-trip time on the right of a row, or a spinner while it is measured again.
private struct PingValue: View {
    var text: String
    var ok: Bool
    var busy: Bool

    var body: some View {
        ZStack(alignment: .trailing) {
            Text(text)
                .foregroundStyle(ok ? .green : .secondary)
                .monospacedDigit()
                .lineLimit(1)
                .fixedSize()
                .opacity(busy ? 0 : 1)
            if busy {
                ProgressView()
            }
        }
    }
}

struct RemoteRow: View {
    var trace: RemoteTrace
    @State private var expanded = false

    var body: some View {
        DisclosureGroup(isExpanded: $expanded) {
            ForEach(Array(trace.hops.enumerated()), id: \.offset) { index, hop in
                TraceHopRow(number: index + 1, hop: hop, isTarget: false, compact: true)
            }
        } label: {
            VStack(alignment: .leading, spacing: 3) {
                Text(place(trace.code, trace.city))
                Text(outcome)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                if !expanded {
                    let path = LinkParse.compactPath(trace.hops)
                    if !path.isEmpty {
                        Text(path)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                            .monospaced()
                    }
                }
            }
            .padding(.vertical, 2)
        }
    }

    private var outcome: String {
        if trace.reached { return linkText("Reaches the address") }
        if let last = trace.hops.last(where: { $0.address != nil })?.address {
            return String(format: linkText("Stops at %@"), last)
        }
        return linkText("No hops came back")
    }
}

/// One hop of a traceroute: its number, address and name, and the round trip.
struct TraceHopRow: View {
    var number: Int
    var hop: TraceHop
    var isTarget: Bool
    var compact = false

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 10) {
            Text(verbatim: "\(number)")
                .font((compact ? Font.caption : Font.subheadline).monospacedDigit())
                .foregroundStyle(.secondary)
                .frame(width: 22, alignment: .trailing)
            VStack(alignment: .leading, spacing: 1) {
                Text(verbatim: hop.address ?? "*")
                    .font((compact ? Font.caption : Font.body).monospaced())
                    .foregroundStyle(hop.address == nil ? .secondary : .primary)
                    .textSelection(.enabled)
                if let name = hop.name {
                    Text(name)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .textSelection(.enabled)
                }
            }
            Spacer(minLength: 8)
            if let ms = hop.millis {
                Text(millis(ms))
                    .font(compact ? .caption : .subheadline)
                    .foregroundStyle(isTarget ? Color.green : Color.secondary)
                    .monospacedDigit()
            }
        }
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
    if let access = report.access { bits.insert(access.verdict.title, at: 0) }
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
