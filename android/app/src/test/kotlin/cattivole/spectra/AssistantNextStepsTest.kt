package cattivole.spectra

import cattivole.spectra.assistant.AssistantActions
import cattivole.spectra.assistant.AssistantActions.PortTarget
import cattivole.spectra.assistant.AssistantText
import cattivole.spectra.assistant.AssistantTopic
import cattivole.spectra.core.BleDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/** Next steps as buttons, service tags out of answers, chat topics and picture questions. */
class AssistantNextStepsTest {
    private val hosts = listOf(PortTarget("192.168.1.1", true), PortTarget("192.168.1.64", false))
    private val momentum = BleDevice("C4:00:00:00:00:01", "Random static", -60, 0).apply { localName = "Momentum 3" }

    private fun <T> inEachLanguage(block: (Locale) -> T) {
        val original = Locale.getDefault()
        try {
            for (locale in listOf(Locale.ENGLISH, Locale.forLanguageTag("ru"))) {
                Locale.setDefault(locale)
                block(locale)
            }
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test
    fun aHostWithEveryPortCheckedGetsNoScanButton() {
        val scanned = listOf(PortTarget("192.168.1.1", true), PortTarget("192.168.1.95", false, allScanned = true))
        val answer = "Просканируйте все порты на `192.168.1.95`, чтобы убедиться."
        assertTrue(AssistantActions.suggestedOffers(answer, ran = emptyList(), devices = emptyList(), hosts = scanned).isEmpty())
        val unscanned = listOf(PortTarget("192.168.1.95", false))
        assertEquals(1, AssistantActions.suggestedOffers(answer, ran = emptyList(), devices = emptyList(), hosts = unscanned).size)
    }

    @Test
    fun adviceInWordsBecomesButtons() {
        val answer = """
            Сканирование показало 5 устройств, Wi‑Fi в порядке.
            На `192.168.1.64` открыт Telnet — проверьте открытые порты на `192.168.1.64`.
            Рекомендую замерить скорость интернета вечером.
            Если хотите, могу подключиться к Momentum 3 и прочитать его прошивку.
        """.trimIndent()
        val offers = AssistantActions.suggestedOffers(answer, ran = emptyList(), devices = listOf(momentum), hosts = hosts)
        // Last advice first: the closing offer is where the answer leads.
        assertEquals(listOf("connect_bluetooth", "speed_test", "scan_ports"), offers.map { it.name })
        assertTrue(offers.all { it.button != null && it.request != null })
    }

    @Test
    fun reportsAndChecksThatRanAreNotOffered() {
        val answer = "Я просканировал сеть: 5 устройств. Сканирование Bluetooth показало наушники. Просканируйте сеть ещё раз вечером."
        val offers = AssistantActions.suggestedOffers(answer, ran = listOf("scan_lan"), devices = emptyList(), hosts = hosts)
        assertEquals(emptyList<String>(), offers.map { it.name })
        assertTrue(AssistantActions.suggestedOffers("Run a speed test in the evening.", emptyList(), emptyList(), hosts).map { it.name } == listOf("speed_test"))
        assertFalse(AssistantActions.advises("The scan found five devices."))
    }

    @Test
    fun everySuggestedButtonRunsItsCheckInANewChat() = inEachLanguage { locale ->
        // A next step opens a clean chat with the button's request: the preflight there must recognize it.
        val answer = "Проверьте порты на 192.168.1.64. Check all ports of 192.168.1.1. Подключитесь к Momentum 3 и прочитайте данные."
        val offers = AssistantActions.suggestedOffers(answer, emptyList(), listOf(momentum), hosts)
        assertEquals(3, offers.size)
        for (offer in offers) {
            val calls = AssistantActions.preflightCalls(offer.request!!, listOf(momentum), hosts)
            assertTrue("${locale.language}: «${offer.request}» runs $calls", calls.any { it.contains(offer.name) })
        }
    }

    @Test
    fun serviceTagsLeaveTheAnswer() {
        assertEquals("По данным Spectra: всё спокойно.", AssistantText.polish("По состоянию на <app_data>: всё спокойно."))
        assertEquals("Этого нет в данных Spectra.", AssistantText.polish("Этого нет в `<app_data>`."))
        assertEquals("Судя по данным: ок, данные Spectra", AssistantText.polish("Судя по данным: ок, </app_data>"))
        assertEquals("According to Spectra's data, fine.", AssistantText.polish("According to the <app_data>, fine."))
        assertEquals("Ответ без тегов: 1 < 2", AssistantText.polish("Ответ без тегов: 1 < 2"))
    }

    @Test
    fun chatTopicsComeFromTheFirstQuestion() {
        assertEquals(listOf(AssistantTopic.LAN), AssistantText.topics("Проанализируй устройства в моей локальной сети"))
        assertEquals(listOf(AssistantTopic.BLUETOOTH), AssistantText.topics("Что за Bluetooth-устройство «Tile»"))
        assertEquals(listOf(AssistantTopic.WIFI), AssistantText.topics("Какой канал Wi‑Fi свободнее?"))
        assertEquals(listOf(AssistantTopic.CAPTURE), AssistantText.topics("Decode this traffic capture"))
        assertEquals(emptyList<AssistantTopic>(), AssistantText.topics("Привет"))
    }

    @Test
    fun theClosingOfferWinsOverEarlierMentions() {
        val answer = "Проверьте MAC в настройках Wi‑Fi и блютуз. Нужно что-то ещё? Например, проверить подключение к интернету?"
        val offers = AssistantActions.suggestedOffers(answer, ran = listOf("scan_bluetooth"), devices = emptyList(), hosts = hosts)
        assertEquals("check_connection", offers.first().name)
        assertFalse(offers.any { it.name == "scan_bluetooth" })
    }

    @Test
    fun deviceQuestionsGetPicturesToo() {
        assertTrue(AssistantText.asksWhatDeviceIs("Что за Bluetooth-устройство «Tile» (C4:00:00:00:00:01): какого оно типа?"))
        assertTrue(AssistantText.asksWhatDeviceIs("Что за устройство Xiaomi (192.168.1.1) в моей локальной сети?"))
        assertFalse(AssistantText.asksWhatDeviceIs("Просканируй локальную сеть"))
    }

    @Test
    fun pictureQuestions() {
        for (q in listOf("Как выглядит эта метка?", "покажи фото роутера", "What does an AirTag look like?")) assertTrue(q, AssistantText.asksForPicture(q))
        for (q in listOf("Просканируй сеть", "Что такое WPS?", "Обнови образ прошивки")) assertFalse(q, AssistantText.asksForPicture(q))
    }
}
