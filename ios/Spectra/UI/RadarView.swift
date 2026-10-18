import SwiftUI

/// Walk-toward-it finder for a Bluetooth device: a ring that fills as the
/// signal strengthens, with a "warmer / colder" read from the recent trend.
/// Useful for locating a hidden tracker. The device keeps advertising, so the
/// view updates live as long as it stays in range.
struct RadarView: View {
    var device: BLEDevice
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        let rssi = device.rssi
        let nearby = device.isNearby(at: .now)
        let level = Proximity.level(rssi: rssi)
        let closeness = Proximity.closeness(rssi: rssi)
        let trend = Proximity.trend(samples: device.samples.suffix(12).map(\.rssi))
        let color = SignalQuality(rssi: rssi).color

        NavigationStack {
            VStack(spacing: 28) {
                VStack(spacing: 4) {
                    Image(systemName: device.systemImage)
                        .font(.title)
                        .foregroundStyle(.secondary)
                    Text(device.displayName)
                        .font(.title3.weight(.semibold))
                        .multilineTextAlignment(.center)
                }

                ZStack {
                    Circle()
                        .stroke(.quaternary, lineWidth: 20)
                    Circle()
                        .trim(from: 0, to: nearby ? closeness : 0)
                        .stroke(color, style: StrokeStyle(lineWidth: 20, lineCap: .round))
                        .rotationEffect(.degrees(-90))
                        .animation(.easeOut(duration: 0.4), value: closeness)
                    VStack(spacing: 2) {
                        Text("\(rssi)")
                            .font(.system(size: 54, weight: .bold, design: .rounded).monospacedDigit())
                            .contentTransition(.numericText())
                        Text("dBm").font(.headline).foregroundStyle(.secondary)
                    }
                }
                .frame(width: 240, height: 240)

                if nearby {
                    Text(level.label)
                        .font(.title2.weight(.semibold))
                        .foregroundStyle(color)
                    Label(trend.label, systemImage: trendSymbol(trend))
                        .font(.headline)
                        .foregroundStyle(trendColor(trend))
                        .contentTransition(.symbolEffect(.replace))
                } else {
                    Text("Signal lost — it may be out of range.")
                        .font(.headline)
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                }

                Text("Move around. The ring fills and reads “Warmer” as you get closer.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 32)
            }
            .padding()
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .navigationTitle("Find")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                }
            }
            .sensoryFeedback(.impact(weight: .light), trigger: level)
        }
    }

    private func trendSymbol(_ trend: Proximity.Trend) -> String {
        switch trend {
        case .warmer: "flame.fill"
        case .colder: "snowflake"
        case .steady: "equal"
        }
    }

    private func trendColor(_ trend: Proximity.Trend) -> Color {
        switch trend {
        case .warmer: .orange
        case .colder: .blue
        case .steady: .secondary
        }
    }
}
