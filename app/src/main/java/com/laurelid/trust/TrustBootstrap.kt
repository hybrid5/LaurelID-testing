package com.laurelid.trust

import android.content.Context
import android.content.res.AssetManager
import com.laurelid.util.Logger
import java.io.IOException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.time.Clock
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Snapshot of verifier trust bootstrap status exposed to the UI. */
data class TrustStatus(
    val anchors: Int,
    val degraded: Boolean,
    val lastUpdated: Instant?,
)

sealed class TrustState(val degraded: Boolean) {
    object Nominal : TrustState(false)
    object Degraded : TrustState(true)
}

/** Coordinates loading trust anchors at start-up and exposes health state. */
@Singleton
class TrustBootstrap @Inject constructor(
    private val primaryProvider: TrustProvider,
    private val clock: Clock,
    private val devProvider: TrustProvider? = null,
) {

    private val anchorsRef = AtomicReference<List<X509Certificate>>(emptyList())
    private val _status = MutableStateFlow(TrustStatus(anchors = 0, degraded = true, lastUpdated = null))
    private val _state = MutableStateFlow<TrustState>(TrustState.Degraded)

    val status: StateFlow<TrustStatus> = _status.asStateFlow()
    val state: StateFlow<TrustState> = _state.asStateFlow()

    /** Loads anchors immediately; safe to call multiple times. */
    fun initialize(): List<X509Certificate> = refreshAnchors()

    fun anchors(): List<X509Certificate> = anchorsRef.get()

    @Synchronized
    fun refreshAnchors(): List<X509Certificate> {
        val primaryAnchors = runCatching { primaryProvider.loadAnchors() }
            .onFailure { Logger.e(TAG, "Failed to load primary trust anchors", it) }
            .getOrDefault(emptyList())
        val devAnchors = devProvider?.let { provider ->
            runCatching { provider.loadAnchors() }
                .onFailure { Logger.w(TAG, "Failed to load dev trust anchors", it) }
                .getOrDefault(emptyList())
        }.orEmpty()
        val combined = (primaryAnchors + devAnchors).distinctBy { it.encoded.contentHashCode() }
        anchorsRef.set(combined)
        val degraded = primaryAnchors.isEmpty()
        val updatedAt = if (combined.isNotEmpty()) clock.instant() else null
        _state.value = if (degraded) TrustState.Degraded else TrustState.Nominal
        _status.value = TrustStatus(anchors = combined.size, degraded = degraded, lastUpdated = updatedAt)
        Logger.i(TAG, "Trust bootstrap completed anchors=${combined.size} degraded=$degraded")
        return combined
    }

    companion object {
        private const val TAG = "TrustBootstrap"
    }
}

/** Loads X.509 certificates from the APK assets directory. */
class AssetTrustProvider @Inject constructor(
    private val context: Context,
    private val assetPath: String = DEFAULT_ASSET_PATH,
) : TrustProvider {

    override fun loadAnchors(): List<X509Certificate> {
        val assets = context.assets
        val certificates = mutableListOf<X509Certificate>()
        val factory = CertificateFactory.getInstance("X.509")
        val normalizedPath = assetPath.trim('/')
        val root = if (normalizedPath.isEmpty()) "" else normalizedPath
        val discovered = collectCertificates(assets, root, root) { path ->
            assets.open(path).use { input ->
                certificates += factory.generateCertificate(input) as X509Certificate
            }
        }
        if (discovered == 0) {
            throw IllegalStateException("No trust anchors found in assets://$root")
        }
        return certificates
    }

    private fun collectCertificates(
        assets: AssetManager,
        path: String,
        rootPath: String,
        onFile: (String) -> Unit,
    ): Int {
        val directory = if (path.isEmpty()) "" else path
        val entries = runCatching { assets.list(directory) }.getOrNull()
            ?: if (directory == rootPath) {
                throw IllegalStateException("Trust anchor directory missing at assets://$rootPath")
            } else {
                return 0
            }
        var count = 0
        for (entry in entries) {
            val childPath = if (directory.isEmpty()) entry else "$directory/$entry"
            if (entry.endsWith(".cer", ignoreCase = true)) {
                onFile(childPath)
                count += 1
                continue
            }
            val nested = try {
                assets.list(childPath)
            } catch (_: IOException) {
                null
            }
            if (!nested.isNullOrEmpty()) {
                count += collectCertificates(assets, childPath, rootPath, onFile)
            }
        }
        return count
    }

    companion object {
        const val DEFAULT_ASSET_PATH: String = "trust/iaca"
    }
}
