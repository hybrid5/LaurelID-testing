package com.laurelid.observability

import android.content.Context
import com.laurelid.BuildConfig
import com.laurelid.util.Logger
import java.io.File
import java.io.IOException
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import org.json.JSONObject

/**
 * Persists structured telemetry events to a newline-delimited JSON file. Intended for staging builds
 * where developers can retrieve the payload from the app's private storage.
 */
class FileStructuredEventExporter(
    private val directoryProvider: () -> File,
    private val isDebugBuild: () -> Boolean = { BuildConfig.DEBUG },
) : IEventExporter {

    constructor(context: Context) : this(
        directoryProvider = { File(context.filesDir, TELEMETRY_DIRECTORY) },
    )

    private val lock = ReentrantLock()

    override fun export(event: StructuredEvent) {
        if (!isDebugBuild.invoke()) {
            Logger.w(TAG, "Skipping telemetry export: plaintext exporter disabled for non-debug builds")
            return
        }
        lock.withLock {
            val directory = directoryProvider.invoke()
            if (!ensureDirectory(directory)) {
                return
            }

            val file = File(directory, EVENTS_FILE)
            val json = event.toJsonString()
            try {
                file.appendText(json + System.lineSeparator())
            } catch (ioException: IOException) {
                Logger.e(TAG, "Unable to append telemetry event", ioException)
            }
        }
    }

    private fun ensureDirectory(directory: File): Boolean {
        if (directory.exists()) {
            return true
        }
        return try {
            directory.mkdirs()
        } catch (securityException: SecurityException) {
            Logger.e(TAG, "Unable to create telemetry directory", securityException)
            false
        }
    }

    private fun StructuredEvent.toJsonString(): String {
        val json = JSONObject()
        json.put(KEY_EVENT, event)
        json.put(KEY_TIMESTAMP_MS, timestampMs)
        scanDurationMs?.let { json.put(KEY_SCAN_DURATION_MS, it) }
        success?.let { json.put(KEY_SUCCESS, it) }
        reasonCode?.let { json.put(KEY_REASON_CODE, it) }
        trustStale?.let { json.put(KEY_TRUST_STALE, it) }
        return json.toString()
    }

    companion object {
        internal const val TELEMETRY_DIRECTORY = "telemetry"
        internal const val EVENTS_FILE = "events.log"
        private const val TAG = "FileEventExporter"
        private const val KEY_EVENT = "event"
        private const val KEY_TIMESTAMP_MS = "timestamp_ms"
        private const val KEY_SCAN_DURATION_MS = "scan_duration_ms"
        private const val KEY_SUCCESS = "success"
        private const val KEY_REASON_CODE = "reason_code"
        private const val KEY_TRUST_STALE = "trust_stale"
    }
}
