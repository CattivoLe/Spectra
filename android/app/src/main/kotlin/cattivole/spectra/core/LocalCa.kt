package cattivole.spectra.core

import java.io.ByteArrayOutputStream
import java.io.File
import java.math.BigInteger
import java.net.InetAddress
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import java.security.KeyStore
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

/**
 * Seals the private keys before they are written to disk. On the phone this
 * is a key in the Android Keystore that never leaves the device, so key files
 * copied off it (a backup, a transfer to a new phone, a rooted read) cannot be
 * opened anywhere else.
 */
interface KeyGuard {
    fun seal(plain: ByteArray): ByteArray
    fun open(sealed: ByteArray): ByteArray
}

/**
 * The certificate this app signs for itself. The private keys stay on this
 * phone: sealed by a [KeyGuard] in a directory that is never backed up.
 *
 * A leaf for each server name is signed by that same key, so traffic can be
 * read when the phone trusts this certificate. Apps that do not trust it
 * close the connection.
 *
 * Keys that can no longer be opened (the sealing key is gone, the files came
 * from another phone) are replaced by a new authority. The phone then has to
 * trust the new certificate; the old one signs nothing any more.
 */
class LocalCa private constructor(
    private val caKey: PrivateKey,
    private val caPublic: PublicKey,
    val certificate: X509Certificate,
    private val leafKey: PrivateKey,
    private val leafPublic: PublicKey,
) {
    private val contexts = ConcurrentHashMap<String, SSLContext>()

    fun certificateDer(): ByteArray = certificate.encoded

    fun fingerprint(): String = sha256(certificate.encoded).joinToString(":") { "%02X".format(it) }

    /** A certificate for [host], signed by this authority. */
    fun leaf(host: String): X509Certificate {
        val name = host.ifBlank { "spectra" }
        val now = System.currentTimeMillis()
        val san = san(name)
        val subject = derName(name.take(64))
        val tbs = tbsCertificate(
            serial = BigInteger(64, random).abs() + BigInteger.ONE,
            issuer = derName(AUTHORITY),
            subject = subject,
            publicKey = leafPublic,
            notBefore = now - 3_600_000L,
            notAfter = now + 366L * 24 * 3_600_000L,
            extensions = cat(
                extension("2.5.29.19", false, Der.seq(Der.bool(false))),
                extension("2.5.29.15", false, Der.bitString(byteArrayOf(0xA0.toByte()))),
                extension("2.5.29.37", false, Der.seq(Der.oid("1.3.6.1.5.5.7.3.1"))),
                extension("2.5.29.14", false, Der.octet(ski(leafPublic))),
                extension("2.5.29.35", false, Der.seq(Der.raw(0x80, ski(caPublic)))),
                extension("2.5.29.17", false, san),
            ),
            issuerKey = caKey,
        )
        return parse(tbs)
    }

    /** TLS context that presents [leaf] for [host] to the phone. */
    fun contextFor(host: String): SSLContext = contexts.getOrPut(host) {
        val cert = leaf(host)
        val store = KeyStore.getInstance("PKCS12")
        store.load(null, null)
        store.setKeyEntry("leaf", leafKey, STORE_PASSWORD, arrayOf(cert, certificate))
        val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        keys.init(store, STORE_PASSWORD)
        SSLContext.getInstance("TLS").apply { init(keys.keyManagers, null, random) }
    }

    private fun persist(dir: File, guard: KeyGuard) {
        dir.mkdirs()
        File(dir, CA_KEY).writeBytes(guard.seal(caKey.encoded))
        File(dir, LEAF_KEY).writeBytes(guard.seal(leafKey.encoded))
        File(dir, "leaf.pub").writeBytes(leafPublic.encoded)
        // The certificate last: its presence is what marks the directory as complete.
        File(dir, CA_CERT).writeBytes(certificate.encoded)
        LEGACY_KEYS.forEach { File(dir, it).delete() }
    }

    companion object {
        const val AUTHORITY = "Spectra"
        private const val CA_CERT = "ca.crt"
        private const val CA_KEY = "ca.key"
        private const val LEAF_KEY = "leaf.key"

        /** Keys written in the clear by earlier versions. */
        private val LEGACY_KEYS = listOf("ca.pk8", "leaf.pk8")
        private val FILES = listOf(CA_CERT, CA_KEY, LEAF_KEY, "leaf.pub") + LEGACY_KEYS

        /** Only for tests and the JVM: keys are written as they are. */
        val PLAIN = object : KeyGuard {
            override fun seal(plain: ByteArray) = plain
            override fun open(sealed: ByteArray) = sealed
        }

        // Used only for the in-memory PKCS12 store that hands the leaf to SSLContext; never written to disk.
        private val STORE_PASSWORD = "spectra".toCharArray()
        private val random = SecureRandom()
        private val gate = Any()
        private val open = HashMap<String, LocalCa>()

        /**
         * Moves an authority from [from] (where earlier versions kept it, inside
         * backed-up storage) to [to], so a certificate the phone already trusts
         * keeps working. Whatever is left in [from] is deleted.
         */
        fun relocate(from: File, to: File) = synchronized(gate) {
            if (!from.isDirectory) return@synchronized
            if (!File(to, CA_CERT).isFile && File(from, CA_CERT).isFile) {
                to.mkdirs()
                for (name in FILES) {
                    val source = File(from, name)
                    if (source.isFile) source.copyTo(File(to, name), overwrite = true)
                }
            }
            from.deleteRecursively()
            open.remove(from.canonicalPath)
        }

        /** The default trust store, used for the real server. */
        fun trustDefault(): SSLContext = SSLContext.getInstance("TLS").apply { init(null, null, null) }

        /** A context that trusts exactly [certificate]. Tests use this for the far side. */
        fun trusting(certificate: X509Certificate): SSLContext {
            val store = KeyStore.getInstance("PKCS12")
            store.load(null, null)
            store.setCertificateEntry("ca", certificate)
            val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            trust.init(store)
            return SSLContext.getInstance("TLS").apply { init(null, trust.trustManagers, null) }
        }

        fun open(dir: File, guard: KeyGuard): LocalCa = synchronized(gate) {
            val key = dir.canonicalPath
            open[key]?.let { return it }
            val existing = if (File(dir, CA_CERT).isFile) runCatching { read(dir, guard) }.getOrNull() else null
            val loaded = existing ?: run {
                // Nothing usable on disk: drop it and start over.
                dir.deleteRecursively()
                create(dir, guard)
            }
            open[key] = loaded
            loaded
        }

        /** Drops the in-memory copy so the next [open] reads the files again. */
        fun forget(dir: File) {
            synchronized(gate) { open.remove(dir.canonicalPath) }
        }

        private fun create(dir: File, guard: KeyGuard): LocalCa {
            val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048, random) }
            val ca = keys.generateKeyPair()
            val leaf = keys.generateKeyPair()
            val now = System.currentTimeMillis()
            val subject = derName(AUTHORITY)
            val tbs = tbsCertificate(
                serial = BigInteger(64, random).abs() + BigInteger.ONE,
                issuer = subject,
                subject = subject,
                publicKey = ca.public,
                notBefore = now - 86_400_000L,
                notAfter = now + 10L * 366 * 24 * 3_600_000L,
                extensions = cat(
                    extension("2.5.29.19", true, Der.seq(Der.bool(true))),
                    extension("2.5.29.15", true, Der.bitString(byteArrayOf(0x06), unused = 1)),
                    extension("2.5.29.14", false, Der.octet(ski(ca.public))),
                ),
                issuerKey = ca.private,
            )
            val created = LocalCa(ca.private, ca.public, parse(tbs), leaf.private, leaf.public)
            created.persist(dir, guard)
            return created
        }

        private fun read(dir: File, guard: KeyGuard): LocalCa {
            val legacy = File(dir, LEGACY_KEYS[0]).isFile
            fun key(sealedName: String, legacyName: String): ByteArray =
                if (legacy) File(dir, legacyName).readBytes() else guard.open(File(dir, sealedName).readBytes())
            val factory = KeyFactory.getInstance("RSA")
            val caKey = factory.generatePrivate(PKCS8EncodedKeySpec(key(CA_KEY, "ca.pk8")))
            val cert = parse(File(dir, CA_CERT).readBytes())
            val leafKey = factory.generatePrivate(PKCS8EncodedKeySpec(key(LEAF_KEY, "leaf.pk8")))
            val leafPublic = factory.generatePublic(X509EncodedKeySpec(File(dir, "leaf.pub").readBytes()))
            // The certificate must belong to the key, or the files are not one authority.
            cert.verify(cert.publicKey)
            val probe = Signature.getInstance("SHA256withRSA").run { initSign(caKey); update(byteArrayOf(1)); sign() }
            val matches = Signature.getInstance("SHA256withRSA").run { initVerify(cert.publicKey); update(byteArrayOf(1)); verify(probe) }
            if (!matches) throw IllegalStateException("certificate and key do not match")
            val loaded = LocalCa(caKey, cert.publicKey, cert, leafKey, leafPublic)
            // Keys from an earlier version were in the clear: seal them now.
            if (legacy) loaded.persist(dir, guard)
            return loaded
        }
    }
}

private fun tbsCertificate(
    serial: BigInteger,
    issuer: ByteArray,
    subject: ByteArray,
    publicKey: PublicKey,
    notBefore: Long,
    notAfter: Long,
    extensions: ByteArray,
    issuerKey: PrivateKey,
): ByteArray {
    val info = Der.seq(
        cat(
            Der.explicit(0, Der.integer(BigInteger.valueOf(2))),
            Der.integer(serial),
            sha256WithRsa(),
            issuer,
            Der.seq(cat(Der.utcTime(notBefore), Der.utcTime(notAfter))),
            subject,
            publicKey.encoded,
            Der.explicit(3, Der.seq(extensions)),
        ),
    )
    val signature = Signature.getInstance("SHA256withRSA").run {
        initSign(issuerKey)
        update(info)
        sign()
    }
    return Der.seq(cat(info, sha256WithRsa(), Der.bitString(signature)))
}

private fun parse(der: ByteArray): X509Certificate =
    CertificateFactory.getInstance("X.509").generateCertificate(der.inputStream()) as X509Certificate

private fun derName(cn: String): ByteArray =
    Der.seq(Der.set(Der.seq(cat(Der.oid("2.5.4.3"), Der.printable(cn)))))

private fun san(host: String): ByteArray {
    val address = numericAddress(host)
    val name = if (address != null) Der.raw(0x87, address.address) else Der.raw(0x82, host.toByteArray(Charsets.US_ASCII))
    return Der.seq(name)
}

private fun numericAddress(host: String): InetAddress? {
    if (host.any { it != ':' && it != '.' && !it.isDigit() && it !in 'a'..'f' && it !in 'A'..'F' }) return null
    val address = runCatching { InetAddress.getByName(host) }.getOrNull() ?: return null
    val printed = address.hostAddress ?: return null
    if (host.contains(':')) return address.takeIf { printed.contains(':') }
    return address.takeIf { printed == host }
}

private fun extension(oid: String, critical: Boolean, value: ByteArray): ByteArray {
    val parts = cat(Der.oid(oid), if (critical) Der.bool(true) else ByteArray(0), Der.octet(value))
    return Der.seq(parts)
}

private fun sha256WithRsa(): ByteArray = Der.seq(cat(Der.oid("1.2.840.113549.1.1.11"), Der.nullBytes()))

private fun ski(key: PublicKey): ByteArray = sha1(key.encoded)

private fun sha1(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-1").digest(bytes)

private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

private fun cat(vararg parts: ByteArray): ByteArray {
    val out = ByteArrayOutputStream()
    parts.forEach { out.write(it) }
    return out.toByteArray()
}

private object Der {
    fun seq(body: ByteArray): ByteArray = tl(0x30, body)
    fun set(body: ByteArray): ByteArray = tl(0x31, body)
    fun integer(value: BigInteger): ByteArray = tl(0x02, value.toByteArray())
    fun bool(value: Boolean): ByteArray = tl(0x01, byteArrayOf(if (value) 0xFF.toByte() else 0))
    fun octet(body: ByteArray): ByteArray = tl(0x04, body)
    fun nullBytes(): ByteArray = byteArrayOf(0x05, 0x00)
    fun printable(text: String): ByteArray = tl(0x13, text.toByteArray(Charsets.US_ASCII))
    fun bitString(body: ByteArray, unused: Int = 0): ByteArray = tl(0x03, byteArrayOf(unused.toByte()) + body)
    fun explicit(tag: Int, body: ByteArray): ByteArray = tl(0xA0 or tag, body)
    fun raw(tag: Int, body: ByteArray): ByteArray = tl(tag, body)

    fun utcTime(millis: Long): ByteArray {
        val format = SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US)
        format.timeZone = TimeZone.getTimeZone("UTC")
        return tl(0x17, format.format(Date(millis)).toByteArray(Charsets.US_ASCII))
    }

    fun oid(dotted: String): ByteArray {
        val parts = dotted.split('.').map { it.toInt() }
        val body = ByteArrayOutputStream()
        body.write(parts[0] * 40 + parts[1])
        for (part in parts.drop(2)) writeBase128(body, part)
        return tl(0x06, body.toByteArray())
    }

    private fun writeBase128(out: ByteArrayOutputStream, value: Int) {
        var shift = 28
        while (shift > 0 && value ushr shift == 0) shift -= 7
        while (shift > 0) {
            out.write((value ushr shift) and 0x7F or 0x80)
            shift -= 7
        }
        out.write(value and 0x7F)
    }

    private fun tl(tag: Int, body: ByteArray): ByteArray = byteArrayOf(tag.toByte()) + length(body.size) + body

    private fun length(size: Int): ByteArray = when {
        size < 0x80 -> byteArrayOf(size.toByte())
        size <= 0xFF -> byteArrayOf(0x81.toByte(), size.toByte())
        size <= 0xFFFF -> byteArrayOf(0x82.toByte(), (size shr 8).toByte(), size.toByte())
        else -> byteArrayOf(0x83.toByte(), (size shr 16).toByte(), (size shr 8).toByte(), size.toByte())
    }
}
