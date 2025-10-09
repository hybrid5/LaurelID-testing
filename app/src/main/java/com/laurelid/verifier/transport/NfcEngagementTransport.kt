package com.laurelid.verifier.transport

import android.app.Activity
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.os.Bundle
import com.laurelid.util.Logger
import java.lang.ref.WeakReference
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * NFC device engagement handler that enables reader mode and captures the device engagement
 * transcript. The implementation keeps a short transcript in memory only while the session is
 * active.
 */
@Singleton
class NfcEngagementTransport @Inject constructor(
    private val adapterProvider: NfcAdapterProvider,
) : EngagementTransport, NfcAdapter.ReaderCallback {

    private val activityRef = AtomicReference<WeakReference<Activity>?>(null)
    private val sessionRef = AtomicReference<EngagementSession?>(null)
    private val sessionFlow = MutableStateFlow<EngagementSession?>(null)

    fun bind(activity: Activity) {
        activityRef.set(WeakReference(activity))
    }

    fun sessions(): StateFlow<EngagementSession?> = sessionFlow

    override suspend fun start(): EngagementSession {
        val adapter = adapterProvider.get() ?: error("NFC adapter unavailable")
        val activity = activityRef.get()?.get() ?: error("Activity binding required before start")
        val session = EngagementSession(UUID.randomUUID().toString(), ByteArray(0))
        sessionRef.set(session)
        sessionFlow.value = session
        adapter.enableReaderMode(
            activity,
            this,
            NFC_FLAGS,
            Bundle().apply { putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 2000) },
        )
        return session
    }

    override suspend fun stop() {
        val adapter = adapterProvider.get() ?: return
        val activity = activityRef.get()?.get() ?: return
        adapter.disableReaderMode(activity)
        sessionRef.set(null)
        sessionFlow.value = null
    }

    override fun onTagDiscovered(tag: Tag) {
        val engagement = sessionRef.get() ?: return
        var ndef: Ndef? = null
        try {
            ndef = Ndef.get(tag) ?: return
            ndef.connect()

            val payload: ByteArray = ndef.ndefMessage
                ?.records?.firstOrNull()?.payload
                ?: ByteArray(0)

            // Ignore empty reads / duplicate consecutive payloads
            if (payload.isEmpty() || payload.contentEquals(engagement.peerInfo)) return

            val updated = engagement.copy(
                transcript = engagement.transcript + payload,
                peerInfo = payload,
            )
            sessionRef.set(updated)
            sessionFlow.value = updated
            Logger.i(TAG, "NFC engagement payload=${payload.decodeToStringOrHex()}")

            // If your flow is single-shot, you can stop reader mode after first successful read:
            // activityRef.get()?.get()?.let { adapterProvider.get()?.disableReaderMode(it) }
        } catch (t: Throwable) {
            Logger.e(TAG, "Failed to read NFC tag", t)
        } finally {
            try { ndef?.close() } catch (_: Throwable) {}
        }
    }

    private fun ByteArray.decodeToStringOrHex(): String {
        return runCatching { String(this, StandardCharsets.UTF_8) }
            .getOrElse { joinToString(separator = "") { String.format("%02x", it) } }
    }

    companion object {
        private const val TAG = "NfcEngagement"
        private const val NFC_FLAGS =
            NfcAdapter.FLAG_READER_NFC_A or
                    NfcAdapter.FLAG_READER_NFC_B or
                    NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK or
                    NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS
    }
}

/** Provider indirection to allow dependency injection and testing. */
fun interface NfcAdapterProvider {
    fun get(): NfcAdapter?
}
