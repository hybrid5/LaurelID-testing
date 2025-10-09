package com.laurelid.mdoc

/**
 * Enumerates device response encodings defined for mobile documents in ISO/IEC 18013-5 §9.3.
 * The kiosk negotiates one of these encodings with the wallet during device engagement.
 */
enum class DeviceResponseFormat {
    /** COSE_Sign1 encoded issuer/device messages (ISO/IEC 18013-5 §9.3.2). */
    COSE_SIGN1,

    /** SD-JWT based responses for selective disclosure wallets (W3C Verifiable Credentials). */
    SD_JWT,
}
