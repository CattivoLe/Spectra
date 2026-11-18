package alexcattivo.localchat.kit

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase

/** [SqlDatabase] поверх системного SQLite. На этапе шифрования его сменит такой же слой поверх SQLCipher. */
class AndroidSqlDatabase(private val db: SQLiteDatabase) : SqlDatabase {
    override fun execute(sql: String, vararg args: Any?) {
        if (args.isEmpty()) db.execSQL(sql) else db.execSQL(sql, args.map(::bindable).toTypedArray())
    }

    override fun query(sql: String, vararg args: Any?): List<Map<String, Any?>> {
        // rawQuery принимает только строки, поэтому числа подставляются через тот же текстовый вид — SQLite их приведёт.
        db.rawQuery(sql, args.map { it?.let(::bindable)?.toString() }.toTypedArray()).use { cursor ->
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

    private fun bindable(value: Any?): Any? = when (value) {
        is Boolean -> if (value) 1 else 0
        else -> value
    }

    private fun value(cursor: Cursor, index: Int): Any? = when (cursor.getType(index)) {
        Cursor.FIELD_TYPE_NULL -> null
        Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(index)
        Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(index)
        Cursor.FIELD_TYPE_BLOB -> cursor.getBlob(index)
        else -> cursor.getString(index)
    }

    companion object {
        fun open(path: String): AndroidSqlDatabase {
            val db = SQLiteDatabase.openOrCreateDatabase(path, null)
            db.setForeignKeyConstraintsEnabled(true)
            return AndroidSqlDatabase(db)
        }
    }
}
