package com.laurelid.auth.crypto

import android.content.Context
import android.content.pm.PackageManager
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import com.laurelid.BuildConfig
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.interfaces.XECPublicKey
import java.security.spec.X509EncodedKeySpec
import java.security.spec.NamedParameterSpec
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.min
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import dagger.hilt.android.qualifiers.ApplicationContext
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
    fun getPrivateKeyHandle(): HpkePrivateKeyHandle
    fun installDebugKey(alias: String, privateKey: ByteArray)
}

sealed interface HpkePrivateKeyHandle {
    data class AndroidKeyStore(val privateKey: PrivateKey) : HpkePrivateKeyHandle
    data class Debug(val parameters: X25519PrivateKeyParameters) : HpkePrivateKeyHandle
}

@Singleton
class AndroidHpkeKeyProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) : HpkeKeyProvider {

    private val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    private val cachedAlias = AtomicReference<String?>(null)
    private val cachedKeyPair = AtomicReference<KeyPair?>()
    private val debugKey = AtomicReference<X25519PrivateKeyParameters?>(null)
    private val lock = Mutex()
    private val strongBoxAvailable: Boolean by lazy {
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)
    }

    override suspend fun ensureKey(alias: String) {
        lock.withLock {
            val currentAlias = cachedAlias.get()
            if (currentAlias == alias && (cachedKeyPair.get() != null || debugKey.get() != null)) return
            val existing = keyStore.getEntry(alias, null) as? KeyStore.PrivateKeyEntry
            if (existing != null) {
                cachedAlias.set(alias)
                cachedKeyPair.set(KeyPair(existing.certificate.publicKey, existing.privateKey))
                debugKey.set(null)
                return
            }
            cachedKeyPair.set(generate(alias))
            cachedAlias.set(alias)
            debugKey.set(null)
        }
    }

    override fun getPublicKeyBytes(): ByteArray {
        debugKey.get()?.let { key ->
            val public = ByteArray(X25519PrivateKeyParameters.KEY_SIZE)
            key.generatePublicKey().encode(public, 0)
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

    override fun getPrivateKeyHandle(): HpkePrivateKeyHandle {
        debugKey.get()?.let { return HpkePrivateKeyHandle.Debug(it) }
        val keyPair = cachedKeyPair.get() ?: error("HPKE key has not been initialised")
        return HpkePrivateKeyHandle.AndroidKeyStore(keyPair.private)
    }

    override fun installDebugKey(alias: String, privateKey: ByteArray) {
        require(privateKey.size == X25519PrivateKeyParameters.KEY_SIZE) { "Invalid X25519 key" }
        if (!BuildConfig.DEBUG) error("Debug key installation is only available in debug builds")
        if (!lock.tryLock()) error("Unable to obtain keystore lock for debug import")
        try {
            cachedKeyPair.set(null)
            cachedAlias.set(alias)
            debugKey.set(X25519PrivateKeyParameters(privateKey.copyOf(), 0))
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
            .setIsStrongBoxBacked(strongBoxAvailable)
            .setUserAuthenticationRequired(false)

        val spec = try {
            builder.build()
        } catch (_: SecurityException) {
            builder.setIsStrongBoxBacked(false).build()
        } catch (_: StrongBoxUnavailableException) {
            builder.setIsStrongBoxBacked(false).build()
        }

        generator.initialize(spec)
        val pair = generator.generateKeyPair()
        cachedKeyPair.set(pair)
        cachedAlias.set(alias)
        debugKey.set(null)
        return pair
    }

    @Suppress("unused")
    private fun delete(alias: String) {
        if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias)
        cachedKeyPair.set(null); cachedAlias.set(null); debugKey.set(null)
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
        val ctx = createRecipient(
            keyProvider.getPrivateKeyHandle(),
            envelope.encapsulatedKey,
            config.info,
            aad,
        )
        return ctx.open(envelope.ciphertext, aad)
    }

    private fun createRecipient(
        handle: HpkePrivateKeyHandle,
        encapsulatedKey: ByteArray,
        info: ByteArray,
        aad: ByteArray,
    ): RecipientContext {
        return when (handle) {
            is HpkePrivateKeyHandle.Debug -> BcRecipientContext(
                privateKey = handle.parameters,
                encapsulatedKey = encapsulatedKey,
                info = info,
                aad = aad,
            )
            is HpkePrivateKeyHandle.AndroidKeyStore -> KeystoreRecipientContext(
                privateKey = handle.privateKey,
                encapsulatedKey = encapsulatedKey,
                info = info,
            )
        }
    }

    private interface RecipientContext {
        fun open(ciphertext: ByteArray, aad: ByteArray): ByteArray
    }

    private class BcRecipientContext(
        private val privateKey: X25519PrivateKeyParameters,
        encapsulatedKey: ByteArray,
        info: ByteArray,
        aad: ByteArray,
    ) : RecipientContext {
        private val ctx: Any

        init {
            ctx = Hpke.createRecipient(privateKey, encapsulatedKey, info, aad)
        }

        override fun open(ciphertext: ByteArray, aad: ByteArray): ByteArray =
            Hpke.open(ctx, ciphertext, aad)
    }

    private class KeystoreRecipientContext(
        private val privateKey: PrivateKey,
        encapsulatedKey: ByteArray,
        info: ByteArray,
    ) : RecipientContext {
        private val key: SecretKeySpec
        private val baseNonce: ByteArray

        init {
            val sharedSecret = deriveSharedSecret(privateKey, encapsulatedKey)
            val schedule = HpkeKeySchedule(sharedSecret, info)
            key = SecretKeySpec(schedule.key, "AES")
            baseNonce = schedule.baseNonce
        }

        override fun open(ciphertext: ByteArray, aad: ByteArray): ByteArray {
            val cipher = Cipher.getInstance(AES_GCM_NO_PADDING)
            val spec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, baseNonce)
            cipher.init(Cipher.DECRYPT_MODE, key, spec)
            if (aad.isNotEmpty()) cipher.updateAAD(aad)
            return cipher.doFinal(ciphertext)
        }
    }

    private data class HpkeKeySchedule(val key: ByteArray, val baseNonce: ByteArray)

    private companion object {
        private const val AES_GCM_NO_PADDING = "AES/GCM/NoPadding"
        private const val GCM_TAG_LENGTH_BITS = 128
        private const val MODE_BASE = 0
        private const val KEM_ID = 0x0020
        private const val KDF_ID = 0x0001
        private const val AEAD_ID = 0x0002
        private const val AES_256_KEY_LEN = 32
        private const val NONCE_LEN = 12
        private const val HASH_LEN = 32
        private val HPKE_VERSION = "HPKE-v1".encodeToByteArray()
        private val SUITE_ID = buildSuiteId()

        private fun buildSuiteId(): ByteArray {
            val buffer = ByteArrayOutputStream()
            buffer.write("HPKE".encodeToByteArray())
            buffer.write(i2osp(KEM_ID, 2))
            buffer.write(i2osp(KDF_ID, 2))
            buffer.write(i2osp(AEAD_ID, 2))
            return buffer.toByteArray()
        }

        private fun deriveSharedSecret(privateKey: PrivateKey, peerPublic: ByteArray): ByteArray {
            val publicKey = decodeX25519PublicKey(peerPublic)
            val keyAgreement = try {
                KeyAgreement.getInstance("X25519")
            } catch (_: Exception) {
                KeyAgreement.getInstance("XDH")
            }
            keyAgreement.init(privateKey)
            keyAgreement.doPhase(publicKey, true)
            return keyAgreement.generateSecret()
        }

        private fun decodeX25519PublicKey(raw: ByteArray) = run {
            require(raw.size == X25519PrivateKeyParameters.KEY_SIZE) { "Invalid encapsulated key" }
            val prefix = byteArrayOf(
                0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x6e, 0x03, 0x21, 0x00,
            )
            val encoded = ByteArray(prefix.size + raw.size)
            System.arraycopy(prefix, 0, encoded, 0, prefix.size)
            System.arraycopy(raw, 0, encoded, prefix.size, raw.size)
            val spec = X509EncodedKeySpec(encoded)
            val factory = try {
                java.security.KeyFactory.getInstance("X25519")
            } catch (_: Exception) {
                java.security.KeyFactory.getInstance("XDH")
            }
            factory.generatePublic(spec)
        }

        private fun HpkeKeySchedule(sharedSecret: ByteArray, info: ByteArray): HpkeKeySchedule {
            val zeroSalt = ByteArray(0)
            val psk = ByteArray(0)
            val pskId = ByteArray(0)
            val pskIdHash = labeledExtract(zeroSalt, "psk_id_hash", pskId)
            val infoHash = labeledExtract(zeroSalt, "info_hash", info)
            val keyScheduleContext = byteArrayOf(MODE_BASE.toByte()) + pskIdHash + infoHash
            val secret = labeledExtract(sharedSecret, "secret", psk)
            val key = labeledExpand(secret, "key", keyScheduleContext, AES_256_KEY_LEN)
            val baseNonce = labeledExpand(secret, "base_nonce", keyScheduleContext, NONCE_LEN)
            return HpkeKeySchedule(key, baseNonce)
        }

        private fun labeledExtract(salt: ByteArray, label: String, ikm: ByteArray): ByteArray {
            val labeledIkm = HPKE_VERSION + SUITE_ID + label.encodeToByteArray() + ikm
            return hkdfExtract(salt, labeledIkm)
        }

        private fun labeledExpand(prk: ByteArray, label: String, info: ByteArray, length: Int): ByteArray {
            val labeledInfo = i2osp(length, 2) + HPKE_VERSION + SUITE_ID + label.encodeToByteArray() + info
            return hkdfExpand(prk, labeledInfo, length)
        }

        private fun hkdfExtract(salt: ByteArray, ikm: ByteArray): ByteArray {
            val mac = Mac.getInstance("HmacSHA256")
            val actualSalt = if (salt.isEmpty()) ByteArray(HASH_LEN) else salt
            mac.init(SecretKeySpec(actualSalt, "HmacSHA256"))
            return mac.doFinal(ikm)
        }

        private fun hkdfExpand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(prk, "HmacSHA256"))
            var previous = ByteArray(0)
            val result = ByteArray(length)
            var offset = 0
            var counter = 1
            while (offset < length) {
                mac.reset()
                if (previous.isNotEmpty()) mac.update(previous)
                mac.update(info)
                mac.update(counter.toByte())
                previous = mac.doFinal()
                val toCopy = min(previous.size, length - offset)
                System.arraycopy(previous, 0, result, offset, toCopy)
                offset += toCopy
                counter++
            }
            return result
        }

        private fun i2osp(value: Int, length: Int): ByteArray {
            require(length in 1..4) { "Unsupported length" }
            val result = ByteArray(length)
            for (i in 0 until length) {
                result[length - 1 - i] = (value shr (8 * i) and 0xFF).toByte()
            }
            return result
        }
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

class InMemoryHpkeKeyProvider(private val privateKey: X25519PrivateKeyParameters) : HpkeKeyProvider {
    private val publicKeyBytes: ByteArray by lazy {
        val public = ByteArray(X25519PrivateKeyParameters.KEY_SIZE)
        privateKey.generatePublicKey().encode(public, 0)
        public
    }

    override suspend fun ensureKey(alias: String) = Unit
    override fun getPublicKeyBytes(): ByteArray = publicKeyBytes.copyOf()
    override fun getPrivateKeyHandle(): HpkePrivateKeyHandle = HpkePrivateKeyHandle.Debug(privateKey)
    override fun installDebugKey(alias: String, privateKey: ByteArray) = Unit
}

data class HpkeKeyMetadata(val alias: String, val createdAt: Instant)
