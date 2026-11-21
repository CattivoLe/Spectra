package cattivole.spectra.platform

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import cattivole.spectra.core.KeyGuard
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Seals the local certificate authority's private keys with an AES key that
 * lives in the Android Keystore. That key is created inside the phone's
 * secure storage and cannot be read out, so sealed files copied to another
 * device, or out of a backup, are useless there.
 */
class KeystoreKeyGuard(private val alias: String = "Spectra.traffic-ca") : KeyGuard {
    override fun seal(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        return byteArrayOf(iv.size.toByte()) + iv + cipher.doFinal(plain)
    }

    override fun open(sealed: ByteArray): ByteArray {
        val ivLength = sealed[0].toInt()
        require(ivLength in 1..32 && sealed.size > 1 + ivLength) { "sealed key is damaged" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, sealed.copyOfRange(1, 1 + ivLength)))
        return cipher.doFinal(sealed.copyOfRange(1 + ivLength, sealed.size))
    }

    @Synchronized
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

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
