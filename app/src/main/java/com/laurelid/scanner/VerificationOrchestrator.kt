package com.laurelid.scanner

import android.content.Context
import com.laurelid.auth.ParsedMdoc
import com.laurelid.auth.VerifierService
import com.laurelid.auth.WalletVerifier
import com.laurelid.config.AdminConfig
import com.laurelid.data.VerificationResult
import com.laurelid.db.DbModule
import com.laurelid.db.VerificationDao
import com.laurelid.db.VerificationEntity
import com.laurelid.integrity.PlayIntegrityGate
import com.laurelid.pos.TransactionManager
import com.laurelid.util.LogManager
import com.laurelid.util.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

interface VerificationExecutor {
    suspend fun verify(parsedMdoc: ParsedMdoc, config: AdminConfig, demoPayloadUsed: Boolean): VerificationResult
    fun buildClientFailureResult(parsedMdoc: ParsedMdoc): VerificationResult
}

@Singleton
open class VerificationOrchestrator @Inject constructor(
    @ApplicationContext context: Context,
    private val walletVerifier: WalletVerifier,
    private val logManager: LogManager,
    private val transactionManager: TransactionManager,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val verificationDao: VerificationDao = DbModule.provideVerificationDao(context.applicationContext),
) : VerificationExecutor {
    private val appContext = context.applicationContext

    override suspend fun verify(
        parsedMdoc: ParsedMdoc,
        config: AdminConfig,
        demoPayloadUsed: Boolean,
    ): VerificationResult {
        val integrityAllowed = PlayIntegrityGate.isAdminAccessAllowed(appContext)
        if (!integrityAllowed) {
            Logger.w(TAG, "Blocking verification due to Play Integrity verdict")
            val failure = VerificationResult(
                success = false,
                ageOver21 = parsedMdoc.ageOver21,
                issuer = null,
                subjectDid = null,
                docType = parsedMdoc.docType,
                error = VerifierService.ERROR_DEVICE_INTEGRITY,
                trustStale = null,
            )
            val sanitized = sanitizeResult(failure)
            persistResult(sanitized, config, demoPayloadUsed)
            transactionManager.record(sanitized)
            return sanitized
        }

        val refreshMillis = TimeUnit.MINUTES.toMillis(config.trustRefreshIntervalMinutes.toLong())

        val rawResult = runCatching {
            withContext(dispatcher) { walletVerifier.verify(parsedMdoc, refreshMillis) }
        }.getOrElse { throwable ->
            Logger.e(TAG, "Verification failed", throwable)
            buildClientFailureResult(parsedMdoc)
        }

        val sanitized = sanitizeResult(rawResult)
        persistResult(sanitized, config, demoPayloadUsed)
        transactionManager.record(sanitized)
        return sanitized
    }

    private suspend fun persistResult(
        result: VerificationResult,
        config: AdminConfig,
        demoPayloadUsed: Boolean,
    ) {
        withContext(dispatcher) {
            val previous = verificationDao.mostRecent()
            val successCount = (previous?.totalSuccessCount ?: 0) + if (result.success) 1 else 0
            val failureCount = (previous?.totalFailureCount ?: 0) + if (result.success) 0 else 1
            val over21Count = (previous?.totalAgeOver21Count ?: 0) + if (result.ageOver21 == true) 1 else 0
            val under21Count = (previous?.totalAgeUnder21Count ?: 0) + if (result.ageOver21 == false) 1 else 0
            val demoCount = (previous?.totalDemoModeCount ?: 0) + if (demoPayloadUsed) 1 else 0

            val entity = VerificationEntity(
                success = result.success,
                ageOver21 = result.ageOver21 == true,
                demoMode = demoPayloadUsed,
                error = result.error,
                tsMillis = System.currentTimeMillis(),
                totalSuccessCount = successCount,
                totalFailureCount = failureCount,
                totalAgeOver21Count = over21Count,
                totalAgeUnder21Count = under21Count,
                totalDemoModeCount = demoCount,
            )
            verificationDao.insert(entity)
            logManager.appendVerification(result, config, demoPayloadUsed)
        }
    }

    override fun buildClientFailureResult(parsedMdoc: ParsedMdoc): VerificationResult {
        return VerificationResult(
            success = false,
            ageOver21 = parsedMdoc.ageOver21,
            issuer = null,
            subjectDid = null,
            docType = parsedMdoc.docType,
            error = VerifierService.ERROR_CLIENT_EXCEPTION,
            trustStale = null,
        )
    }

    fun sanitizeResult(result: VerificationResult): VerificationResult {
        if (result.success) {
            return result
        }
        return result.copy(
            issuer = null,
            subjectDid = null,
            error = VerifierService.sanitizeReasonCode(result.error),
        )
    }

    companion object {
        private const val TAG = "VerificationOrchestrator"
    }
}
