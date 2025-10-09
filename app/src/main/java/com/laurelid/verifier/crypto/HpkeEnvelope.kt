package com.laurelid.verifier.crypto

import java.nio.ByteBuffer

/**
 * Simple container for HPKE payloads emitted by the wallet. The `enc` parameter is the
 * encapsulated key while [ciphertext] contains the encrypted MSO response. The raw format used by
 * the kiosk concatenates these values with a 2-byte length prefix for the encapsulated key.
 */
data class HpkeEnvelope(
    val encapsulatedKey: ByteArray,
    val ciphertext: ByteArray,
) {
    fun toByteArray(): ByteArray {
        val buffer = ByteBuffer.allocate(2 + encapsulatedKey.size + ciphertext.size)
        buffer.putShort(encapsulatedKey.size.toShort())
        buffer.put(encapsulatedKey)
        buffer.put(ciphertext)
        return buffer.array()
    }

    companion object {
        fun parse(raw: ByteArray): HpkeEnvelope {
            require(raw.size >= 4) { "Envelope too small" }
            val buffer = ByteBuffer.wrap(raw)
            val encLength = buffer.short.toInt() and 0xFFFF
            require(encLength > 0) { "Encapsulated key must be present" }
            require(buffer.remaining() >= encLength) { "Malformed HPKE envelope" }
            val enc = ByteArray(encLength)
            buffer.get(enc)
            val cipher = ByteArray(buffer.remaining())
            buffer.get(cipher)
            return HpkeEnvelope(enc, cipher)
        }
    }
}
