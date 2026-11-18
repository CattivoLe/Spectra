package alexcattivo.localchat.kit

import alexcattivo.localchat.kit.web.SecretStore
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Секреты (ключ Brave Search) в файлах, зашифрованных ключом из Android Keystore. Как связка ключей в iOS. */
class KeystoreSecretStore(private val directory: File, private val alias: String = "LocalChat.secrets") : SecretStore {
    override fun read(account: String): String? {
        val file = File(directory, "$account.secret")
        if (!file.exists()) return null
        return runCatching {
            val data = file.readBytes()
            val ivLength = data[0].toInt()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data.copyOfRange(1, 1 + ivLength)))
            cipher.doFinal(data.copyOfRange(1 + ivLength, data.size)).decodeToString()
        }.getOrNull()
    }

    override fun write(value: String?, account: String) {
        val file = File(directory, "$account.secret")
        if (value.isNullOrEmpty()) { file.delete(); return }
        directory.mkdirs()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        file.writeBytes(byteArrayOf(cipher.iv.size.toByte()) + cipher.iv + cipher.doFinal(value.toByteArray()))
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
        }.generateKey()
    }
}
