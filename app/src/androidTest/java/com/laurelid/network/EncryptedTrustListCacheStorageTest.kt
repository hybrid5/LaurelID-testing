package com.laurelid.network

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.text.Charsets
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EncryptedTrustListCacheStorageTest {

    private lateinit var context: Context
    private lateinit var directory: File
    private lateinit var cacheFile: File

    @BeforeTest
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        directory = File(context.filesDir, "encrypted-trust-cache-test").apply {
            deleteRecursively()
            mkdirs()
        }
        cacheFile = File(directory, "trust_list.json")
    }

    @AfterTest
    fun tearDown() {
        directory.deleteRecursively()
    }

    @Test
    fun encryptedCacheIsNotPlainText() {
        val storage = EncryptedTrustListCacheStorage(context, cacheFile)
        val json = """{"entries":{"AZ":"cert"},"revokedSerialNumbers":[]}"""
        storage.write(json)

        val raw = cacheFile.readBytes()
        assertTrue(raw.isNotEmpty(), "encrypted cache should persist bytes")

        val printableCount = raw.count { byte ->
            byte in 0x20..0x7e || byte == 0x0a.toByte() || byte == 0x0d.toByte() || byte == 0x09.toByte()
        }
        assertTrue(printableCount < raw.size, "encrypted cache should contain non-printable bytes")

        val contents = String(raw, Charsets.UTF_8)
        assertFalse(contents.contains("\"entries\""), "encrypted cache must not expose JSON fields")
    }
}
