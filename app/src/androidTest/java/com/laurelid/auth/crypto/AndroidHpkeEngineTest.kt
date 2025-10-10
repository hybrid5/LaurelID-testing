package com.laurelid.auth.crypto

import android.content.Context
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.security.KeyStore
import kotlin.text.Regex
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertEquals
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
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val provider = AndroidHpkeKeyProvider(context)
        provider.ensureKey(alias)
        val engine = BouncyCastleHpkeEngine(provider)
        assertTrue(provider.getPrivateKeyHandle() is HpkePrivateKeyHandle.AndroidKeyStore)
        val hpke = HPKE.create(
            HPKE.KEM_X25519_HKDF_SHA256,
            HPKE.KDF_HKDF_SHA256,
            HPKE.AEAD_AES_GCM_256,
        )
        instrumentation.uiAutomation.executeShellCommand("logcat -c").closeQuietly()

        val aad = "instrumented-aad".encodeToByteArray()
        val plaintext = "hpke strongbox".encodeToByteArray()
        val publicKeyBytes = provider.getPublicKeyBytes()
        assertEquals(32, publicKeyBytes.size)
        val sender = hpke.createSenderContext(
            X25519PublicKeyParameters(publicKeyBytes, 0),
            ByteArray(0),
            ByteArray(0),
            aad,
        )
        val ciphertext = sender.seal(plaintext, aad)
        val envelope = HpkeEnvelope(sender.encapsulated(), ciphertext)

        val decrypted = engine.decrypt(envelope.toByteArray(), aad)
        assertContentEquals(plaintext.toList(), decrypted.toList())

        val logDescriptor = instrumentation.uiAutomation.executeShellCommand("logcat -d -v raw LaurelID:I *:S")
        val logs = readLogs(logDescriptor)
        val privateMaterialPattern = Regex("\\b[0-9a-fA-F]{32,}\\b")
        assertTrue(logs.lines().filter { it.contains("LaurelID:HpkeKeyProvider") }.size <= 1)
        assertFalse(privateMaterialPattern.containsMatchIn(logs))
    }

    @Test
    fun returnsHandleForKeystoreKey() = runBlocking {
        val provider = AndroidHpkeKeyProvider(context)
        provider.ensureKey(alias)
        val handle = provider.getPrivateKeyHandle()
        assertTrue(handle is HpkePrivateKeyHandle.AndroidKeyStore)
    }
}

private fun ParcelFileDescriptor.closeQuietly() {
    try {
        close()
    } catch (_: Exception) {
    }
}

private fun readLogs(fd: ParcelFileDescriptor): String {
    return try {
        ParcelFileDescriptor.AutoCloseInputStream(fd).use { stream ->
            stream.bufferedReader().use { it.readText() }
        }
    } finally {
        fd.closeQuietly()
    }
}
