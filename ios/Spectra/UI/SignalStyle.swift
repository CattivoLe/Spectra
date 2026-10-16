import SwiftUI

/// How a signal level reads to a person, and the colour it is drawn in.
enum SignalQuality {
    case excellent, good, fair, weak, poor

    init(rssi: Int) {
        switch rssi {
        case (-55)...: self = .excellent
        case -65 ..< -55: self = .good
        case -73 ..< -65: self = .fair
        case -82 ..< -73: self = .weak
        default: self = .poor
        }
    }

    var label: LocalizedStringKey {
        switch self {
        case .excellent: "Excellent"
        case .good: "Good"
        case .fair: "Fair"
        case .weak: "Weak"
        case .poor: "Poor"
        }
    }

    var color: Color {
        switch self {
        case .excellent: .green
        case .good: .mint
        case .fair: .yellow
        case .weak: .orange
        case .poor: .red
        }
    }

    /// Fill for the Wi-Fi glyph's variable value.
    static func fraction(rssi: Int) -> Double {
        min(max(Double(rssi + 95) / 50, 0.05), 1)
    }
}

/// The Wi-Fi glyph, filled to the signal level.
struct SignalGlyph: View {
    var rssi: Int
    var inRange = true

    var body: some View {
        Image(systemName: "wifi", variableValue: inRange ? SignalQuality.fraction(rssi: rssi) : 0)
            .foregroundStyle(inRange ? SignalQuality(rssi: rssi).color : .secondary)
            .symbolRenderingMode(.hierarchical)
    }
}

/// A small line of recent signal samples.
struct Sparkline: View {
    var values: [Int]
    var color: Color

    var body: some View {
        SparklineShape(values: values)
            .stroke(color, style: StrokeStyle(lineWidth: 1.5, lineCap: .round, lineJoin: .round))
    }
}

private struct SparklineShape: Shape {
    var values: [Int]

    func path(in rect: CGRect) -> Path {
        var path = Path()
        guard values.count > 1 else { return path }
        // Scaled to the values themselves, with at least a 12 dB window so
        // a steady signal stays a calm line instead of amplified noise.
        let lowest = Double(values.min()!), highest = Double(values.max()!)
        let span = max(highest - lowest, 12)
        let low = (lowest + highest - span) / 2, high = low + span
        let step = rect.width / CGFloat(values.count - 1)
        for (index, value) in values.enumerated() {
            let clamped = min(max(Double(value), low), high)
            let point = CGPoint(
                x: rect.minX + CGFloat(index) * step,
                y: rect.minY + rect.height * (1 - (clamped - low) / (high - low))
            )
            if index == 0 { path.move(to: point) } else { path.addLine(to: point) }
        }
        return path
    }
}

extension AccessPointReading {
    var displayName: String { ssid ?? String(localized: "Hidden network") }

    var securitySymbol: String {
        switch auth.strength {
        case .none: "lock.open.fill"
        case .weak: "lock.trianglebadge.exclamationmark.fill"
        case .fair, .strong: "lock.fill"
        }
    }

    var securityColor: Color {
        switch auth.strength {
        case .none: .orange
        case .weak: .red
        case .fair: .secondary
        case .strong: .green
        }
    }
}

/// Frequencies in the reader's own notation ("2,4 ГГц" in Russian).
nonisolated enum Units {
    static func megahertz(_ value: Int) -> String {
        Measurement(value: Double(value), unit: UnitFrequency.megahertz)
            .formatted(.measurement(width: .abbreviated, usage: .asProvided, numberFormatStyle: .number.grouping(.never)))
    }

    static func gigahertz(_ value: Double) -> String {
        Measurement(value: value, unit: UnitFrequency.gigahertz)
            .formatted(.measurement(width: .abbreviated, usage: .asProvided, numberFormatStyle: .number.precision(.fractionLength(0...1))))
    }
}
