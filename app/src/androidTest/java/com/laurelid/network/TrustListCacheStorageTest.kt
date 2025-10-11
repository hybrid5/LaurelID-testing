package com.laurelid.network

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TrustListCacheStorageTest {

    private lateinit var context: Context
    private lateinit var cacheFile: File
    private lateinit var storage: EncryptedTrustListCacheStorage

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        cacheFile = File(context.cacheDir, "trust/test_trust_list.json")
        cacheFile.parentFile?.let { parent ->
            if (!parent.exists()) {
                parent.mkdirs()
            }
        }
        if (cacheFile.exists()) {
            cacheFile.delete()
        }
        storage = EncryptedTrustListCacheStorage(context, cacheFile)
    }

    @After
    fun tearDown() {
        storage.delete()
    }

    @Test
    fun `encrypted cache persists without exposing plaintext`() {
        val plaintext = """{"entries":[{"issuer":"Test","cert":"MIIB"}],"version":1}"""
        storage.write(plaintext)

        assertTrue(cacheFile.exists())
        val rawBytes = cacheFile.readBytes()
        assertNotEquals(plaintext.toByteArray(Charsets.UTF_8).toList(), rawBytes.toList(), "Cache should not match plaintext bytes")

        val restored = storage.read()
        assertEquals(plaintext, restored)
    }

    @Test
    fun `corrupted ciphertext clears cache`() {
        storage.write("""{"empty":true}""")
        cacheFile.writeText("tampered")

        val restored = storage.read()
        assertNull(restored)
        assertTrue(!cacheFile.exists() || cacheFile.length() == 0L, "Corrupted cache should be deleted")
    }
}
