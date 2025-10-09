package com.laurelid.auth.crypto

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Wrapper for sensitive byte arrays that ensures zeroization when closed. 【RFC9180§5】
 */
class SecureBytes internal constructor(private val buffer: ByteArray) : AutoCloseable {

    private val closed = AtomicBoolean(false)

    /** Returns a defensive copy suitable for parsing without retaining the original buffer. */
    fun copy(): ByteArray = buffer.copyOf()

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            buffer.fill(0)
        }
    }

    companion object {
        fun wrap(bytes: ByteArray): SecureBytes = SecureBytes(bytes)
    }
}
