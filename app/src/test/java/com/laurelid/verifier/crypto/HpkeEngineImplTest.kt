package com.laurelid.verifier.crypto

import java.io.File
import java.util.Base64
import kotlin.test.assertContentEquals
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.json.JSONObject
import org.junit.Test

class HpkeEngineImplTest {

    @Test
    fun decryptsSampleVector() {
        val json = File("src/main/res/raw/sample_hpke_vector.json").readText()
        val payload = JSONObject(json)
        val privateKey = Base64.getDecoder().decode(payload.getString("recipient_private_key_b64"))
        val encapsulated = Base64.getDecoder().decode(payload.getString("encapsulated_key_b64"))
        val ciphertext = Base64.getDecoder().decode(payload.getString("ciphertext_b64"))
        val aad = payload.optString("aad_b64").takeIf { it.isNotEmpty() }?.let {
            Base64.getDecoder().decode(it)
        }
        val expectedPlaintext = Base64.getDecoder().decode(payload.getString("plaintext_b64"))

        val privateParams = X25519PrivateKeyParameters(privateKey, 0)
        val provider = object : HpkeKeyProvider {
            private val public = ByteArray(32).also { privateParams.generatePublicKey().encode(it, 0) }
            override fun getPublicKeyBytes(): ByteArray = public
            override fun getPrivateKeyParameters(): X25519PrivateKeyParameters = privateParams
        }

        val engine = HpkeEngineImpl(provider)
        val envelope = HpkeEnvelope(encapsulated, ciphertext)
        val plaintext = engine.decrypt(envelope.toByteArray(), aad)
        assertContentEquals(expectedPlaintext.asList(), plaintext.asList())
    }
}
