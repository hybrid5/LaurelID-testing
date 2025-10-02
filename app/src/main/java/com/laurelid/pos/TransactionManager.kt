package com.laurelid.pos

import com.laurelid.data.VerificationResult
import com.laurelid.util.Logger

import javax.inject.Inject

class TransactionManager @Inject constructor() {
    fun record(result: VerificationResult) {
        // TODO: Integrate with local printer/POS systems once hardware is finalized.
        Logger.d(TAG, "Recorded verification event: success=${result.success}")
    }

    fun printResult(result: VerificationResult) {
        // TODO: Replace with ESC/POS commands once printer integration is finalized.
        val status = if (result.success) "VERIFIED" else "REJECTED"
        Logger.i(TAG, "Printer stub – status=$status at ${System.currentTimeMillis()}")
    }
    
    companion object {
        private const val TAG = "TransactionManager"
    }
}
