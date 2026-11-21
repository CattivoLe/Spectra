package cattivole.spectra.assistant

import alexcattivo.localchat.kit.AppDataAttachment
import cattivole.spectra.SpectraModel
import cattivole.spectra.core.MacAddress
import cattivole.spectra.core.PcapAnalyzer
import cattivole.spectra.core.PortRisk
import cattivole.spectra.core.RiskLevel
import cattivole.spectra.core.Threats
import cattivole.spectra.core.TrackerAssessment
import cattivole.spectra.core.TrackerDetector

/**
 * Reads the live scanners of Spectra into an [AssistantSnapshot]. Read-only: the assistant can look at what the app
 * has found; [AssistantActions] is what may start a scan. Mirrors AssistantSources.swift.
 */
class AssistantSources(val model: SpectraModel, val captures: CaptureShelf, val memory: AssistantMemory) {
    fun attachment(question: String): AppDataAttachment {
        // The dossier is assembled per question: only what is in front of the user, or named in the question.
        val current = snapshot().let { it.copy(memory = memory.digest(AssistantDigest.subjects(question, it))) }
        return AssistantDigest.attachment(question, current)
    }

    /**
     * What a summary card shows: its findings and steps, and the data its model actions read. Null when there is
     * nothing to summarize (no recorded traffic or capture handed over).
     */
    fun cardFacts(subject: CardSubject, now: Long = System.currentTimeMillis()): CardFacts? = when (subject) {
        CardSubject.Traffic -> captures.currentRecording?.let { recording ->
            AssistantCards.traffic(recording.flows, recording.name, TrafficDigest.text(recording.flows, recording.name, "recorded on the phone (Traffic screen)"))
        }
        CardSubject.Capture -> captures.latest?.let { entry ->
            AssistantCards.capture(entry.analysis, entry.name, CaptureDigest.text(entry.analysis, entry.name, entry.origin, ::describe))
        }
        else -> {
            val base = snapshot(now)
            val key = when (subject) {
                is CardSubject.Host -> listOfNotNull(subject.ip, base.hosts.firstOrNull { it.ip == subject.ip }?.mac)
                is CardSubject.Bluetooth -> listOf(subject.address)
                is CardSubject.Wifi -> listOf(subject.bssid)
                else -> base.hosts.flatMap { listOfNotNull(it.ip, it.mac) }
            }
            val subjects = key.mapNotNull { AssistantMemory.normalize(it) }.toSet()
            AssistantCards.facts(subject, base.copy(memory = memory.digest(subjects)), now)
        }
    }

    /** Folds what is on screen into the long-term baselines. Cheap on purpose: no capture decode, no threat assessment. */
    fun recordBaselines(now: Long = System.currentTimeMillis()) {
        memory.observe(sightings(now), now)
    }

    private fun sightings(now: Long): List<AssistantMemory.Sighting> {
        val found = mutableListOf<AssistantMemory.Sighting>()
        for (device in model.lan.devices) if (!device.isLocalDevice) {
            found.add(
                AssistantMemory.Sighting(
                    device.macAddress?.text ?: device.ipAddress,
                    device.displayName,
                    AssistantMemory.Baseline.Kind.HOST,
                    ports = device.openPorts.map { it.number },
                    portsKnown = device.portsScanned,
                ),
            )
        }
        for (network in model.store.networks().values) if (network.isInRange) {
            val reading = network.latest
            found.add(
                AssistantMemory.Sighting(
                    reading.bssid.text,
                    network.revealedSsid ?: reading.ssid ?: reading.bssid.text,
                    AssistantMemory.Baseline.Kind.WIFI,
                    rssi = reading.rssi,
                ),
            )
        }
        for (device in model.ble.devices.values) if (device.isNearby(now)) {
            found.add(AssistantMemory.Sighting(device.address, device.displayName, AssistantMemory.Baseline.Kind.BLUETOOTH, rssi = device.rssi))
        }
        return found
    }

    /** Just enough to choose the opening questions of an empty chat, without building a whole snapshot. */
    fun readiness(): AssistantStarters.Readiness = AssistantStarters.Readiness(
        sensorConnected = model.link.isConnected,
        hasHosts = model.lan.devices.isNotEmpty(),
        hasBluetooth = model.ble.devices.isNotEmpty(),
        hasWifi = model.store.networks().isNotEmpty(),
        hasLinkReport = model.connection.report?.status != null,
        hasCapture = captures.latest != null || captures.currentRecording != null || (!model.link.rawCapturing && model.link.rawFrameCount > 0),
    )

    fun snapshot(now: Long = System.currentTimeMillis()): AssistantSnapshot {
        val networks = model.store.networks().values.toList()
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
                streaming = (network.traffic?.bytes ?: 0L) >= Threats.STREAMING_BYTE_THRESHOLD,
            )
        }
        val findings = Threats.assess(networks, model.store.deauthEvents).map { finding ->
            val (title, detail) = when (finding.kind) {
                Threats.Kind.DEAUTH_ATTACK -> "Deauthentication attack" to
                    "${finding.frames} deauth frames on channel ${finding.channel}${if (finding.broadcast) " sent to everyone" else ""}${finding.name?.let { " against \"$it\"" } ?: ""}"
                Threats.Kind.EVIL_TWIN -> "Possible evil twin" to
                    "\"${finding.ssid ?: "?"}\" is broadcast by ${finding.apCount} access points${if (finding.differByAuth) " with different security" else ""}"
                Threats.Kind.STREAMING -> "Steady data stream" to
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
                risks = risks.map { "${it.title} (${it.level.label})" },
                isGateway = device.isGateway,
                isThisPhone = device.isLocalDevice,
                isNew = device.isNew,
                trust = device.trust.name.lowercase(),
                note = device.note,
                portRisks = risks.map { AssistantSnapshot.PortRiskItem(it.title, it.level >= RiskLevel.HIGH) },
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
            )
        }
        val sessions = model.history.entries.map {
            AssistantSnapshot.Session(it.kindTitle, it.savedAt, it.source, it.placeLabel, it.itemCount)
        }
        val report = model.connection.report
        val speed = model.speed.result
        val link = if (report?.status != null) {
            val down = report.speed?.downBps ?: speed?.downBps
            val up = report.speed?.upBps ?: speed?.upBps
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
                latencyMs = report.speed?.latencyMs ?: speed?.idleLatencyMs,
                jitterMs = speed?.jitterMs,
                intercepted = report.tls?.intercepted,
                resolverLies = report.resolver?.liesAboutMissingNames,
                exposedPorts = report.exposure?.openPorts?.map { it.port }.orEmpty(),
                access = report.access?.verdict?.name?.lowercase(),
            )
        } else if (speed != null) {
            AssistantSnapshot.Link(
                checkedAt = speed.measuredAt,
                downMbps = speed.downBps?.let { it / 1_000_000 },
                upMbps = speed.upBps?.let { it / 1_000_000 },
                latencyMs = speed.idleLatencyMs,
                jitterMs = speed.jitterMs,
            )
        } else {
            null
        }
        val gatt = model.gatt.lastRead?.let { read ->
            val device = model.ble.devices[read.address]
            val age = ((now - read.readAt) / 60_000L).toInt()
            GattDigest.text(read, device?.displayName ?: read.address, device?.rssi, if (age < 1) "Read just now from" else "Read $age min ago from")
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
        )
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
