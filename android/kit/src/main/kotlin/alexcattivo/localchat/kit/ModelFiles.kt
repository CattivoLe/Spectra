package alexcattivo.localchat.kit

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

object ModelFiles {
    /** SHA-256 файла. Читает кусками, поэтому память не растёт вместе с размером модели. */
    fun sha256(file: File): String = file.inputStream().use { sha256(it) }

    fun sha256(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(4 * 1024 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Файл начинается с байтов «GGUF». Это защита от случайно выбранного не того файла, а не проверка целостности. */
    fun isGGUF(head: ByteArray): Boolean = head.size >= 4 && head.copyOf(4).contentEquals("GGUF".toByteArray())

    /** Имя, которого ещё нет в папке: «модель.gguf», «модель 2.gguf», … */
    fun uniqueFile(name: String, directory: File): File {
        var candidate = File(directory, name)
        if (!candidate.exists()) return candidate
        val base = name.substringBeforeLast('.')
        val ext = name.substringAfterLast('.', "")
        var number = 2
        do {
            candidate = File(directory, if (ext.isEmpty()) "$base $number" else "$base $number.$ext")
            number++
        } while (candidate.exists())
        return candidate
    }
}
