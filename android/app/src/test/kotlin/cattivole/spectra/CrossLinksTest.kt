package cattivole.spectra

import cattivole.spectra.assistant.AssistantActions
import cattivole.spectra.assistant.AssistantCards
import cattivole.spectra.assistant.AssistantSnapshot
import cattivole.spectra.assistant.CardAction
import cattivole.spectra.assistant.CardFinding
import cattivole.spectra.assistant.CardPrompts
import cattivole.spectra.assistant.CardRisk
import cattivole.spectra.assistant.CardSubject
import cattivole.spectra.assistant.CrossLink
import cattivole.spectra.assistant.CrossLinks
import cattivole.spectra.assistant.InternetCards
import cattivole.spectra.assistant.TrafficCards
import cattivole.spectra.core.TrafficFlow
import cattivole.spectra.ui.tr
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Locale

/**
 * Ties between the app's screens: one device on the LAN scan, on the air, over Bluetooth, in the router's table and
 * in the phone's traffic. The same cases as CrossLinksTests.swift.
 */
class CrossLinksTest {
    private val original = Locale.getDefault()
    private val now = 1_800_000_000_000L

    @Before
    fun english() = Locale.setDefault(Locale.ENGLISH)

    @After
    fun restore() = Locale.setDefault(original)

    private val router = AssistantSnapshot.Host(ip = "192.168.1.1", mac = "50:FF:20:11:22:30", name = "Keenetic", maker = "Keenetic", isGateway = true)
    private val camera = AssistantSnapshot.Host(ip = "192.168.1.64", mac = "44:19:B6:00:00:64", name = "IPC-Yard", maker = "Hikvision")
    private val speaker = AssistantSnapshot.Host(ip = "192.168.1.30", mac = "00:1B:66:00:00:30", name = "Momentum-3", maker = "Sennheiser")
    private val phone = AssistantSnapshot.Host(ip = "192.168.1.20", name = "This phone", isThisPhone = true)

    private fun home(stations: List<AssistantSnapshot.Station> = emptyList(), current: Boolean = true, name: String = "Home") =
        AssistantSnapshot.Wifi(
            name = name, bssid = "52:FF:20:11:22:31", rssi = -48, channel = 36, band = "5", security = "WPA2",
            isCurrent = current, clients = stations.size, stations = stations,
        )

    private fun snapshot(
        hosts: List<AssistantSnapshot.Host> = emptyList(),
        wifi: List<AssistantSnapshot.Wifi> = emptyList(),
        bluetooth: List<AssistantSnapshot.Bluetooth> = emptyList(),
    ) = AssistantSnapshot(hosts = hosts, wifi = wifi, bluetooth = bluetooth, wifiName = "Home")

    private fun forwarding(host: AssistantSnapshot.Host, targets: List<AssistantSnapshot.ForwardTarget>) = host.copy(
        forwarding = AssistantSnapshot.Forwarding(true, targets.map { "${it.proto} ${it.externalPort} → ${it.client}:${it.internalPort}" }, targets),
    )

    private fun exposure(ports: List<Int>, via: String? = "wifi") =
        AssistantSnapshot.Link(checkedAt = now, status = "online", exposedPorts = ports, exposureChecked = true, via = via)

    private fun texts(links: List<CrossLink>) = links.mapNotNull { it.finding?.text }

    // A host and its Wi‑Fi side.

    @Test
    fun aHostIsFoundOnTheAirByItsHardwareAddress() {
        // The sensor writes the address in its own case: the match must not care.
        val s = snapshot(hosts = listOf(router, camera), wifi = listOf(home(listOf(AssistantSnapshot.Station("44:19:b6:00:00:64", -57)))))
        val links = CrossLinks.host(s, camera, now)
        assertEquals(listOf(tr("On Wi‑Fi “%s”, channel %d; the sensor hears it at %d dBm.", "Home", 36, -57)), texts(links))
        assertTrue(links[0].line.contains("52:FF:20:11:22:31") && links[0].line.contains("WPA2"))
        // The router is the access point, not a client of it.
        val own = snapshot(hosts = listOf(router), wifi = listOf(home(listOf(AssistantSnapshot.Station(router.mac!!, -40)))))
        assertTrue(CrossLinks.host(own, router, now).isEmpty())
        // A host the scan got no address for cannot be matched.
        assertTrue(CrossLinks.host(s, camera.copy(mac = null), now).isEmpty())
    }

    @Test
    fun aSteadySenderIsNamedOnItsOwnCardAndOnTheNetworks() {
        val s = snapshot(hosts = listOf(router, camera), wifi = listOf(home()))
            .copy(senders = listOf(AssistantSnapshot.Sender(camera.mac!!, "52:FF:20:11:22:31", 150_000)))
        val own = CrossLinks.host(s, camera, now)
        assertEquals(CardRisk.WARNING, own.first().finding?.risk)
        assertTrue(own.first().line.contains("1200 kbit/s"))
        assertEquals(
            listOf(tr("%s sends data over Wi‑Fi the whole time, as a camera or microphone would.", "IPC-Yard (192.168.1.64)")),
            texts(CrossLinks.lan(s, now)),
        )
        // The card shows it, and the model reads it under the heading.
        val facts = AssistantCards.host(s, camera.ip, now)
        assertTrue(facts.findings.any { it.risk == CardRisk.WARNING && it.text == own[0].finding?.text })
        assertTrue(facts.data.contains(CrossLinks.HEADING) && facts.data.contains("1200 kbit/s"))
        assertTrue(facts.deepData.contains("1200 kbit/s"))
    }

    // Ports opened from the internet.

    @Test
    fun aForwardedPortIsTiedToTheDeviceItLeadsTo() {
        val gateway = forwarding(
            router,
            listOf(AssistantSnapshot.ForwardTarget("TCP", 8080, camera.ip, 80), AssistantSnapshot.ForwardTarget("UDP", 5000, "192.168.1.99", 5000)),
        )
        val s = snapshot(hosts = listOf(gateway, camera))
        val quiet = CrossLinks.host(s, camera, now)
        assertEquals(listOf(tr("The router forwards ports from the internet to this device: %s.", "TCP 8080 → 80")), texts(quiet))
        assertEquals(CardRisk.CRITICAL, quiet[0].finding?.risk)
        // The router's own card already lists its table.
        assertTrue(CrossLinks.host(s, gateway, now).isEmpty())
        // The whole network: the rule is named for the model, and the one that leads nowhere is shown.
        val lan = CrossLinks.lan(s, now)
        assertTrue(lan.any { it.finding == null && it.line.contains("TCP 8080") && it.line.contains("IPC-Yard (192.168.1.64)") })
        assertEquals(listOf(tr("The router forwards port %d to %s, which is not on the network now.", 5000, "192.168.1.99")), texts(lan))

        // The connection check found the same port answering from outside.
        val open = s.copy(link = exposure(listOf(8080, 443)))
        assertTrue(tr("Open from the internet right now: %s — the router forwards it to this device.", "8080") in texts(CrossLinks.host(open, camera, now)))
        assertTrue(tr("%s: open from the internet right now on port %d.", "IPC-Yard (192.168.1.64)", 8080) in texts(CrossLinks.lan(open, now)))
        val internet = CrossLinks.internet(open)
        assertEquals(
            listOf(
                tr("Port %d open from the internet leads to %s.", 8080, "IPC-Yard (192.168.1.64)"),
                tr("Port %d answers from the internet but is not in the router's UPnP table: the router itself answers, or the port is forwarded by hand in its settings.", 443),
            ),
            texts(internet),
        )
        assertEquals(listOf(CardRisk.CRITICAL, CardRisk.INFO), internet.map { it.finding?.risk })
        assertFalse(CrossLinks.asksForwarding(open))
        val card = InternetCards.internet(open, now)
        assertTrue(card.findings.any { it.text == internet[0].finding?.text })
        assertTrue(card.data.contains(CrossLinks.HEADING))
    }

    @Test
    fun openPortsWithoutTheRoutersTableAskForIt() {
        val s = snapshot(hosts = listOf(router, camera)).copy(link = exposure(listOf(8080)))
        assertTrue(CrossLinks.asksForwarding(s))
        assertTrue(texts(CrossLinks.internet(s)).isEmpty())
        assertTrue(CrossLinks.internet(s).first().line.contains("has not been read"))
        assertEquals(AssistantActions.Name.CHECK_PORT_FORWARDING.raw, InternetCards.internet(s, now).steps.first().tool)
        // Checked over the mobile network: the public address is the operator's, the router has nothing to do with it.
        val mobile = s.copy(link = exposure(listOf(8080), via = "cellular"))
        assertTrue(CrossLinks.internet(mobile).isEmpty() && !CrossLinks.asksForwarding(mobile))
        assertTrue(InternetCards.internet(mobile, now).steps.none { it.tool == AssistantActions.Name.CHECK_PORT_FORWARDING.raw })
    }

    // Bluetooth.

    @Test
    fun oneNameOnBothSidesTiesAHostToItsBluetoothSide() {
        val heard = AssistantSnapshot.Bluetooth(name = "MOMENTUM 3", company = "Sennheiser", rssi = -61, address = "B1")
        val s = snapshot(hosts = listOf(router, speaker, phone), bluetooth = listOf(heard))
        assertEquals(listOf(tr("Also heard over Bluetooth as “%s” (%d dBm): probably the same device.", "MOMENTUM 3", -61)), texts(CrossLinks.host(s, speaker, now)))
        assertEquals(listOf(tr("Probably the same device as %s on your local network.", "Momentum-3 (192.168.1.30)")), texts(CrossLinks.bluetooth(s, heard, now)))
        assertTrue(AssistantCards.bluetooth(s, "B1", now).data.contains("probably the same device as Momentum-3 (192.168.1.30)"))

        // A risky port on the LAN side is worth a warning on the Bluetooth card.
        val risky = speaker.copy(portRisks = listOf(AssistantSnapshot.PortRiskItem("Telnet: clear-text login", high = true)))
        val warned = CrossLinks.bluetooth(snapshot(hosts = listOf(risky), bluetooth = listOf(heard)), heard, now)
        assertEquals(CardRisk.WARNING, warned.first().finding?.risk)
        assertTrue(warned.first().finding!!.text.contains("Telnet"))
    }

    @Test
    fun aNameIsNoTieWhenItIsNotTheDevicesOwnOrNotTheOnlyOne() {
        // Two hosts carry the name: which of them is heard is unknown.
        val heard = AssistantSnapshot.Bluetooth(name = "Momentum 3", rssi = -61, address = "B1")
        assertNull(CrossLinks.lanTwin(snapshot(hosts = listOf(speaker, speaker.copy(ip = "192.168.1.31")), bluetooth = listOf(heard)), heard))
        // A name every phone of a kind carries.
        val iphone = AssistantSnapshot.Host(ip = "192.168.1.40", name = "iPhone", maker = "Apple")
        assertNull(CrossLinks.bluetoothTwin(snapshot(hosts = listOf(iphone), bluetooth = listOf(AssistantSnapshot.Bluetooth(name = "iPhone", rssi = -50, address = "B2"))), iphone))
        // The Bluetooth name is the maker's, not the device's own.
        val nameless = AssistantSnapshot.Bluetooth(name = "Momentum 3", rssi = -61, address = "B3", ownName = false)
        assertNull(CrossLinks.lanTwin(snapshot(hosts = listOf(speaker), bluetooth = listOf(nameless)), nameless))
        // A host named by its maker or its address alone.
        val plain = AssistantSnapshot.Host(ip = "192.168.1.50", name = "Sennheiser", maker = "Sennheiser")
        assertNull(CrossLinks.bluetoothTwin(snapshot(hosts = listOf(plain), bluetooth = listOf(AssistantSnapshot.Bluetooth(name = "Sennheiser", rssi = -50, address = "B4"))), plain))
        // The same device heard under two addresses is still one name.
        val twice = listOf(
            AssistantSnapshot.Bluetooth(name = "Momentum 3", rssi = -70, address = "B5"),
            AssistantSnapshot.Bluetooth(name = "MOMENTUM 3", rssi = -55, address = "B6"),
        )
        assertEquals("B6", CrossLinks.bluetoothTwin(snapshot(hosts = listOf(speaker), bluetooth = twice), speaker)?.address)
        // A longer name that holds the shorter one whole.
        assertTrue(CrossLinks.namesMatch("[LG] webOS TV OLED55C1", "LG webOS TV"))
        assertFalse(CrossLinks.namesMatch("TV", "TV"))
    }

    @Test
    fun aBluetoothDeviceAndANetworkOfOneNameAreTied() {
        val printer = AssistantSnapshot.Bluetooth(name = "DIRECT-7A-HP Laser", rssi = -58, address = "B1")
        val own = AssistantSnapshot.Wifi(name = "DIRECT-7A-HP Laser", bssid = "AA:BB:CC:00:00:01", rssi = -50, channel = 6, band = "2.4", security = "WPA2")
        val s = snapshot(wifi = listOf(own, home()), bluetooth = listOf(printer))
        assertEquals(
            listOf(tr("A Wi‑Fi network with the same name is on air (%s): probably this device's own access point.", "WPA2")),
            texts(CrossLinks.bluetooth(s, printer, now)),
        )
        assertTrue(
            tr("A Bluetooth device with the same name is nearby (“%s”, %d dBm): probably the one that broadcasts this network.", "DIRECT-7A-HP Laser", -58) in
                texts(CrossLinks.wifi(s, own, now)),
        )
    }

    // A Wi‑Fi network and the local network behind it.

    @Test
    fun theNetworkThePhoneIsOnShowsItsLocalNetwork() {
        val risky = camera.copy(portRisks = listOf(AssistantSnapshot.PortRiskItem("Telnet: clear-text login", high = true)))
        val ap = home(listOf(AssistantSnapshot.Station(camera.mac!!, -57), AssistantSnapshot.Station("DE:AD:BE:EF:00:01", -70)))
        val s = snapshot(hosts = listOf(router, risky, speaker, phone), wifi = listOf(ap)).copy(link = exposure(emptyList()).copy(intercepted = false))
        assertEquals(
            listOf(
                tr("Your phone is on this network: the network scan found %d devices on it.", 4),
                tr("Devices on it that need attention: %s.", "IPC-Yard (192.168.1.64)"),
                tr("The connection check on it: the internet works, HTTPS is not intercepted."),
                tr("Most likely your router's access point: %s.", "Keenetic (192.168.1.1)"),
                tr("Devices of your network on it: %s.", "IPC-Yard (192.168.1.64)"),
            ),
            texts(CrossLinks.wifi(s, ap, now)),
        )
        val facts = AssistantCards.wifi(s, ap.bssid, now)
        assertTrue(facts.findings.any { it.risk == CardRisk.WARNING && it.text.contains("IPC-Yard") })
        assertTrue(facts.data.contains("Telnet: clear-text login"))
        // The deep analysis reads the connection check made through this network.
        assertTrue(facts.deepData.contains("Latest connection check"))

        // Interception found by the connection check belongs on the network's card.
        val tapped = s.copy(link = s.link!!.copy(intercepted = true))
        assertTrue(CrossLinks.wifi(tapped, ap, now).any { it.finding?.risk == CardRisk.CRITICAL })
        // Another network nearby gets none of it.
        val other = AssistantSnapshot.Wifi(name = "Cafe", bssid = "AA:BB:CC:00:00:02", rssi = -70, channel = 1, band = "2.4", security = "Open")
        assertTrue(CrossLinks.wifi(tapped, other, now).isEmpty())
    }

    @Test
    fun stationsTheScanDidNotFindAreCounted() {
        val stations = listOf(camera.mac!!, "DE:AD:BE:EF:00:01", "DE:AD:BE:EF:00:02", "DE:AD:BE:EF:00:03").map { AssistantSnapshot.Station(it, -60) }
        val s = snapshot(hosts = listOf(router, camera, phone), wifi = listOf(home(stations)))
        // Three stations match no host; one of them may be the phone, which the scan lists without an address.
        assertEquals(2, CrossLinks.quietStations(s)?.first)
        assertEquals(
            listOf(tr("The sensor hears %d more devices on your Wi‑Fi than the network scan found: asleep, or kept apart from the phone.", 2)),
            texts(CrossLinks.lan(s, now)),
        )
        // While the scan runs its list is not whole yet.
        assertNull(CrossLinks.quietStations(s.copy(lanScanning = true)))
        // Without any hardware address from the scan there is nothing to compare by.
        assertNull(CrossLinks.quietStations(s.copy(hosts = listOf(phone, AssistantSnapshot.Host(ip = "192.168.1.5", name = "192.168.1.5")))))
        // A neighbour's network is not counted.
        assertNull(CrossLinks.quietStations(snapshot(hosts = listOf(router, camera), wifi = listOf(home(stations, current = false, name = "Neighbour")))))
    }

    // Traffic, captures, history.

    private fun flow(id: Long, to: String, port: Int = 443, tls: Boolean = true, request: String? = null) =
        TrafficFlow(id, "TCP", "10.0.0.2", 50_000, to, port, null, request, null, tls, 0, 0, 0, 1000, false)

    @Test
    fun recordedTrafficAndTheLastCaptureNameTheHost() {
        val flows = listOf(
            flow(1, camera.ip, port = 80, tls = false, request = "GET /"), flow(2, camera.ip), flow(3, "93.184.216.34"),
            flow(4, router.ip, port = 53, tls = false),
        )
        val peers = CrossLinks.localPeers(flows, setOf(router.ip, camera.ip))
        // The name lookup the router answers is not a talk with the router.
        assertEquals(listOf(AssistantSnapshot.LocalPeer(camera.ip, 2, cleartext = true)), peers)
        val s = snapshot(hosts = listOf(router, camera)).copy(
            localPeers = peers, captureName = "Home.pcap", captureTalkers = listOf(AssistantSnapshot.CaptureTalker("44:19:b6:00:00:64", 310)),
        )
        assertEquals(
            listOf(
                tr("The phone talked to it in the recorded traffic: %d connections, some unencrypted.", 2),
                tr("In the last capture (%s): %d packets from or to it.", "Home.pcap", 310),
            ),
            texts(CrossLinks.host(s, camera, now)),
        )
        // The recording's own card names the device behind the address.
        val local = mapOf(camera.ip to "IPC-Yard · Hikvision")
        val card = AssistantCards.traffic(flows, "x", "d", local)
        assertTrue(card.findings.any { it.text == tr("The phone talked to devices on your network: %s.", "IPC-Yard · Hikvision (192.168.1.64)") })
        assertTrue(card.data.contains("IPC-Yard · Hikvision (192.168.1.64) ×2, plain HTTP"))
        // One connection's card says the server is a device of the network.
        val context = TrafficCards.Context(null, flows, local = local)
        val connection = TrafficCards.connection(flows[1], context)
        assertTrue(connection.findings.any { it.text == tr("The server is a device on your network: %s (%s).", "IPC-Yard · Hikvision", camera.ip) })
        assertTrue(connection.data.contains("found by the LAN scan: IPC-Yard · Hikvision (192.168.1.64)"))
        assertNull(TrafficCards.localTie(flows[2], context))
    }

    @Test
    fun aDeviceMetLongAgoSaysSinceWhen() {
        val old = camera.copy(firstSeen = now - 40L * 24 * 3600 * 1000)
        assertEquals(listOf(tr("First seen on %s.", CrossLinks.day(old.firstSeen!!))), texts(CrossLinks.host(snapshot(hosts = listOf(old)), old, now)))
        // Met today, or flagged as new: nothing to add.
        val fresh = camera.copy(firstSeen = now - 3_600_000)
        assertTrue(CrossLinks.host(snapshot(hosts = listOf(fresh)), fresh, now).isEmpty())
        val flagged = old.copy(isNew = true)
        assertTrue(CrossLinks.host(snapshot(hosts = listOf(flagged)), flagged, now).isEmpty())
    }

    // On a card.

    @Test
    fun aCardShowsAtMostFourTiesAndTheModelReadsThemAll() {
        val links = (1..6).map { CrossLink(CardFinding(if (it == 6) CardRisk.CRITICAL else CardRisk.INFO, "t$it"), "l$it") }
        assertEquals(listOf("t6", "t1", "t2", "t3"), CrossLinks.findings(links).map { it.text })
        assertEquals(7, CrossLinks.text(links).lines().size)
        assertTrue(CrossLinks.text(emptyList()).isEmpty() && CrossLinks.block(emptyList()).isEmpty())
        // A card with nothing tied reads as before: no empty heading.
        assertFalse(AssistantCards.host(snapshot(hosts = listOf(camera)), camera.ip, now).data.contains(CrossLinks.HEADING))
        // The model is told what the ties are.
        assertTrue(CardPrompts.system(CardAction.SUMMARY, CardSubject.Kind.HOST).contains("Ties to the app's other screens"))
    }

    @Test
    fun aRoutersAccessPointIsKnownByItsAddress() {
        assertTrue(CrossLinks.near("50:FF:20:11:22:30", "52:FF:20:11:22:31"))
        assertTrue(CrossLinks.near("50:FF:20:11:22:30", "50:FF:20:11:22:30"))
        assertFalse(CrossLinks.near("50:FF:20:11:22:30", "50:FF:20:11:23:30"))
        assertFalse(CrossLinks.near("50:FF:20:11:22:30", "not an address"))
    }
}
