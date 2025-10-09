package com.laurelid.auth.session

import com.laurelid.BuildConfig

/** Feature toggles compiled into BuildConfig for verifier functionality. */
object VerifierFeatureFlags {
    val useOfflineTestVectors: Boolean = BuildConfig.USE_OFFLINE_TEST_VECTORS
    val devProfileMode: Boolean = BuildConfig.DEVPROFILE_MODE
    val qrEnabled: Boolean = BuildConfig.TRANSPORT_QR_ENABLED
    val nfcEnabled: Boolean = BuildConfig.TRANSPORT_NFC_ENABLED
    val bleEnabled: Boolean = BuildConfig.TRANSPORT_BLE_ENABLED
}

