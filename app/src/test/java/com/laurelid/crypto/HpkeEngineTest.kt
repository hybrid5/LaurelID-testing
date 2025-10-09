package com.laurelid.crypto

import java.security.KeyPairGenerator
import java.security.spec.NamedParameterSpec
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
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
        val generator = KeyPairGenerator.getInstance("XDH")
        generator.initialize(NamedParameterSpec("X25519"))
        val pair = generator.generateKeyPair()
        val provider = InMemoryHpkeKeyProvider(pair)
        val publicKey = provider.getPublicKeyBytes()
        val privateParams = provider.getPrivateKeyParameters()
        assertEquals(publicKey.size, (privateParams as X25519PrivateKeyParameters).encoded.size)
    }

    private fun readResource(path: String): String =
        javaClass.classLoader!!.getResource(path)!!.readText()
}
