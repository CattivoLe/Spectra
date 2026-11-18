package alexcattivo.localchat.kit

/**
 * Минимальный слой SQL, на котором стоит [ChatStore]. На телефоне его реализует системный SQLite
 * (потом — зашифрованный SQLCipher), в JVM-тестах — SQLite через JDBC. Так схема и запросы проверяются тестами.
 */
interface SqlDatabase {
    fun execute(sql: String, vararg args: Any?)
    fun query(sql: String, vararg args: Any?): List<Map<String, Any?>>
    fun <T> transaction(block: () -> T): T
    var userVersion: Int
}

internal fun Map<String, Any?>.string(key: String): String = this[key] as String
internal fun Map<String, Any?>.stringOrNull(key: String): String? = this[key] as String?
internal fun Map<String, Any?>.long(key: String): Long = (this[key] as Number).toLong()
internal fun Map<String, Any?>.int(key: String): Int = (this[key] as Number).toInt()
internal fun Map<String, Any?>.bool(key: String): Boolean = (this[key] as Number).toInt() != 0
internal fun Map<String, Any?>.double(key: String): Double = (this[key] as Number).toDouble()
