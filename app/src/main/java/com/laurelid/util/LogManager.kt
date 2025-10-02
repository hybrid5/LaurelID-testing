package com.laurelid.util

import android.content.Context
import android.util.Base64
import androidx.annotation.VisibleForTesting
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.MasterKey
import com.laurelid.auth.VerifierService
import com.laurelid.config.AdminConfig
import com.laurelid.data.VerificationResult
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.time.Clock
import java.util.concurrent.TimeUnit
import javax.inject.Singleton
import org.json.JSONObject

@Singleton
open class LogManager constructor(
    @ApplicationContext private val context: Context,
    private val clock: Clock,
) {
    private val masterKey by lazy {
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
    }

    protected open val maxLogBytes: Long = 64 * 1024L

    open fun purgeLegacyLogs() {
        val prefs = context.getSharedPreferences(MIGRATION_PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_LEGACY_PURGED, false)) {
            return
        }

        val dir = File(context.filesDir, LOG_DIR)
        if (dir.exists()) {
            dir.deleteRecursively()
        }

        prefs.edit().putBoolean(KEY_LEGACY_PURGED, true).apply()
    }

    open fun appendVerification(result: VerificationResult, config: AdminConfig, demoModeUsed: Boolean) {
        try {
            val dir = File(context.filesDir, LOG_DIR)
            if (!dir.exists() && !dir.mkdirs()) {
                Logger.w(TAG, "Unable to create log directory")
                return
            }
            val file = File(dir, LOG_FILE)
            rotateIfNeeded(file)

            val payload = JSONObject().apply {
                put("ts", clock.millis())
                put("venueId", REDACTED_VENUE_ID)
                put("success", JSONObject.NULL)
                put("ageOver21", JSONObject.NULL)
                val reasonCode = VerifierService.sanitizeReasonCode(result.error)
                val errorHash = reasonCode?.let { hashError(it) }
                put("error", errorHash ?: JSONObject.NULL)
                put("demoMode", JSONObject.NULL)
            }.toString()

            val lines = readEncryptedLines(file).toMutableList()
            lines.add(payload)
            writeEncryptedLines(file, lines)
        } catch (ioException: IOException) {
            Logger.e(TAG, "Unable to append verification log", ioException)
        } catch (securityException: GeneralSecurityException) {
            Logger.e(TAG, "Unable to encrypt verification log", securityException)
        }
    }

    open fun purgeOldLogs() {
        val dir = File(context.filesDir, LOG_DIR)
        val logFile = File(dir, LOG_FILE)
        if (!logFile.exists()) return

        val lines = readEncryptedLines(logFile)
        if (lines.isEmpty()) return

        val cutoff = clock.millis() - TimeUnit.DAYS.toMillis(30)
        val filtered = lines.filter { line ->
            val timestamp = TIMESTAMP_REGEX.find(line)?.groupValues?.getOrNull(1)?.toLongOrNull()
            timestamp == null || timestamp >= cutoff
        }
        if (filtered.size == lines.size) {
            return
        }
        try {
            if (filtered.isEmpty()) {
                if (!logFile.delete()) {
                    Logger.w(TAG, "Unable to delete expired verification log file")
                }
            } else {
                writeEncryptedLines(logFile, filtered)
            }
            Logger.i(TAG, "Purged verification logs older than 30 days")
        } catch (ioException: IOException) {
            Logger.e(TAG, "Unable to rewrite verification logs", ioException)
        } catch (securityException: GeneralSecurityException) {
            Logger.e(TAG, "Unable to encrypt verification logs during purge", securityException)
        }
    }

    @VisibleForTesting
    internal open fun readEncryptedLines(file: File): List<String> {
        if (!file.exists()) return emptyList()
        return try {
            createEncryptedFile(file).openFileInput().bufferedReader().use { reader ->
                reader.readLines().filter { it.isNotBlank() }
            }
        } catch (ioException: IOException) {
            Logger.e(TAG, "Unable to read encrypted verification logs", ioException)
            emptyList()
        } catch (securityException: GeneralSecurityException) {
            Logger.e(TAG, "Unable to decrypt verification logs", securityException)
            emptyList()
        }
    }

    private fun writeEncryptedLines(file: File, lines: List<String>) {
        val encryptedFile = createEncryptedFile(file)
        encryptedFile.openFileOutput().use { outputStream ->
            val content = if (lines.isEmpty()) "" else lines.joinToString(separator = "\n", postfix = "\n")
            outputStream.write(content.toByteArray(Charsets.UTF_8))
        }
    }

    private fun createEncryptedFile(file: File): EncryptedFile {
        return EncryptedFile.Builder(
            context,
            file,
            masterKey,
            EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB,
        ).build()
    }

    private fun rotateIfNeeded(file: File) {
        if (!file.exists()) return
        if (file.length() < maxLogBytes) return

        val backup = File(file.parentFile, LOG_FILE_BACKUP)
        if (backup.exists() && !backup.delete()) {
            Logger.w(TAG, "Unable to delete existing rotated log file")
        }
        if (!file.renameTo(backup)) {
            Logger.w(TAG, "Unable to rotate verification log")
        }
    }

    private companion object {
        private const val LOG_DIR = "logs"
        private const val LOG_FILE = "verify.log"
        private const val LOG_FILE_BACKUP = "verify.log.1"
        private const val MIGRATION_PREFS = "log_migrations"
        private const val KEY_LEGACY_PURGED = "legacy_logs_purged"
        private val TIMESTAMP_REGEX = Regex("\"ts\":(\\d+)")
        private const val TAG = "LogManager"
        private const val REDACTED_VENUE_ID = "REDACTED"
    }

    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    internal open fun hashError(error: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(error.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(digest, Base64.NO_WRAP)
    }
}
