import Foundation
import Testing
@testable import Spectra

struct InternetAccessTests {
    @Test func theLightFollowsWhoAnswers() {
        #expect(AccessCheck.verdict(openReached: 5, listedReached: 5) == .full)
        #expect(AccessCheck.verdict(openReached: 2, listedReached: 0) == .full)
        // One foreign answer next to a working allow-list is a leak, not the open internet.
        #expect(AccessCheck.verdict(openReached: 1, listedReached: 4) == .whitelistOnly)
        #expect(AccessCheck.verdict(openReached: 0, listedReached: 1) == .whitelistOnly)
        #expect(AccessCheck.verdict(openReached: 1, listedReached: 0) == .full)
        #expect(AccessCheck.verdict(openReached: 0, listedReached: 0) == .none)
    }

    @Test func blockedServicesTurnTheLightYellow() {
        // Google and GitHub open, YouTube and Instagram do not: filtered, not full.
        #expect(AccessCheck.verdict(openReached: 5, listedReached: 5, blockedFailed: 4) == .filtered)
        // One failing service can be that site having a bad minute.
        #expect(AccessCheck.verdict(openReached: 5, listedReached: 5, blockedFailed: 1) == .full)
        // An allow-list is still told apart from selective blocking.
        #expect(AccessCheck.verdict(openReached: 0, listedReached: 3, blockedFailed: 6) == .whitelistOnly)
        let open = AccessCheck.openHosts.map { ResourceReach(host: $0, ok: true, millis: 30) }
        let blocked = AccessCheck.blockedHosts.map { ResourceReach(host: $0, ok: $0 == "www.linkedin.com", millis: nil) }
        let report = AccessReport(open: open, listed: [], blocked: blocked)
        #expect(report.verdict == .filtered)
        #expect(report.unreachable.count == AccessCheck.blockedHosts.count - 1)
        // Checks saved before blocked services were asked keep their verdict.
        #expect(AccessReport(open: open, listed: []).verdict == .full)
        let old = try? JSONDecoder().decode(AccessReport.self, from: Data(#"{"open":[],"listed":[]}"#.utf8))
        #expect(old?.blocked == nil)
    }

    @Test func aReportJudgesItsOwnSites() {
        let down = AccessCheck.openHosts.map { ResourceReach(host: $0, ok: false, millis: nil) }
        let up = AccessCheck.listedHosts.map { ResourceReach(host: $0, ok: true, millis: 40) }
        #expect(AccessReport(open: down, listed: up).verdict == .whitelistOnly)
        #expect(AccessReport(open: down, listed: []).verdict == InternetAccess.none)
    }

    @Test func typedSitesAreReducedToAHost() {
        #expect(AccessCheck.normalize("YouTube.com") == "youtube.com")
        #expect(AccessCheck.normalize(" https://www.youtube.com/watch?v=1 ") == "www.youtube.com")
        #expect(AccessCheck.normalize("user@example.org:8443/path") == "example.org")
        #expect(AccessCheck.normalize("example.com.") == "example.com")
        #expect(AccessCheck.normalize("localhost") == nil)
        #expect(AccessCheck.normalize("two words.com") == nil)
        #expect(AccessCheck.normalize("") == nil)
    }

    @Test func theListIsKeptAndHasNoDuplicates() throws {
        let suite = "resources-\(UUID().uuidString)"
        let defaults = try #require(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        let watch = ResourceWatch(defaults: defaults)
        #expect(watch.hosts == ResourceWatch.defaults)
        #expect(watch.add("https://Example.org/page"))
        #expect(!watch.add("example.org"))
        #expect(!watch.add("nonsense"))
        watch.remove("youtube.com")
        let reloaded = ResourceWatch(defaults: defaults)
        #expect(reloaded.hosts.last == "example.org")
        #expect(!reloaded.hosts.contains("youtube.com"))
    }

    @Test func theRouteReadsBothSwitches() throws {
        let suite = "route-\(UUID().uuidString)"
        let defaults = try #require(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        #expect(ProbeRoute.current(defaults) == .wifi)
        defaults.set(true, forKey: ProbeRoute.cellularKey)
        #expect(ProbeRoute.current(defaults) == .cellular)
        defaults.set(true, forKey: ProbeRoute.vpnKey)
        #expect(ProbeRoute.current(defaults) == .vpn)
    }
}
