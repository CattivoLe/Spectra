import Darwin
import Foundation
import Network
import Observation

/// Measures the network the phone is joined to: whether the internet answers,
/// the public address, a short speed sample, which countries open, and whether
/// that public address answers from other countries.
///
/// The path out is traced from the phone itself (`LocalTracer`, ICMP with a
/// growing TTL); the path in is asked of servers in other countries. Sockets are pinned to Wi-Fi or Ethernet
/// when that interface is up, so a VPN is not what gets measured.
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

    /// Keeps a full speed test on the current check, so History has it. A
    /// test run before any check starts a report of its own.
    func attach(speed: SpeedSample) {
        guard !running else { return }
        if report == nil {
            var fresh = LinkSnapshot()
            fresh.status = .online
            report = fresh
        }
        report?.speed = speed
        report?.speedFailed = false
        keepIfUseful()
    }

    // MARK: Asking one place again

    /// Rows being measured again, by id.
    private(set) var repinging: Set<String> = []

    /// Connects to one country's site again and replaces its time.
    func repingRegion(_ code: String) {
        guard !running, let region = report?.regions.first(where: { $0.code == code }), !repinging.contains("region-" + code) else { return }
        repinging.insert("region-" + code)
        Task { @MainActor in
            let interface = await currentInterface()
            let millis = await LinkHTTP.connectMillis(host: region.host, port: 443, timeout: 2.2, interfaceType: interface)
            repinging.remove("region-" + code)
            guard let index = report?.regions.firstIndex(where: { $0.code == code }) else { return }
            report?.regions[index] = RegionReach(code: code, host: region.host, ok: millis != nil, millis: millis)
        }
    }

    /// Asks the same check-host node to ping the public address again.
    func repingAbroad(_ id: String) {
        guard !running, let view = report?.abroad.first(where: { $0.id == id }), let node = view.node,
              let ip = report?.external?.ip, !repinging.contains("abroad-" + id) else { return }
        repinging.insert("abroad-" + id)
        Task { @MainActor in
            defer { repinging.remove("abroad-" + id) }
            let interface = await currentInterface()
            let start = await remoteStart("https://check-host.net/check-ping?host=\(encode(ip))&node=\(encode(node))", interface)
            guard case .ok(let request, let nodes)? = start else { return }
            let views = await poll(id: request, interface: interface) { data -> (complete: Bool, value: [AbroadView])? in
                guard let parsed = LinkParse.abroad(data, nodes: nodes) else { return nil }
                return (parsed.complete, parsed.views)
            }
            guard var fresh = views?.first, let index = report?.abroad.firstIndex(where: { $0.id == id }) else { return }
            // Keep the place as first named, in case the node reports it differently.
            fresh.code = view.code
            fresh.city = view.city
            report?.abroad[index] = fresh
        }
    }

    private func currentInterface() async -> NWInterface.InterfaceType? {
        let throughVpn = UserDefaults.standard.bool(forKey: "probeViaVpn")
        if case .pinned(let type) = await LinkHTTP.probeInterface(viaVpn: throughVpn) { return type }
        return nil
    }

    // MARK: Ports from the internet

    /// Ports a home router is most often found answering on from outside.
    static let exposurePorts = [21, 22, 23, 80, 443, 445, 3389, 7547, 8080, 8443]

    private(set) var checkingExposure = false
    private var exposureTask: Task<Void, Never>?
    /// Ports the person typed in, tried along with the usual ones next time.
    private(set) var extraExposurePorts: [Int] = []

    /// Asks check-host.net to connect to the public address on each of the
    /// usual ports, one request per port, and keeps the answers on the report.
    func checkExposure() {
        checkExposure(ports: Self.exposurePorts + extraExposurePorts)
    }

    /// Tries one more port from outside and adds it to the list.
    func checkExposure(port: Int) {
        guard (1...65535).contains(port) else { return }
        if !Self.exposurePorts.contains(port), !extraExposurePorts.contains(port) {
            extraExposurePorts.append(port)
        }
        checkExposure(ports: [port], merge: true)
    }

    private func checkExposure(ports: [Int], merge: Bool = false) {
        guard let ip = report?.external?.ip, !running, !checkingExposure else { return }
        checkingExposure = true
        exposureTask = Task { @MainActor in
            let throughVpn = UserDefaults.standard.bool(forKey: "probeViaVpn")
            let interface: NWInterface.InterfaceType?
            if case .pinned(let type) = await LinkHTTP.probeInterface(viaVpn: throughVpn) { interface = type } else { interface = nil }
            var result = await probeExposure(ip: ip, ports: ports, interface: interface)
            guard !Task.isCancelled, report?.external?.ip == ip else {
                checkingExposure = false
                return
            }
            if merge, let previous = report?.exposure, previous.ip == ip {
                var combined = previous.ports.filter { old in !result.ports.contains { $0.port == old.port } }
                combined += result.ports
                result.ports = combined
                if result.note == .none { result.note = previous.note }
            }
            report?.exposure = result
            checkingExposure = false
            keepIfUseful()
        }
    }

    private func probeExposure(ip: String, ports: [Int], interface: NWInterface.InterfaceType?) async -> ExposureReport {
        var started: [(port: Int, id: String, node: String)] = []
        var note = RemoteNote.none
        for port in ports {
            let start = await remoteStart("https://check-host.net/check-tcp?host=\(encode(ip)):\(port)&max_nodes=1", interface)
            switch start {
            case .ok(let id, let nodes)?:
                if let node = nodes.first { started.append((port, id, node.id)) }
            case .busy?:
                note = .busy
            default:
                if note == .none { note = .failed }
            }
            if Task.isCancelled { break }
            if case .busy? = start { break }
            try? await Task.sleep(for: .milliseconds(250))
        }
        var results: [Int: Bool] = [:]
        var pending = started
        for _ in 0..<10 where !pending.isEmpty {
            try? await Task.sleep(for: .milliseconds(1500))
            if Task.isCancelled { break }
            var still: [(port: Int, id: String, node: String)] = []
            for item in pending {
                guard let result = try? await LinkHTTP.send(
                    host: "check-host.net",
                    path: "/check-result/\(item.id)",
                    accept: "application/json",
                    timeout: 8,
                    maxBytes: 100_000,
                    interfaceType: interface
                ), let open = LinkParse.tcpOpen(result.body, node: item.node) else {
                    still.append(item)
                    continue
                }
                results[item.port] = open
            }
            pending = still
        }
        let ports = started.map { ExposedPort(port: $0.port, open: results[$0.port] ?? false) }
        return ExposureReport(ip: ip, checkedAt: .now, ports: ports, note: note)
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
                : (ProbeRoute.current() == .cellular
                    ? "There is no mobile network to check."
                    : "Connect to a Wi-Fi or Ethernet network first.")
            report = current
            return
        case .system:
            interface = nil
        case .pinned(let type):
            interface = type
        }
        guard live() else { return }
        stage("Checking the gateway", 0.12, mine)
        // The Wi-Fi router is not on the mobile network's path.
        let gatewayAddress = interface == .cellular ? nil : SubnetInfo.current()?.gateway
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

        stage("Checking access", 0.34, mine)
        current.access = await AccessCheck.run(interface: interface)
        guard live() else { return }
        report = current

        stage("Checking countries", 0.40, mine)
        current.regions = await probeRegions(interface)
        guard live() else { return }
        report = current

        if webOk {
            stage("Checking certificates", 0.48, mine)
            async let tls = TLSInspector.check(interface: interface)
            async let resolver = DNSInspector.check(interface: interface)
            let tlsResult = await tls
            let dnsResult = await resolver
            guard live() else { return }
            current.tls = tlsResult
            current.resolver = dnsResult
            report = current
        }
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
        // The path out, traced from this phone while the remote servers work.
        let interfaceName: String? = if let interface { await LinkHTTP.interfaceName(interface) } else { nil }
        async let localTrace = Self.traceOut(interfaceName: interfaceName)
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
        current.localTrace = await localTrace
        guard live() else { return }
        report = current
    }

    /// The hops from here to the trace target, with their reverse DNS names.
    nonisolated private static func traceOut(interfaceName: String?) async -> LocalTrace {
        var trace = await LocalTracer.trace(interfaceName: interfaceName)
        let names = await LocalTracer.names(for: trace.hops.compactMap(\.address))
        for index in trace.hops.indices {
            if let address = trace.hops[index].address { trace.hops[index].name = names[address] }
        }
        return trace
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
                    // Either family: an IPv6-only network answers with AAAA.
                    var hints = addrinfo()
                    hints.ai_family = AF_UNSPEC
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
