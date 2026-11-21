package alexcattivo.localchat.engine

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/**
 * Посчитанный KV-кэш начала промпта — системного сообщения, — сохранённый на диск между чатами и запусками.
 *
 * Каждый новый чат начинается с одного и того же длинного системного промпта и инструкций инструментов; на процессоре
 * телефона их разбор занимает десятки секунд. Движок считает их один раз, сохраняет состояние сюда, а следующий новый
 * чат загружает файл. Здесь только имена файлов и уборка; вызовы llama.cpp — в движке (формат файла — prompt_state.h).
 * То же самое, что PromptCache.swift в iOS.
 */
class PromptCache(val directory: File, val keep: Int = 1) {
    /** От чего зависит сохранённое состояние. Любое отличие — файл не подходит. */
    data class Key(
        val model: String,
        val modelBytes: Long,
        val contextLength: Int,
        /** Где лежит KV-кэш (процессор или GPU): раскладка у них может отличаться. */
        val backend: String,
        val prefix: IntArray,
    ) {
        /** Имя файла — хэш всего перечисленного: изменился промпт, модель или настройка — файл просто не найдётся. */
        val fileName: String
            get() {
                val digest = MessageDigest.getInstance("SHA-256")
                digest.update("$model\n$modelBytes\n$contextLength\n$backend\n$LLAMA_BUILD\n".toByteArray())
                val tokens = ByteBuffer.allocate(prefix.size * 4).order(ByteOrder.LITTLE_ENDIAN)
                prefix.forEach { tokens.putInt(it) }
                digest.update(tokens.array())
                val hex = digest.digest().joinToString("") { "%02x".format(it) }
                return "system-${hex.take(32)}.$EXTENSION"
            }

        override fun equals(other: Any?) = other is Key && model == other.model && modelBytes == other.modelBytes &&
            contextLength == other.contextLength && backend == other.backend && prefix.contentEquals(other.prefix)

        override fun hashCode() = fileName.hashCode()
    }

    fun file(key: Key) = File(directory, key.fileName)

    /** Сюда новое состояние пишется перед тем, как заменить файл: недописанный файл не должен загрузиться. */
    fun temporaryFile(key: Key) = File(directory, key.fileName + ".partial")

    fun prepareDirectory(): Boolean = directory.isDirectory || directory.mkdirs()

    /**
     * Ставит только что записанное состояние на место и удаляет лишние файлы: недописанные и всё, что старше [keep]
     * последних состояний. С `keep = 1` остаётся только новое: состояния старого промпта, другой модели или другой
     * llama.cpp больше не прочитаются, а каждое — десятки мегабайт. Больше одного нужно, когда у приложения несколько
     * системных промптов (в Spectra — свой у каждого действия сводки), иначе они вытесняют друг друга.
     */
    fun commit(key: Key): Boolean {
        val target = file(key)
        target.delete()
        if (!temporaryFile(key).renameTo(target)) {
            temporaryFile(key).delete()
            return false
        }
        target.setLastModified(System.currentTimeMillis())
        prune(keeping = key.fileName)
        return true
    }

    /** Отмечает состояние как только что использованное: при уборке уходят самые давно не нужные. */
    fun touch(key: Key) {
        file(key).setLastModified(System.currentTimeMillis())
    }

    /** Удаляет из папки всё, кроме `keeping` и ещё [keep] − 1 самых свежих готовых состояний. */
    fun prune(keeping: String?) {
        val files = directory.listFiles() ?: return
        val others = files.filter { it.name != keeping && it.name.endsWith(".$EXTENSION") }
            .sortedByDescending { it.lastModified() }
            .take(maxOf(0, keep - 1))
            .map { it.name }
            .toSet()
        files.forEach { if (it.name != keeping && it.name !in others) it.delete() }
    }

    /** Убирает один файл — например, тот, что не загрузился. */
    fun discard(key: Key) {
        file(key).delete()
    }

    companion object {
        /** Версия llama.cpp, которой записаны файлы: в другой версии состояние может не загрузиться. */
        const val LLAMA_BUILD = "b12359"
        /** Начало короче этого не стоит файла: оно считается быстрее секунды. */
        const val MINIMUM_TOKENS = 64
        private const val EXTENSION = "kvstate"

        /**
         * Где в токенах кончается системная часть промпта: общее начало тех же системных сообщений перед двумя разными
         * вопросами. Ищется по токенам, а не по тексту, — так граница никогда не режет токен.
         */
        fun sharedPrefixLength(a: IntArray, b: IntArray): Int {
            var index = 0
            val limit = minOf(a.size, b.size)
            while (index < limit && a[index] == b[index]) index++
            return index
        }

        /**
         * Начало, которое стоит сохранить для этого промпта, или `null`: слишком короткое либо промпт начинается не с
         * него (шаблон может разбить границу на токены иначе, когда за ней настоящий вопрос).
         */
        fun usablePrefix(prefix: IntArray, prompt: IntArray): IntArray? {
            if (prefix.size < MINIMUM_TOKENS || prefix.size >= prompt.size) return null
            for (i in prefix.indices) if (prompt[i] != prefix[i]) return null
            return prefix
        }
    }
}
