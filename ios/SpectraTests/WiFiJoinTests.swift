import Foundation
import Testing
@testable import Spectra

struct WiFiJoinTests {
    @Test func openAndOWENeedNoPassword() {
        #expect(AuthMode.open.joinMethod == .open)
        #expect(AuthMode.owe.joinMethod == .open)
    }

    @Test func wepIsItsOwnMethod() {
        #expect(AuthMode.wep.joinMethod == .wep)
    }

    @Test func pskCoversWPAFamilies() {
        #expect(AuthMode.wpaPSK.joinMethod == .psk)
        #expect(AuthMode.wpa2PSK.joinMethod == .psk)
        #expect(AuthMode.wpaWPA2PSK.joinMethod == .psk)
        #expect(AuthMode.wpa3PSK.joinMethod == .psk)
        #expect(AuthMode.wpa2WPA3PSK.joinMethod == .psk)
    }

    @Test func enterpriseAndUnknownAreUnsupported() {
        #expect(AuthMode.wpa2Enterprise.joinMethod == .unsupported)
        // WAPI (8), DPP (13) and anything else iOS will not take from a passphrase.
        #expect(AuthMode(rawValue: 8).joinMethod == .unsupported)
        #expect(AuthMode(rawValue: 13).joinMethod == .unsupported)
        #expect(AuthMode(rawValue: 99).joinMethod == .unsupported)
    }
}
