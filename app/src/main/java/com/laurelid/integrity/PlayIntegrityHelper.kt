package com.laurelid.integrity

import android.content.Context
import android.util.Base64
import android.util.Log
import com.google.android.play.core.integrity.IntegrityManagerFactory
import com.google.android.play.core.integrity.StandardIntegrityManager
import com.google.android.play.core.integrity.StandardIntegrityManager.PrepareIntegrityTokenRequest
import com.google.android.play.core.integrity.StandardIntegrityManager.StandardIntegrityToken
import com.google.android.play.core.integrity.StandardIntegrityManager.StandardIntegrityTokenProvider
import com.google.android.play.core.integrity.StandardIntegrityManager.StandardIntegrityTokenRequest
import com.google.android.gms.tasks.Tasks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.SecureRandom

interface PlayIntegrityVerdictProvider {
    suspend fun fetchVerdict(): PlayIntegrityVerdict
}

class PlayIntegrityHelper(
    private val context: Context,
    private val projectNumberProvider: ProjectNumberProvider = BuildConfigProjectNumberProvider(),
    private val nonceGenerator: NonceGenerator = NonceGenerator(),
    private val integrityManagerProvider: (Context) -> StandardIntegrityManager = { appContext ->
        // Standard Integrity manager (Play Core)
        IntegrityManagerFactory.createStandard(appContext)
    },
) : PlayIntegrityVerdictProvider {

    override suspend fun fetchVerdict(): PlayIntegrityVerdict {
        val projectNumber = projectNumberProvider.getProjectNumber()
        if (projectNumber == null) {
            Log.w(TAG, "Cloud project number missing; treating Play Integrity verdict as unknown.")
            return PlayIntegrityVerdict.UNKNOWN
        }

        return withContext(Dispatchers.IO) {
            try {
                val manager = integrityManagerProvider(context)

                // Step 1: Prepare (bind to your Play Console project)
                val prepareReq = PrepareIntegrityTokenRequest.builder()
                    .setCloudProjectNumber(projectNumber)
                    .build()
                val provider: StandardIntegrityTokenProvider =
                    Tasks.await(manager.prepareIntegrityToken(prepareReq))

                // Step 2: Request (per-request hash/nonce)
                val tokenReq = StandardIntegrityTokenRequest.builder()
                    .setRequestHash(nonceGenerator.generateRequestHash())
                    .build()
                val token: StandardIntegrityToken = Tasks.await(provider.request(tokenReq))

                // On-device we can't interpret the token; your server must verify it with Google.
                mapVerdict(token)
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to obtain Play Integrity token/verdict", t)
                PlayIntegrityVerdict.UNKNOWN
            }
        }
    }

    private fun mapVerdict(@Suppress("UNUSED_PARAMETER") token: StandardIntegrityToken): PlayIntegrityVerdict {
        // Standard Integrity doesn't expose the verdict client-side.
        // Return UNKNOWN here; server verification should decide pass/fail.
        return PlayIntegrityVerdict.UNKNOWN
    }

    class NonceGenerator(
        private val secureRandom: SecureRandom = SecureRandom(),
    ) {
        fun generateRequestHash(): String {
            val nonce = ByteArray(NONCE_LENGTH_BYTES)
            secureRandom.nextBytes(nonce)
            return Base64.encodeToString(
                nonce,
                Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
            )
        }

        private companion object {
            private const val NONCE_LENGTH_BYTES = 32
        }
    }

    interface ProjectNumberProvider {
        fun getProjectNumber(): Long?
    }

    class BuildConfigProjectNumberProvider : ProjectNumberProvider {
        override fun getProjectNumber(): Long? {
            val projectNumber = com.laurelid.BuildConfig.PLAY_INTEGRITY_PROJECT_NUMBER
            return projectNumber.takeIf { it > 0 }
        }
    }

    private companion object {
        private const val TAG = "PlayIntegrityHelper"
    }
}
