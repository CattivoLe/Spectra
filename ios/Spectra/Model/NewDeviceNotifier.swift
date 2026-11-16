import Foundation
import UserNotifications

/// Tells the user that a scan found hosts the network had never shown, even
/// when the app has gone to the background while the scan ran.
enum NewDeviceNotifier {
    /// Asks for permission the first time it is needed and posts one notice
    /// for the whole batch. Nothing is sent when the user declined.
    static func notify(_ devices: [LANDevice], networkLabel: String?) async {
        guard !devices.isEmpty else { return }
        let center = UNUserNotificationCenter.current()
        let settings = await center.notificationSettings()
        switch settings.authorizationStatus {
        case .notDetermined:
            guard (try? await center.requestAuthorization(options: [.alert, .sound])) == true else { return }
        case .denied:
            return
        default:
            break
        }

        let content = UNMutableNotificationContent()
        content.title = devices.count == 1
            ? String(localized: "New device on your network")
            : String(localized: "\(devices.count) new devices on your network")
        content.body = devices.prefix(4).map(line).joined(separator: "\n")
        if devices.count > 4 {
            content.body += "\n" + String(localized: "and \(devices.count - 4) more")
        }
        content.sound = .default
        content.threadIdentifier = "new-devices"
        if let networkLabel { content.subtitle = networkLabel }

        let request = UNNotificationRequest(identifier: "new-devices-\(Date.now.timeIntervalSince1970)", content: content, trigger: nil)
        try? await center.add(request)
    }

    private static func line(_ device: LANDevice) -> String {
        device.displayName == device.ipAddress ? device.ipAddress : "\(device.displayName) · \(device.ipAddress)"
    }

    /// Shows the banner while the app is in front too; the user may be on
    /// another tab, or the scan may have run on from the Network tab.
    static let presenter = Presenter()

    final class Presenter: NSObject, UNUserNotificationCenterDelegate {
        nonisolated func userNotificationCenter(
            _ center: UNUserNotificationCenter,
            willPresent notification: UNNotification
        ) async -> UNNotificationPresentationOptions {
            [.banner, .list]
        }
    }
}
