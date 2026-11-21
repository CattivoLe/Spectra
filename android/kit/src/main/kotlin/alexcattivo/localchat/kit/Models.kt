package alexcattivo.localchat.kit

import alexcattivo.localchat.engine.ChatMessage
import alexcattivo.localchat.engine.GenerationParams
import java.util.UUID

/** Диалог. Системный промпт хранится копией: на случай, если персонажа удалят. */
data class Chat(
    val id: UUID,
    val title: String,
    val systemPrompt: String,
    val createdAt: Long,
    val updatedAt: Long,
    /** Персонаж диалога. Пока он есть, его промпт и параметры главнее копии выше. */
    val personaID: UUID? = null,
    /** Название модели, с которой диалог вёлся в последний раз. */
    val modelName: String? = null,
    /** The folder the chat was put in; `null` — not in any. */
    val folderID: UUID? = null,
) {
    companion object {
        const val DEFAULT_SYSTEM_PROMPT = ""
    }
}

/** A folder of chats, made by the user. */
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
) {
    val asChatMessage: ChatMessage get() = ChatMessage(role, content)
}

/** Персонаж: характер собеседника. Это данные, а не код: промпт, температура и длина ответов. */
/**
 * Facts from the host app (Wi-Fi scan, LAN, Bluetooth, history, connection check) that go in front of a question.
 * The assistant reads them from the phone; nothing here ever leaves the device.
 */
data class AppDataAttachment(
    /** Short names of what was attached, shown under the answer ("Wi‑Fi", "LAN"). */
    val topics: List<String>,
    /** The text placed ahead of the question. */
    val text: String,
)

data class Persona(
    val id: UUID = UUID.randomUUID(),
    val name: String,
    val avatar: String = "💬",
    val systemPrompt: String,
    val temperature: Double = 0.7,
    val maxTokens: Int = 600,
    val isBuiltIn: Boolean = false,
    val sortOrder: Int = 100,
    val createdAt: Long = System.currentTimeMillis(),
) {
    val generationParams: GenerationParams
        get() = GenerationParams(maxTokens = maxTokens, temperature = temperature.toFloat())

    companion object {
        val friendlyID: UUID = UUID.fromString("00000000-0000-4000-8000-000000000001")
        val taxiID: UUID = UUID.fromString("00000000-0000-4000-8000-000000000002")
        val helperID: UUID = UUID.fromString("00000000-0000-4000-8000-000000000003")
        val grumblerID: UUID = UUID.fromString("00000000-0000-4000-8000-000000000004")

        /** Те же встроенные персонажи, что в iOS-версии. */
        val builtIns = listOf(
            Persona(friendlyID, "Собеседник", "🙂", """Ты — дружелюбный собеседник. Отвечай по-русски, если к тебе не обратились на другом языке: живо, по делу и без лишних списков. Если чего-то не знаешь, так и скажи, не выдумывай.""", 0.7, 600, true, 1, 0),
            Persona(taxiID, "Попутчик из такси", "🚕", """Ты — разговорчивый таксист, который везёт пассажира и просто болтает с ним по дороге. Говори по-русски, просто и тепло, как в жизни: две–четыре короткие фразы, шутки, наблюдения, байки и встречные вопросы. Не выдавай догадки за факты: если не знаешь, отшутись или признайся, что не уверен. Не ругайся.""", 0.85, 300, true, 2, 0),
            Persona(helperID, "Помощник", "🛠", """Ты — спокойный и точный помощник. Отвечай по-русски, по делу и по порядку, при необходимости списком. Если не уверен или не знаешь, прямо скажи об этом. Не выдумывай факты, цитаты и ссылки.""", 0.4, 900, true, 3, 0),
            Persona(grumblerID, "Ворчун", "😒", """Ты — добродушный ворчун: любишь поворчать на жизнь, погоду и пробки, но по-доброму и с юмором. Отвечай по-русски, коротко и живо. Без брани и оскорблений собеседника. Если чего-то не знаешь, ворчливо признайся в этом, а не выдумывай.""", 0.8, 300, true, 4, 0),
        )
    }
}

/** Запись журнала: что именно было отправлено в интернет и куда. Видна в настройках. */
data class SearchLogEntry(val id: Long, val date: Long, val provider: String, val query: String, val succeeded: Boolean)

/** Длина ответа в понятных словах: три ступени вместо числа токенов. */
enum class AnswerLength(val tokens: Int) {
    Short(250), Medium(600), Long(1100);

    companion object {
        fun nearest(tokens: Int) = entries.minBy { kotlin.math.abs(it.tokens - tokens) }
    }
}

/** Сведения о собеседнике, которые модель учитывает в разговоре. Хранятся только на этом телефоне. */
data class UserProfile(
    val isEnabled: Boolean = true,
    val name: String = "",
    val address: Address = Address.Unspecified,
    val about: String = "",
    val updatedAt: Long = System.currentTimeMillis(),
) {
    enum class Address(val raw: String) { Unspecified("unspecified"), Informal("informal"), Formal("formal") }

    val isEmpty: Boolean get() = name.isBlank() && about.isBlank() && address == Address.Unspecified

    /** Блок для системного промпта. `null`, если профиль выключен или пуст. */
    val promptBlock: String?
        get() {
            if (!isEnabled || isEmpty) return null
            val lines = mutableListOf("Сведения о собеседнике, с которым ты разговариваешь (это рассказ о нём, а не о тебе; не путай себя с собеседником):")
            name.trim().takeIf { it.isNotEmpty() }?.let { lines.add("- Собеседника зовут $it.") }
            when (address) {
                Address.Informal -> lines.add("- Обращайся к собеседнику на «ты».")
                Address.Formal -> lines.add("- Обращайся к собеседнику на «вы».")
                Address.Unspecified -> Unit
            }
            about.trim().takeIf { it.isNotEmpty() }?.let { lines.add("- Собеседник рассказал о себе так: «${it.take(ABOUT_LIMIT)}»") }
            return lines.joinToString("\n")
        }

    companion object {
        const val ABOUT_LIMIT = 600
    }
}

/**
 * Заметка, которую модель сохранила сама («запомни, что…»). Хранится в той же зашифрованной базе и попадает в промпт
 * каждого диалога, пока включены заметки. Пользователь видит и удаляет их на экране «О вас».
 */
data class MemoryNote(val id: java.util.UUID = java.util.UUID.randomUUID(), val text: String, val createdAt: Long = System.currentTimeMillis()) {
    companion object {
        /** Сколько заметок хранится всего: каждая едет с каждым вопросом, и их рост не должен съесть контекст. */
        const val LIMIT = 40
        /** Длина одной заметки: это факт, а не пересказ разговора. */
        const val TEXT_LIMIT = 200

        fun clean(text: String): String {
            val flat = text.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
            return if (flat.length > TEXT_LIMIT) flat.take(TEXT_LIMIT) else flat
        }

        /** Блок для системного промпта. `null`, когда заметок нет. */
        fun promptBlock(notes: List<MemoryNote>): String? {
            if (notes.isEmpty()) return null
            return (listOf("Что ты знаешь о собеседнике из прошлых разговоров. Учитывай это в ответах, когда это к месту:") +
                notes.map { "- ${it.text}" }).joinToString("\n")
        }
    }
}

/** Что модель помнит между диалогами. Обе настройки меняются на экране «О вас». */
class MemorySettings(private val settings: SettingsStore) {
    /** Модель может сама сохранять и удалять заметки («запомни», «забудь»), и они учитываются в разговорах. */
    var notesEnabled: Boolean
        get() = settings.getBoolean("memory.notes", true)
        set(value) = settings.putBoolean("memory.notes", value)

    /** К вопросу подмешиваются подходящие ответы из других диалогов. */
    var recallEnabled: Boolean
        get() = settings.getBoolean("memory.recall", true)
        set(value) = settings.putBoolean("memory.recall", value)
}

/** Что-то сказанное в другом диалоге, что относится к нынешнему вопросу. */
data class Recollection(val id: java.util.UUID, val chatTitle: String, val date: Long, val text: String)
