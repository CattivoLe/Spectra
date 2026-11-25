package cattivole.spectra

import cattivole.spectra.core.DiscoveredPort
import cattivole.spectra.core.LanDevice
import cattivole.spectra.core.MacAddress
import cattivole.spectra.core.MemoryTextStore
import cattivole.spectra.core.WebLink
import cattivole.spectra.core.WebLinkColor
import cattivole.spectra.core.WebLinkIcon
import cattivole.spectra.core.WebLinkNetwork
import cattivole.spectra.core.WebLinks
import cattivole.spectra.ui.vector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebLinksTest {
    private val home = WebLinkNetwork("lan:gw:AA:BB:CC:00:00:FF", listOf("lan:gw:AA:BB:CC:00:00:FF", "lan:net:192.168.1.0/24"), "Home")

    private fun host(ip: String, mac: String? = null, name: String? = null, ports: List<Int> = listOf(80)) = LanDevice(
        ipAddress = ip,
        macAddress = mac?.let(MacAddress::parse),
        hostnames = listOfNotNull(name),
        openPorts = ports.map { DiscoveredPort(it, DiscoveredPort.serviceName(it)) },
        portsScanned = true,
    )

    @Test
    fun keptAndDroppedAndPersisted() {
        val store = MemoryTextStore()
        val links = WebLinks(store, icon = { "Rounded.Storage" })
        val nas = host("192.168.1.10", "AA:BB:CC:00:00:01", "nas.local", listOf(443, 5000))
        assertTrue(links.toggle(nas, 5000, home))
        assertTrue(links.toggle(nas, 443, home))
        assertEquals(listOf("http://192.168.1.10:5000", "https://192.168.1.10"), links.links.map { it.address })
        assertEquals(listOf(nas.displayName), links.titles)

        val again = WebLinks(store)
        assertEquals(links.links, again.links)
        assertFalse(again.toggle(nas, 5000, home))
        assertEquals(listOf(443), again.links.map { it.port })
        assertNotNull(again.link(nas, 443, home))
    }

    @Test
    fun portWithoutAWebPageIsNotKept() {
        val links = WebLinks()
        assertFalse(links.toggle(host("192.168.1.10", ports = listOf(22)), 22, home))
        assertTrue(links.links.isEmpty())
    }

    @Test
    fun followsTheDeviceToANewAddress() {
        val links = WebLinks()
        links.toggle(host("192.168.1.10", "AA:BB:CC:00:00:01", "nas.local", listOf(5000)), 5000, home)
        links.edit(links.links.first().id, "", "http://192.168.1.10:5000/admin?x=1", null, null)
        val other = host("192.168.1.10", "AA:BB:CC:00:00:02", "tv.local")
        val moved = host("192.168.1.77", "AA:BB:CC:00:00:01", "nas.local", listOf(5000))
        links.follow(listOf(other, moved), home)
        assertEquals("http://192.168.1.77:5000/admin?x=1", links.links.first().address)
        assertNotNull(links.link(moved, 5000, home))
        assertNull(links.link(other, 5000, home))
    }

    @Test
    fun twoDevicesSwapAddresses() {
        val links = WebLinks()
        links.toggle(host("192.168.1.10", name = "nas.local"), 80, home)
        links.toggle(host("192.168.1.20", name = "camera.local"), 80, home)
        links.follow(listOf(host("192.168.1.20", name = "nas.local"), host("192.168.1.10", name = "camera.local")), home)
        assertEquals(listOf("http://192.168.1.20", "http://192.168.1.10"), links.links.map { it.address })
    }

    @Test
    fun namesakeWithItsOwnLinkDoesNotTakeOver() {
        val links = WebLinks()
        links.toggle(host("192.168.1.10", name = "plug.local"), 80, home)
        links.toggle(host("192.168.1.11", name = "plug.local"), 80, home)
        links.follow(listOf(host("192.168.1.11", name = "plug.local")), home)
        assertEquals(listOf("192.168.1.10", "192.168.1.11"), links.links.map { it.ip })
    }

    @Test
    fun anotherNetworkLeavesTheLinkAlone() {
        val links = WebLinks()
        links.toggle(host("192.168.1.1", name = "router.lan"), 80, home)
        val elsewhere = WebLinkNetwork("lan:net:10.0.0.0/24", listOf("lan:net:10.0.0.0/24"), "Office")
        assertNull(links.link(host("192.168.1.1", name = "router.lan"), 80, elsewhere))
        links.follow(listOf(host("10.0.0.1", name = "router.lan")), elsewhere)
        assertEquals("http://192.168.1.1", links.links.first().address)
        assertEquals("Home", links.links.first().networkLabel)
    }

    @Test
    fun keepsTheUsersNameAndTakesTheDevices() {
        val links = WebLinks()
        val device = host("192.168.1.10", name = "esp.local")
        links.toggle(device, 80, home)
        device.customName = "Thermostat"
        links.follow(listOf(device), home)
        assertEquals("Thermostat", links.links.first().title)
        links.edit(links.links.first().id, " Heating ", "thermo.local/status", null, null)
        links.follow(listOf(host("192.168.1.30", name = "esp.local")), home)
        assertEquals("Heating", links.links.first().title)
        assertEquals("http://thermo.local/status", links.links.first().address)
    }

    @Test
    fun pickedIconStaysAndAutoBringsTheDevicesBack() {
        val store = MemoryTextStore()
        val links = WebLinks(store, icon = { "Rounded.Storage" })
        val nas = host("192.168.1.10", name = "nas.local")
        links.toggle(nas, 80, home)
        val link = links.links.first()
        links.edit(link.id, "", link.address, "heart", WebLinkColor.PINK)
        links.follow(listOf(nas), home)
        assertEquals("heart", WebLinks(store).links.first().customIcon)
        assertEquals("pink", WebLinks(store).links.first().color)
        assertEquals("Rounded.Storage", WebLinks(store).links.first().icon)
        links.edit(link.id, "", link.address, null, null)
        assertNull(WebLinks(store).links.first().customIcon)
    }

    @Test
    fun orderChangesWithinANetworkOnly() {
        val store = MemoryTextStore()
        val links = WebLinks(store)
        val office = WebLinkNetwork("lan:net:10.0.0.0/24", listOf("lan:net:10.0.0.0/24"), "Office")
        links.toggle(host("192.168.1.10", name = "nas.local"), 80, home)
        links.toggle(host("10.0.0.1", name = "printer.local"), 80, office)
        links.toggle(host("192.168.1.20", name = "camera.local"), 80, home)
        links.toggle(host("192.168.1.30", name = "tv.local"), 80, home)
        val ids = links.links.map { it.id }
        val order = listOf(ids[3], ids[1], ids[0], ids[2])
        links.reorder(listOf(ids[3], ids[0], ids[2]))
        assertEquals(order, links.links.map { it.id })
        assertEquals(order, WebLinks(store).links.map { it.id })
        links.reorder(listOf("unknown", ids[0]))
        assertEquals(order, links.links.map { it.id })
    }

    @Test
    fun oneNetworkIsOneGroupWhateverItIsCalled() {
        val links = WebLinks()
        val keys = listOf("lan:gw:1", "lan:net:192.168.1.0/24")
        val unnamed = WebLinkNetwork("lan:gw:1", keys, "192.168.1.0/24")
        val named = WebLinkNetwork("lan:gw:1", keys, "Home")
        val office = WebLinkNetwork("lan:gw:2", listOf("lan:gw:2"), "Office")
        links.toggle(host("10.0.0.1", name = "printer.local"), 80, office)
        links.toggle(host("192.168.1.10", name = "nas.local"), 80, named)
        links.toggle(host("192.168.1.20", name = "camera.local"), 80, unnamed)
        val groups = links.groups(unnamed)
        assertEquals(listOf("Home", "Office"), groups.map { it.label })
        assertEquals(listOf("192.168.1.10", "192.168.1.20"), groups.first().links.map { it.ip })
        links.follow(listOf(host("192.168.1.10", name = "nas.local")), unnamed)
        assertEquals("Home", links.links[1].networkLabel)
        assertEquals(listOf("Office", "Home"), links.groups(WebLinkNetwork(null, emptyList(), null)).map { it.label })
    }

    @Test
    fun iconsAreSuggestedByNameThenHintsThenPort() {
        fun ids(vararg texts: String, port: Int = 80, own: String? = null) =
            WebLinkIcon.suggestions(texts.toList(), port, own).map { it.id }
        assertEquals(listOf("storage", "server", "media", "download"), ids("", "DiskStation"))
        assertEquals(listOf("camera", "storage"), ids("Камера во дворе", "nas").take(2))
        assertEquals("camera", ids("", "unknown", "Network Camera").first())
        assertEquals("smartHome", ids("", "host", port = 8123).first())
        assertEquals("camera", ids("cam2").first())
        assertEquals(listOf("laptop", "computer", "terminal"), ids("MacBook-Air pixel activity"))
        assertEquals("smartHome", ids("Home Assistant").first())
        assertTrue("shield" in ids("Pi-hole"))
        assertEquals(listOf("server", "media", "download"), ids("nas", own = "storage"))
        assertEquals(listOf("sensor", "plug", "smartHome"), ids("host", own = "board"))
        assertEquals(listOf("smartHome", "server", "web"), ids("host"))
        assertEquals(5, ids("router nas camera lock light plug fan water").size)
    }

    @Test
    fun everyIconHasAPictureOfItsOwn() {
        assertEquals(WebLinkIcon.all.size, WebLinkIcon.all.map { it.id }.toSet().size)
        assertTrue(WebLinkIcon.all.all { icon -> icon.related.all { WebLinkIcon.with(it) != null } })
        assertEquals(WebLinkIcon.all.size, WebLinkIcon.all.map { it.vector().name }.toSet().size)
    }

    @Test
    fun typedAddresses() {
        assertEquals("http://192.168.1.1:8080/ui", WebLink.normalized(" 192.168.1.1:8080/ui "))
        assertEquals("https://nas.local", WebLink.normalized("https://nas.local"))
        assertEquals("http://my_nas:81", WebLink.normalized("my_nas:81"))
        for (bad in listOf("ftp://nas.local", "javascript:alert(1)", "http://", "my nas", "")) {
            assertNull(bad, WebLink.normalized(bad))
        }
    }

    @Test
    fun brokenStoreIsEmpty() {
        val store = MemoryTextStore().apply { write("webLinks", "not json") }
        assertTrue(WebLinks(store).links.isEmpty())
    }
}
