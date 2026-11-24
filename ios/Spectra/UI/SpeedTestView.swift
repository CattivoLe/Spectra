import Charts
import SwiftUI

/// The full speed test: a live chart while it runs, then the numbers a
/// speed-test site would show, kept on the current connection check.
struct SpeedTestView: View {
    @Environment(SpeedTester.self) private var tester
    @Environment(LinkProbe.self) private var probe
    @AppStorage("probeViaVpn") private var viaVpn = false
    @AppStorage("speedServer") private var serverRaw = SpeedServerChoice.cloudflare.raw
    @AppStorage("autosaveHistory") private var autosave = true
    @Environment(HistoryStore.self) private var history
    @Environment(LANScanner.self) private var lanScanner

    private var server: SpeedServerChoice { SpeedServerChoice(raw: serverRaw) }

    var body: some View {
        List {
            Section {
                VpnToggle(viaVpn: $viaVpn)
                    .disabled(tester.running)
                NavigationLink {
                    SpeedServerPicker(serverRaw: $serverRaw, viaVpn: viaVpn)
                } label: {
                    LabeledContent("Server") {
                        Text(serverLabel)
                            .lineLimit(1)
                    }
                }
                .disabled(tester.running)
            }
            Section {
                // One row, so no separators cut through the card.
                VStack(alignment: .leading, spacing: 14) {
                    headline
                    SpeedChart(points: tester.points)
                        .frame(height: 160)
                    if tester.running {
                        ProgressView(value: tester.progress)
                            .tint(.teal)
                    }
                }
                .padding(.vertical, 6)
            }
            if let result = tester.result {
                resultsSection(result)
            } else if case .failed(let reason) = tester.phase {
                Section {
                    Label(reason, systemImage: "exclamationmark.triangle")
                        .foregroundStyle(.orange)
                }
            } else if !tester.running {
                Section {
                    Button("Start speed test", systemImage: "speedometer") { tester.start(viaVpn: viaVpn, server: server) }
                } footer: {
                    Text(server == .cloudflare
                         ? "Eight seconds down and eight up over several streams to Cloudflare's speed servers, which see your IP address. Latency is measured before and during the transfers, since a full queue is what makes a fast line feel slow."
                         : "Eight seconds down and eight up over one stream each way to a Measurement Lab server. M-Lab publishes every measurement, your IP address included, as open data. Latency is measured before and during the transfers.")
                }
            }
        }
        .navigationTitle("Speed test")
        .navigationBarTitleDisplayMode(.inline)
        .summaryButton(isShown: !tester.running && tester.result != nil, subject: { .internet })
        .toolbar {
            // The result rides on the current connection check, which is what gets saved.
            if !autosave, !tester.running, tester.result != nil, let report = probe.report {
                ToolbarItem(placement: .topBarTrailing) {
                    SaveToHistoryButton {
                        history.upsertConnection(SavedConnection(viaVpn: viaVpn, viaCellular: ProbeRoute.current() == .cellular, snapshot: report),
                                                 networkKey: lanScanner.networkKey,
                                                 networkLabel: lanScanner.networkLabel, source: "This iPhone")
                    }
                }
            }
            ToolbarItem(placement: .topBarTrailing) {
                if tester.running {
                    Button("Stop", systemImage: "stop.fill", action: tester.stop)
                } else {
                    Button("Test again", systemImage: "arrow.clockwise") { tester.start(viaVpn: viaVpn, server: server) }
                }
            }
        }
        .onChange(of: tester.result) { _, result in
            if let result { probe.attach(speed: result.sample) }
        }
    }

    private var serverLabel: String {
        switch server {
        case .cloudflare:
            return String(localized: "Cloudflare, nearest")
        case .mlab(nil):
            return String(localized: "M-Lab, nearest")
        case .mlab(let machine?):
            if let found = tester.mlabServers.first(where: { $0.machine == machine }), !found.city.isEmpty {
                return "M-Lab · \(found.city)"
            }
            return "M-Lab · \(machine.split(separator: ".").first ?? "")"
        }
    }

    /// The big number: live throughput while a transfer runs, the final
    /// download otherwise.
    private var headline: some View {
        let (value, caption): (Double?, String) = switch tester.phase {
        case .download: (tester.liveBps, String(localized: "Download"))
        case .upload: (tester.liveBps, String(localized: "Upload"))
        case .latency: (nil, String(localized: "Measuring latency…"))
        case .preparing: (nil, String(localized: "Connecting…"))
        case .done: (tester.result?.downBps, String(localized: "Download"))
        case .idle, .failed: (nil, String(localized: "Not measured yet"))
        }
        return VStack(alignment: .leading, spacing: 4) {
            HStack(alignment: .firstTextBaseline, spacing: 6) {
                Text(value.map { mbps($0) } ?? "–")
                    .font(.system(size: 56, weight: .semibold, design: .rounded).monospacedDigit())
                    .foregroundStyle(value == nil ? AnyShapeStyle(.tertiary) : AnyShapeStyle(.primary))
                    .contentTransition(.numericText())
                Text("Mbit/s")
                    .font(.title3)
                    .foregroundStyle(.secondary)
            }
            Text(caption)
                .font(.subheadline)
                .foregroundStyle(.secondary)
        }
        .padding(.top, 4)
        .animation(.default, value: tester.liveBps)
    }

    /// A direction that moved too little to tell says so, instead of leaving its row out.
    private func speedValue(_ bps: Double?) -> Text {
        bps.map { Text(rate($0)).monospacedDigit() } ?? Text("Could not be measured")
    }

    private func resultsSection(_ result: SpeedResult) -> some View {
        Section {
            LabeledContent("Download") { speedValue(result.downBps) }
            LabeledContent("Upload") { speedValue(result.upBps) }
            if let latency = result.idleLatencyMs {
                LabeledContent("Latency") { Text(millis(latency)).monospacedDigit() }
            }
            if let jitter = result.jitterMs {
                LabeledContent("Jitter") { Text(millis(jitter)).monospacedDigit() }
            }
            if let loaded = result.loadedDownLatencyMs {
                LabeledContent("Latency while downloading") { Text(millis(loaded)).monospacedDigit() }
            }
            if let loaded = result.loadedUpLatencyMs {
                LabeledContent("Latency while uploading") { Text(millis(loaded)).monospacedDigit() }
            }
            if let grade = result.bufferbloatGrade {
                LabeledContent("Bufferbloat") {
                    Text(grade)
                        .font(.body.weight(.semibold))
                        .foregroundStyle(gradeColor(grade))
                }
            }
            LabeledContent("Streams") { Text(verbatim: "\(result.streams) ↓ · \(result.upStreams ?? SpeedTester.uploadStreams) ↑") }
            if let server = result.server {
                LabeledContent("Server") {
                    Text(verbatim: "\(result.provider ?? "Cloudflare") · \(server)")
                        .multilineTextAlignment(.trailing)
                }
            }
        } header: {
            Text("Result")
        } footer: {
            VStack(alignment: .leading, spacing: 6) {
                if let substitution = tester.substitution {
                    Label(substitution, systemImage: "arrow.triangle.swap")
                        .foregroundStyle(.orange)
                }
                if tester.throttled {
                    Label("Cloudflare limited this test after several in a row from your address, so the figures may be lower than the line can do.", systemImage: "exclamationmark.triangle")
                        .foregroundStyle(.orange)
                }
                Text(verdict(result))
            }
        }
    }

    private func verdict(_ result: SpeedResult) -> String {
        guard let grade = result.bufferbloatGrade, let idle = result.idleLatencyMs else {
            return linkText("Throughput is the steady part of each transfer, after the first second of ramp-up.")
        }
        let loaded = [result.loadedDownLatencyMs, result.loadedUpLatencyMs].compactMap { $0 }.max() ?? idle
        switch grade {
        case "A", "B":
            return String(format: linkText("Latency stays near %lld ms even with the line full: calls and games will not suffer while something downloads."), Int64(loaded))
        case "C":
            return String(format: linkText("Latency grows to %lld ms under load. Noticeable in calls while someone else streams or downloads."), Int64(loaded))
        default:
            return String(format: linkText("Latency climbs to %lld ms under load: a full queue in the router or modem. Smart queue management (SQM, QoS) on the router fixes this."), Int64(loaded))
        }
    }

    private func gradeColor(_ grade: String) -> Color {
        switch grade {
        case "A": .green
        case "B": .mint
        case "C": .yellow
        case "D": .orange
        default: .red
        }
    }

    private func mbps(_ bps: Double) -> String {
        let value = bps / 1_000_000
        return value >= 100 ? String(format: "%.0f", value) : String(format: "%.1f", value)
    }
}

/// Throughput over time. Both directions share one fixed time axis, upload
/// drawn over download, so the chart spans its full width from the start and
/// only the curves change as samples arrive.
struct SpeedChart: View {
    var points: [SpeedPoint]
    var seconds: Double = SpeedTester.downloadSeconds

    private var downLabel: String { String(localized: "Download") }
    private var upLabel: String { String(localized: "Upload") }

    /// Each curve starts from zero at 0 s instead of hanging in the air.
    private var plotted: [SpeedPoint] {
        var result: [SpeedPoint] = []
        for (direction, id) in [(SpeedPoint.Direction.down, -1), (.up, -2)] {
            // The last sample lands up to a quarter second past the end: it
            // is pinned to the right edge so the curve spans the full width.
            var series: [SpeedPoint] = []
            for point in points where point.direction == direction {
                if point.seconds <= seconds {
                    series.append(point)
                } else if point.seconds <= seconds + 0.5, series.last?.seconds != seconds {
                    var pinned = point
                    pinned.seconds = seconds
                    series.append(pinned)
                }
            }
            if !series.isEmpty {
                result.append(SpeedPoint(id: id, direction: direction, seconds: 0, bps: 0))
                result += series
            }
        }
        return result
    }

    /// A round ceiling that changes rarely, so the curve is not rescaled at every sample.
    private var ceiling: Double {
        let peak = (points.map(\.bps).max() ?? 0) / 1_000_000 * 1.1
        let steps: [Double] = [10, 25, 50, 100, 200, 300, 400, 500, 750, 1000, 1500, 2000, 2500, 5000, 10000]
        return steps.first { $0 >= peak } ?? peak
    }

    var body: some View {
        Chart(plotted) { point in
            let label = point.direction == .down ? downLabel : upLabel
            // Unstacked: each area starts from zero instead of sitting on the other.
            AreaMark(x: .value("Time", point.seconds), y: .value("Mbit/s", point.bps / 1_000_000),
                     series: .value("Direction", point.direction.rawValue), stacking: .unstacked)
                .foregroundStyle(by: .value("Direction", label))
                .opacity(0.22)
                .interpolationMethod(.monotone)
            LineMark(x: .value("Time", point.seconds), y: .value("Mbit/s", point.bps / 1_000_000), series: .value("Direction", point.direction.rawValue))
                .foregroundStyle(by: .value("Direction", label))
                .lineStyle(StrokeStyle(lineWidth: 2, lineCap: .round, lineJoin: .round))
                .interpolationMethod(.monotone)
        }
        .chartForegroundStyleScale([downLabel: Color.teal, upLabel: Color.orange])
        .chartXScale(domain: 0 ... seconds)
        .chartYScale(domain: 0 ... ceiling)
        .chartXAxis {
            AxisMarks(values: .stride(by: 2)) { value in
                AxisGridLine()
                AxisValueLabel {
                    if let seconds = value.as(Double.self) { Text(verbatim: "\(Int(seconds)) s") }
                }
            }
        }
        .chartYAxis {
            AxisMarks(position: .leading, values: .automatic(desiredCount: 3))
        }
        .chartLegend(position: .top, alignment: .leading, spacing: 14)
        .chartPlotStyle { $0.clipped() }
        .overlay {
            if points.isEmpty {
                Text("The chart fills in as the test runs.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
        .animation(.easeOut(duration: 0.25), value: points.count)
    }
}

/// Cloudflare, or one of the Measurement Lab machines near this network.
struct SpeedServerPicker: View {
    @Binding var serverRaw: String
    var viaVpn: Bool
    @Environment(SpeedTester.self) private var tester
    @Environment(\.dismiss) private var dismiss

    private var selected: SpeedServerChoice { SpeedServerChoice(raw: serverRaw) }

    var body: some View {
        List {
            Section {
                row(.cloudflare, title: Text("Cloudflare"), detail: Text("Nearest edge, chosen by the network"))
            } footer: {
                Text("Cloudflare answers on one address everywhere and the network routes you to the nearest of its sites, so a particular one cannot be picked. Several parallel streams fill fast lines.")
            }
            Section {
                row(.mlab(machine: nil), title: Text("Nearest"), detail: Text("Picked by M-Lab at test time"))
                ForEach(tester.mlabServers) { server in
                    row(.mlab(machine: server.machine),
                        title: Text(verbatim: [server.city, countryName(server.country)].filter { !$0.isEmpty }.joined(separator: ", ")),
                        detail: Text(verbatim: server.shortName))
                }
                if tester.locating {
                    HStack(spacing: 10) {
                        ProgressView()
                        Text("Asking M-Lab for servers…")
                            .foregroundStyle(.secondary)
                    }
                } else if tester.mlabServers.isEmpty {
                    Button("Load servers", systemImage: "arrow.clockwise") { tester.loadServers(viaVpn: viaVpn) }
                }
            } header: {
                Text(verbatim: "Measurement Lab")
            } footer: {
                Text("M-Lab offers the few machines nearest to this network. It measures over one stream each way and publishes every result, your IP address included, as open data.")
            }
        }
        .navigationTitle("Server")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear { tester.loadServers(viaVpn: viaVpn) }
    }

    private func row(_ choice: SpeedServerChoice, title: Text, detail: Text) -> some View {
        Button {
            serverRaw = choice.raw
            dismiss()
        } label: {
            HStack(spacing: 12) {
                VStack(alignment: .leading, spacing: 2) {
                    title
                        .foregroundStyle(.primary)
                    detail
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                Spacer(minLength: 8)
                if selected == choice {
                    Image(systemName: "checkmark")
                        .font(.body.weight(.semibold))
                        .foregroundStyle(.tint)
                }
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}
