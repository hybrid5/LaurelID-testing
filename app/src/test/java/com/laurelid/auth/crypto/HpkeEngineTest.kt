package com.laurelid.auth.crypto

import java.security.KeyPairGenerator
import java.security.spec.NamedParameterSpec
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.bouncycastle.crypto.hpke.HPKE
import org.junit.Test

class HpkeEngineTest {

    @Test
    fun decryptsVector() {
        val generator = KeyPairGenerator.getInstance("XDH")
        generator.initialize(NamedParameterSpec("X25519"))
        val keyPair = generator.generateKeyPair()
        val provider = InMemoryHpkeKeyProvider(keyPair)
        val engine = BouncyCastleHpkeEngine(provider)
        val hpke = HPKE.create(HPKE.KEM_X25519_HKDF_SHA256, HPKE.KDF_HKDF_SHA256, HPKE.AEAD_AES_GCM_256)
        val sender = hpke.createSenderContext(keyPair.public, ByteArray(0), ByteArray(0), ByteArray(0))
        val plaintext = "Hello HPKE".encodeToByteArray()
        val ciphertext = sender.seal(plaintext, ByteArray(0))
        val envelope = HpkeEnvelope(sender.encapsulated(), ciphertext)

        val decrypted = engine.decrypt(envelope.toByteArray(), ByteArray(0))
        decrypted.use { secure ->
            assertContentEquals(plaintext, secure.copy())
        }
        val wiped = decrypted.copy()
        assertTrue(wiped.all { it == 0.toByte() })
        assertEquals(provider.getPublicKeyBytes().size, 32)
    }
}

