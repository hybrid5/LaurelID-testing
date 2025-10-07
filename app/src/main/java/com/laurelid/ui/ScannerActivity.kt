package com.laurelid.ui

import android.Manifest
import android.app.PendingIntent
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
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
import androidx.activity.addCallback
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.laurelid.BuildConfig
import com.laurelid.R
import com.laurelid.config.AdminConfig
import com.laurelid.config.AdminPinManager
import com.laurelid.config.ConfigManager
import com.laurelid.config.EncryptedAdminPinStorage
import com.laurelid.db.DbModule
import com.laurelid.kiosk.KioskWatchdogService
import com.laurelid.data.VerificationResult
import com.laurelid.network.TrustListApiFactory
import com.laurelid.network.TrustListEndpointPolicy
import com.laurelid.network.TrustListRepository
import com.laurelid.scanner.ScannerUiState
import com.laurelid.scanner.ScannerViewModel
import com.laurelid.scanner.camera.CameraXAnalyzer
import com.laurelid.scanner.nfc.NfcHandler
import com.laurelid.util.KioskUtil
import com.laurelid.util.Logger
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Named

@AndroidEntryPoint
class ScannerActivity : AppCompatActivity() {

    // Replaced `by viewModels()` with ViewModelProvider to avoid the unresolved reference
    private lateinit var viewModel: ScannerViewModel

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
    lateinit var trustListApiFactory: TrustListApiFactory

    @Inject
    @Named("trustListBaseUrl")
    lateinit var defaultTrustListBaseUrl: String

    @Inject
    lateinit var nfcHandler: NfcHandler

    private val cameraExecutor: ExecutorService by lazy { Executors.newSingleThreadExecutor() }
    private val barcodeScanner by lazy {
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(com.google.mlkit.vision.barcode.common.Barcode.FORMAT_QR_CODE)
                .build()
        )
    }
    private val verificationDao by lazy { DbModule.provideVerificationDao(applicationContext) }

    private var cameraProvider: ProcessCameraProvider? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var nfcAdapter: NfcAdapter? = null
    private var nfcPendingIntent: PendingIntent? = null
    private var nfcIntentFilters: Array<IntentFilter>? = null
    private var currentConfig: AdminConfig = AdminConfig()
    private var currentBaseUrl: String? = null
    private var demoJob: Job? = null
    private var endpointUpdateJob: Job? = null
    private var nextDemoSuccess = true
    private var cameraRunning = false

    private val adminTouchHandler = Handler(Looper.getMainLooper())
    private var adminDialogShowing = false

    private var currentUiState: ScannerUiState = ScannerUiState()

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
        onBackPressedDispatcher.addCallback(this) { /* kiosk: no-op */ }

        // Initialize ViewModel via ViewModelProvider (works with Hilt due to @AndroidEntryPoint)
        viewModel = ViewModelProvider(this)[ScannerViewModel::class.java]

        configManager = ConfigManager(applicationContext)
        adminPinManager = AdminPinManager(EncryptedAdminPinStorage(applicationContext))
        currentConfig = configManager.getConfig()
        currentBaseUrl = trustListRepository.currentBaseUrl() ?: resolveBaseUrl(currentConfig)

        rebuildNetworkDependencies(currentConfig, true) // Force initial build
        viewModel.updateConfig(currentConfig)

        // Android 14+: do NOT start foreground services here
        // KioskWatchdogService.start(this)
        enterLockTaskIfPermitted()

        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        bindViews()
        setupAdminGesture()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { renderState(it) }
            }
        }

        if (!currentConfig.demoMode) {
            requestCameraPermission()
            processNfcIntent(intent)
        }
    }

    override fun onStart() {
        super.onStart()
        // Safe point to start a foreground service (activity is visible/starting)
        startWatchdogIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        val newConfig = configManager.getConfig()
        val configChanged = currentConfig != newConfig
        currentConfig = newConfig
        viewModel.updateConfig(newConfig)

        rebuildNetworkDependencies(currentConfig, configChanged)

        if (currentConfig.demoMode) {
            disableForegroundDispatch()
        } else {
            enableForegroundDispatch()
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                requestCameraPermission()
            }
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
        stopCamera()
        KioskWatchdogService.notifyScannerVisible(false)
    }

    override fun onStop() {
        super.onStop()
        if (!hasWindowFocus()) {
            KioskUtil.setImmersiveMode(window)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (!currentConfig.demoMode) {
            processNfcIntent(intent)
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

    private fun bindViews() {
        previewView = findViewById(R.id.previewView)
        statusText = findViewById(R.id.scannerStatus)
        hintText = findViewById(R.id.scannerHint)
        progressBar = findViewById(R.id.scannerProgress)
        debugButton = findViewById(R.id.debugLogButton)

        applyUiState(ScannerUiState())

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
            true
        }
        rootLayout?.setOnTouchListener(touchListener)
    }

    private fun renderState(state: ScannerUiState) {
        if (isFinishing) return
        val previous = currentUiState
        currentUiState = state
        applyUiState(state)

        when (state.phase) {
            ScannerUiState.Phase.Verifying -> stopCamera()
            ScannerUiState.Phase.Scanning -> {
                if (!state.demoMode) {
                    startCameraIfPermitted()
                } else {
                    stopCamera()
                }
            }
            ScannerUiState.Phase.Result -> stopCamera()
        }

        if (state.demoMode) {
            startDemoMode()
        } else {
            stopDemoMode()
        }

        state.result?.let {
            if (previous.result != it) {
                navigateToResult(it)
                viewModel.onResultConsumed()
            }
        }

        state.errorMessageRes?.let { resId ->
            Toast.makeText(this, resId, Toast.LENGTH_LONG).show()
            viewModel.onErrorConsumed()
        }
    }

    private fun applyUiState(state: ScannerUiState) {
        if (statusText.text != getString(state.statusTextRes)) {
            statusText.animate()
                .alpha(0f)
                .setDuration(150)
                .withEndAction {
                    statusText.setText(state.statusTextRes)
                    statusText.animate().alpha(1f).setDuration(150).start()
                }.start()
        }
        hintText.text = getString(state.hintTextRes)
        progressBar.visibility = if (state.showProgress) View.VISIBLE else View.INVISIBLE
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
            val padding = (16 * resources.displayMetrics.density).toInt()
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
                startCameraIfPermitted()
            }
            shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) -> {
                AlertDialog.Builder(this)
                    .setTitle(R.string.camera_permission_title)
                    .setMessage(R.string.camera_permission_rationale)
                    .setPositiveButton(android.R.string.ok) { _, _ -> permissionLauncher.launch(Manifest.permission.CAMERA) }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
            else -> {
                permissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }
    }

    private fun startCameraIfPermitted() {
        if (cameraRunning || currentConfig.demoMode) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        startCamera()
    }

    private fun startCamera() {
        if (cameraRunning || currentConfig.demoMode) {
            Logger.i(TAG, "Camera start prevented: demoMode=${currentConfig.demoMode}, running=$cameraRunning")
            return
        }

        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val provider = runCatching { cameraProviderFuture.get() }
                .onFailure { Logger.e(TAG, "Failed to get CameraProvider instance", it) }
                .getOrNull() ?: return@addListener

            cameraProvider = provider
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            val analyzer = CameraXAnalyzer(
                scope = lifecycleScope,
                barcodeScanner = barcodeScanner,
                onPayload = { payload -> viewModel.submitQrPayload(payload) },
                shouldProcess = {
                    !isFinishing && currentUiState.phase == ScannerUiState.Phase.Scanning && !currentUiState.demoMode
                }
            )

            analysis.setAnalyzer(cameraExecutor, analyzer)
            imageAnalysis = analysis

            runCatching {
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                cameraRunning = true
                Logger.i(TAG, "Camera use cases bound to lifecycle.")
            }.onFailure { error ->
                Logger.e(TAG, "Failed to bind camera use cases", error)
                analysis.clearAnalyzer()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun stopCamera() {
        if (!cameraRunning) return
        runCatching { imageAnalysis?.clearAnalyzer() }
        cameraProvider?.unbindAll()
        cameraRunning = false
        Logger.i(TAG, "Camera unbound.")
    }

    private fun processNfcIntent(intent: Intent?) {
        if (intent == null || currentConfig.demoMode) {
            return
        }
        if (intent.action != NfcAdapter.ACTION_NDEF_DISCOVERED) {
            return
        }
        if (currentUiState.isProcessing) {
            Toast.makeText(this, R.string.toast_processing, Toast.LENGTH_SHORT).show()
            return
        }
        val payload = nfcHandler.extractPayload(intent)
        if (payload != null) {
            Logger.i(TAG, "NFC payload received for verification")
            viewModel.submitNfcPayload(payload)
        } else {
            Toast.makeText(this, R.string.toast_nfc_error, Toast.LENGTH_LONG).show()
            Logger.w(TAG, "NFC NDEF message did not contain a valid mdoc payload.")
        }
    }

    private fun resolveBaseUrl(config: AdminConfig): String {
        val resolved = TrustListEndpointPolicy.resolveBaseUrl(config)
        if (!TrustListEndpointPolicy.allowOverride && config.apiEndpointOverride.isNotBlank()) {
            Logger.w(TAG, "Ignoring trust list override; not permitted in this build.")
        }
        return resolved
    }

    private fun navigateToResult(result: VerificationResult) {
        if (isFinishing) return
        val intent = Intent(this, ResultActivity::class.java).apply {
            putExtra(ResultActivity.EXTRA_VERIFICATION_RESULT, result)
        }
        startActivity(intent)
    }

    private fun dumpLatestVerifications() {
        lifecycleScope.launch(Dispatchers.IO) {
            val latest = verificationDao.latest(10)
            latest.forEach { Logger.d(TAG, "DB entry: $it") }
        }
    }

    private fun startDemoMode() {
        if (demoJob?.isActive == true) return
        Logger.i(TAG, "Starting demo mode loop")
        stopCamera()
        demoJob = lifecycleScope.launch {
            while (isActive && currentConfig.demoMode) {
                delay(DEMO_INTERVAL_MS)
                if (!isActive || !currentConfig.demoMode) break
                viewModel.submitDemoPayload { nextDemoPayload() }
            }
        }
    }

    private fun stopDemoMode() {
        demoJob?.cancel()
        demoJob = null
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
        if (nfcPendingIntent == null) {
            val intent = Intent(this, ScannerActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
            nfcPendingIntent = PendingIntent.getActivity(this, 0, intent, flags)
        }

        if (nfcIntentFilters == null) {
            val ndef = IntentFilter(NfcAdapter.ACTION_NDEF_DISCOVERED).apply {
                try {
                    addDataType(NfcHandler.MDL_MIME_TYPE)
                } catch (e: IntentFilter.MalformedMimeTypeException) {
                    Logger.e(TAG, "Failed to add NFC MIME type", e)
                    throw RuntimeException("Failed to add NFC MIME type", e)
                }
                addCategory(Intent.CATEGORY_DEFAULT)
            }
            nfcIntentFilters = arrayOf(ndef)
        }
        nfcHandler.enableForegroundDispatch(this, nfcAdapter, nfcPendingIntent, nfcIntentFilters)
    }

    private fun disableForegroundDispatch() {
        nfcHandler.disableForegroundDispatch(this, nfcAdapter)
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

    companion object {
        private const val TAG = "ScannerActivity"
        private const val ADMIN_HOLD_DURATION_MS = 5000L
        private const val DEMO_INTERVAL_MS = 3000L

        @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
        internal fun shouldEnterLockTask(config: AdminConfig, lockTaskPermitted: Boolean): Boolean {
            return !config.demoMode && lockTaskPermitted
        }
    }

    /**
     * Android 14+: Foreground services cannot be started from background/early app states.
     * Start the watchdog only once the activity is visible.
     */
    private fun startWatchdogIfNeeded() {
        try {
            val intent = Intent(this, KioskWatchdogService::class.java)
            ContextCompat.startForegroundService(this, intent)
            Logger.i(TAG, "Watchdog start requested from ScannerActivity.onStart()")
        } catch (t: Throwable) {
            Logger.w(TAG, "Deferred watchdog start failed; will retry later.", t)
        }
    }
}
