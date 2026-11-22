import Foundation
import Network
import NetworkExtension
import Testing
@testable import Spectra

struct LocalNetworkTests {
    /// Only an open network is insecure: `fetchCurrent` leaves `isSecure` false
    /// for every network, which made a protected Wi-Fi look open.
    @Test func securityTypeDecidesWhetherWiFiIsOpen() {
        #expect(WiFiInfo.isSecure(.open) == false)
        #expect(WiFiInfo.isSecure(.WEP))
        #expect(WiFiInfo.isSecure(.personal))
        #expect(WiFiInfo.isSecure(.enterprise))
        #expect(WiFiInfo.isSecure(.unknown), "no warning without proof")
    }

    @Test func policyErrorMeansAccessWasRefused() {
        #expect(LocalNetworkAccess.verdict(for: .dns(LocalNetworkAccess.policyDenied)) == .denied)
        #expect(LocalNetworkAccess.verdict(for: .dns(DNSServiceErrorType(-65569))) == nil)
        #expect(LocalNetworkAccess.verdict(for: .posix(.ENETDOWN)) == nil)
    }

    @Test func checkEndsOnCancel() async {
        let task = Task { await LocalNetworkAccess.check(timeout: .seconds(30)) }
        task.cancel()
        let result = await task.value
        #expect(result != .denied)
    }

    @Test func probeServiceIsDeclared() throws {
        let services = try #require(Bundle.main.object(forInfoDictionaryKey: "NSBonjourServices") as? [String])
        #expect(services.contains(LocalNetworkAccess.serviceType))
    }
}
