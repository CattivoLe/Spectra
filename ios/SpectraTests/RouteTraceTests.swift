import Foundation
import Testing
@testable import Spectra

struct RouteTraceTests {
    @Test func targetsAreReadFromWhatWasTyped() {
        #expect(RouteTrace.target(from: " Google.com ") == "google.com")
        #expect(RouteTrace.target(from: "https://ya.ru/search?q=1") == "ya.ru")
        #expect(RouteTrace.target(from: "1.1.1.1") == "1.1.1.1")
        #expect(RouteTrace.target(from: "example.com:8443") == "example.com")
        #expect(RouteTrace.target(from: "2606:4700:4700::1111") == "2606:4700:4700::1111")
        #expect(RouteTrace.target(from: "[2606:4700:4700::1111]:443") == "2606:4700:4700::1111")
        #expect(RouteTrace.target(from: "two words") == nil)
        #expect(RouteTrace.target(from: "") == nil)
    }

    @Test func onlyPublicAddressesArePlaced() {
        #expect(RouteTrace.isPublic("8.8.8.8"))
        #expect(!RouteTrace.isPublic("192.168.1.1"))
        #expect(!RouteTrace.isPublic("10.20.0.1"))
        #expect(!RouteTrace.isPublic("172.20.0.1"))
        #expect(RouteTrace.isPublic("172.32.0.1"))
        #expect(!RouteTrace.isPublic("100.64.0.1"))
        #expect(RouteTrace.isPublic("2a00:1450::1"))
        #expect(!RouteTrace.isPublic("fe80::1"))
    }

    @Test func ipwhoRepliesBecomePlacesAndAPath() throws {
        let json = #"{"ip":"8.8.8.8","success":true,"latitude":37.386,"longitude":-122.0838,"city":"Mountain View","country_code":"US","connection":{"isp":"Google LLC"}}"#
        let place = try #require(RouteTrace.place(Data(json.utf8)))
        #expect(place.city == "Mountain View")
        #expect(place.isp == "Google LLC")
        #expect(RouteTrace.place(Data(#"{"success":false}"#.utf8)) == nil)
        let near = HopPlace(latitude: 37.3861, longitude: -122.0839)
        let far = HopPlace(latitude: 52.37, longitude: 4.89)
        let hops = [TraceHop(address: "192.168.1.1", millis: 2), TraceHop(address: "8.8.4.4", millis: 9),
                    TraceHop(address: nil, millis: nil), TraceHop(address: "8.8.8.8", millis: 10), TraceHop(address: "9.9.9.9", millis: 40)]
        // Hop 4 is in the same spot as hop 2 and folds into it.
        let path = RouteTrace.path(hops, places: ["8.8.4.4": place, "8.8.8.8": near, "9.9.9.9": far])
        #expect(path.map(\.ttl) == [2, 5])
    }
}
