package alexcattivo.localchat.kit

import alexcattivo.localchat.engine.ChatMessage
import java.util.UUID

/** Где хранятся настройки (веб-поиск): на телефоне — SharedPreferences, в тестах — память. */
interface SettingsStore {
    fun getBoolean(key: String, default: Boolean): Boolean
    fun putBoolean(key: String, value: Boolean)
    fun getInt(key: String, default: Int): Int
    fun putInt(key: String, value: Int)
    fun getString(key: String): String?
    fun putString(key: String, value: String?)
}

/** Сохранённая сводка (или диалог прежних версий). В `systemPrompt` Spectra хранит, о чём сводка. */
data class Chat(
    val id: UUID,
    val title: String,
    val systemPrompt: String,
    val createdAt: Long,
    val updatedAt: Long,
    /** Название модели, с которой диалог вёлся в последний раз. */
    val modelName: String? = null,
    /** The folder the chat was put in; `null` — not in any. */
    val folderID: UUID? = null,
) {
    companion object {
        const val DEFAULT_SYSTEM_PROMPT = ""
    }
}

/** A folder of summaries, made by the user. */
data class ChatFolder(val id: UUID, val name: String, val createdAt: Long)

/** Одно сообщение диалога. */
data class StoredMessage(
    val id: UUID,
    val chatID: UUID,
    /** Порядковый номер в диалоге: по нему сообщения упорядочиваются и обрезаются. */
    val seq: Int,
    val role: ChatMessage.Role,
    val content: String,
    val createdAt: Long,
    /** Ответ оборван пользователем, нехваткой памяти или длиной. */
    val interrupted: Boolean = false,
    /** Какая модель написала ответ. */
    val modelName: String? = null,
    /** Что нашлось в интернете по этому вопросу (JSON), у сообщения пользователя. */
    val searchJson: String? = null,
)

/** Запись журнала: что именно было отправлено в интернет и куда. Видна в настройках. */
data class SearchLogEntry(val id: Long, val date: Long, val provider: String, val query: String, val succeeded: Boolean)
