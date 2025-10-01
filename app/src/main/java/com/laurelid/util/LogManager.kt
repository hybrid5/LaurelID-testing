package com.laurelid.util

import android.content.Context
import com.laurelid.config.AdminConfig
import com.laurelid.data.VerificationResult
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.time.Clock
import java.util.concurrent.TimeUnit
import javax.inject.Singleton
import org.json.JSONObject

@Singleton
open class LogManager constructor(
    @ApplicationContext private val context: Context,
    private val clock: Clock,
) {
    private const val LOG_DIR = "logs"
    private const val LOG_FILE = "verify.log"
    private const val MIGRATION_PREFS = "log_migrations"
    private const val KEY_LEGACY_PURGED = "legacy_logs_purged"
    private val TIMESTAMP_REGEX = Regex("\"ts\":(\\d+)")

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
            if (!dir.exists()) {
                dir.mkdirs()
            }
            val file = File(dir, LOG_FILE)
            val payload = JSONObject().apply {
                put("ts", clock.millis())
                put("venueId", config.venueId)
                put("success", result.success)
                put("ageOver21", result.ageOver21 == true)
                put("error", result.error)
                put("demoMode", demoModeUsed)
            }.toString()
            file.appendText("$payload\n")
        } catch (ioException: IOException) {
            Logger.e(TAG, "Unable to append verification log", ioException)
        }
    }

    open fun purgeOldLogs() {
        val logFile = File(File(context.filesDir, LOG_DIR), LOG_FILE)
        if (!logFile.exists()) return
        val lines = try {
            logFile.readLines()
        } catch (ioException: IOException) {
            Logger.e(TAG, "Unable to read verification logs for retention", ioException)
            return
        }
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
                logFile.delete()
            } else {
                logFile.writeText(filtered.joinToString(separator = "\n", postfix = "\n"))
            }
            Logger.i(TAG, "Purged verification logs older than 30 days")
        } catch (ioException: IOException) {
            Logger.e(TAG, "Unable to rewrite verification logs", ioException)
        }
    }

    private const val TAG = "LogManager"
}
