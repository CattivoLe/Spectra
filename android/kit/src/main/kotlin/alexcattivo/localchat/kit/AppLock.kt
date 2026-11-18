package alexcattivo.localchat.kit

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Проверка владельца телефона: отпечаток, лицо или PIN-код экрана блокировки. */
interface Authenticating {
    /** Можно ли вообще проверить владельца (настроена ли блокировка экрана). */
    fun isAvailable(): Boolean
    suspend fun authenticate(reason: AuthReason): Boolean
}

/** Зачем спрашиваем: текст для системного окна составляет приложение. */
enum class AuthReason { Unlock, Enable, Disable }

/** Где хранятся настройки блокировки. На телефоне — SharedPreferences, в тестах — память. */
interface SettingsStore {
    fun getBoolean(key: String, default: Boolean): Boolean
    fun putBoolean(key: String, value: Boolean)
    fun getInt(key: String, default: Int): Int
    fun putInt(key: String, value: Int)
    fun getString(key: String): String?
    fun putString(key: String, value: String?)
}

/**
 * Блокировка приложения. Это защита от того, кто взял разблокированный телефон: данные на диске
 * шифруются отдельно, ключом из Keystore, и от этой блокировки не зависят. Повторяет AppLock.swift.
 */
class AppLock(
    private val authenticator: Authenticating,
    private val settings: SettingsStore,
    private val now: () -> Long = System::currentTimeMillis,
) {
    enum class GracePeriod(val seconds: Int) { Immediately(0), OneMinute(60), FiveMinutes(300) }

    var isEnabled by mutableStateOf(settings.getBoolean(ENABLED, false))
        private set
    /** При холодном запуске включённая блокировка сразу закрывает приложение. */
    var isLocked by mutableStateOf(isEnabled)
        private set
    var isAuthenticating by mutableStateOf(false)
        private set

    private var graceState by mutableStateOf(GracePeriod.entries.firstOrNull { it.seconds == settings.getInt(GRACE, 0) } ?: GracePeriod.Immediately)
    var gracePeriod: GracePeriod
        get() = graceState
        set(value) { graceState = value; settings.putInt(GRACE, value.seconds) }

    private var hideState by mutableStateOf(settings.getBoolean(HIDE, true))
    /** Закрывать содержимое в списке недавних приложений. */
    var hideInSwitcher: Boolean
        get() = hideState
        set(value) { hideState = value; settings.putBoolean(HIDE, value) }

    private var backgroundedAt: Long? = null

    val canAuthenticate: Boolean get() = authenticator.isAvailable()

    // MARK: - Жизненный цикл

    fun appDidEnterBackground() {
        backgroundedAt = now()
    }

    fun appDidBecomeActive() {
        val since = backgroundedAt
        backgroundedAt = null
        if (!isEnabled || isLocked || since == null) return
        if (now() - since >= gracePeriod.seconds * 1000L) isLocked = true
    }

    // MARK: - Действия

    suspend fun unlock() {
        if (!isLocked || isAuthenticating) return
        isAuthenticating = true
        try {
            if (authenticator.authenticate(AuthReason.Unlock)) isLocked = false
        } finally {
            isAuthenticating = false
        }
    }

    /** Включает блокировку. Сначала проверяет владельца, чтобы не включить то, что потом не открыть. */
    suspend fun enable(): Boolean {
        if (!canAuthenticate || !authenticator.authenticate(AuthReason.Enable)) return false
        isEnabled = true
        settings.putBoolean(ENABLED, true)
        return true
    }

    /** Выключить блокировку может только владелец. */
    suspend fun disable(): Boolean {
        if (!authenticator.authenticate(AuthReason.Disable)) return false
        isEnabled = false
        isLocked = false
        settings.putBoolean(ENABLED, false)
        return true
    }

    private companion object {
        const val ENABLED = "lock.enabled"
        const val GRACE = "lock.grace"
        const val HIDE = "lock.hideInSwitcher"
    }
}
