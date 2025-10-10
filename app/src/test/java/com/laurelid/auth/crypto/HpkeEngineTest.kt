package com.laurelid.auth.crypto

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.laurelid.BuildConfig
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.interfaces.XECPublicKey
import java.security.spec.NamedParameterSpec
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.bouncycastle.crypto.hpke.HPKE
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HpkeEngineTest {

    private val hpke: HPKE = HPKE.create(
        HPKE.KEM_X25519_HKDF_SHA256,
        HPKE.KDF_HKDF_SHA256,
        HPKE.AEAD_AES_GCM_256,
    )

    @Test
    fun publicKeyBytesAre32BytesAndInterop() {
        val deterministic = deterministicPrivateKey()
        val provider = InMemoryHpkeKeyProvider(deterministic)
        val engine = BouncyCastleHpkeEngine(provider)
        val sender = hpke.createSenderContext(
            deterministic.generatePublicKey(),
            ByteArray(0),
            ByteArray(0),
            ByteArray(0),
        )
        val plaintext = "Hello HPKE".encodeToByteArray()
        val ciphertext = sender.seal(plaintext, ByteArray(0))
        val envelope = HpkeEnvelope(sender.encapsulated(), ciphertext)

        val decrypted = engine.decrypt(envelope.toByteArray(), ByteArray(0))
        assertContentEquals(plaintext.toList(), decrypted.toList())
        assertEquals(32, provider.getPublicKeyBytes().size)
        val expectedPublic = ByteArray(32)
        deterministic.generatePublicKey().encode(expectedPublic, 0)
        assertContentEquals(expectedPublic.toList(), provider.getPublicKeyBytes().toList())
    }

    @Test
    fun keystoreRecipientDecryptsMultipleRecords() {
        val keyPair = deterministicKeyPair()
        val provider = TestHandleKeyProvider(keyPair)
        val engine = BouncyCastleHpkeEngine(provider)
        runBlocking { engine.initRecipient("alias") }

        val publicKeyBytes = provider.getPublicKeyBytes()
        assertEquals(32, publicKeyBytes.size)
        val sender = hpke.createSenderContext(
            X25519PublicKeyParameters(publicKeyBytes, 0),
            ByteArray(0),
            ByteArray(0),
            ByteArray(0),
        )

        val aad = "multi-record-aad".encodeToByteArray()
        val messages = (0 until 3).map { index -> "record-$index".encodeToByteArray() }
        val openMethod = recipientOpenMethod()
        val encapsulated = sender.encapsulated()
        val recipient = recipientContext(engine, provider.getPrivateKeyHandle(), encapsulated, ByteArray(0), aad)

        messages.forEach { message ->
            val ciphertext = sender.seal(message, aad)
            val decrypted = openMethod.invoke(recipient, ciphertext, aad) as ByteArray
            assertContentEquals(message.toList(), decrypted.toList())
        }
    }

    @Test
    fun installDebugKeyOnlyAvailableInDebugBuild() {
        assumeTrue(BuildConfig.DEBUG)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val provider = AndroidHpkeKeyProvider(context)
        val debugKey = ByteArray(32) { (it + 1).toByte() }
        runBlocking { provider.ensureKey("debug-alias") }
        provider.installDebugKey("debug-alias", debugKey)
        val handle = provider.getPrivateKeyHandle()
        assertTrue(handle is HpkePrivateKeyHandle.Debug)
        val sender = hpke.createSenderContext(
            handle.parameters.generatePublicKey(),
            ByteArray(0),
            ByteArray(0),
            ByteArray(0),
        )
        val ciphertext = sender.seal("debug".encodeToByteArray(), ByteArray(0))
        val engine = BouncyCastleHpkeEngine(provider)
        runBlocking { engine.initRecipient("debug-alias") }
        val decrypted = engine.decrypt(HpkeEnvelope(sender.encapsulated(), ciphertext).toByteArray(), ByteArray(0))
        assertContentEquals("debug".encodeToByteArray().toList(), decrypted.toList())
    }

    private fun deterministicPrivateKey(): X25519PrivateKeyParameters {
        val seed = ByteArray(32) { it.toByte() }
        return X25519PrivateKeyParameters(seed, 0)
    }

    private fun deterministicKeyPair(): KeyPair {
        val generator = KeyPairGenerator.getInstance("XDH")
        val random = SecureRandom.getInstance("SHA1PRNG").apply { setSeed(ByteArray(32) { (it * 3).toByte() }) }
        generator.initialize(NamedParameterSpec("X25519"), random)
        return generator.generateKeyPair()
    }

    private fun recipientContext(
        engine: BouncyCastleHpkeEngine,
        handle: HpkePrivateKeyHandle,
        encapsulatedKey: ByteArray,
        info: ByteArray,
        aad: ByteArray,
    ): Any {
        val method = BouncyCastleHpkeEngine::class.java.getDeclaredMethod(
            "createRecipient",
            HpkePrivateKeyHandle::class.java,
            ByteArray::class.java,
            ByteArray::class.java,
            ByteArray::class.java,
        )
        method.isAccessible = true
        return method.invoke(engine, handle, encapsulatedKey, info, aad)
    }

    private fun recipientOpenMethod() =
        BouncyCastleHpkeEngine::class.java.declaredClasses.first { it.simpleName == "RecipientContext" }
            .getDeclaredMethod("open", ByteArray::class.java, ByteArray::class.java)
            .apply { isAccessible = true }

    private class TestHandleKeyProvider(private val keyPair: KeyPair) : HpkeKeyProvider {
        override suspend fun ensureKey(alias: String) = Unit

        override fun getPublicKeyBytes(): ByteArray {
            val public = keyPair.public as XECPublicKey
            val encoded = public.u.toByteArray()
            return if (encoded.size == 32) {
                encoded.copyOf()
            } else {
                ByteArray(32).apply {
                    val offset = size - encoded.size
                    System.arraycopy(encoded, 0, this, offset, encoded.size)
                }
            }
        }

        override fun getPrivateKeyHandle(): HpkePrivateKeyHandle =
            HpkePrivateKeyHandle.AndroidKeyStore(keyPair.private)

        override fun installDebugKey(alias: String, privateKey: ByteArray) = Unit
    }
}

