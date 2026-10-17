import CoreLocation
import Observation

/// Keeps a recent coarse fix while the app is in use, so a save made on the
/// phone can be tagged with where it happened. A Cap LoRa-1262 fix travels
/// with the Cardputer's own saved scans and is not read from here.
@Observable
final class LocationService: NSObject, CLLocationManagerDelegate {
    struct Coordinate: Equatable {
        var latitude: Double
        var longitude: Double
    }

    private(set) var coordinate: Coordinate?
    private(set) var authorization: CLAuthorizationStatus
    private let manager = CLLocationManager()

    override init() {
        authorization = manager.authorizationStatus
        super.init()
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyHundredMeters
    }

    var isAuthorized: Bool {
        authorization == .authorizedWhenInUse || authorization == .authorizedAlways
    }

    /// Ask for permission the first time, and keep a fix flowing once granted.
    func start() {
        switch manager.authorizationStatus {
        case .notDetermined:
            manager.requestWhenInUseAuthorization()
        case .authorizedWhenInUse, .authorizedAlways:
            manager.startUpdatingLocation()
        default:
            break
        }
    }

    func stop() {
        manager.stopUpdatingLocation()
    }

    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        authorization = manager.authorizationStatus
        if isAuthorized {
            manager.startUpdatingLocation()
        } else {
            coordinate = nil
        }
    }

    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let location = locations.last else { return }
        coordinate = Coordinate(
            latitude: location.coordinate.latitude,
            longitude: location.coordinate.longitude
        )
    }

    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {}
}
