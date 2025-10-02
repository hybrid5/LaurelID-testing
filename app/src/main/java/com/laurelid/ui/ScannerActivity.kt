package com.laurelid.ui

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputFilter
import android.text.InputType
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.VisibleForTesting
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.mlkit.vision.barcode.BarcodeScanning // Ensured
import com.google.mlkit.vision.barcode.BarcodeScannerOptions // Changed to this specific import
import com.google.mlkit.vision.barcode.common.Barcode // Kept as common
import com.google.mlkit.vision.common.InputImage
import com.laurelid.BuildConfig
import com.laurelid.R
import com.laurelid.auth.ISO18013Parser
import com.laurelid.auth.MdocParseException
import com.laurelid.auth.ParsedMdoc // Ensure this class exists and has necessary fields
import com.laurelid.auth.VerifierService
import com.laurelid.auth.WalletVerifier
import com.laurelid.config.AdminConfig
import com.laurelid.config.AdminPinManager
import com.laurelid.config.ConfigManager
import com.laurelid.config.EncryptedAdminPinStorage
import com.laurelid.data.VerificationResult // Ensure this is Parcelable
import com.laurelid.db.DbModule
import com.laurelid.db.VerificationEntity
import com.laurelid.kiosk.KioskWatchdogService
import com.laurelid.network.TrustListApiFactory
import com.laurelid.network.TrustListEndpointPolicy
import com.laurelid.network.TrustListRepository
import com.laurelid.pos.TransactionManager
import com.laurelid.util.KioskUtil
import com.laurelid.util.LogManager
import com.laurelid.util.Logger
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.charset.StandardCharsets
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Named

@AndroidEntryPoint
class ScannerActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var statusText: TextView
    private lateinit var hintText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var debugButton: Button
    private lateinit var configManager: ConfigManager
    private lateinit var adminPinManager: AdminPinManager

    @Inject
    lateinit var trustListRepository: TrustListRepository

    @Inject
    lateinit var walletVerifier: WalletVerifier

    @Inject
    lateinit var logManager: LogManager

    @Inject
    lateinit var trustListApiFactory: TrustListApiFactory

    @Inject
    @Named("trustListBaseUrl")
    lateinit var defaultTrustListBaseUrl: String

    private val parser = ISO18013Parser()
    private val cameraExecutor: ExecutorService by lazy { Executors.newSingleThreadExecutor() }
    private val barcodeScanner by lazy {
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build()
        )
    }

    private val verificationDao by lazy { DbModule.provideVerificationDao(applicationContext) }
    private val transactionManager by lazy { TransactionManager() }

    private var cameraProvider: ProcessCameraProvider? = null
    private var isProcessingCredential = false
    private var currentState: ScannerState = ScannerState.SCANNING
    private var nfcAdapter: NfcAdapter? = null
    private var nfcPendingIntent: PendingIntent? = null
    private var nfcIntentFilters: Array<IntentFilter>? = null
    private var currentConfig: AdminConfig = AdminConfig()
    private var currentBaseUrl: String? = null
    private var demoJob: Job? = null
    private var endpointUpdateJob: Job? = null
    private var nextDemoSuccess = true

    private val adminTouchHandler = Handler(Looper.getMainLooper())
    private var adminDialogShowing = false

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startCamera()
            } else {
                Toast.makeText(this, R.string.error_camera_permission, Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_scanner)
        KioskUtil.prepareForLockscreen(this)
        KioskUtil.applyKioskDecor(window)
        KioskUtil.blockBackPress(this)

        configManager = ConfigManager(applicationContext)
        adminPinManager = AdminPinManager(EncryptedAdminPinStorage(applicationContext))
        currentConfig = configManager.getConfig()
        currentBaseUrl = trustListRepository.currentBaseUrl() ?: resolveBaseUrl(currentConfig)

        rebuildNetworkDependencies(currentConfig, true) // Force initial build

        KioskWatchdogService.start(this)
        enterLockTaskIfPermitted()

        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        bindViews()
        setupAdminGesture()

        if (!currentConfig.demoMode) {
            requestCameraPermission()
            handleNfcIntent(intent) // Handle intent that started the activity
        }
    }

    override fun onResume() {
        super.onResume()
        val newConfig = configManager.getConfig()
        val configChanged = currentConfig != newConfig
        currentConfig = newConfig

        rebuildNetworkDependencies(currentConfig, configChanged)


        if (currentConfig.demoMode) {
            stopCamera()
            startDemoMode()
            disableForegroundDispatch()
        } else {
            stopDemoMode()
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                startCamera()
            } else {
                requestCameraPermission()
            }
            enableForegroundDispatch()
        }
        KioskUtil.prepareForLockscreen(this)
        KioskUtil.setImmersiveMode(window)
        enterLockTaskIfPermitted()
        KioskWatchdogService.notifyScannerVisible(true)
    }

    override fun onPause() {
        super.onPause()
        if (nfcAdapter != null && !currentConfig.demoMode) {
             disableForegroundDispatch()
        }
        stopDemoMode()
        // No need to call stopCamera() explicitly if using ProcessCameraProvider,
        // as it's lifecycle-aware. It will unbind when the lifecycle stops.
    }

    override fun onStop() {
        super.onStop()
        if (!hasWindowFocus()) {
            KioskUtil.setImmersiveMode(window)
        }
    }

    override fun onNewIntent(intent: Intent) { // Changed to non-nullable Intent
        super.onNewIntent(intent)
        setIntent(intent) // Update the activity's intent
        if (!currentConfig.demoMode) {
            handleNfcIntent(intent) // handleNfcIntent takes Intent?, so this is fine
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        demoJob?.cancel()
        endpointUpdateJob?.cancel()
        if (!cameraExecutor.isShutdown) {
            cameraExecutor.shutdown()
        }
        barcodeScanner.close()
        KioskWatchdogService.notifyScannerVisible(false)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            KioskUtil.setImmersiveMode(window)
        }
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        KioskUtil.setImmersiveMode(window)
    }

    override fun onBackPressed() {
        // Kiosk mode: back press is blocked by KioskUtil or by not calling super.onBackPressed()
    }

    private fun bindViews() {
        previewView = findViewById(R.id.previewView)
        statusText = findViewById(R.id.scannerStatus)
        hintText = findViewById(R.id.scannerHint)
        progressBar = findViewById(R.id.scannerProgress)
        debugButton = findViewById(R.id.debugLogButton)

        updateState(ScannerState.SCANNING) // Initial state

        if (BuildConfig.DEBUG) {
            debugButton.visibility = View.VISIBLE
            debugButton.setOnClickListener { dumpLatestVerifications() }
        } else {
            debugButton.visibility = View.GONE
        }
    }

    private fun setupAdminGesture() {
        val rootLayout = findViewById<View>(R.id.scannerRoot)
        val touchListener = View.OnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    adminTouchHandler.postDelayed({ promptForAdminPin() }, ADMIN_HOLD_DURATION_MS)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    adminTouchHandler.removeCallbacksAndMessages(null)
                }
            }
            true // Consume event
        }
        rootLayout?.setOnTouchListener(touchListener)
    }

    private fun promptForAdminPin() {
        if (adminDialogShowing) return
        adminDialogShowing = true

        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            filters = arrayOf(InputFilter.LengthFilter(AdminPinManager.MAX_PIN_LENGTH))
            isSingleLine = true
            hint = getString(R.string.admin_pin_hint)
        }
        val container = FrameLayout(this).apply {
            val padding = (16 * resources.displayMetrics.density).toInt() // 16dp
            setPadding(padding, padding, padding, padding)
            addView(input, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.admin_pin_prompt)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { dialog, _ ->
                handleAdminPinEntry(input.text.toString())
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel) { dialog, _ -> dialog.dismiss() }
            .setOnDismissListener {
                adminDialogShowing = false
                adminTouchHandler.removeCallbacksAndMessages(null)
            }
            .setCancelable(false)
            .show()
    }

    private fun handleAdminPinEntry(entered: String) {
        if (!adminPinManager.isPinSet()) {
            Toast.makeText(this, R.string.admin_pin_not_set, Toast.LENGTH_LONG).show()
            startActivity(Intent(this, AdminActivity::class.java))
            return
        }

        when (val result = adminPinManager.verifyPin(entered)) {
            AdminPinManager.VerificationResult.Success -> {
                startActivity(Intent(this, AdminActivity::class.java))
            }
            is AdminPinManager.VerificationResult.Failure -> {
                val attempts = result.remainingAttempts
                if (attempts > 0) {
                    Toast.makeText(
                        this,
                        getString(R.string.admin_pin_attempts_remaining, attempts),
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    Toast.makeText(this, R.string.admin_pin_error, Toast.LENGTH_SHORT).show()
                }
            }
            is AdminPinManager.VerificationResult.Locked -> {
                val message = getString(
                    R.string.admin_pin_locked,
                    formatLockoutDuration(result.remainingMillis)
                )
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            }
            AdminPinManager.VerificationResult.PinNotSet -> {
                Toast.makeText(this, R.string.admin_pin_not_set, Toast.LENGTH_LONG).show()
                startActivity(Intent(this, AdminActivity::class.java))
            }
        }
    }

    private fun formatLockoutDuration(remainingMillis: Long): String {
        val totalSeconds = TimeUnit.MILLISECONDS.toSeconds(remainingMillis.coerceAtLeast(0))
        val roundedSeconds = if (remainingMillis % 1000L == 0L) totalSeconds else totalSeconds + 1
        val minutes = (roundedSeconds / 60).toInt()
        val seconds = (roundedSeconds % 60).toInt()
        return if (minutes > 0) {
            if (seconds > 0) {
                getString(
                    R.string.duration_minutes_seconds,
                    resources.getQuantityString(R.plurals.duration_minutes, minutes, minutes),
                    resources.getQuantityString(R.plurals.duration_seconds, seconds, seconds)
                )
            } else {
                resources.getQuantityString(R.plurals.duration_minutes, minutes, minutes)
            }
        } else {
            resources.getQuantityString(R.plurals.duration_seconds, seconds.coerceAtLeast(1), seconds.coerceAtLeast(1))
        }
    }

    private fun requestCameraPermission() {
        if (currentConfig.demoMode) {
            Logger.i(TAG, "Demo mode active, camera permission not requested.")
            return
        }
        when {
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED -> {
                startCamera()
            }
            shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) -> {
                AlertDialog.Builder(this)
                    .setTitle(R.string.camera_permission_title)
                    .setMessage(R.string.camera_permission_rationale) // String resource directly
                    .setPositiveButton(android.R.string.ok) { _, _ -> permissionLauncher.launch(Manifest.permission.CAMERA) }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
            else -> {
                permissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }
    }

    private fun startCamera() {
        if (currentConfig.demoMode || isProcessingCredential) {
            Logger.i(TAG, "Camera start prevented: demoMode=${currentConfig.demoMode}, isProcessing=$isProcessingCredential")
            return
        }
        // Removed hasBoundCameras() check as unbindAll() is sufficient

        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to get CameraProvider instance", e)
                return@addListener
            }

            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                if (isProcessingCredential) { // Re-check before processing
                    imageProxy.close()
                    return@setAnalyzer
                }
                val mediaImage = imageProxy.image ?: run { imageProxy.close(); return@setAnalyzer }
                val inputImage = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)

                barcodeScanner.process(inputImage)
                    .addOnSuccessListener { barcodes ->
                        if (isProcessingCredential) return@addOnSuccessListener // Already handling one
                        barcodes.firstOrNull { it.format == Barcode.FORMAT_QR_CODE && it.rawValue != null }
                            ?.rawValue?.let { payload -> runOnUiThread { handleQrPayload(payload) } }
                    }
                    .addOnFailureListener { e -> Logger.e(TAG, "Barcode scanning failed", e) }
                    .addOnCompleteListener { imageProxy.close() }
            }
            try {
                cameraProvider?.unbindAll() // Unbind existing use cases before rebinding
                cameraProvider?.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageAnalysis)
                Logger.i(TAG, "Camera use cases bound to lifecycle.")
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to bind camera use cases", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun stopCamera() {
        cameraProvider?.unbindAll()
        Logger.i(TAG, "Camera unbound.")
    }

    private fun handleQrPayload(payload: String) {
        if (isProcessingCredential) {
            Toast.makeText(this, R.string.toast_processing, Toast.LENGTH_SHORT).show()
            return
        }
        val isDemo = currentConfig.demoMode
        val effectivePayload = if (isDemo) nextDemoPayload() else payload
        Logger.i(TAG, if (isDemo) "Demo mode active; substituting simulated QR payload" else "QR payload received for verification")

        isProcessingCredential = true
        stopCamera() // Stop camera explicitly to free resources during verification
        updateState(ScannerState.VERIFYING)
        try {
            // Assume parser.parseFromQrPayload is synchronous or handles its own threading
            val parsedMdoc = parser.parseFromQrPayload(effectivePayload)
            verifyAndPersist(parsedMdoc, isDemo)
        } catch (error: MdocParseException) {
            onCredentialParsingFailed("QR", error)
        } catch (error: Exception) {
            onCredentialParsingFailed("QR", error)
        }
    }

    private fun handleNfcIntent(intent: Intent?) {
        if (intent == null || currentConfig.demoMode || NfcAdapter.ACTION_NDEF_DISCOVERED != intent.action) {
            return
        }
        if (intent.type != MDL_MIME_TYPE) {
             Logger.w(TAG, "NFC intent received with incorrect MIME type: ${intent.type}")
            return
        }
        if (isProcessingCredential) {
            Toast.makeText(this, R.string.toast_processing, Toast.LENGTH_SHORT).show()
            return
        }

        val messages: Array<NdefMessage>? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES, NdefMessage::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES)?.mapNotNull { it as? NdefMessage }?.toTypedArray()
        }

        val record = messages?.firstOrNull()?.records?.firstOrNull { candidate ->
            candidate.tnf == NdefRecord.TNF_MIME_MEDIA &&
            String(candidate.type, StandardCharsets.US_ASCII) == MDL_MIME_TYPE // Use StandardCharsets
        }
        val payload = record?.payload

        if (payload != null) {
            Logger.i(TAG, "NFC payload received for verification")
            isProcessingCredential = true
            stopCamera() // Stop camera when processing NFC
            updateState(ScannerState.VERIFYING)
            try {
                val parsedMdoc = parser.parseFromNfc(payload) // Assume parseFromNfc exists and is similar to parseFromQrPayload
                verifyAndPersist(parsedMdoc, demoPayloadUsed = false)
            } catch (error: MdocParseException) {
                onCredentialParsingFailed("NFC", error)
            } catch (error: Exception) {
                onCredentialParsingFailed("NFC", error)
            }
        } else {
            Toast.makeText(this, R.string.toast_nfc_error, Toast.LENGTH_LONG).show() // Ensure string exists
            Logger.w(TAG, "NFC NDEF message did not contain a valid mdoc payload.")
        }
    }

    private fun onCredentialParsingFailed(source: String, error: Throwable) {
        if (error is MdocParseException) {
            Logger.w(TAG, "Failed to parse $source credential: ${error.error.detail}", error)
        } else {
            Logger.e(TAG, "Unexpected error while parsing $source credential", error)
        }
        showCredentialParsingErrorToast()
        recoverFromProcessingFailure()
    }

    private fun showCredentialParsingErrorToast() {
        Toast.makeText(this, R.string.result_details_error_unknown, Toast.LENGTH_LONG).show()
    }

    private fun recoverFromProcessingFailure() {
        isProcessingCredential = false
        updateState(ScannerState.SCANNING)
        if (currentConfig.demoMode) {
            if (demoJob?.isActive != true) {
                startDemoMode()
            }
        } else {
            val hasPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
            if (hasPermission) {
                startCamera()
            } else {
                requestCameraPermission()
            }
        }
    }

    private fun handleDemoPayload() {
        if (!currentConfig.demoMode || isProcessingCredential) return
        Logger.i(TAG, "Demo mode injecting simulated credential")
        isProcessingCredential = true
        stopCamera()
        updateState(ScannerState.VERIFYING)
        val parsedMdoc = parser.parseFromQrPayload(nextDemoPayload())
        verifyAndPersist(parsedMdoc, demoPayloadUsed = true)
    }

    private fun verifyAndPersist(parsedMdoc: ParsedMdoc, demoPayloadUsed: Boolean) {
        val configSnapshot = currentConfig // Use a snapshot of config for this verification
        val refreshMillis = TimeUnit.MINUTES.toMillis(configSnapshot.trustRefreshIntervalMinutes.toLong())

        lifecycleScope.launch {
            val verifier = walletVerifier

            val verificationResult = runCatching {
                verifier.verify(parsedMdoc, refreshMillis)
            }.getOrElse { throwable ->
                Logger.e(TAG, "Verification failed with exception", throwable)
                buildClientFailureResult(parsedMdoc)
            }

            persistResult(verificationResult, configSnapshot, demoPayloadUsed)
            transactionManager.record(verificationResult) // Assuming TransactionManager.record exists
            // transactionManager.printResult(verificationResult) // This seems for debugging, decide if needed

            navigateToResult(verificationResult) // Navigate after all processing
        }
    }

    private fun resolveBaseUrl(config: AdminConfig): String {
        val resolved = TrustListEndpointPolicy.resolveBaseUrl(config)
        if (!TrustListEndpointPolicy.allowOverride && config.apiEndpointOverride.isNotBlank()) {
            Logger.w(TAG, "Ignoring trust list override; not permitted in this build.")
        }
        return resolved
    }

    private fun updateState(state: ScannerState) {
        if (state == currentState && !isFinishing) return // Avoid updates if already in state or finishing
        currentState = state

        val textRes = when (state) {
            ScannerState.SCANNING -> R.string.scanner_status_scanning
            ScannerState.VERIFYING -> R.string.scanner_status_verifying
            ScannerState.RESULT -> R.string.scanner_status_ready // Or "Processed"
        }
        val hintRes = when (state) {
            ScannerState.SCANNING -> R.string.scanner_hint
            ScannerState.VERIFYING -> R.string.scanner_status_verifying // Or a "Verifying, please wait..."
            ScannerState.RESULT -> R.string.scanner_status_ready // Or "Tap to scan next"
        }

        // Ensure UI updates are on the main thread, though animate() usually handles this.
        runOnUiThread {
            statusText.animate()
                .alpha(0f)
                .setDuration(150)
                .withEndAction {
                    statusText.setText(textRes) // Ensure string resources exist
                    statusText.animate().alpha(1f).setDuration(150).start()
                }.start()
            hintText.text = getString(hintRes) // Ensure string resources exist
            progressBar.visibility = if (state == ScannerState.VERIFYING) View.VISIBLE else View.INVISIBLE
        }
    }

    private fun navigateToResult(result: VerificationResult) {
        if (isFinishing) return
        updateState(ScannerState.RESULT) // Update state before navigating
        val intent = Intent(this, ResultActivity::class.java).apply {
            putExtra(ResultActivity.EXTRA_VERIFICATION_RESULT, result) // Assuming ResultActivity.EXTRA_VERIFICATION_RESULT and VerificationResult is Parcelable
        }
        startActivity(intent)
        isProcessingCredential = false // Reset for next scan if not finishing
        if (!currentConfig.demoMode) { 
            // If not finishing and not demo mode, restart camera
            // requestCameraPermission() // This will call startCamera if needed.
            // For now, let onResume handle camera restart if the activity is brought back to foreground.
        } else if (currentConfig.demoMode) {
            // Demo mode will auto-restart via its loop if still active.
        }
    }

    private fun dumpLatestVerifications() {
        lifecycleScope.launch(Dispatchers.IO) {
            val latest = verificationDao.latest(10)
            latest.forEach { Logger.d(TAG, "DB entry: $it") }
        }
    }

    private suspend fun persistResult(result: VerificationResult, config: AdminConfig, demoPayloadUsed: Boolean) {
        withContext(Dispatchers.IO) {
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
            Logger.i(TAG, "Stored verification result (success=${result.success})")
            logManager.appendVerification(result, config, demoPayloadUsed)
        }
    }

    private fun startDemoMode() {
        if (demoJob?.isActive == true) return
        Logger.i(TAG, "Starting demo mode loop")
        stopCamera() // Ensure camera is stopped for demo mode
        updateState(ScannerState.SCANNING) // Or a specific demo state
        isProcessingCredential = false // Reset for demo loop
        demoJob = lifecycleScope.launch {
            while (isActive) {
                delay(DEMO_INTERVAL_MS)
                if (!isActive) break // Check again after delay
                if (isProcessingCredential) continue
                if (currentConfig.demoMode) { // Ensure still in demo mode
                    handleDemoPayload()
                } else {
                    stopDemoMode() // Exit if demo mode was turned off
                    break
                }
            }
        }
    }

    private fun stopDemoMode() {
        demoJob?.cancel()
        demoJob = null
        if (currentState != ScannerState.VERIFYING) { // Only reset to scanning if not actively verifying
            updateState(ScannerState.SCANNING)
        }
    }

    private fun nextDemoPayload(): String {
        val subject = "demo-${System.currentTimeMillis()}-${(0..1000).random()}"
        val payload = if (nextDemoSuccess) {
            "mdoc://demo?age21=1&issuer=AZ-MVD&subject=$subject&doctype=org.iso.18013.5.1.mDL"
        } else {
            "mdoc://demo?age21=0&issuer=CA-DMV&subject=$subject&doctype=org.iso.18013.5.1.mDL"
        }
        nextDemoSuccess = !nextDemoSuccess
        return payload
    }

    private fun enableForegroundDispatch() {
        if (nfcAdapter == null || !nfcAdapter!!.isEnabled) {
            Logger.w(TAG, "NFC adapter not available or not enabled, cannot enable foreground dispatch.")
            return
        }
        try {
            if (nfcPendingIntent == null) {
                val intent = Intent(this, javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                        (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
                nfcPendingIntent = PendingIntent.getActivity(this, 0, intent, flags)
            }

            if (nfcIntentFilters == null) {
                val ndef = IntentFilter(NfcAdapter.ACTION_NDEF_DISCOVERED).apply {
                    try {
                        addDataType(MDL_MIME_TYPE)
                    } catch (e: IntentFilter.MalformedMimeTypeException) {
                        Logger.e(TAG, "Failed to add NFC MIME type", e)
                        throw RuntimeException("Failed to add NFC MIME type", e)
                    }
                    addCategory(Intent.CATEGORY_DEFAULT)
                }
                nfcIntentFilters = arrayOf(ndef)
            }
            nfcAdapter?.enableForegroundDispatch(this, nfcPendingIntent, nfcIntentFilters, null)
            Logger.i(TAG, "NFC foreground dispatch enabled.")
        } catch (e: Exception) {
            Logger.e(TAG, "Error enabling NFC foreground dispatch", e)
        }
    }

    private fun disableForegroundDispatch() {
        try {
            nfcAdapter?.disableForegroundDispatch(this)
            Logger.i(TAG, "NFC foreground dispatch disabled.")
        } catch (e: IllegalStateException) { // Can sometimes happen if activity is closing
            Logger.w(TAG, "Failed to disable NFC foreground dispatch", e)
        }
    }

    private fun rebuildNetworkDependencies(config: AdminConfig, forceRebuild: Boolean) {
        val targetBaseUrl = resolveBaseUrl(config)
        if (forceRebuild || targetBaseUrl != currentBaseUrl) {
            Logger.i(
                TAG,
                "Rebuilding network dependencies. Force: $forceRebuild, Current URL: $currentBaseUrl, Target URL: $targetBaseUrl"
            )
            endpointUpdateJob?.cancel()
            endpointUpdateJob = lifecycleScope.launch {
                try {
                    val api = withContext(Dispatchers.IO) { trustListApiFactory.create(targetBaseUrl) }
                    trustListRepository.updateEndpoint(api, targetBaseUrl)
                    currentBaseUrl = targetBaseUrl
                    Logger.i(TAG, "Network dependencies rebuilt for URL: $targetBaseUrl")
                } catch (e: IllegalArgumentException) {
                    Logger.e(TAG, "Invalid trust list URL provided ($targetBaseUrl), falling back to default.", e)
                    Toast.makeText(this@ScannerActivity, "Invalid API URL, using default.", Toast.LENGTH_LONG).show()
                    val fallbackApi = withContext(Dispatchers.IO) { trustListApiFactory.create(defaultTrustListBaseUrl) }
                    trustListRepository.updateEndpoint(fallbackApi, defaultTrustListBaseUrl)
                    currentBaseUrl = defaultTrustListBaseUrl
                }
            }
        }
    }

    private fun enterLockTaskIfPermitted() {
        val lockTaskPermitted = KioskUtil.isLockTaskPermitted(this)
        if (!shouldEnterLockTask(currentConfig, lockTaskPermitted)) {
            Logger.i(
                TAG,
                "Skipping lock task entry. demoMode=${currentConfig.demoMode}, permitted=$lockTaskPermitted"
            )
            return
        }
        KioskUtil.startLockTaskIfPermitted(this, lockTaskPermitted)
    }

    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    internal fun currentBaseUrlForTest(): String? = currentBaseUrl

    private enum class ScannerState { SCANNING, VERIFYING, RESULT }

    companion object {
        private const val TAG = "ScannerActivity"
        private const val MDL_MIME_TYPE = "application/iso.18013-5+mdoc"
        private const val ADMIN_HOLD_DURATION_MS = 3000L // Reduced from 5s for easier testing
        private const val DEMO_INTERVAL_MS = 3000L // Interval for demo mode payloads

        @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
        internal fun shouldEnterLockTask(config: AdminConfig, lockTaskPermitted: Boolean): Boolean {
            return !config.demoMode && lockTaskPermitted
        }

        @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
        internal fun buildClientFailureResult(parsedMdoc: ParsedMdoc): VerificationResult {
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
    }
}
