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
import org.bouncycastle.crypto.params.AsymmetricKeyParameter
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters

typealias SecureBytes = ByteArray

interface HpkeEngine {
    suspend fun initRecipient(keyAlias: String = DEFAULT_KEY_ALIAS)
    fun decrypt(cipher: ByteArray, aad: ByteArray = EMPTY_AAD): SecureBytes
    companion object {
        const val DEFAULT_KEY_ALIAS = "laurelid_hpke_x25519"
        private val EMPTY_AAD = ByteArray(0)
    }
}

interface HpkeKeyProvider {
    suspend fun ensureKey(alias: String)
    fun getPublicKeyBytes(): ByteArray
    fun getPrivateKeyParameters(): AsymmetricKeyParameter
    fun installDebugKey(alias: String, privateKey: ByteArray)
}

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
            if (currentAlias == alias && (cachedKeyPair.get() != null || debugScalar.get() != null)) return
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
        val scalar = extractScalar(privateKey)
        return X25519PrivateKeyParameters(scalar, 0)
    }

    override fun installDebugKey(alias: String, privateKey: ByteArray) {
        require(privateKey.size == X25519PrivateKeyParameters.KEY_SIZE) { "Invalid X25519 key" }
        if (!BuildConfig.DEBUG) error("Debug key installation is only available in debug builds")
        if (!lock.tryLock()) error("Unable to obtain keystore lock for debug import")
        try {
            cachedKeyPair.set(null)
            cachedAlias.set(alias)
            debugScalar.set(privateKey.copyOf())
        } finally {
            lock.unlock()
        }
    }

    private fun generate(alias: String): KeyPair {
        val generator = KeyPairGenerator.getInstance("XDH", ANDROID_KEYSTORE)
        val builder = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_AGREE_KEY or KeyProperties.PURPOSE_DECRYPT,
        )
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setAlgorithmParameterSpec(NamedParameterSpec("X25519"))
            .setIsStrongBoxBacked(true)
            .setUserAuthenticationRequired(false)

        val spec = try { builder.build() } catch (_: SecurityException) {
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
        if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias)
        cachedKeyPair.set(null); cachedAlias.set(null); debugScalar.set(null)
    }

    companion object { private const val ANDROID_KEYSTORE = "AndroidKeyStore" }
}

@Singleton
class BouncyCastleHpkeEngine @Inject constructor(
    private val keyProvider: HpkeKeyProvider,
    private val config: HpkeConfig = HpkeConfig.default(),
) : HpkeEngine {

    private object Hpke {
        // Lazily bind to whichever HPKE API is present at runtime
        private val hpkeClass = Class.forName("org.bouncycastle.crypto.hpke.HPKE")
        private val create = runCatching {
            hpkeClass.getMethod("create", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        }.getOrElse {
            // Some builds expose enums; fallback by resolving via ints still works because enums have `ordinal`-like int constants exposed as fields
            hpkeClass.getMethod("create", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        }

        // Resolve suite constants as ints (works across API shapes)
        private fun const(name: String): Int =
            (hpkeClass.getField(name).get(null) as? Int)
                ?: error("HPKE constant $name not found or not an int")

        private val KEM = const("KEM_X25519_HKDF_SHA256")
        private val KDF = const("KDF_HKDF_SHA256")
        private val AEAD = const("AEAD_AES_GCM_256")

        // Methods on the returned instance
        private val recipientCtxMethod by lazy {
            // createRecipientContext(AsymmetricKeyParameter, byte[], byte[], byte[])
            val anyInstance = create.invoke(null, KEM, KDF, AEAD)
            anyInstance.javaClass.getMethod(
                "createRecipientContext",
                AsymmetricKeyParameter::class.java,
                ByteArray::class.java,
                ByteArray::class.java,
                ByteArray::class.java,
            )
        }
        private val openMethod by lazy {
            val anyInstance = create.invoke(null, KEM, KDF, AEAD)
            val cls = anyInstance.javaClass
                .getMethod("createRecipientContext",
                    AsymmetricKeyParameter::class.java,
                    ByteArray::class.java,
                    ByteArray::class.java,
                    ByteArray::class.java).returnType
            cls.getMethod("open", ByteArray::class.java, ByteArray::class.java)
        }

        fun createRecipient(priv: AsymmetricKeyParameter, enc: ByteArray, info: ByteArray, aad: ByteArray): Any {
            val hpke = create.invoke(null, KEM, KDF, AEAD)
            return recipientCtxMethod.invoke(hpke, priv, enc, info, aad)
        }

        fun open(ctx: Any, ciphertext: ByteArray, aad: ByteArray): ByteArray {
            @Suppress("UNCHECKED_CAST")
            return openMethod.invoke(ctx, ciphertext, aad) as ByteArray
        }
    }

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
        val ctx = Hpke.createRecipient(
            keyProvider.getPrivateKeyParameters(),
            envelope.encapsulatedKey,
            config.info,
            aad,
        )
        return Hpke.open(ctx, envelope.ciphertext, aad)
    }
}

data class HpkeConfig(val info: ByteArray = ByteArray(0)) {
    companion object { fun default(): HpkeConfig = HpkeConfig(info = "LaurelID-mDL".toByteArray()) }
}

data class HpkeEnvelope(val encapsulatedKey: ByteArray, val ciphertext: ByteArray) {
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

data class HpkeKeyMetadata(val alias: String, val createdAt: Instant)

private fun extractScalar(privateKey: XECPrivateKey): ByteArray {
    val scalarValue: Any? = privateKey.scalar
    return when (scalarValue) {
        is ByteArray -> scalarValue
        is Optional<*> -> {
            val bytes = scalarValue.orElseThrow { IllegalStateException("Missing scalar in XECPrivateKey") }
            bytes as? ByteArray ?: throw IllegalStateException("Unexpected scalar representation: ${bytes?.javaClass?.name}")
        }
        null -> throw IllegalStateException("Missing scalar in XECPrivateKey")
        else -> throw IllegalStateException("Unexpected scalar representation: ${scalarValue.javaClass.name}")
    }
}
