package com.laurelid.auth.crypto

import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import org.bouncycastle.crypto.hpke.HPKE
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HpkeEngineTest {

    @Test
    fun decryptsVector() {
        val privateKey = X25519PrivateKeyParameters(SecureRandomSource.next(), 0)
        val provider = InMemoryHpkeKeyProvider(privateKey)
        val engine = BouncyCastleHpkeEngine(provider)
        val hpke = HPKE.create(HPKE.KEM_X25519_HKDF_SHA256, HPKE.KDF_HKDF_SHA256, HPKE.AEAD_AES_GCM_256)
        val sender = hpke.createSenderContext(privateKey.generatePublicKey(), ByteArray(0), ByteArray(0), ByteArray(0))
        val plaintext = "Hello HPKE".encodeToByteArray()
        val ciphertext = sender.seal(plaintext, ByteArray(0))
        val envelope = HpkeEnvelope(sender.encapsulated(), ciphertext)

        val decrypted = engine.decrypt(envelope.toByteArray(), ByteArray(0))
        assertContentEquals(plaintext.toList(), decrypted.toList())
        assertEquals(32, provider.getPublicKeyBytes().size)
    }
}

private object SecureRandomSource {
    fun next(): ByteArray = java.security.SecureRandom().generateSeed(X25519PrivateKeyParameters.KEY_SIZE)
}

