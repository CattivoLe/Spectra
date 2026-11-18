package alexcattivo.localchat.kit

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Откуда берётся ключ шифрования базы. Ключ — 32 случайных байта, а не пароль: подбирать его бессмысленно. */
fun interface DatabaseKeyProvider {
    fun key(): ByteArray
}

/**
 * Ключ базы хранится в файле в зашифрованном виде, а зашифрован он ключом из Android Keystore. Тот создаётся
 * внутри защищённого хранилища телефона и не извлекается оттуда даже с правами приложения, поэтому
 * скопированные файлы на другом устройстве не расшифровать. Аналог связки ключей iOS с «ThisDeviceOnly».
 */
class KeystoreKeyProvider(private val file: File, private val alias: String = "LocalChat.database-wrapping") : DatabaseKeyProvider {
    @Synchronized
    override fun key(): ByteArray {
        if (file.exists()) return unwrap(file.readBytes())
        val key = ByteArray(KEY_LENGTH).also { SecureRandom().nextBytes(it) }
        file.parentFile?.mkdirs()
        file.writeBytes(wrap(key))
        return key
    }

    /** Удаляет ключ. После этого прежняя база нечитаема, даже если файл остался на диске. */
    fun deleteKey() {
        file.delete()
        runCatching { keyStore().deleteEntry(alias) }
    }

    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun wrappingKey(): SecretKey {
        (keyStore().getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private fun wrap(key: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, wrappingKey())
        val iv = cipher.iv
        return byteArrayOf(iv.size.toByte()) + iv + cipher.doFinal(key)
    }

    private fun unwrap(data: ByteArray): ByteArray {
        val ivLength = data[0].toInt()
        val iv = data.copyOfRange(1, 1 + ivLength)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, wrappingKey(), GCMParameterSpec(128, iv))
        val key = cipher.doFinal(data.copyOfRange(1 + ivLength, data.size))
        if (key.size != KEY_LENGTH) throw IllegalStateException("database key is damaged")
        return key
    }

    companion object {
        const val KEY_LENGTH = 32
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
