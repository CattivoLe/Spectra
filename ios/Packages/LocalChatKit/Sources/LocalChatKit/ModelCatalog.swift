import Foundation

/// Модель из каталога. Каталог лежит в обычном JSON, поэтому список обновляется без правки кода.
public struct CatalogModel: Codable, Identifiable, Hashable, Sendable {
    public var id: String
    public var name: String
    /// A name short enough for one line in a menu (about 16 characters), when `name` is longer than that.
    public var shortName: String?
    public var summary: String
    public var fileName: String
    public var url: URL
    public var sizeBytes: Int64
    /// Контрольная сумма SHA-256 файла, строчными шестнадцатеричными цифрами.
    public var sha256: String
    public var licenseName: String
    public var licenseURL: URL
    public var recommended: Bool
    /// Важное предупреждение, которое показывается перед загрузкой (например, про отключённые ограничения).
    public var notice: String?
    /// "embedding": a helper model for search by meaning that never takes part in a conversation. Empty: a chat model.
    public var kind: String?

    public var isEmbedding: Bool { kind == "embedding" }
}

/// Насколько модель подходит устройству.
public enum ModelFit: Equatable, Sendable {
    /// Запас памяти большой.
    case comfortable
    /// Запустится, но телефону будет тесно: возможны вылеты при других открытых приложениях.
    case tight
    /// Не запустится: файл не помещается в память.
    case tooBig
}

public enum ModelCatalog {
    public static func decode(_ data: Data) throws -> [CatalogModel] {
        struct File: Decodable { var models: [CatalogModel] }
        return try JSONDecoder().decode(File.self, from: data).models
    }

    /// Оценка по размеру файла и памяти устройства.
    /// Веса читаются с диска и в «личный» лимит приложения почти не входят (на iPhone 16 с 8 ГБ модель в 5 ГБ работала),
    /// но целиком поместиться в оперативную память всё равно должны. Границы — оценка по замерам, а не гарантия.
    public static func fit(fileSizeBytes: Int64, physicalMemoryBytes: UInt64) -> ModelFit {
        let size = Double(fileSizeBytes)
        let memory = Double(physicalMemoryBytes)
        if size <= memory * 0.5 { return .comfortable }
        if size <= memory * 0.65 { return .tight }
        return .tooBig
    }
}
