// swift-tools-version: 6.0
import PackageDescription

// Chat storage (encrypted SQLite), model downloads, web search and voice for the Assistant tab.
let package = Package(
    name: "LocalChatKit",
    platforms: [.iOS("18.0")],
    products: [
        .library(name: "LocalChatKit", targets: ["LocalChatKit"]),
    ],
    dependencies: [
        .package(path: "../LocalChatEngine"),
        // GRDB 7.12.0 built with SQLCipher (database encryption), see ../GRDBSQLCipher/LOCALCHAT-NOTES.md.
        .package(path: "../GRDBSQLCipher"),
    ],
    targets: [
        .target(
            name: "LocalChatKit",
            dependencies: [
                .product(name: "LocalChatEngine", package: "LocalChatEngine"),
                .product(name: "GRDB", package: "GRDBSQLCipher"),
            ]
        ),
    ]
)
