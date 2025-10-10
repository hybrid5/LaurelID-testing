package com.laurelid.crypto

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import com.laurelid.auth.crypto.HpkePrivateKeyHandle
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters

class HpkeEngineTest {

    @Test
    fun `parse sample envelope`() {
        val encoded = readResource("mdoc/payloads/sample_ciphertext.b64")
        val envelopeBytes = Base64.getDecoder().decode(encoded.trim())
        val envelope = HpkeEnvelope.parse(envelopeBytes)
        assertEquals(2, envelope.encapsulatedKey.size)
        assertEquals(5, envelope.ciphertext.size)
        val roundTrip = envelope.toByteArray()
        assertContentEquals(envelopeBytes.toList(), roundTrip.toList())
    }

    @Test
    fun `in-memory provider exposes key bytes`() {
        val privateKey = X25519PrivateKeyParameters(SecureRandomSource.next(), 0)
        val provider = InMemoryHpkeKeyProvider(privateKey)
        val publicKey = provider.getPublicKeyBytes()
        val handle = provider.getPrivateKeyHandle() as HpkePrivateKeyHandle.Debug
        assertEquals(publicKey.size, handle.parameters.encoded.size)
    }

    private fun readResource(path: String): String =
        javaClass.classLoader!!.getResource(path)!!.readText()
}

private object SecureRandomSource {
    fun next(): ByteArray = java.security.SecureRandom().generateSeed(X25519PrivateKeyParameters.KEY_SIZE)
}
