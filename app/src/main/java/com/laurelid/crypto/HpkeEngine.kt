package com.laurelid.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.nio.ByteBuffer
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.interfaces.XECPrivateKey
import java.security.interfaces.XECPublicKey
import java.security.spec.NamedParameterSpec
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.bouncycastle.crypto.hpke.HPKE
import org.bouncycastle.crypto.params.AsymmetricKeyParameter
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters

/**
 * HPKE recipient implementation following RFC 9180 §5 for X25519/HKDF-SHA256/AES-GCM suites. 【RFC9180§5】
 */
interface HpkeEngine {
    suspend fun initRecipient(keyAlias: String = DEFAULT_KEY_ALIAS)
    fun decrypt(cipher: ByteArray, aad: ByteArray = EMPTY_AAD): ByteArray

    companion object {
        const val DEFAULT_KEY_ALIAS = "laurelid_hpke_x25519"
        private val EMPTY_AAD = ByteArray(0)
    }
}

/** Provides access to hardware-isolated HPKE key material using Android Keystore. */
interface HpkeKeyProvider {
    suspend fun ensureKey(alias: String)
    fun getPublicKeyBytes(): ByteArray
    fun getPrivateKeyParameters(): AsymmetricKeyParameter
    fun installDebugKey(alias: String, privateKey: ByteArray)
}

/** Android-backed HPKE key provider using StrongBox when available. 【AndroidKeystore】 */
@Singleton
class AndroidHpkeKeyProvider @Inject constructor() : HpkeKeyProvider {

    private val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    private val cachedAlias = AtomicReference<String?>(null)
    private val cachedKeyPair = AtomicReference<KeyPair?>()
    private val debugScalar = AtomicReference<ByteArray?>(null)
    private val lock = Mutex()

    override suspend fun ensureKey(alias: String) {
        lock.withLock {
            val currentAlias = cachedAlias.get()
            if (currentAlias == alias && (cachedKeyPair.get() != null || debugScalar.get() != null)) {
                return
            }
            val existing = keyStore.getEntry(alias, null) as? KeyStore.PrivateKeyEntry
            if (existing != null) {
                cachedAlias.set(alias)
                cachedKeyPair.set(KeyPair(existing.certificate.publicKey, existing.privateKey))
                debugScalar.set(null)
                return
            }
            cachedKeyPair.set(generate(alias))
            cachedAlias.set(alias)
            debugScalar.set(null)
        }
    }

    override fun getPublicKeyBytes(): ByteArray {
        debugScalar.get()?.let { scalar ->
            val privateKey = X25519PrivateKeyParameters(scalar, 0)
            val public = ByteArray(X25519PrivateKeyParameters.KEY_SIZE)
            privateKey.generatePublicKey().encode(public, 0)
            return public
        }
        val keyPair = cachedKeyPair.get() ?: error("HPKE key has not been initialised")
        val publicKey = keyPair.public as? XECPublicKey ?: error("HPKE public key must be X25519")
        val encoded = publicKey.u.toByteArray()
        return if (encoded.size == X25519PrivateKeyParameters.KEY_SIZE) {
            encoded
        } else {
            ByteArray(X25519PrivateKeyParameters.KEY_SIZE).apply {
                val offset = size - encoded.size
                System.arraycopy(encoded, 0, this, offset, encoded.size)
            }
        }
    }

    override fun getPrivateKeyParameters(): AsymmetricKeyParameter {
        debugScalar.get()?.let { return X25519PrivateKeyParameters(it, 0) }
        val keyPair = cachedKeyPair.get() ?: error("HPKE key has not been initialised")
        val privateKey = keyPair.private as? XECPrivateKey ?: error("HPKE private key must be X25519")
        val scalar = privateKey.scalar ?: throw CertificateException("Android Keystore did not expose scalar")
        return X25519PrivateKeyParameters(scalar, 0)
    }

    override fun installDebugKey(alias: String, privateKey: ByteArray) {
        require(privateKey.size == X25519PrivateKeyParameters.KEY_SIZE) { "Invalid X25519 key" }
        if (!lock.tryLock()) {
            throw IllegalStateException("Unable to obtain keystore lock for debug import")
        }
        try {
            cachedKeyPair.set(null)
            cachedAlias.set(alias)
            debugScalar.set(privateKey.copyOf())
        } finally {
            lock.unlock()
        }
    }

    private fun generate(alias: String): KeyPair {
        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_XDH, ANDROID_KEYSTORE)
        val builder = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_AGREE_KEY or KeyProperties.PURPOSE_DECRYPT,
        )
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setAlgorithmParameterSpec(NamedParameterSpec("X25519"))
            .setIsStrongBoxBacked(true)
            .setUserAuthenticationRequired(false)
        val spec = try {
            builder.build()
        } catch (_: SecurityException) {
            builder.setIsStrongBoxBacked(false).build()
        }
        generator.initialize(spec)
        val pair = generator.generateKeyPair()
        cachedKeyPair.set(pair)
        cachedAlias.set(alias)
        debugScalar.set(null)
        return pair
    }

    private companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    }
}

/** BouncyCastle HPKE decryptor for mDL payloads. */
@Singleton
class BouncyCastleHpkeEngine @Inject constructor(
    private val keyProvider: HpkeKeyProvider,
    private val config: HpkeConfig = HpkeConfig.default(),
) : HpkeEngine {

    private val initLock = Mutex()
    private var initialised = false

    override suspend fun initRecipient(keyAlias: String) {
        initLock.withLock {
            if (!initialised) {
                keyProvider.ensureKey(keyAlias)
                initialised = true
            }
        }
    }

    override fun decrypt(cipher: ByteArray, aad: ByteArray): ByteArray {
        check(initialised) { "HPKE recipient not initialised" }
        val envelope = HpkeEnvelope.parse(cipher)
        val hpke = HPKE.create(config.kem, config.kdf, config.aead)
        val recipient = hpke.createRecipientContext(
            keyProvider.getPrivateKeyParameters(),
            envelope.encapsulatedKey,
            config.info,
            aad,
        )
        return recipient.open(envelope.ciphertext, aad)
    }
}

/** HPKE suite parameters negotiated with the wallet (RFC 9180 §7). 【RFC9180§7】 */
data class HpkeConfig(
    val kem: Int,
    val kdf: Int,
    val aead: Int,
    val info: ByteArray = ByteArray(0),
) {
    companion object {
        fun default(): HpkeConfig = HpkeConfig(
            kem = HPKE.KEM_X25519_HKDF_SHA256,
            kdf = HPKE.KDF_HKDF_SHA256,
            aead = HPKE.AEAD_AES_GCM_256,
            info = "LaurelID-mDL".toByteArray(),
        )
    }
}

/** Minimal HPKE envelope encoding matching ISO/IEC 18013-7 Appendix C. */
data class HpkeEnvelope(
    val encapsulatedKey: ByteArray,
    val ciphertext: ByteArray,
) {
    fun toByteArray(): ByteArray {
        val buffer = ByteBuffer.allocate(2 + encapsulatedKey.size + ciphertext.size)
        buffer.putShort(encapsulatedKey.size.toShort())
        buffer.put(encapsulatedKey)
        buffer.put(ciphertext)
        return buffer.array()
    }

    companion object {
        fun parse(raw: ByteArray): HpkeEnvelope {
            require(raw.size >= 4) { "HPKE envelope too small" }
            val buffer = ByteBuffer.wrap(raw)
            val encLength = buffer.short.toInt() and 0xFFFF
            require(encLength > 0) { "Missing encapsulated key" }
            require(buffer.remaining() >= encLength) { "Malformed HPKE envelope" }
            val encapsulated = ByteArray(encLength)
            buffer.get(encapsulated)
            val ciphertext = ByteArray(buffer.remaining())
            buffer.get(ciphertext)
            return HpkeEnvelope(encapsulated, ciphertext)
        }
    }
}

/** Records metadata for key rotation policies. */
data class HpkeKeyMetadata(
    val alias: String,
    val createdAt: Instant,
)

/** Simple in-memory provider for tests. */
class InMemoryHpkeKeyProvider(private val keyPair: KeyPair) : HpkeKeyProvider {
    override suspend fun ensureKey(alias: String) = Unit
    override fun getPublicKeyBytes(): ByteArray = (keyPair.public as XECPublicKey).u.toByteArray()
    override fun getPrivateKeyParameters(): AsymmetricKeyParameter {
        val privateKey = keyPair.private as XECPrivateKey
        val scalar = privateKey.scalar ?: throw CertificateException("Missing scalar")
        return X25519PrivateKeyParameters(scalar, 0)
    }
    override fun installDebugKey(alias: String, privateKey: ByteArray) = Unit
}
