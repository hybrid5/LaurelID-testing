package com.laurelid.deviceengagement

import android.app.Activity
import android.nfc.NfcAdapter
import android.os.Bundle
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicReference

/**
 * NFC reader-mode transport wrapper that activates ISO/IEC 18013-5 device engagement polling.
 * The actual response is delivered through {@link Transport.receive}. 【ISO18013-5§7.2】【ISO18013-7§8.3】
 */
class NfcTransport private constructor(
    descriptor: TransportDescriptor,
    private val adapterProvider: () -> NfcAdapter?,
) : Transport(descriptor, TAG), NfcAdapter.ReaderCallback {

    private val activityRef = AtomicReference<WeakReference<Activity>?>(null)

    fun bind(activity: Activity) {
        activityRef.set(WeakReference(activity))
    }

    override suspend fun onStarted() {
        val adapter = adapterProvider() ?: throw TransportException.Unsupported("NFC adapter unavailable")
        val activity = activityRef.get()?.get() ?: throw IllegalStateException("Activity binding required before NFC start")
        adapter.enableReaderMode(
            activity,
            this,
            NFC_FLAGS,
            Bundle().apply { putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 2000) },
        )
    }

    override suspend fun onStopped() {
        val adapter = adapterProvider() ?: return
        val activity = activityRef.get()?.get() ?: return
        adapter.disableReaderMode(activity)
    }

    override fun onTagDiscovered(tag: android.nfc.Tag?) {
        // TODO(PROD): stream ISO-DEP / NDEF payloads into the session transcript.
    }

    companion object {
        private const val TAG = "NfcTransport"
        private const val NFC_FLAGS =
            NfcAdapter.FLAG_READER_NFC_A or
                NfcAdapter.FLAG_READER_NFC_B or
                NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK or
                NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS

        fun fromDescriptor(
            descriptor: TransportDescriptor,
            adapterProvider: () -> NfcAdapter?,
        ): NfcTransport = NfcTransport(descriptor, adapterProvider)
    }
}
