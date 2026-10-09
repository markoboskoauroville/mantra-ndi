// swift-tools-version:5.9
// MANTRA REMOTE for macOS (v136): the camera's remote control on the Mac, over Mantra Link (shared/kotlin/.../Link.kt).
import PackageDescription

let package = Package(
    name: "MantraRemote",
    platforms: [.macOS(.v13)],
    targets: [
        .executableTarget(name: "MantraRemote", path: "Sources/MantraRemote"),
        .testTarget(name: "MantraRemoteTests", dependencies: ["MantraRemote"], path: "Tests/MantraRemoteTests")
    ]
)
