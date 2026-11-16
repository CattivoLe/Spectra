// swift-tools-version: 6.0
import PackageDescription

// On-device inference engine (llama.cpp) behind a small Swift API. Used by the Assistant tab.
let package = Package(
    name: "LocalChatEngine",
    platforms: [.iOS("18.0")],
    products: [
        .library(name: "LocalChatEngine", targets: ["LocalChatEngine"]),
    ],
    dependencies: [
        // Parses the chat templates stored inside model files.
        .package(url: "https://github.com/huggingface/swift-jinja.git", exact: "3.2.0"),
    ],
    targets: [
        // llama.cpp b12359 built by ios/Tools/build-llama.sh (device + simulator slices).
        // Not checked in: run the script once after cloning.
        .binaryTarget(name: "llama", path: "Vendor/llama.xcframework"),
        .target(
            name: "LocalChatEngine",
            dependencies: ["llama", .product(name: "Jinja", package: "swift-jinja")]
        ),
    ]
)
