package alexcattivo.localchat.kit

import alexcattivo.localchat.kit.tools.ToolCalls
import alexcattivo.localchat.kit.tools.ToolOffer
import org.junit.Assert.assertEquals
import org.junit.Test

class ToolOfferTest {
    private val offer: (String) -> ToolOffer? = { name ->
        when (name) {
            "check_connection" -> ToolOffer(name, "проверку соединения", "Проверить соединение?", "Проверь интернет-соединение")
            "scan_lan" -> ToolOffer(name, "сканирование сети", "Просканировать сеть?", "Просканируй сеть")
            "capture_pcap" -> ToolOffer(name, "запись трафика")
            else -> null
        }
    }

    @Test
    fun toolNamesBecomeWordsAndOffers() {
        val (text, offers) = ToolCalls.humanize(
            "Интернет: без данных (выполни `check_connection`). Устройств нет — запусти **scan_lan**, потом снова scan_lan; " +
                "при необходимости capture_pcap. Поле wifi_name не трогать.",
            offer,
        )
        assertEquals(
            "Интернет: без данных (выполни проверку соединения). Устройств нет — запусти сканирование сети, потом снова " +
                "сканирование сети; при необходимости запись трафика. Поле wifi_name не трогать.",
            text,
        )
        // One offer per check, in the order mentioned; a tool without a request is only reworded.
        assertEquals(listOf("check_connection", "scan_lan"), offers.map { it.name })
    }

    @Test
    fun aCheckThatAlreadyRanIsNotOfferedAgain() {
        val (text, offers) = ToolCalls.humanize("Сканирование готово; для деталей снова запусти scan_lan.", offer, ran = listOf("scan_lan"))
        assertEquals("Сканирование готово; для деталей снова запусти сканирование сети.", text)
        assertEquals(emptyList<ToolOffer>(), offers)
    }
}
