import CoreLocation
import Observation

/// Collects the phone's path during a live (war-driving) session, so the map
/// can draw where you have walked. Points are kept in memory only; the saved
/// coverage comes from History as usual.
@Observable
final class TrackRecorder {
    private(set) var points: [CLLocationCoordinate2D] = []
    var isRecording = false

    /// Minimum move between kept points (~8 m), so a stationary phone does not
    /// pile up thousands of identical samples.
    private let minStepMetres = 8.0

    func clear() {
        points.removeAll()
    }

    /// Adds a fix while recording, skipping ones too close to the last to matter.
    func note(_ coordinate: CLLocationCoordinate2D) {
        guard isRecording else { return }
        if let last = points.last, Self.distance(last, coordinate) < minStepMetres {
            return
        }
        points.append(coordinate)
    }

    private static func distance(_ a: CLLocationCoordinate2D, _ b: CLLocationCoordinate2D) -> Double {
        CLLocation(latitude: a.latitude, longitude: a.longitude)
            .distance(from: CLLocation(latitude: b.latitude, longitude: b.longitude))
    }
}
