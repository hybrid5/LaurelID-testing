package com.laurelid.deviceengagement

import android.nfc.NfcAdapter

/** Provider wrapper to lazily obtain the system NFC adapter. */
fun interface NfcAdapterProvider {
    fun get(): NfcAdapter?
}
