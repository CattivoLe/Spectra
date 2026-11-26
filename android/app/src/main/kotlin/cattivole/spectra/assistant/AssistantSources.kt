package cattivole.spectra.assistant

import cattivole.spectra.core.TrafficFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import cattivole.spectra.SpectraModel
import cattivole.spectra.core.MacAddress
import cattivole.spectra.core.PcapAnalyzer
import cattivole.spectra.core.PortRisk
import cattivole.spectra.core.RiskLevel
import cattivole.spectra.core.Threats
import cattivole.spectra.core.TrackerAssessment
import cattivole.spectra.core.TrackerDetector

/**
 * Reads the live scanners of Spectra into an [AssistantSnapshot] for the summary cards. Read-only: the card's
 * step buttons start scans through [AssistantActions]. Mirrors AssistantSources.swift.
 */
class AssistantSources(val model: SpectraModel, val captures: CaptureShelf) {
    /** What the Check a link screen and the link card's buttons found about recent links. */
    val links = LinkShelf()

    /**
     * What a summary card shows: its findings and steps, and the data its model actions read. Null when there is
     * nothing to summarize (no recorded traffic or capture handed over).
     */
    fun cardFacts(subject: CardSubject, now: Long = System.currentTimeMillis()): CardFacts? = when (subject) {
        CardSubject.Traffic, is CardSubject.Request, is CardSubject.Endpoint, is CardSubject.Connection ->
            captures.prepared(subject) ?: trafficWork(subject, bodies = false)?.invoke()
        CardSubject.Capture -> captures.latest?.let { entry ->
            AssistantCards.capture(entry.analysis, entry.name, CaptureDigest.text(entry.analysis, entry.name, entry.origin, ::describe))
        }
        is CardSubject.Link -> links.facts(subject.link, now)
        else -> AssistantCards.facts(subject, snapshot(now), now)
    }

    /** What the cards of recorded requests read the recordings through. */
    val library: TrafficLibrary get() = captures.library

    /**
     * Works out a card of recorded traffic off the main thread, bodies included: it looks through every request of a
     * recording. [cardFacts] then finds it ready; without this it reads the recording alone, at once.
     */
    suspend fun prepare(subject: CardSubject) {
        val work = trafficWork(subject, bodies = true) ?: return
        withContext(Dispatchers.IO) { work() }?.let { captures.keep(it, subject) }
    }

    private fun trafficWork(subject: CardSubject, bodies: Boolean): (() -> CardFacts?)? {
        val local = localNames()
        fun context(session: String?, flows: List<TrafficFlow>) =
            TrafficCards.Context(session, flows, if (bodies) library.bodies(session) else null, library.recording(), local)
        return when (subject) {
            CardSubject.Traffic -> captures.currentRecording?.let { recording ->
                {
                    AssistantCards.traffic(recording.flows, recording.name, TrafficDigest.text(recording.flows, recording.name, "recorded on the phone (Traffic screen)"), local)
                }
            }
            is CardSubject.Request -> {
                val index = subject.ref.index ?: return null
                val (session, flow) = library.find(subject.ref) ?: return null
                val context = context(session, library.flows(session) ?: listOf(flow));
                { TrafficCards.request(flow, index, context) }
            }
            is CardSubject.Connection -> {
                val (session, flow) = library.find(subject.ref) ?: return null
                val context = context(session, library.flows(session) ?: listOf(flow));
                { TrafficCards.connection(flow, context) }
            }
            is CardSubject.Endpoint -> {
                val context = context(subject.session, library.flows(subject.session) ?: return null);
                { TrafficCards.endpoint(subject.key, context) }
            }
            else -> null
        }
    }

    fun snapshot(now: Long = System.currentTimeMillis()): AssistantSnapshot {
        val networks = model.store.networks().values.toList()
        val senders = model.store.steadySenders
        val wifi = networks.map { network ->
            val reading = network.latest
            val name = network.revealedSsid ?: reading.ssid
            AssistantSnapshot.Wifi(
                name = name.orEmpty(),
                bssid = reading.bssid.text,
                rssi = reading.rssi,
                channel = reading.channel,
                band = reading.band.label,
                security = reading.auth.name,
                maker = if (reading.bssid.isLocallyAdministered) null else reading.bssid.vendor(),
                isCurrent = model.wifiJoiner.isCurrent(network),
                isHidden = name.isNullOrEmpty(),
                inRange = network.isInRange,
                clients = network.clients.size,
                streaming = network.movesDataSteadily || senders.any { it.traffic.bssid == network.id },
                stations = network.clients.map { AssistantSnapshot.Station(it.mac.text, it.rssi) },
                firstSeen = network.firstSeen,
            )
        }
        val findings = Threats.assess(networks, model.store.deauthEvents, senders, model.lan::nameOf).map { finding ->
            val (title, detail) = when (finding.kind) {
                Threats.Kind.DEAUTH_ATTACK -> "Deauthentication attack" to
                    "${finding.frames} deauth frames on channel ${finding.channel}${if (finding.broadcast) " sent to everyone" else ""}${finding.name?.let { " against \"$it\"" } ?: ""}"
                Threats.Kind.EVIL_TWIN -> "Possible evil twin" to
                    "\"${finding.ssid ?: "?"}\" is broadcast by ${finding.apCount} access points${if (finding.differByAuth) " with different security" else ""}"
                Threats.Kind.STREAMING -> if (finding.sender != null) "A device keeps sending data" to
                    "${finding.senderName} sends data the whole time through \"${finding.name}\", as a camera or microphone would"
                else "Steady data stream" to
                    "\"${finding.name ?: finding.ssid ?: "a network"}\" moves data the whole time, as a camera or microphone feed would"
            }
            AssistantSnapshot.Finding(
                if (finding.severity == Threats.Severity.CRITICAL) "critical" else "warning",
                title,
                detail,
                finding.networks.map { it.text },
            )
        }
        val hosts = model.lan.devices.map { device ->
            val risks = PortRisk.findings(device).filter { it.level >= RiskLevel.MEDIUM }
            AssistantSnapshot.Host(
                ip = device.ipAddress,
                mac = device.macAddress?.text,
                name = device.displayName,
                maker = device.identity.manufacturer ?: device.manufacturer,
                system = device.identity.operatingSystem,
                ports = device.openPorts.map { "${it.number} ${it.service}" },
                portsScanned = device.portsScanned,
                allPortsScanned = device.allPortsScanned,
                risks = risks.map { "${it.title} (${it.level.label})" },
                isGateway = device.isGateway,
                isThisPhone = device.isLocalDevice,
                isNew = device.isNew,
                trust = device.trust.name.lowercase(),
                note = device.note,
                portRisks = risks.map { AssistantSnapshot.PortRiskItem(it.title, it.level >= RiskLevel.HIGH) },
                forwarding = device.profile?.portForwarding?.takeIf { device.isGateway }?.let { table ->
                    AssistantSnapshot.Forwarding(
                        table.supported,
                        table.mappings.filter { it.enabled }.map { "${it.proto} ${it.externalPort} → ${it.internalClient}:${it.internalPort}" },
                        table.mappings.filter { it.enabled }.map { AssistantSnapshot.ForwardTarget(it.proto, it.externalPort, it.internalClient, it.internalPort) },
                    )
                },
                firstSeen = device.firstSeen,
            )
        }
        val bluetooth = model.ble.devices.values.map { device ->
            val assessment: TrackerAssessment? = TrackerDetector.assess(device, now)
            AssistantSnapshot.Bluetooth(
                name = device.displayName,
                company = device.companyName,
                rssi = device.rssi,
                nearby = device.isNearby(now),
                minutesNearby = (device.presentFor(now) / 60_000L).toInt(),
                tracker = assessment?.kind?.label,
                trackerLevel = assessment?.level?.name?.lowercase(),
                away = assessment?.separated,
                address = device.address,
                ownName = device.hasOwnName,
                firstSeen = device.firstSeen,
            )
        }
        val sessions = model.history.entries.map {
            AssistantSnapshot.Session(it.kindTitle, it.savedAt, it.source, it.placeLabel, it.itemCount)
        }
        val report = model.connection.report
        val speed = model.speed.result
        val link = if (report?.status != null) {
            // The connection check samples the download; a full speed test run after it is the fresher word.
            val fresher = speed != null && speed.measuredAt >= report.checkedAt
            val down = if (fresher) speed?.downBps ?: report.speed?.downBps else report.speed?.downBps ?: speed?.downBps
            val up = if (fresher) speed?.upBps ?: report.speed?.upBps else report.speed?.upBps ?: speed?.upBps
            AssistantSnapshot.Link(
                checkedAt = report.checkedAt,
                status = report.status?.name?.lowercase(),
                detail = report.detail,
                gatewayMs = report.gateway?.millis,
                dnsMs = report.dns?.millis,
                webMs = report.web?.millis,
                publicIp = report.external?.ip,
                provider = report.external?.isp,
                country = report.external?.countryCode,
                downMbps = down?.let { it / 1_000_000 },
                upMbps = up?.let { it / 1_000_000 },
                latencyMs = if (fresher) speed?.idleLatencyMs ?: report.speed?.latencyMs else report.speed?.latencyMs ?: speed?.idleLatencyMs,
                jitterMs = speed?.jitterMs,
                intercepted = report.tls?.intercepted,
                resolverLies = report.resolver?.liesAboutMissingNames,
                exposedPorts = report.exposure?.openPorts?.map { it.port }.orEmpty(),
                access = report.access?.verdict?.name?.lowercase(),
                gatewayOk = report.gateway?.ok,
                dnsOk = report.dns?.ok,
                webOk = report.web?.ok,
                resolverName = report.resolver?.let { it.resolverName ?: it.resolverIp },
                resolverCountry = report.resolver?.resolverCountry,
                blocked = report.access?.unreachable.orEmpty(),
                tlsReached = report.tls?.reachable,
                exposureChecked = report.exposure != null,
                bufferbloat = speed?.bufferbloatGrade,
                speedFailed = report.speedFailed,
                via = model.connection.route.name.lowercase(),
                warp = report.external?.warp == true,
            )
        } else if (speed != null) {
            AssistantSnapshot.Link(
                checkedAt = speed.measuredAt,
                downMbps = speed.downBps?.let { it / 1_000_000 },
                upMbps = speed.upBps?.let { it / 1_000_000 },
                latencyMs = speed.idleLatencyMs,
                jitterMs = speed.jitterMs,
                bufferbloat = speed.bufferbloatGrade,
            )
        } else {
            null
        }
        val gatt = model.gatt.lastRead?.let { read ->
            val device = model.ble.devices[read.address]
            val age = ((now - read.readAt) / 60_000L).toInt()
            GattDigest.text(read, device?.displayName ?: read.address, device?.rssi, if (age < 1) "Read just now from" else "Read $age min ago from")
        }
        val connection = model.connection
        val sites = connection.resources.mapNotNull { host ->
            connection.resourceResults[host]?.let { AssistantSnapshot.SiteReach(host, it.ok, it.millis) }
        }
        val route = connection.routeTarget?.let { target ->
            AssistantSnapshot.Route(
                target = target,
                address = connection.routeAddress,
                hops = connection.routeTrace?.hops.orEmpty().map { hop ->
                    val place = hop.address?.let { connection.routePlaces[it] }
                    AssistantSnapshot.RouteHop(
                        hop.address,
                        hop.millis,
                        place?.let { listOfNotNull(listOfNotNull(it.city, it.countryCode).joinToString(", ").ifEmpty { null }, it.isp).joinToString(" · ") }
                            ?.ifEmpty { null },
                    )
                },
                failure = connection.routeFailure,
                running = connection.routeRunning,
            )
        }
        return AssistantSnapshot(
            wifiName = model.wifiJoiner.currentSsid,
            networkLabel = model.lan.networkLabel,
            sensorConnected = model.link.isConnected,
            sensorName = model.link.info?.name,
            lastWifiScan = model.store.lastScanFinished,
            wifi = wifi,
            findings = findings,
            deauthFrames = model.store.deauthEvents.sumOf { it.count },
            probingDevices = model.store.probingDevices.size,
            hosts = hosts,
            lanScanning = model.lan.scanning,
            bluetooth = bluetooth,
            sessions = sessions,
            link = link,
            capture = captureText(),
            gatt = gatt,
            gattAddress = model.gatt.lastRead?.address,
            gattItems = model.gatt.lastRead?.services.orEmpty().flatMap { service ->
                val vendor = cattivole.spectra.core.Catalog.serviceName(service.uuid) == null
                val serviceName = service.name ?: cattivole.spectra.core.Catalog.serviceName(service.uuid) ?: service.uuid
                service.characteristics.map { c ->
                    AssistantSnapshot.GattItem(
                        serviceName,
                        c.name ?: cattivole.spectra.core.Catalog.characteristicName(c.uuid) ?: c.uuid,
                        c.value?.let { cattivole.spectra.core.GattValue.decode(c.uuid, it) },
                        c.properties.contains("Write"),
                        vendor,
                    )
                }
            },
            sites = sites,
            route = route,
            senders = senders.map { AssistantSnapshot.Sender(it.traffic.mac.text, it.traffic.bssid.text, it.traffic.upBytesPerSecond) },
            localPeers = CrossLinks.localPeers(captures.currentRecording?.flows.orEmpty(), hosts.map { it.ip }.toSet()),
            captureName = captures.latest?.name,
            captureTalkers = captures.latest?.analysis?.summary?.talkers.orEmpty().map { AssistantSnapshot.CaptureTalker(it.endpoint, it.packets) },
        )
    }

    /** What the cards of recorded traffic call a server that is a device of the local network: by its address. */
    private fun localNames(): Map<String, String> = model.lan.devices.filter { !it.isLocalDevice }.associate { device ->
        val maker = device.identity.manufacturer ?: device.manufacturer
        val name = AssistantDigest.clean(device.displayName, 30)
        device.ipAddress to if (maker == null || name.contains(maker)) name else "$name · ${AssistantDigest.clean(maker)}"
    }

    /** Both kinds of recorded traffic the user may mean by "the capture", newest first. */
    private fun captureText(): String? {
        val parts = mutableListOf<Pair<Long, String>>()
        pcapText()?.let(parts::add)
        captures.currentRecording?.let { recording ->
            parts.add(recording.keptAt to TrafficDigest.text(recording.flows, recording.name, "recorded on the phone (Traffic screen)"))
        }
        if (parts.isEmpty()) return null
        return parts.sortedByDescending { it.first }.joinToString("\n\n") { it.second }
    }

    /** The capture last decoded on the Decode pcap screen, or else the one the sensor still holds from its last run. */
    private fun pcapText(): Pair<Long, String>? {
        captures.latest?.let { entry ->
            return entry.decodedAt to CaptureDigest.text(entry.analysis, entry.name, entry.origin, ::describe)
        }
        val link = model.link
        if (link.rawCapturing || link.rawFrameCount <= 0) return null
        val analysis = runCatching { PcapAnalyzer.analyze(link.rawCapturePcap()) }.getOrNull() ?: return null
        val name = link.rawCaptureNetwork?.let { bssid ->
            model.store.networks()[bssid]?.let { it.revealedSsid ?: it.latest.ssid } ?: bssid.text
        } ?: "the sensor's last capture"
        return 0L to CaptureDigest.text(analysis, name, "taken by the Spectra sensor, not opened yet", ::describe)
    }

    /** A name for an address seen in a capture: the Wi‑Fi network it belongs to, a LAN device, or the maker. */
    fun describe(address: String): String? {
        MacAddress.parse(address)?.let { mac ->
            model.store.networks()[mac]?.let { network ->
                val name = network.revealedSsid ?: network.latest.ssid
                return if (name != null) "access point of \"$name\"" else "hidden access point"
            }
            model.lan.devices.firstOrNull { it.macAddress == mac }?.let { return "${it.displayName} (${it.ipAddress})" }
            if (mac.isLocallyAdministered) return "random MAC"
            return mac.vendor()
        }
        return model.lan.devices.firstOrNull { it.ipAddress == address }?.displayName
    }
}
