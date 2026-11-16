import Charts
import SwiftUI

struct ChannelsView: View {
    @Environment(NetworkStore.self) private var store

    private func inRange(_ band: Band) -> [AccessPointReading] {
        store.networks.values
            .filter { $0.isInRange && $0.latest.band == band }
            .map(\.latest)
            .sorted { $0.rssi > $1.rssi }
    }

    private var inRange: [AccessPointReading] { inRange(.twoPointFourGHz) }
    private var five: [AccessPointReading] { inRange(.fiveGHz) }

    var body: some View {
        List {
                if inRange.isEmpty && five.isEmpty {
                    ContentUnavailableView(
                        "Nothing on the air yet",
                        systemImage: "waveform.path",
                        description: Text("Channel use is drawn from the networks the sensor finds.")
                    )
                    .listRowBackground(Color.clear)
                }
                if !inRange.isEmpty {
                    Section {
                        ChannelSpectrumChart(readings: inRange)
                            .frame(height: 240)
                            .padding(.vertical, 8)
                    } header: {
                        Text(Units.gigahertz(2.4))
                    } footer: {
                        Text("Each hump is one network: its width is the channels it covers, its height how loud it is here.")
                    }

                    recommendation

                    Section("Networks per channel") {
                        ForEach(1...13, id: \.self) { channel in
                            ChannelRow(channel: channel, readings: inRange)
                        }
                    }
                }
                if !five.isEmpty {
                    fiveGHzSections
                }
            }
            .navigationTitle("Channels")
            .animation(.default, value: inRange.map(\.rssi) + five.map(\.rssi))
    }

    /// 5 GHz from an ESP32-C5 sensor: the spectrum over the channels in use,
    /// the quietest common channel, and the busy channels one per row.
    @ViewBuilder
    private var fiveGHzSections: some View {
        Section {
            ChannelSpectrumChart(readings: five, band: .fiveGHz)
                .frame(height: 240)
                .padding(.vertical, 8)
        } header: {
            Text(Units.gigahertz(5))
        } footer: {
            Text("Heard by an ESP32-C5 sensor. The Cardputer and the WROOM-32 hear 2.4 GHz only.")
        }
        let best = ChannelAdvisor.recommendationFiveGHz(from: five)
        Section {
            adviceRow(best, detail: "Of the non-DFS channels 36–48 and 149–161, this one meets the least interference.")
        }
        Section("Networks per channel") {
            ForEach(Array(Set(five.map(\.channel))).sorted(), id: \.self) { channel in
                ChannelRow(channel: channel, readings: five)
            }
        }
    }

    private func adviceRow(_ channel: Int, detail: LocalizedStringKey) -> some View {
        HStack(spacing: 14) {
            Text("\(channel)")
                .font(.system(size: 30, weight: .bold, design: .rounded))
                .foregroundStyle(Color.accentColor)
                .minimumScaleFactor(0.6)
                .frame(width: 52, height: 52)
                .background(Color.accentColor.opacity(0.12), in: .rect(cornerRadius: 12))
            VStack(alignment: .leading, spacing: 3) {
                Text("Quietest channel here").font(.headline)
                Text(detail)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }
        }
        .padding(.vertical, 4)
    }

    private var recommendation: some View {
        let best = ChannelAdvisor.recommendation(from: inRange)
        return Section {
            HStack(spacing: 14) {
                Text("\(best)")
                    .font(.system(size: 34, weight: .bold, design: .rounded))
                    .foregroundStyle(Color.accentColor)
                    .frame(width: 52, height: 52)
                    .background(Color.accentColor.opacity(0.12), in: .rect(cornerRadius: 12))
                VStack(alignment: .leading, spacing: 3) {
                    Text("Quietest channel here").font(.headline)
                    Text("Of the non-overlapping channels 1, 6 and 11, this one meets the least interference. Set it on your router if you control one.")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }
            }
            .padding(.vertical, 4)
        }
    }
}

private struct ChannelRow: View {
    var channel: Int
    var readings: [AccessPointReading]

    var body: some View {
        let onChannel = readings.filter { $0.channel == channel }
        let level = ChannelAdvisor.level(ChannelAdvisor.congestion(on: channel, from: readings))
        HStack(spacing: 12) {
            Text("\(channel)")
                .font(.body.monospacedDigit().weight(.semibold))
                .lineLimit(1)
                .fixedSize()
                .frame(minWidth: 36, alignment: .trailing)
            Gauge(value: level) { EmptyView() }
                .gaugeStyle(.accessoryLinearCapacity)
                .tint(level < 0.35 ? .green : level < 0.6 ? .yellow : level < 0.8 ? .orange : .red)
            Text(onChannel.isEmpty ? "—" : "\(onChannel.count)")
                .font(.callout.monospacedDigit())
                .foregroundStyle(.secondary)
                .frame(width: 24, alignment: .trailing)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text("Channel \(channel)"))
        .accessibilityValue("\(onChannel.count) networks, \(Int(level * 100))% busy")
    }
}

struct ChannelSpectrumChart: View {
    var readings: [AccessPointReading]
    var band: Band = .twoPointFourGHz

    private static let floor = -100.0

    private struct Point: Identifiable {
        var key: String
        var x: Double
        var y: Double
        var id: String { "\(key)-\(x)" }
    }

    private struct Peak: Identifiable {
        var key: String
        var name: String
        var x: Double
        var y: Double
        var id: String { key }
    }

    /// A flat-topped hump over the channels a network covers.
    private var points: [Point] {
        readings.flatMap { reading -> [Point] in
            let span = reading.occupiedChannels
            let centre = (span.lowerBound + span.upperBound) / 2
            let half = (span.upperBound - span.lowerBound) / 2
            return stride(from: span.lowerBound, through: span.upperBound, by: 0.25).map { x in
                let t = abs(x - centre) / half
                let shape = t <= 0.6 ? 1 : pow(cos((t - 0.6) / 0.4 * .pi / 2), 2)
                let y = Self.floor + (Double(reading.rssi) - Self.floor) * shape
                return Point(key: reading.bssid.description, x: x, y: y)
            }
        }
    }

    /// Names on the loudest few humps, skipping any that would land on a
    /// label already placed; labelling them all is unreadable.
    private var peaks: [Peak] {
        var placed: [Peak] = []
        for reading in readings where placed.count < 6 {
            let span = reading.occupiedChannels
            let peak = Peak(
                key: reading.bssid.description,
                name: reading.displayName,
                x: (span.lowerBound + span.upperBound) / 2,
                y: Double(reading.rssi)
            )
            let crowded = placed.contains { abs($0.x - peak.x) < 2.5 && abs($0.y - peak.y) < 8 }
            if !crowded {
                placed.append(peak)
            }
        }
        return placed
    }

    /// Top of the signal axis: just above the loudest network, leaving room
    /// for its label, so quiet airwaves don't sit at the bottom of an empty chart.
    private var ceiling: Double {
        let loudest = Double(readings.map(\.rssi).max() ?? -70)
        let top = ((loudest + 5) / 5).rounded(.up) * 5
        return min(-20, max(Self.floor + 25, top))
    }

    private var signalMarks: [Double] {
        let step = ceiling - Self.floor > 50 ? 20.0 : 10.0
        return Array(stride(from: -90, through: ceiling, by: step))
    }

    /// 2.4 GHz always shows channels 1–13; 5 GHz shows just the stretch in use,
    /// since 36–165 would squeeze every network into a sliver.
    private var channelDomain: ClosedRange<Double> {
        let spans = readings.map(\.occupiedChannels)
        if band == .fiveGHz {
            let low = spans.map(\.lowerBound).min() ?? 36
            let high = spans.map(\.upperBound).max() ?? 48
            // Wide margins, so a three-digit label at either end is not cut.
            return (low - 4) ... (high + 4)
        }
        let low = min(1, spans.map(\.lowerBound).min() ?? 1)
        let high = max(13, spans.map(\.upperBound).max() ?? 13)
        return (low - 0.5) ... (high + 0.5)
    }

    private var channelMarks: [Int] {
        guard band == .fiveGHz else { return Array(1...13) }
        let domain = channelDomain
        let used = Set(readings.map(\.channel))
        // The used channels when they fit, otherwise the band's landmarks.
        if used.count <= 8 { return used.sorted() }
        return [36, 52, 100, 116, 132, 149, 165].filter { domain.contains(Double($0)) }
    }

    var body: some View {
        Chart {
            ForEach(points) { point in
                AreaMark(
                    x: .value("Channel", point.x),
                    yStart: .value("Floor", Self.floor),
                    yEnd: .value("Signal", point.y)
                )
                .foregroundStyle(by: .value("Network", point.key))
                .opacity(0.22)
                .interpolationMethod(.catmullRom)

                LineMark(x: .value("Channel", point.x), y: .value("Signal", point.y), series: .value("Network", point.key))
                    .foregroundStyle(by: .value("Network", point.key))
                    .lineStyle(StrokeStyle(lineWidth: 1.5))
                    .interpolationMethod(.catmullRom)
            }
            ForEach(peaks) { peak in
                PointMark(x: .value("Channel", peak.x), y: .value("Signal", peak.y))
                    .symbolSize(0)
                    .annotation(position: .top, spacing: 2) {
                        Text(peak.name)
                            .font(.caption2)
                            .foregroundStyle(.secondary)
                            .multilineTextAlignment(.center)
                            .lineLimit(2)
                            .frame(maxWidth: 150)
                            .fixedSize(horizontal: false, vertical: true)
                            .padding(.horizontal, 4)
                            .background(.background.opacity(0.7), in: .rect(cornerRadius: 4))
                    }
            }
        }
        .chartLegend(.hidden)
        .chartXScale(domain: channelDomain)
        .chartYScale(domain: Self.floor ... ceiling)
        .chartXAxis {
            AxisMarks(values: channelMarks) { _ in
                AxisGridLine()
                AxisValueLabel()
            }
        }
        .chartYAxis {
            AxisMarks(position: .leading, values: signalMarks)
        }
    }
}
