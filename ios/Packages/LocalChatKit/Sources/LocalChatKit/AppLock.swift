import Foundation
import LocalAuthentication
import Observation

/// Проверка владельца телефона: Face ID, Touch ID или код-пароль.
public protocol Authenticating: Sendable {
    /// Можно ли вообще проверить владельца (настроен ли код-пароль на устройстве).
    func isAvailable() -> Bool
    func authenticate(reason: String) async -> Bool
    /// «Face ID», «Touch ID», «Optic ID» или «код-пароль».
    func methodName() -> String
}

public struct SystemAuthenticator: Authenticating {
    public init() {}

    public func isAvailable() -> Bool {
        LAContext().canEvaluatePolicy(.deviceOwnerAuthentication, error: nil)
    }

    /// Face ID с запасным вариантом — код-пароль устройства: иначе при сбое распознавания лица в приложение не войти.
    public func authenticate(reason: String) async -> Bool {
        let context = LAContext()
        return (try? await context.evaluatePolicy(.deviceOwnerAuthentication, localizedReason: reason)) ?? false
    }

    public func methodName() -> String {
        let context = LAContext()
        _ = context.canEvaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, error: nil)
        switch context.biometryType {
        case .faceID: return "Face ID"
        case .touchID: return "Touch ID"
        case .opticID: return "Optic ID"
        default: return String(localized: "passcode")
        }
    }
}

/// Блокировка приложения. Это защита от того, кто взял разблокированный телефон: данные на диске
/// шифруются отдельно, ключом из связки ключей, и от этой блокировки не зависят.
@MainActor
@Observable
public final class AppLock {
    public enum GracePeriod: Int, CaseIterable, Sendable {
        case immediately = 0
        case oneMinute = 60
        case fiveMinutes = 300

        public var title: String {
            switch self {
            case .immediately: String(localized: "Immediately")
            case .oneMinute: String(localized: "After a minute")
            case .fiveMinutes: String(localized: "After 5 minutes")
            }
        }
    }

    public private(set) var isEnabled: Bool
    public private(set) var isLocked: Bool
    public private(set) var isAuthenticating = false
    public var gracePeriod: GracePeriod {
        didSet { defaults.set(gracePeriod.rawValue, forKey: Keys.grace) }
    }
    /// Закрывать содержимое экраном-заглушкой в переключателе приложений.
    public var hideInSwitcher: Bool {
        didSet { defaults.set(hideInSwitcher, forKey: Keys.hide) }
    }

    private enum Keys {
        static let enabled = "lock.enabled"
        static let grace = "lock.grace"
        static let hide = "lock.hideInSwitcher"
    }

    private let authenticator: any Authenticating
    private let defaults: UserDefaults
    private let now: @Sendable () -> Date
    private var backgroundedAt: Date?

    public init(authenticator: any Authenticating = SystemAuthenticator(), defaults: UserDefaults = .standard,
                now: @escaping @Sendable () -> Date = { Date() }) {
        self.authenticator = authenticator
        self.defaults = defaults
        self.now = now
        let enabled = defaults.bool(forKey: Keys.enabled)
        isEnabled = enabled
        isLocked = enabled   // при холодном запуске включённая блокировка сразу закрывает приложение
        gracePeriod = GracePeriod(rawValue: defaults.integer(forKey: Keys.grace)) ?? .immediately
        hideInSwitcher = defaults.object(forKey: Keys.hide) as? Bool ?? true
    }

    public var methodName: String { authenticator.methodName() }
    public var canAuthenticate: Bool { authenticator.isAvailable() }

    // MARK: - Жизненный цикл

    public func appDidEnterBackground() {
        backgroundedAt = now()
    }

    public func appDidBecomeActive() {
        defer { backgroundedAt = nil }
        guard isEnabled, !isLocked, let backgroundedAt else { return }
        if now().timeIntervalSince(backgroundedAt) >= TimeInterval(gracePeriod.rawValue) { isLocked = true }
    }

    // MARK: - Действия

    public func unlock() async {
        guard isLocked, !isAuthenticating else { return }
        isAuthenticating = true
        defer { isAuthenticating = false }
        if await authenticator.authenticate(reason: String(localized: "Unlock your chats")) { isLocked = false }
    }

    /// Включает блокировку. Сначала проверяет владельца, чтобы не включить то, что потом не открыть.
    @discardableResult
    public func enable() async -> Bool {
        guard canAuthenticate, await authenticator.authenticate(reason: String(localized: "Turn on the app lock")) else { return false }
        isEnabled = true
        defaults.set(true, forKey: Keys.enabled)
        return true
    }

    /// Выключить блокировку может только владелец.
    @discardableResult
    public func disable() async -> Bool {
        guard await authenticator.authenticate(reason: String(localized: "Turn off the app lock")) else { return false }
        isEnabled = false
        isLocked = false
        defaults.set(false, forKey: Keys.enabled)
        return true
    }
}
