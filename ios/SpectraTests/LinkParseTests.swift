import Foundation
import Testing
@testable import Spectra

struct LinkParseTests {
    @Test func cloudflareTraceKeepsThePublicAddress() {
        let text = """
        fl=abc
        ip=203.0.113.8
        loc=DE
        colo=FRA
        warp=on
        """
        let address = LinkParse.external(fromTrace: text)
        #expect(address?.ip == "203.0.113.8")
        #expect(address?.countryCode == "DE")
        #expect(address?.colo == "FRA")
        #expect(address?.warp == true)
        #expect(LinkParse.external(fromTrace: "colo=FRA") == nil)
    }

    @Test func whoisFillsTheProviderWithoutDroppingTheEdge() throws {
        let trace = try #require(LinkParse.external(fromTrace: "ip=203.0.113.8\nloc=DE\ncolo=FRA\n"))
        let merged = LinkParse.mergeWhois(Data("""
        {"success":true,"country_code":"DE","city":"Berlin","connection":{"asn":3320,"isp":"Deutsche Telekom","org":"DTAG"}}
        """.utf8), into: trace)
        #expect(merged.city == "Berlin")
        #expect(merged.asn == "AS3320")
        #expect(merged.isp == "Deutsche Telekom")
        #expect(merged.colo == "FRA")
        #expect(LinkParse.mergeWhois(Data(#"{"success":false}"#.utf8), into: trace) == trace)
    }

    @Test func checkHostStartReadsNodesAndTheRateLimit() {
        let started = LinkParse.checkStart(Data("""
        {"ok":1,"request_id":"abc","nodes":{"de1.node.check-host.net":["de","Germany","Falkenstein","1.2.3.4","AS24940"]}}
        """.utf8))
        guard case .ok(let id, let nodes) = started else {
            Issue.record("expected a started check")
            return
        }
        #expect(id == "abc")
        #expect(nodes == [CheckNode(id: "de1.node.check-host.net", code: "de", city: "Falkenstein")])
        #expect(LinkParse.checkStart(Data(#"{"error":"limit_exceeded"}"#.utf8)) == .busy)
        #expect(LinkParse.checkStart(Data("not json".utf8)) == .failed)
    }

    @Test func abroadPingTreatsSecondsAndPendingNodes() throws {
        let nodes = [
            CheckNode(id: "us1", code: "us", city: "Los Angeles"),
            CheckNode(id: "ch1", code: "ch", city: "Zurich"),
            CheckNode(id: "pt1", code: "pt", city: "Viana"),
        ]
        let parsed = try #require(LinkParse.abroad(Data("""
        {"us1":[[["OK",0.044,"203.0.113.8"],["TIMEOUT",3.005],["OK",0.030]]],
         "ch1":[[null]],
         "pt1":null}
        """.utf8), nodes: nodes))
        #expect(!parsed.complete)
        #expect(parsed.views.count == 2)
        #expect(parsed.views[0].millis == 30)
        #expect(parsed.views[0].ok)
        #expect(!parsed.views[1].ok)
        #expect(parsed.views[1].city == "Zurich")
    }

    @Test func tracerouteTimesAreMilliseconds() throws {
        let nodes = [CheckNode(id: "jp1", code: "jp", city: "Tokyo")]
        let json = Data("""
        {"jp1":[[[{"host":"172.16.0.1","query_times":["0.50","0.39"]}],[{"query_times":[null]},{"host":"103.214.168.255","query_times":["1.85"]}],[{"host":"1.1.1.1","query_times":["1.12","0.96"]}]]]}
        """.utf8)
        let parsed = try #require(LinkParse.traces(json, nodes: nodes, target: "1.1.1.1"))
        #expect(parsed.complete)
        let trace = try #require(parsed.traces.first)
        #expect(trace.reached)
        #expect(trace.hops[0].address == "172.16.0.1")
        #expect(trace.hops[0].millis == 1)
        #expect(trace.hops[1].address == "103.214.168.255")
        #expect(trace.hops[1].millis == 2)
        #expect(LinkParse.compactPath(trace.hops) == "172.16.0.1 → 103.214.168.255 → 1.1.1.1")
    }

    @Test func compactPathCollapsesSilentHops() {
        let hops = [
            TraceHop(address: "10.0.0.1", millis: 1),
            TraceHop(address: nil, millis: nil),
            TraceHop(address: nil, millis: nil),
            TraceHop(address: "10.1.1.1", millis: 4),
            TraceHop(address: "10.2.2.2", millis: 5),
            TraceHop(address: "10.3.3.3", millis: 6),
            TraceHop(address: "10.4.4.4", millis: 7),
        ]
        #expect(LinkParse.compactPath(hops) == "10.0.0.1 → * → … → 10.3.3.3 → 10.4.4.4")
    }
}
