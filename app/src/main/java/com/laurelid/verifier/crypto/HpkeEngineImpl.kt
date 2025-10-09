package com.laurelid.verifier.crypto

import javax.inject.Inject
import javax.inject.Singleton
import org.bouncycastle.crypto.hpke.HPKE
import org.bouncycastle.crypto.params.AsymmetricKeyParameter

/**
 * HPKE decryptor backed by BouncyCastle. The envelope format is defined in [HpkeEnvelope].
 */
@Singleton
class HpkeEngineImpl @Inject constructor(
    private val keyProvider: HpkeKeyProvider,
    private val config: HpkeConfig = HpkeConfig.default(),
) : HpkeEngine {

    override fun decrypt(enc: ByteArray, aad: ByteArray?): ByteArray {
        val envelope = HpkeEnvelope.parse(enc)
        val recipientContext = createRecipientContext(envelope, aad)
        return recipientContext.open(envelope.ciphertext, aad ?: EMPTY_AAD)
    }

    private fun createRecipientContext(envelope: HpkeEnvelope, aad: ByteArray?): HPKE.RecipientContext {
        val hpke = HPKE.create(config.kem, config.kdf, config.aead)
        val privateKeyParam = toBcPrivateKey()
        return hpke.createRecipientContext(
            privateKeyParam,
            envelope.encapsulatedKey,
            config.info,
            aad ?: EMPTY_AAD,
        )
    }

    private fun toBcPrivateKey(): AsymmetricKeyParameter {
        return keyProvider.getPrivateKeyParameters()
    }

    companion object {
        private val EMPTY_AAD = ByteArray(0)
    }
}

/** Configuration describing the HPKE primitive suite in use. */
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
        )
    }
}
