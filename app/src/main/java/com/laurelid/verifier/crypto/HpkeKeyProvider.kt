package com.laurelid.verifier.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.interfaces.XECPrivateKey
import java.security.interfaces.XECPublicKey
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters

/**
 * Provides access to the verifier's HPKE key pair. Keys are generated and stored inside the
 * Android Keystore and, when available, backed by StrongBox.
 */
interface HpkeKeyProvider {
    fun getPublicKeyBytes(): ByteArray
    fun getPrivateKeyParameters(): X25519PrivateKeyParameters
}

@Singleton
class AndroidHpkeKeyProvider @Inject constructor() : HpkeKeyProvider {
    private val alias = "laurelid_hpke_x25519"
    private val cachedKey = AtomicReference<KeyPair>()

    override fun getPublicKeyBytes(): ByteArray {
        val publicKey = ensureKeyPair().public as XECPublicKey
        return publicKey.u.toByteArray()
    }

    override fun getPrivateKeyParameters(): X25519PrivateKeyParameters {
        val privateKey = ensureKeyPair().private as? XECPrivateKey
            ?: error("HPKE key must be X25519")
        val scalar = privateKey.scalar
            ?: error("HPKE private key does not expose scalar material")
        return X25519PrivateKeyParameters(scalar, 0)
    }

    private fun ensureKeyPair(): KeyPair {
        return cachedKey.get() ?: synchronized(this) {
            cachedKey.get() ?: generateOrLoad().also { cachedKey.set(it) }
        }
    }

    private fun generateOrLoad(): KeyPair {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = keyStore.getEntry(alias, null) as? KeyStore.PrivateKeyEntry
        if (existing != null) {
            return KeyPair(existing.certificate.publicKey, existing.privateKey)
        }

        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_XDH, "AndroidKeyStore")
        val builder = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_AGREE_KEY or KeyProperties.PURPOSE_DECRYPT,
        )
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setAlgorithmParameterSpec(
                java.security.spec.NamedParameterSpec("X25519"),
            )
            .setIsStrongBoxBacked(true)
            .setUserAuthenticationRequired(false)
        try {
            generator.initialize(builder.build())
        } catch (securityException: Exception) {
            // StrongBox may be unavailable. Retry without it.
            val fallbackBuilder = builder.setIsStrongBoxBacked(false).build()
            generator.initialize(fallbackBuilder)
        }
        return generator.generateKeyPair().also { cachedKey.set(it) }
    }
}

/** Simple in-memory provider for tests. */
class InMemoryHpkeKeyProvider(private val keyPair: KeyPair) : HpkeKeyProvider {
    override fun getPublicKeyBytes(): ByteArray = (keyPair.public as XECPublicKey).u.toByteArray()

    override fun getPrivateKeyParameters(): X25519PrivateKeyParameters {
        val privateKey = keyPair.private as XECPrivateKey
        val scalar = privateKey.scalar ?: error("Missing scalar")
        return X25519PrivateKeyParameters(scalar, 0)
    }
}
