package alexcattivo.localchat.kit

import java.sql.Connection
import java.sql.DriverManager

/** SQLite в памяти через JDBC: та же схема и те же запросы, что на телефоне. */
class JdbcDatabase : SqlDatabase {
    private val connection: Connection = DriverManager.getConnection("jdbc:sqlite::memory:")

    override fun execute(sql: String, vararg args: Any?) {
        connection.prepareStatement(sql).use { statement ->
            args.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
            statement.execute()
        }
    }

    override fun query(sql: String, vararg args: Any?): List<Map<String, Any?>> =
        connection.prepareStatement(sql).use { statement ->
            args.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
            statement.executeQuery().use { result ->
                val meta = result.metaData
                buildList {
                    while (result.next()) {
                        add((1..meta.columnCount).associate { meta.getColumnLabel(it) to result.getObject(it) })
                    }
                }
            }
        }

    override fun <T> transaction(block: () -> T): T {
        val wasAuto = connection.autoCommit
        connection.autoCommit = false
        return try {
            block().also { connection.commit() }
        } catch (error: Throwable) {
            connection.rollback()
            throw error
        } finally {
            connection.autoCommit = wasAuto
        }
    }

    override var userVersion: Int
        get() = query("PRAGMA user_version").first().values.first().let { (it as Number).toInt() }
        set(value) = execute("PRAGMA user_version = $value")
}
