package alexcattivo.localchat.kit.web

import alexcattivo.localchat.kit.SettingsStore
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.net.URI
import java.util.Locale

/** Хранилище секретов (ключ поискового сервиса). На телефоне — под защитой Keystore, в тестах — память. */
interface SecretStore {
    fun read(account: String): String?
    fun write(value: String?, account: String)
}

/**
 * Настройки доступа модели в интернет. По умолчанию всё выключено: приложение работает только на устройстве.
 * Наружу уходит одно — короткий поисковый запрос; диалог, профиль и сведения об устройстве не передаются.
 */
class WebAccess(private val settings: SettingsStore, private val secrets: SecretStore) {
    /** The host app's rule for the query planner: which questions it answers from its own data, without the web. */
    var plannerNote: String? = null
    enum class ProviderKind(val title: String) { Searxng("SearXNG"), Brave("Brave Search") }

    private fun <T> setting(initial: T, save: (T) -> Unit) = Persisted(initial, save)

    /** Значение, которое видно Compose и сразу сохраняется. */
    inner class Persisted<T>(initial: T, private val save: (T) -> Unit) {
        var value by mutableStateOf(initial)
            private set
        fun set(newValue: T) { value = newValue; save(newValue) }
    }

    private val enabledState = setting(settings.getBoolean(Keys.ENABLED, false)) { settings.putBoolean(Keys.ENABLED, it) }
    var isEnabled: Boolean get() = enabledState.value; set(value) = enabledState.set(value)

    private val providerState = setting(ProviderKind.entries.firstOrNull { it.name.lowercase() == settings.getString(Keys.PROVIDER) } ?: ProviderKind.Searxng) {
        settings.putString(Keys.PROVIDER, it.name.lowercase())
    }
    var provider: ProviderKind get() = providerState.value; set(value) = providerState.set(value)

    /** Язык поиска («ru», «en» или «auto»): с явным языком поисковики отвечают точнее. */
    private val languageState = setting(settings.getString(Keys.LANGUAGE) ?: if (Locale.getDefault().language == "en") "en" else "ru") {
        settings.putString(Keys.LANGUAGE, it)
    }
    var searchLanguage: String get() = languageState.value; set(value) = languageState.set(value)

    private val addressState = setting(settings.getString(Keys.SEARXNG) ?: "") { settings.putString(Keys.SEARXNG, it) }
    var searxngAddress: String get() = addressState.value; set(value) = addressState.set(value)

    private val countState = setting(settings.getInt(Keys.COUNT, 3).takeIf { it in 1..5 } ?: 3) { settings.putInt(Keys.COUNT, it) }
    var resultCount: Int get() = countState.value; set(value) = countState.set(value)

    /** Показывать запрос перед отправкой и давать его поправить. */
    private val confirmState = setting(settings.getBoolean(Keys.CONFIRM, true)) { settings.putBoolean(Keys.CONFIRM, it) }
    var confirmEachSearch: Boolean get() = confirmState.value; set(value) = confirmState.set(value)

    private val braveState = setting(secrets.read(BRAVE_ACCOUNT) ?: "") { secrets.write(it, BRAVE_ACCOUNT) }
    var braveKey: String get() = braveState.value; set(value) = braveState.set(value)

    /** Читать найденные страницы целиком, а не только короткие описания из выдачи. */
    private val readPagesState = setting(settings.getBoolean(Keys.READ_PAGES, true)) { settings.putBoolean(Keys.READ_PAGES, it) }
    var readPages: Boolean get() = readPagesState.value; set(value) = readPagesState.set(value)

    private val pagesState = setting(settings.getInt(Keys.PAGES, 3).takeIf { it in 1..5 } ?: 3) { settings.putInt(Keys.PAGES, it) }
    var pagesToRead: Int get() = pagesState.value; set(value) = pagesState.set(value)

    /** Отбирать куски страниц по смыслу (если установлена модель эмбеддингов), а не только по словам. */
    private val meaningState = setting(settings.getBoolean(Keys.MEANING, true)) { settings.putBoolean(Keys.MEANING, it) }
    var useMeaning: Boolean get() = meaningState.value; set(value) = meaningState.set(value)

    /** Если в найденном нет ответа, поискать ещё раз другим запросом (один повтор). */
    private val retryState = setting(settings.getBoolean(Keys.RETRY, true)) { settings.putBoolean(Keys.RETRY, it) }
    var retryIfMissing: Boolean get() = retryState.value; set(value) = retryState.set(value)

    /** Составлять запрос моделью с учётом разговора и пропускать поиск, когда он не нужен. */
    private val smartState = setting(settings.getBoolean(Keys.SMART, true)) { settings.putBoolean(Keys.SMART, it) }
    var smartQuery: Boolean get() = smartState.value; set(value) = smartState.set(value)

    /** В голосовом разговоре искать без окна подтверждения: показать его там некому. Запросы всё равно пишутся в журнал. */
    private val voiceFreeState = setting(settings.getBoolean(Keys.VOICE_FREE, true)) { settings.putBoolean(Keys.VOICE_FREE, it) }
    var voiceSearchWithoutConfirmation: Boolean get() = voiceFreeState.value; set(value) = voiceFreeState.set(value)

    /** Модель для отбора по смыслу. Задаёт приложение, когда она установлена. */
    var embedder: PassageEmbedder? = null

    /** Спрашивает пользователя перед отправкой запроса. Возвращает запрос (возможно, исправленный) или `null` при отказе. */
    var approve: (suspend (String) -> String?)? = null

    /** Как читать страницы: в приложении — сеть, в тестах — заготовки. */
    var fetcher: PageFetching = PageFetcher()
    /** Как получить поисковик: в тестах подменяется. */
    var providerFactory: ((WebAccess) -> SearchProvider)? = null

    fun needsConfirmation(voiceMode: Boolean) = confirmEachSearch && !(voiceMode && voiceSearchWithoutConfirmation)

    /**
     * Облегчённый поиск в голосовом разговоре: на телефоне без расчёта на видеокарте полный поиск с проверкой
     * занимал до полутора минут, а в разговоре ответ должен начаться быстро. Меньше страниц и найденного в промпте.
     */
    var lightInVoice = true

    /** Полный поиск: выдача, чтение страниц и отбор кусков под вопрос. `light` — облегчённый, для голоса. */
    fun makeResearch(light: Boolean = false): WebResearch = WebResearch(
        provider = makeProvider(),
        fetcher = fetcher,
        pagesToRead = if (!readPages) 0 else if (light) minOf(pagesToRead, 2) else pagesToRead,
        // Для чтения страниц полезно больше кандидатов: лишнее отсеет отбор кусков.
        resultCount = if (readPages) (if (light) 4 else 5) else resultCount,
        // Меньше, чем в iOS (3600): на Android модель считается на процессоре, и каждый токен промпта дорог.
        budget = if (light) 2000 else 2400,
        embedder = if (useMeaning) embedder else null,
    )

    /** Что мешает поиску работать; `null` — всё настроено. */
    val configurationProblem: NotConfiguredReason?
        get() = when (provider) {
            ProviderKind.Searxng -> if (validate(searxngAddress) == null) NotConfiguredReason.SearxngAddress else null
            ProviderKind.Brave -> if (braveKey.isBlank()) NotConfiguredReason.BraveKey else null
        }

    fun makeProvider(): SearchProvider {
        providerFactory?.let { return it(this) }
        configurationProblem?.let { throw WebSearchException.NotConfigured(it) }
        return when (provider) {
            ProviderKind.Searxng -> SearXNGProvider(validate(searxngAddress)!!, searchLanguage)
            ProviderKind.Brave -> BraveProvider(braveKey.trim())
        }
    }

    private object Keys {
        const val ENABLED = "web.enabled"
        const val PROVIDER = "web.provider"
        const val LANGUAGE = "web.language"
        const val SEARXNG = "web.searxng"
        const val COUNT = "web.count"
        const val CONFIRM = "web.confirm"
        const val READ_PAGES = "web.readPages"
        const val PAGES = "web.pagesToRead"
        const val MEANING = "web.useMeaning"
        const val RETRY = "web.retryIfMissing"
        const val SMART = "web.smartQuery"
        const val VOICE_FREE = "web.voiceWithoutConfirmation"
    }

    companion object {
        private const val BRAVE_ACCOUNT = "brave-key"

        /**
         * Адрес сервера: только https. Обычный http допускается лишь для своей сети (localhost, *.local, частные адреса),
         * иначе запросы шли бы по интернету открытым текстом.
         */
        fun validate(address: String): String? {
            val trimmed = address.trim()
            val uri = runCatching { URI(trimmed) }.getOrNull() ?: return null
            val host = uri.host ?: return null
            if (host.isEmpty()) return null
            return when (uri.scheme) {
                "https" -> trimmed
                "http" -> if (isLocal(host)) trimmed else null
                else -> null
            }
        }

        fun isLocal(host: String): Boolean {
            if (host == "localhost" || host.endsWith(".local")) return true
            val parts = host.split(".").mapNotNull { it.toIntOrNull() }
            if (parts.size != 4) return false
            return parts[0] == 10 || parts[0] == 127 || (parts[0] == 192 && parts[1] == 168) || (parts[0] == 172 && parts[1] in 16..31)
        }
    }
}
