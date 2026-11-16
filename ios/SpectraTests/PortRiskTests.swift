import Foundation
import Testing
@testable import Spectra

struct PortRiskTests {
    private func host(_ ports: [Int], scanned: Bool = true, gateway: Bool = false, profile: LANProfile = LANProfile()) -> LANDevice {
        LANDevice(
            ipAddress: "192.168.1.50",
            openPorts: ports.map { DiscoveredPort(number: $0, service: DiscoveredPort.serviceName(for: $0)) },
            portsScanned: scanned,
            isGateway: gateway,
            profile: profile
        )
    }

    @Test func unscannedHostHasNoVerdict() {
        let device = host([23], scanned: false)
        #expect(device.riskFindings.isEmpty)
        #expect(device.riskLevel == nil)
        #expect(!device.needsAttention)
    }

    @Test func benignPortsAreNotFindings() {
        let device = host([443, 8443, 7000, 62078])
        #expect(device.riskFindings.isEmpty)
        #expect(device.riskLevel == nil)
        #expect(PortRisk.level(forPort: 443) == nil)
    }

    @Test func telnetIsHighAndSortsFirst() {
        let device = host([80, 22, 23])
        let findings = device.riskFindings
        #expect(findings.map(\.level) == [.high, .low, .info])
        #expect(findings.first?.ports == [23])
        #expect(device.riskLevel == .high)
        #expect(device.needsAttention)
    }

    @Test func sshAloneIsOnlyANote() {
        let device = host([22])
        #expect(device.riskLevel == .info)
        #expect(!device.needsAttention)
    }

    @Test func smbPortsFoldIntoOneFinding() {
        let device = host([139, 445])
        let findings = device.riskFindings
        #expect(findings.count == 1)
        #expect(findings[0].ports == [139, 445])
        #expect(findings[0].level == .medium)
    }

    @Test func guestSharesOutrankPlainSMB() {
        let windows = WindowsHost(computerName: "OFFICE-PC", domain: nil, shares: ["Users", "Public"], guestSession: true)
        let device = host([445], profile: LANProfile(windows: windows))
        let findings = device.riskFindings
        #expect(findings.count == 1)
        #expect(findings[0].level == .high)
        #expect(findings[0].detail.contains("Users"))
        #expect(findings[0].ports == [445])

        // A guest session that found no shares is the ordinary SMB note.
        let quiet = WindowsHost(computerName: "OFFICE-PC", domain: nil, shares: [], guestSession: true)
        #expect(host([445], profile: LANProfile(windows: quiet)).riskLevel == .medium)
    }

    @Test func gatewayWebPageIsCalledTheRouterAdminPage() {
        let router = host([80, 443], gateway: true)
        let findings = router.riskFindings
        #expect(findings.count == 1)
        #expect(findings[0].level == .low)
        #expect(findings[0].title != host([80]).riskFindings[0].title)
    }

    @Test func savedHostsAreAssessedTheSameWay() {
        let live = host([3389, 5900])
        let back = SavedLAN(live).device()
        #expect(back.riskFindings == live.riskFindings)
        #expect(back.riskLevel == .medium)
    }
}
