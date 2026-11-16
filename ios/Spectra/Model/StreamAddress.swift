import Foundation

/// Where a camera's video stream lives, for a player like VLC. Mirrors the
/// Android `StreamAddress`; keep the paths in step.
nonisolated enum StreamAddress {
    static let rtspPorts: Set<Int> = [554, 8554]
    static let rtmpPort = 1935

    /// The stream path a maker's cameras use by default. A bare address shows
    /// nothing on most cameras; the player asks for the password itself.
    private static let pathsByMaker: [(names: [String], path: String)] = [
        (["hikvision", "ezviz", "hiwatch"], "/Streaming/Channels/101"),
        (["dahua", "amcrest", "imou", "lorex"], "/cam/realmonitor?channel=1&subtype=0"),
        (["reolink"], "/h264Preview_01_main"),
        (["tp-link", "tapo"], "/stream1"),
        (["uniview", "unv"], "/media/video1"),
        (["axis"], "/axis-media/media.amp"),
        (["foscam"], "/videoMain"),
    ]

    static func rtspPath(maker: String?) -> String {
        guard let folded = maker?.lowercased() else { return "/" }
        return pathsByMaker.first { entry in entry.names.contains { folded.contains($0) } }?.path ?? "/"
    }

    /// The player address of `port`, or nil when the port carries no stream.
    /// `speaksRTSP` is true when the port answered an RTSP probe, whatever its number.
    static func url(ip: String, port: Int, speaksRTSP: Bool, maker: String?) -> String? {
        let host = ip.contains(":") ? "[\(ip)]" : ip
        if speaksRTSP || rtspPorts.contains(port) {
            let portPart = port == 554 ? "" : ":\(port)"
            return "rtsp://\(host)\(portPart)\(rtspPath(maker: maker))"
        }
        if port == rtmpPort { return "rtmp://\(host):\(port)/" }
        return nil
    }
}

extension LANDevice {
    /// The player address of `port` when it carries a camera stream; nil otherwise.
    func streamAddress(_ port: Int) -> String? {
        let maker = [identity.manufacturer, manufacturer, profile.upnp?.manufacturer,
                     profile.rtsp.first { $0.port == port }?.server]
            .compactMap { $0 }.joined(separator: " ")
        return StreamAddress.url(
            ip: ipAddress, port: port,
            speaksRTSP: profile.rtsp.contains { $0.port == port },
            maker: maker.isEmpty ? nil : maker
        )
    }
}
