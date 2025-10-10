package com.laurelid.auth.crypto

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.security.KeyStore
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.bouncycastle.crypto.hpke.HPKE
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidHpkeEngineTest {

    private lateinit var context: Context
    private lateinit var alias: String
    private lateinit var keyStore: KeyStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        alias = "hpke_test_${System.currentTimeMillis()}"
        keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (keyStore.containsAlias(alias)) {
            keyStore.deleteEntry(alias)
        }
    }

    @After
    fun tearDown() {
        if (this::keyStore.isInitialized && keyStore.containsAlias(alias)) {
            keyStore.deleteEntry(alias)
        }
    }

    @Test
    fun decryptsCiphertextWithKeystoreHandle() = runBlocking {
        val provider = AndroidHpkeKeyProvider(context)
        provider.ensureKey(alias)
        val engine = BouncyCastleHpkeEngine(provider)
        val config = HpkeConfig.default()
        val hpke = HPKE.create(
            HPKE.KEM_X25519_HKDF_SHA256,
            HPKE.KDF_HKDF_SHA256,
            HPKE.AEAD_AES_GCM_256,
        )
        val aad = "instrumented-aad".encodeToByteArray()
        val plaintext = "hpke strongbox".encodeToByteArray()
        val sender = hpke.createSenderContext(
            X25519PublicKeyParameters(provider.getPublicKeyBytes(), 0),
            ByteArray(0),
            config.info,
            aad,
        )
        val ciphertext = sender.seal(plaintext, aad)
        val envelope = HpkeEnvelope(sender.encapsulated(), ciphertext)

        val decrypted = engine.decrypt(envelope.toByteArray(), aad)
        assertContentEquals(plaintext.toList(), decrypted.toList())
    }

    @Test
    fun returnsHandleForKeystoreKey() = runBlocking {
        val provider = AndroidHpkeKeyProvider(context)
        provider.ensureKey(alias)
        val handle = provider.getPrivateKeyHandle()
        assertTrue(handle is HpkePrivateKeyHandle.AndroidKeyStore)
    }
}
