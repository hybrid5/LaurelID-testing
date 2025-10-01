package com.laurelid.integrity

import android.content.Context
import android.util.Base64
import android.util.Log
import com.google.android.gms.play.integrity.IntegrityManagerFactory
import com.google.android.gms.play.integrity.StandardIntegrityManager
import com.google.android.gms.play.integrity.StandardIntegrityManager.PrepareIntegrityTokenRequest
import com.google.android.gms.play.integrity.StandardIntegrityManager.PrepareIntegrityTokenResponse
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
        IntegrityManagerFactory.create(appContext).standardIntegrityManager()
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
                val request = PrepareIntegrityTokenRequest.builder()
                    .setCloudProjectNumber(projectNumber)
                    .setRequestHash(nonceGenerator.generateRequestHash())
                    .build()
                val response = Tasks.await(manager.prepareIntegrityToken(request))
                mapVerdict(response)
            } catch (throwable: Throwable) {
                Log.e(TAG, "Failed to obtain Play Integrity verdict", throwable)
                PlayIntegrityVerdict.UNKNOWN
            }
        }
    }

    private fun mapVerdict(response: PrepareIntegrityTokenResponse): PlayIntegrityVerdict {
        return when (response.deviceIntegrity()) {
            PrepareIntegrityTokenResponse.DEVICE_INTEGRITY_MEETS_DEVICE_INTEGRITY ->
                PlayIntegrityVerdict.MEETS_DEVICE_INTEGRITY
            PrepareIntegrityTokenResponse.DEVICE_INTEGRITY_FAILED ->
                PlayIntegrityVerdict.FAILED_DEVICE_INTEGRITY
            else -> PlayIntegrityVerdict.UNKNOWN
        }
    }

    class NonceGenerator(
        private val secureRandom: SecureRandom = SecureRandom(),
    ) {
        fun generateRequestHash(): String {
            val nonce = ByteArray(NONCE_LENGTH_BYTES)
            secureRandom.nextBytes(nonce)
            return Base64.encodeToString(nonce, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
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
