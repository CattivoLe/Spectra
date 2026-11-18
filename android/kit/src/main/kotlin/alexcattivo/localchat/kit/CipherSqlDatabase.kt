package alexcattivo.localchat.kit

import android.database.Cursor
import java.io.File
import net.zetetic.database.sqlcipher.SQLiteDatabase

/**
 * [SqlDatabase] поверх SQLCipher: файл базы зашифрован AES-256, ключ — сырые 32 байта (`x'…'`), как в iOS-версии.
 * Старая открытая база (с первого этапа) при открытии переносится в зашифрованную со сверкой числа записей.
 */
class CipherSqlDatabase private constructor(private val db: SQLiteDatabase) : SqlDatabase {
    override fun execute(sql: String, vararg args: Any?) {
        if (args.isEmpty()) db.execSQL(sql) else db.execSQL(sql, args.map(::bindable).toTypedArray())
    }

    override fun query(sql: String, vararg args: Any?): List<Map<String, Any?>> {
        db.rawQuery(sql, *args.map(::bindable).toTypedArray()).use { cursor ->
            return buildList {
                while (cursor.moveToNext()) {
                    add((0 until cursor.columnCount).associate { cursor.getColumnName(it) to value(cursor, it) })
                }
            }
        }
    }

    override fun <T> transaction(block: () -> T): T {
        db.beginTransaction()
        try {
            val result = block()
            db.setTransactionSuccessful()
            return result
        } finally {
            db.endTransaction()
        }
    }

    override var userVersion: Int
        get() = db.version
        set(value) { db.version = value }

    private fun bindable(value: Any?): Any? = if (value is Boolean) (if (value) 1 else 0) else value

    private fun value(cursor: Cursor, index: Int): Any? = when (cursor.getType(index)) {
        Cursor.FIELD_TYPE_NULL -> null
        Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(index)
        Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(index)
        Cursor.FIELD_TYPE_BLOB -> cursor.getBlob(index)
        else -> cursor.getString(index)
    }

    class EncryptionMismatchException : Exception("encrypted copy does not match the original")

    companion object {
        @Volatile private var loaded = false

        private fun ensureLoaded() {
            if (!loaded) synchronized(this) { if (!loaded) { System.loadLibrary("sqlcipher"); loaded = true } }
        }

        private fun hex(key: ByteArray) = key.joinToString("") { "%02x".format(it) }

        /** Пароль в виде сырого ключа: SQLCipher не растягивает его, он и так случаен. */
        private fun rawKey(key: ByteArray) = "x'${hex(key)}'".toByteArray()

        fun open(file: File, key: ByteArray): CipherSqlDatabase {
            ensureLoaded()
            file.parentFile?.mkdirs()
            if (isPlaintextDatabase(file)) encryptInPlace(file, key)
            val db = SQLiteDatabase.openOrCreateDatabase(file, rawKey(key), null, null, null)
            db.execSQL("PRAGMA foreign_keys = ON")
            return CipherSqlDatabase(db)
        }

        /** База в памяти, тоже через SQLCipher: на случай, если файл открыть не удалось. */
        fun inMemory(): CipherSqlDatabase {
            ensureLoaded()
            return CipherSqlDatabase(SQLiteDatabase.openOrCreateDatabase(":memory:", ByteArray(0), null, null, null))
        }

        /** Обычная база начинается с видимой строки «SQLite format 3»; зашифрованная выглядит как случайные байты. */
        fun isPlaintextDatabase(file: File): Boolean {
            if (!file.exists() || file.length() < 16) return false
            val head = file.inputStream().use { it.readNBytes(16) }
            return head.contentEquals("SQLite format 3\u0000".toByteArray())
        }

        /**
         * Переносит данные из открытой базы в зашифрованную копию, сверяет число записей и только потом подменяет файл.
         * Если что-то пошло не так, исходный файл остаётся нетронутым.
         */
        private fun encryptInPlace(file: File, key: ByteArray) {
            val temporary = File(file.parentFile, file.name + ".encrypting")
            for (suffix in listOf("", "-wal", "-shm", "-journal")) File(temporary.path + suffix).delete()

            val plain = SQLiteDatabase.openOrCreateDatabase(file, ByteArray(0), null, null, null)
            try {
                plain.rawExecSQL("ATTACH DATABASE '${temporary.path}' AS encrypted KEY \"x'${hex(key)}'\"")
                plain.rawExecSQL("SELECT sqlcipher_export('encrypted')")
                fun count(sql: String) = plain.rawQuery(sql).use { it.moveToFirst(); it.getInt(0) }
                val plainCounts = count("SELECT COUNT(*) FROM chat") to count("SELECT COUNT(*) FROM message")
                val copyCounts = count("SELECT COUNT(*) FROM encrypted.chat") to count("SELECT COUNT(*) FROM encrypted.message")
                // Версия схемы хранится в заголовке файла и sqlcipher_export её не переносит.
                plain.rawExecSQL("PRAGMA encrypted.user_version = ${plain.version}")
                plain.rawExecSQL("DETACH DATABASE encrypted")
                if (plainCounts != copyCounts) throw EncryptionMismatchException()
            } finally {
                plain.close()
            }

            // Новая база должна открываться ключом, прежде чем заменит старую.
            SQLiteDatabase.openOrCreateDatabase(temporary, rawKey(key), null, null, null).use { check ->
                check.rawQuery("SELECT COUNT(*) FROM message").use { it.moveToFirst() }
            }
            if (!temporary.renameTo(file)) throw IllegalStateException("could not replace the database file")
            for (suffix in listOf("-wal", "-shm", "-journal")) File(file.path + suffix).delete()
        }
    }
}
