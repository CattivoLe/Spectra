import Foundation
import Testing
@testable import Spectra

struct OUIRegistryTests {
    @Test func curatedNamesStillWin() {
        #expect(OUIDatabase.lookup("B8:27:EB") == "Raspberry Pi")
        #expect(OUIDatabase.lookup("3C:22:FB") == "Apple")
    }

    @Test func theFullRegistryIsThere() {
        // Katun Corporation: an MA-L block no curated list would carry.
        #expect(OUIDatabase.lookup("38:E2:CA") == "Katun")
        #expect(MACAddress(string: "38:E2:CA:00:11:22")?.manufacturer == "Katun")
    }

    @Test func smallerBlocksBeatTheRegistrationAuthority() {
        // 70:B3:D5 belongs to the IEEE Registration Authority; its 36-bit
        // sub-blocks belong to the companies that bought them.
        let mac = try? #require(MACAddress(string: "70:B3:D5:0A:B1:23"))
        #expect(mac?.manufacturer == "KST technology")
        #expect(OUIDatabase.lookup("70:B3:D5") == nil)
    }

    @Test func legalSuffixesAreStripped() {
        let names = ["38:E2:CA", "28:6F:B9"].compactMap(OUIDatabase.lookup)
        #expect(!names.isEmpty)
        for name in names {
            #expect(!name.lowercased().hasSuffix("ltd."))
            #expect(!name.lowercased().hasSuffix("corporation"))
        }
    }
}
