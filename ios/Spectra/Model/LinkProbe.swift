import Darwin
import Foundation
import Network
import Observation

/// Measures the network the phone is joined to: whether the internet answers,
/// the public address, a short speed sample, which countries open, and whether
/// that public address answers from other countries.
///
/// iOS does not allow the probes a traceroute needs, so the path is asked of
/// servers in other countries instead. Sockets are pinned to Wi-Fi or Ethernet
/// when that interface is up, so a VPN is not what gets measured.
///
/// Country hosts stay in step with `REGION_HOSTS` in Android `LinkProbe.kt`.
@Observable
final class LinkProbe {
    private(set) var running = false
    private(set) var phase = ""
    private(set) var fraction = 0.0
    private(set) var report: LinkSnapshot?

    private var task: Task<Void, Never>?
    private var token = 0
    /// Called on the main actor when a check ends with something worth keeping.
    var onFinished: ((LinkSnapshot, Bool) -> Void)?

    func check() {
        token += 1
        let mine = token
        task?.cancel()
        running = true
        phase = "Reading the network"
        fraction = 0.04
        report = LinkSnapshot()
        task = Task { @MainActor in
            await self.perform(mine)
            if self.token == mine {
                self.running = false
                self.fraction = 1
                self.phase = ""
                self.keepIfUseful()
            }
        }
    }

    func stop() {
        token += 1
        task?.cancel()
        running = false
        phase = ""
        keepIfUseful()
    }

    private func keepIfUseful() {
        guard let report, report.status != nil, report.status != .noNetwork else { return }
        let viaVpn = UserDefaults.standard.bool(forKey: "probeViaVpn")
        onFinished?(report, viaVpn)
    }

    @MainActor
    private func perform(_ mine: Int) async {
        guard var current = report else { return }
        func live() -> Bool { token == mine && !Task.isCancelled }
        let throughVpn = UserDefaults.standard.bool(forKey: "probeViaVpn")
        let interface: NWInterface.InterfaceType?
        switch await LinkHTTP.probeInterface(viaVpn: throughVpn) {
        case .unavailable:
            guard live() else { return }
            current.status = .noNetwork
            current.detail = throughVpn
                ? "There is no network connection to check."
                : "Connect to a Wi-Fi or Ethernet network first."
            report = current
            return
        case .system:
            interface = nil
        case .pinned(let type):
            interface = type
        }
        guard live() else { return }
        stage("Checking the gateway", 0.12, mine)
        let gatewayAddress = SubnetInfo.current()?.gateway
        let gateway = await probeGateway(gatewayAddress, interface: interface)
        let dns = await probeDNS()
        guard live() else { return }
        stage("Looking up the external address", 0.28, mine)
        let trace = try? await LinkHTTP.send(
            host: "1.1.1.1",
            path: "/cdn-cgi/trace",
            timeout: 8,
            maxBytes: 16_000,
            interfaceType: interface
        )
        let externalFromTrace = trace.flatMap { result -> ExternalAddress? in
            guard result.status == 200 else { return nil }
            return LinkParse.external(fromTrace: String(decoding: result.body, as: UTF8.self))
        }
        let webOk = externalFromTrace != nil
        let webMillis = webOk ? await LinkHTTP.connectMillis(host: "1.1.1.1", port: 443, timeout: 2, interfaceType: interface) : nil
        guard live() else { return }
        current.gateway = gateway
        current.dns = dns
        current.web = ProbeResult(name: "1.1.1.1", ok: webOk, millis: webMillis)
        var external = externalFromTrace
        current.external = external
        applyStatus(&current, gateway: gateway, dns: dns, webOk: webOk)
        report = current
        if var found = external, live() {
            if let whois = try? await LinkHTTP.send(
                host: "ipwho.is",
                path: "/\(found.ip)",
                timeout: 8,
                maxBytes: 64_000,
                interfaceType: interface
            ), whois.status == 200 {
                found = LinkParse.mergeWhois(whois.body, into: found)
                external = found
                current.external = found
                guard live() else { return }
                report = current
            }
        }
        guard live() else { return }

        stage("Checking countries", 0.42, mine)
        current.regions = await probeRegions(interface)
        guard live() else { return }
        report = current
        guard live() else { return }

        if webOk {
            stage("Measuring speed", 0.58, mine)
            let sample = await measureSpeed(interface)
            guard live() else { return }
            current.speed = sample
            current.speedFailed = sample.latencyMs == nil && sample.downBps == nil && sample.upBps == nil
            report = current
        }
        guard live() else { return }

        let ip = current.external?.ip
        stage(ip != nil ? "Asking other countries" : "Tracing the path", 0.74, mine)
        async let ping = remoteStart(ip.map { "https://check-host.net/check-ping?host=\(encode($0))&max_nodes=6" }, interface)
        async let traceReq = remoteStart(ip.map { "https://check-host.net/check-traceroute?host=\(encode($0))&max_nodes=3" }, interface)
        let startedPing = await ping
        let startedTrace = await traceReq
        guard live() else { return }
        stage("Tracing the path", 0.88, mine)
        async let abroad = abroadOutcome(startedPing, asked: ip != nil, interface: interface)
        async let traces = traceOutcome(startedTrace, target: ip, asked: ip != nil, interface: interface)
        let abroadResult = await abroad
        let traceResult = await traces
        guard live() else { return }
        current.abroad = abroadResult.views
        current.abroadNote = abroadResult.note
        current.remoteTraces = traceResult.traces
        current.traceNote = traceResult.note
        report = current
    }

    private func applyStatus(_ report: inout LinkSnapshot, gateway: ProbeResult?, dns: ProbeResult?, webOk: Bool) {
        if webOk {
            report.status = .online
        } else if gateway?.ok == true, dns?.ok != true {
            report.status = .lan
            report.detail = "The gateway answers, but nothing past it does."
        } else if dns?.ok == true {
            report.status = .offline
            report.detail = "DNS works, but secure web does not. A sign-in page may be blocking it."
        } else {
            report.status = .offline
            report.detail = "No route to the internet from this network."
        }
    }

    @MainActor
    private func stage(_ text: String, _ next: Double, _ mine: Int) {
        guard token == mine else { return }
        phase = text
        fraction = next
    }

    private func probeGateway(_ gateway: String?, interface: NWInterface.InterfaceType?) async -> ProbeResult? {
        guard let gateway else { return nil }
        for port: UInt16 in [443, 80, 53] {
            if let millis = await LinkHTTP.connectMillis(host: gateway, port: port, timeout: 0.8, interfaceType: interface) {
                return ProbeResult(name: gateway, ok: true, millis: millis)
            }
        }
        return ProbeResult(name: gateway, ok: false, millis: nil)
    }

    private func probeDNS() async -> ProbeResult {
        let start = ContinuousClock.now
        let ok = await resolve(Self.DNSName)
        let millis = ok ? max(1, Int((seconds(since: start) * 1000).rounded())) : nil
        return ProbeResult(name: Self.DNSName, ok: ok, millis: millis)
    }

    private func resolve(_ host: String) async -> Bool {
        await withTaskGroup(of: Bool?.self) { group in
            group.addTask { () -> Bool? in
                await Task.detached {
                    var hints = addrinfo()
                    hints.ai_family = AF_INET
                    hints.ai_socktype = SOCK_STREAM
                    var info: UnsafeMutablePointer<addrinfo>?
                    let err = getaddrinfo(host, nil, &hints, &info)
                    if let info { freeaddrinfo(info) }
                    return err == 0
                }.value
            }
            group.addTask { () -> Bool? in
                try? await Task.sleep(for: .seconds(3))
                return nil
            }
            let first = await group.next() ?? nil
            group.cancelAll()
            return first == true
        }
    }

    private func probeRegions(_ interface: NWInterface.InterfaceType?) async -> [RegionReach] {
        await withTaskGroup(of: RegionReach.self) { group in
            var remaining = Self.regionHosts.makeIterator()
            var open = 0
            func enqueue() {
                guard open < 6, let next = remaining.next() else { return }
                open += 1
                let code = next.0
                let host = next.1
                group.addTask {
                    let millis = await LinkHTTP.connectMillis(host: host, port: 443, timeout: 2.2, interfaceType: interface)
                    return RegionReach(code: code, host: host, ok: millis != nil, millis: millis)
                }
            }
            for _ in 0..<6 { enqueue() }
            var found: [String: RegionReach] = [:]
            for await reach in group {
                found[reach.code] = reach
                open -= 1
                enqueue()
            }
            return Self.regionHosts.compactMap { found[$0.0] }
        }
    }

    private func measureSpeed(_ interface: NWInterface.InterfaceType?) async -> SpeedSample {
        var samples: [Int] = []
        for _ in 0..<4 {
            if let millis = await LinkHTTP.connectMillis(host: "1.1.1.1", port: 443, timeout: 2, interfaceType: interface) {
                samples.append(millis)
            }
        }
        let down = await transfer(download: true, interface: interface)
        let up = await transfer(download: false, interface: interface)
        return SpeedSample(latencyMs: median(samples), downBps: down, upBps: up)
    }

    private func transfer(download: Bool, interface: NWInterface.InterfaceType?) async -> Double? {
        do {
            if download {
                let result = try await LinkHTTP.send(
                    host: "speed.cloudflare.com",
                    path: "/__down?bytes=10000000",
                    timeout: 12,
                    maxBytes: 8_000_000,
                    maxSeconds: 5,
                    keepBody: false,
                    interfaceType: interface
                )
                guard result.status == 200 else { return nil }
                return rate(bytes: result.bytes, seconds: result.seconds)
            }
            let result = try await LinkHTTP.send(
                host: "speed.cloudflare.com",
                path: "/__up",
                method: "POST",
                body: Data(count: 750_000),
                timeout: 12,
                maxBytes: 8_000,
                keepBody: false,
                interfaceType: interface
            )
            guard result.status == 200 else { return nil }
            return rate(bytes: 750_000, seconds: result.seconds)
        } catch {
            return nil
        }
    }

    private func remoteStart(_ url: String?, _ interface: NWInterface.InterfaceType?) async -> CheckStart? {
        guard let url, let parts = URL(string: url), let host = parts.host, let path = parts.query.map({ parts.path + "?" + $0 }) else {
            return nil
        }
        guard let result = try? await LinkHTTP.send(
            host: host,
            path: path,
            accept: "application/json",
            timeout: 10,
            maxBytes: 200_000,
            interfaceType: interface
        ) else { return nil }
        return LinkParse.checkStart(result.body)
    }

    private func abroadOutcome(
        _ start: CheckStart?,
        asked: Bool,
        interface: NWInterface.InterfaceType?
    ) async -> (views: [AbroadView], note: RemoteNote) {
        guard asked else { return ([], .none) }
        guard let start else { return ([], .failed) }
        switch start {
        case .busy:
            return ([], .busy)
        case .failed:
            return ([], .failed)
        case .ok(let id, let nodes):
            let views = await poll(id: id, interface: interface) { data -> (complete: Bool, value: [AbroadView])? in
                guard let parsed = LinkParse.abroad(data, nodes: nodes) else { return nil }
                return (parsed.complete, parsed.views)
            }
            return (views ?? [], .none)
        }
    }

    private func traceOutcome(
        _ start: CheckStart?,
        target: String?,
        asked: Bool,
        interface: NWInterface.InterfaceType?
    ) async -> (traces: [RemoteTrace], note: RemoteNote) {
        guard asked, let target else { return ([], .none) }
        guard let start else { return ([], .failed) }
        switch start {
        case .busy:
            return ([], .busy)
        case .failed:
            return ([], .failed)
        case .ok(let id, let nodes):
            let traces = await poll(id: id, interface: interface) { data -> (complete: Bool, value: [RemoteTrace])? in
                guard let parsed = LinkParse.traces(data, nodes: nodes, target: target) else { return nil }
                return (parsed.complete, parsed.traces)
            }
            return (traces ?? [], .none)
        }
    }

    private func poll<T>(
        id: String,
        interface: NWInterface.InterfaceType?,
        parse: (Data) -> (complete: Bool, value: T)?
    ) async -> T? {
        var last: T?
        for _ in 0..<12 {
            try? await Task.sleep(for: .milliseconds(1100))
            if Task.isCancelled { return last }
            guard let result = try? await LinkHTTP.send(
                host: "check-host.net",
                path: "/check-result/\(id)",
                accept: "application/json",
                timeout: 8,
                maxBytes: 500_000,
                interfaceType: interface
            ) else { continue }
            guard let parsed = parse(result.body) else { continue }
            last = parsed.value
            if parsed.complete { return parsed.value }
        }
        return last
    }

    private func rate(bytes: Int, seconds: TimeInterval) -> Double? {
        guard bytes >= 80_000, seconds >= 0.25 else { return nil }
        return Double(bytes) * 8 / seconds
    }

    private func median(_ values: [Int]) -> Int? {
        guard !values.isEmpty else { return nil }
        return values.sorted()[values.count / 2]
    }

    private func encode(_ ip: String) -> String {
        ip.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? ip
    }

    private static let DNSName = "one.one.one.one"
    private static let regionHosts: [(String, String)] = [
        ("US", "www.usa.gov"),
        ("DE", "www.denic.de"),
        ("NL", "www.ripe.net"),
        ("GB", "www.gov.uk"),
        ("FR", "www.afnic.fr"),
        ("RU", "ya.ru"),
        ("CN", "www.baidu.com"),
        ("JP", "www.yahoo.co.jp"),
        ("KR", "www.naver.com"),
        ("BR", "registro.br"),
        ("IN", "www.nic.in"),
        ("AU", "www.abc.net.au"),
        ("SG", "www.sgnic.sg"),
        ("ZA", "www.gov.za"),
    ]
}

private func seconds(since start: ContinuousClock.Instant) -> TimeInterval {
    let parts = (ContinuousClock.now - start).components
    return TimeInterval(parts.seconds) + TimeInterval(parts.attoseconds) / 1e18
}
