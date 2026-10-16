import Charts
import SwiftUI

struct ChannelsView: View {
    @Environment(NetworkStore.self) private var store

    private var inRange: [AccessPointReading] {
        store.networks.values
            .filter { $0.isInRange && $0.latest.band == .twoPointFourGHz }
            .map(\.latest)
            .sorted { $0.rssi > $1.rssi }
    }

    var body: some View {
        NavigationStack {
            List {
                if inRange.isEmpty {
                    ContentUnavailableView(
                        "Nothing on the air yet",
                        systemImage: "waveform.path",
                        description: Text("Channel use is drawn from the networks the sensor finds.")
                    )
                    .listRowBackground(Color.clear)
                } else {
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
            }
            .navigationTitle("Channels")
            .animation(.default, value: inRange.map(\.rssi))
        }
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
                .frame(width: 26, alignment: .trailing)
            Gauge(value: level) { EmptyView() }
                .gaugeStyle(.accessoryLinearCapacity)
                .tint(level < 0.35 ? .green : level < 0.6 ? .yellow : level < 0.8 ? .orange : .red)
            Text(onChannel.isEmpty ? "—" : "\(onChannel.count)")
                .font(.callout.monospacedDigit())
                .foregroundStyle(.secondary)
                .frame(width: 24, alignment: .trailing)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Channel \(channel)")
        .accessibilityValue("\(onChannel.count) networks, \(Int(level * 100))% busy")
    }
}

struct ChannelSpectrumChart: View {
    var readings: [AccessPointReading]

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
        let top = ((loudest + 10) / 10).rounded(.up) * 10
        return min(-20, max(Self.floor + 30, top))
    }

    private var signalMarks: [Double] {
        let step = ceiling - Self.floor > 50 ? 20.0 : 10.0
        return Array(stride(from: -90, through: ceiling, by: step))
    }

    private var channelDomain: ClosedRange<Double> {
        let spans = readings.map(\.occupiedChannels)
        let low = min(1, spans.map(\.lowerBound).min() ?? 1)
        let high = max(13, spans.map(\.upperBound).max() ?? 13)
        return (low - 0.5) ... (high + 0.5)
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
            AxisMarks(values: Array(1...13)) { _ in
                AxisGridLine()
                AxisValueLabel()
            }
        }
        .chartYAxis {
            AxisMarks(position: .leading, values: signalMarks)
        }
    }
}
