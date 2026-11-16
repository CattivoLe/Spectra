import Foundation
import Security

/// Откуда берётся ключ шифрования базы. Ключ — 32 случайных байта, а не пароль: подбирать его бессмысленно.
public protocol DatabaseKeyProvider: Sendable {
    func key() throws -> Data
}

/// Тестовый ключ.
public struct FixedKeyProvider: DatabaseKeyProvider {
    public let value: Data
    public init(_ value: Data) { self.value = value }
    public func key() throws -> Data { value }
}

public enum KeychainError: LocalizedError {
    case status(OSStatus)
    case badKey

    public var errorDescription: String? {
        switch self {
        case .status(let code): String(localized: "Keychain returned error \(code).")
        case .badKey: String(localized: "The encryption key in the keychain is damaged.")
        }
    }
}

/// Хранит ключ в связке ключей iOS. Ключ создаётся при первом запуске и остаётся только на этом устройстве:
/// он не синхронизируется с iCloud Keychain и не попадает в резервные копии, поэтому и расшифровать базу
/// где-то ещё нельзя. Доступен после первой разблокировки телефона после перезагрузки.
public struct KeychainKeyProvider: DatabaseKeyProvider {
    public static let keyLength = 32
    private let service: String
    private let account: String

    public init(service: String = "cattivole.Spectra.assistant", account: String = "database-key") {
        self.service = service
        self.account = account
    }

    public func key() throws -> Data {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        var result: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        switch status {
        case errSecSuccess:
            guard let data = result as? Data, data.count == Self.keyLength else { throw KeychainError.badKey }
            return data
        case errSecItemNotFound:
            return try create()
        default:
            throw KeychainError.status(status)
        }
    }

    /// Удаляет ключ. После этого прежняя база нечитаема: так «стирание всего» работает даже если файл останется на диске.
    public func deleteKey() {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
        SecItemDelete(query as CFDictionary)
    }

    private func create() throws -> Data {
        var bytes = [UInt8](repeating: 0, count: Self.keyLength)
        guard SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes) == errSecSuccess else { throw KeychainError.badKey }
        let data = Data(bytes)
        let attributes: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecValueData as String: data,
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
            kSecAttrSynchronizable as String: false,
        ]
        let status = SecItemAdd(attributes as CFDictionary, nil)
        guard status == errSecSuccess else { throw KeychainError.status(status) }
        return data
    }
}
