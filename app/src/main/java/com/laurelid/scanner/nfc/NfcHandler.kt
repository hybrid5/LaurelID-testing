package com.laurelid.scanner.nfc

import android.app.PendingIntent
import android.content.Intent
import android.content.IntentFilter
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.os.Build
import androidx.annotation.VisibleForTesting
import com.laurelid.util.Logger
import java.nio.charset.StandardCharsets
import javax.inject.Inject

class NfcHandler @Inject constructor() {

    fun enableForegroundDispatch(
        activity: androidx.appcompat.app.AppCompatActivity,
        adapter: NfcAdapter?,
        pendingIntent: PendingIntent?,
        intentFilters: Array<IntentFilter>?,
    ) {
        if (adapter == null || !adapter.isEnabled) {
            Logger.w(TAG, "NFC adapter not available or not enabled, cannot enable foreground dispatch.")
            return
        }
        try {
            adapter.enableForegroundDispatch(activity, pendingIntent, intentFilters, null)
            Logger.i(TAG, "NFC foreground dispatch enabled.")
        } catch (error: Exception) {
            Logger.e(TAG, "Error enabling NFC foreground dispatch", error)
        }
    }

    fun disableForegroundDispatch(activity: androidx.appcompat.app.AppCompatActivity, adapter: NfcAdapter?) {
        try {
            adapter?.disableForegroundDispatch(activity)
            Logger.i(TAG, "NFC foreground dispatch disabled.")
        } catch (error: IllegalStateException) {
            Logger.w(TAG, "Failed to disable NFC foreground dispatch", error)
        }
    }

    fun extractPayload(intent: Intent?): ByteArray? {
        if (intent == null || intent.action != NfcAdapter.ACTION_NDEF_DISCOVERED) {
            return null
        }
        if (intent.type != MDL_MIME_TYPE) {
            Logger.w(TAG, "NFC intent received with incorrect MIME type: ${intent.type}")
            return null
        }
        val messages: Array<NdefMessage>? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES, NdefMessage::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES)?.mapNotNull { it as? NdefMessage }?.toTypedArray()
        }

        val record = messages?.firstOrNull()?.records?.firstOrNull { candidate ->
            candidate.tnf == NdefRecord.TNF_MIME_MEDIA &&
                String(candidate.type, StandardCharsets.US_ASCII) == MDL_MIME_TYPE
        }
        return record?.payload
    }

    @VisibleForTesting
    internal fun isMdocMimeType(type: ByteArray): Boolean {
        return String(type, StandardCharsets.US_ASCII) == MDL_MIME_TYPE
    }

    companion object {
        private const val TAG = "NfcHandler"
        const val MDL_MIME_TYPE = "application/iso.18013-5+mdoc"
    }
}
