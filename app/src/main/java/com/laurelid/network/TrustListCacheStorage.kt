package com.laurelid.network

import android.content.Context
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.MasterKey
import com.laurelid.util.Logger
import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException

internal interface TrustListCacheStorage {
    fun read(): String?
    fun write(contents: String)
    fun delete()
}

internal class EncryptedTrustListCacheStorage(
    private val context: Context,
    private val file: File,
) : TrustListCacheStorage {

    private val masterKey by lazy {
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
    }

    override fun read(): String? {
        if (!file.exists()) {
            return null
        }
        return try {
            createEncryptedFile().openFileInput().bufferedReader().use { reader ->
                reader.readText()
            }
        } catch (security: GeneralSecurityException) {
            Logger.e(TAG, "Unable to decrypt trust list cache", security)
            delete()
            null
        } catch (ioException: IOException) {
            Logger.e(TAG, "Unable to read encrypted trust list cache", ioException)
            null
        }
    }

    override fun write(contents: String) {
        try {
            file.parentFile?.let { parent ->
                if (!parent.exists() && !parent.mkdirs()) {
                    Logger.w(TAG, "Unable to create trust list cache directory")
                }
            }
            createEncryptedFile().openFileOutput().use { output ->
                output.write(contents.toByteArray(Charsets.UTF_8))
            }
        } catch (security: GeneralSecurityException) {
            Logger.e(TAG, "Unable to encrypt trust list cache", security)
            throw IOException("Unable to persist trust list cache", security)
        } catch (ioException: IOException) {
            Logger.e(TAG, "Unable to persist encrypted trust list cache", ioException)
            throw ioException
        }
    }

    override fun delete() {
        if (file.exists() && !file.delete()) {
            Logger.w(TAG, "Unable to delete trust list cache file")
        }
    }

    private fun createEncryptedFile(): EncryptedFile {
        return EncryptedFile.Builder(
            context,
            file,
            masterKey,
            EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB,
        ).build()
    }

    companion object {
        private const val TAG = "EncryptedTrustCache"
    }
}

internal class AssetTrustListSeedStorage(
    private val context: Context,
    private val assetPath: String,
) : TrustListCacheStorage {

    override fun read(): String? {
        return try {
            context.assets.open(assetPath).bufferedReader().use { reader ->
                reader.readText()
            }
        } catch (throwable: Throwable) {
            Logger.w(TAG, "Unable to read seed trust list asset ($assetPath)", throwable)
            null
        }
    }

    override fun write(contents: String) {
        throw UnsupportedOperationException("Seed asset is read-only")
    }

    override fun delete() {
        // Seed assets are immutable; nothing to delete.
    }

    companion object {
        private const val TAG = "TrustSeedAsset"
    }
}
