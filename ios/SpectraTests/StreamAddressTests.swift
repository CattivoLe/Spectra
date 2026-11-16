import Testing
@testable import Spectra

struct StreamAddressTests {
    @Test func camerasGetTheirMakersStreamPath() {
        #expect(StreamAddress.url(ip: "192.168.1.60", port: 554, speaksRTSP: false, maker: "Hikvision") == "rtsp://192.168.1.60/Streaming/Channels/101")
        #expect(StreamAddress.url(ip: "192.168.1.61", port: 8554, speaksRTSP: false, maker: "Dahua Technology") == "rtsp://192.168.1.61:8554/cam/realmonitor?channel=1&subtype=0")
        #expect(StreamAddress.url(ip: "192.168.1.62", port: 554, speaksRTSP: false, maker: "TP-LINK TECHNOLOGIES") == "rtsp://192.168.1.62/stream1")
        #expect(StreamAddress.url(ip: "192.168.1.63", port: 554, speaksRTSP: false, maker: nil) == "rtsp://192.168.1.63/")
        // A port that answered RTSP on an unusual number still opens as a stream.
        #expect(StreamAddress.url(ip: "192.168.1.64", port: 10554, speaksRTSP: true, maker: nil) == "rtsp://192.168.1.64:10554/")
        #expect(StreamAddress.url(ip: "192.168.1.65", port: 1935, speaksRTSP: false, maker: nil) == "rtmp://192.168.1.65:1935/")
        #expect(StreamAddress.url(ip: "fe80::1", port: 554, speaksRTSP: false, maker: nil) == "rtsp://[fe80::1]/")
        #expect(StreamAddress.url(ip: "192.168.1.66", port: 80, speaksRTSP: false, maker: "Hikvision") == nil)
    }
}
