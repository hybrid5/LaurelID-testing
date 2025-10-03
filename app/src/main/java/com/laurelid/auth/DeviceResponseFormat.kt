package com.laurelid.auth

import java.util.Locale

/**
 * Enumeration of supported ISO 18013-5 device response encodings.
 */
enum class DeviceResponseFormat(val label: String) {
    COSE_SIGN1("cose-sign1"),
    SD_JWT("sd-jwt");

    companion object {
        fun fromLabel(label: String): DeviceResponseFormat? {
            val normalized = label.lowercase(Locale.ROOT).replace("_", "-")
            return entries.firstOrNull { candidate ->
                candidate.label == normalized ||
                    candidate.name.equals(normalized, ignoreCase = true) ||
                    candidate.aliases.contains(normalized)
            }
        }

        private val DeviceResponseFormat.aliases: Set<String>
            get() = when (this) {
                COSE_SIGN1 -> setOf("cose", "cose-sign", "1")
                SD_JWT -> setOf("sdjwt", "sd-jwt", "2")
            }
    }
}
