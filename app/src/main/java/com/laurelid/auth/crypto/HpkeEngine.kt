package com.laurelid.auth.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.laurelid.BuildConfig
import java.nio.ByteBuffer
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.interfaces.XECPrivateKey
import java.security.interfaces.XECPublicKey
import java.security.spec.NamedParameterSpec
import java.time.Instant
import java.util.Optional
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.bouncycastle.crypto.hpke.HPKE
import org.bouncycastle.crypto.hpke.HPKE.AEAD_AES_GCM_256
import org.bouncycastle.crypto.hpke.HPKE.KDF_HKDF_SHA256
import org.bouncycastle.crypto.hpke.HPKE.KEM_X25519_HKDF_SHA256
import org.bouncycastle.crypto.params.AsymmetricKeyParameter
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters

/**
 * HPKE recipient implementation for the kiosk verifier. The parameters follow ISO/IEC 18013-7:
 * KEM=X25519-HKDF-SHA256, KDF=HKDF-SHA256, AEAD=AES-256-GCM. RFC 9180 defines the primitive suite
 * that wallets must use for offline Verify-with-Wallet engagements.
 */
interface HpkeEngine {
    /** Prepares the engine by ensuring the private key exists inside Android Keystore. */
    suspend fun initRecipient(keyAlias: String = DEFAULT_KEY_ALIAS)

    /** Decrypts an HPKE envelope emitted by the wallet. */
    fun decrypt(cipher: ByteArray, aad: ByteArray = EMPTY_AAD): SecureBytes

    companion object {
        const val DEFAULT_KEY_ALIAS = "laurelid_hpke_x25519"
        private val EMPTY_AAD = ByteArray(0)
    }
}

/** Provides access to the verifier's HPKE key pair stored inside Android Keystore. */
interface HpkeKeyProvider {
    suspend fun ensureKey(alias: String)
    fun getPublicKeyBytes(): ByteArray
    fun getPrivateKeyParameters(): AsymmetricKeyParameter

    /** Installs a deterministic test key; only enabled for debug builds and unit tests. */
    fun installDebugKey(alias: String, privateKey: ByteArray)
}

/**
 * Android-backed implementation that keeps the HPKE key hardware isolated. Keys are generated on
 * demand and cached in memory to avoid repeated keystore access.
 */
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
        val publicKey = keyPair.public as? XECPublicKey
            ?: error("HPKE public key must be X25519")
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
        val privateKey = keyPair.private as? XECPrivateKey
            ?: error("HPKE private key must be X25519")
        val scalar = extractScalar(privateKey)
        return X25519PrivateKeyParameters(scalar, 0)
    }

    override fun installDebugKey(alias: String, privateKey: ByteArray) {
        require(privateKey.size == X25519PrivateKeyParameters.KEY_SIZE) { "Invalid X25519 key" }
        if (!BuildConfig.DEBUG) {
            throw SecurityException("Debug key installation is only available in debug builds")
        }
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

    @Suppress("unused")
    private fun delete(alias: String) {
        if (keyStore.containsAlias(alias)) {
            keyStore.deleteEntry(alias)
        }
        cachedKeyPair.set(null)
        cachedAlias.set(null)
        debugScalar.set(null)
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    }
}

/** HPKE decryptor built on top of BouncyCastle's HPKE implementation. */
@Singleton
class BouncyCastleHpkeEngine @Inject constructor(
    private val keyProvider: HpkeKeyProvider,
    private val config: HpkeConfig = HpkeConfig.default(),
) : HpkeEngine {

    private val hpke = HPKE.create(KEM_X25519_HKDF_SHA256, KDF_HKDF_SHA256, AEAD_AES_GCM_256)

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

    override fun decrypt(cipher: ByteArray, aad: ByteArray): SecureBytes {
        check(initialised) { "HPKE recipient not initialised" }
        val envelope = HpkeEnvelope.parse(cipher)
        val recipient = hpke.createRecipientContext(
            keyProvider.getPrivateKeyParameters(),
            envelope.encapsulatedKey,
            config.info,
            aad,
        )
        val plaintext = recipient.open(envelope.ciphertext, aad)
        return SecureBytes.wrap(plaintext)
    }
}

/** Encapsulates HPKE context information strings. */
data class HpkeConfig(
    val info: ByteArray = ByteArray(0),
) {
    companion object {
        fun default(): HpkeConfig = HpkeConfig(
            info = "LaurelID-mDL".toByteArray(),
        )
    }
}

/**
 * Minimal HPKE envelope: uint16 length-prefixed encapsulated key followed by ciphertext bytes.
 */
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

/** Simple in-memory provider for unit tests. */
class InMemoryHpkeKeyProvider(private val keyPair: KeyPair) : HpkeKeyProvider {
    override suspend fun ensureKey(alias: String) = Unit
    override fun getPublicKeyBytes(): ByteArray = (keyPair.public as XECPublicKey).u.toByteArray()
    override fun getPrivateKeyParameters(): AsymmetricKeyParameter {
        val privateKey = keyPair.private as XECPrivateKey
        val scalar = extractScalar(privateKey)
        return X25519PrivateKeyParameters(scalar, 0)
    }
    override fun installDebugKey(alias: String, privateKey: ByteArray) = Unit
}

/** Records when HPKE keys were last rotated, allowing scheduled re-keying policies. */
data class HpkeKeyMetadata(
    val alias: String,
    val createdAt: Instant,
)

/** Extracts the raw X25519 scalar from an XEC private key across API levels. */
private fun extractScalar(privateKey: XECPrivateKey): ByteArray {
    val scalarValue: Any? = privateKey.scalar
    return when (scalarValue) {
        is ByteArray -> scalarValue
        is Optional<*> -> {
            val bytes = scalarValue.orElseThrow {
                IllegalStateException("Missing scalar in XECPrivateKey")
            }
            bytes as? ByteArray
                ?: throw IllegalStateException("Unexpected scalar representation: ${bytes?.javaClass?.name}")
        }
        null -> throw IllegalStateException("Missing scalar in XECPrivateKey")
        else -> throw IllegalStateException("Unexpected scalar representation: ${scalarValue.javaClass.name}")
    }
}
