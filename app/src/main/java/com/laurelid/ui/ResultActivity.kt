package com.laurelid.ui

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import com.laurelid.R
import com.laurelid.data.VerificationResult // Ensure this is Parcelable and has the necessary fields
import com.laurelid.util.KioskUtil
import com.laurelid.util.Logger // Added for logging potential issues

class ResultActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private var verificationResult: VerificationResult? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_result)
        KioskUtil.applyKioskDecor(window)
        KioskUtil.blockBackPress(this)

        verificationResult = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_VERIFICATION_RESULT, VerificationResult::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_VERIFICATION_RESULT)
        }

        if (verificationResult == null) {
            Logger.e(TAG, "VerificationResult is null. Cannot display result.")
            Toast.makeText(this, "Error displaying result.", Toast.LENGTH_LONG).show()
            // Fallback or finish if critical data is missing
            finish()
            return
        }

        bindResult()
        scheduleReturn()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            KioskUtil.setImmersiveMode(window)
        }
    }

    override fun onBackPressed() {
        // Disable back navigation while in kiosk mode.
        // KioskUtil.blockBackPress(this) also handles this in onCreate.
    }

    private fun bindResult() {
        val result = verificationResult ?: return // Should not happen due to check in onCreate

        val rootLayout: ConstraintLayout = findViewById(R.id.resultRoot)
        val titleView: TextView = findViewById(R.id.resultTitle)
        val detailView: TextView = findViewById(R.id.resultDetail)
        val issuerView: TextView = findViewById(R.id.resultIssuer)
        val iconView: TextView = findViewById(R.id.resultIcon)

        if (result.success) {
            rootLayout.setBackgroundColor(ContextCompat.getColor(this, R.color.verification_success))
            iconView.text = "✓" // Checkmark symbol
            titleView.text = getString(R.string.result_verified)
            // Assuming ageOver21 is a boolean in VerificationResult
            val ageDetail = if (result.ageOver21 == true) "21+" else "Under 21" // Handle null case for ageOver21 if it's nullable
            detailView.text = getString(R.string.result_success_detail, ageDetail)
        } else {
            rootLayout.setBackgroundColor(ContextCompat.getColor(this, R.color.verification_failure))
            iconView.text = "✕" // X symbol
            titleView.text = getString(R.string.result_rejected)
            val errorDetail = result.error ?: getString(R.string.result_details_error_unknown)
            detailView.text = getString(R.string.result_failure_detail, errorDetail)
        }

        val issuerText = result.issuer.takeIf { !it.isNullOrBlank() } ?: getString(R.string.result_unknown_issuer)
        issuerView.text = getString(R.string.result_issuer, issuerText)

        // Animate views after setting their initial text content
        animateViews(iconView, titleView, detailView, issuerView)
    }

    private fun animateViews(vararg views: View) { // Changed to View to be more generic
        val animations = views.flatMapIndexed { index, view ->
            view.alpha = 0f
            view.scaleX = 0.7f
            view.scaleY = 0.7f
            val alpha = ObjectAnimator.ofFloat(view, View.ALPHA, 1f)
            val scaleX = ObjectAnimator.ofFloat(view, View.SCALE_X, 1f)
            val scaleY = ObjectAnimator.ofFloat(view, View.SCALE_Y, 1f)
            val startDelay = index * 80L // Stagger start of animations

            listOf(alpha, scaleX, scaleY).onEach { animator ->
                animator.duration = 250L
                animator.startDelay = startDelay
            }
        }
        AnimatorSet().apply {
            playTogether(animations)
            start()
        }
    }

    private fun scheduleReturn() {
        handler.postDelayed({
            // Simply finish this activity to return to ScannerActivity if it wasn't finished.
            // Or, start ScannerActivity explicitly if it's always finished.
            // Assuming ScannerActivity might not be finished to allow quick re-scan.
            finish()
            // If ScannerActivity is always finished, then:
            // val intent = Intent(this, ScannerActivity::class.java)
            // intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            // startActivity(intent)
            // finish()
        }, RESULT_DISPLAY_DELAY_MS)
    }

    companion object {
        const val EXTRA_VERIFICATION_RESULT = "extra_verification_result"
        // Removed duplicate: const val EXTRA_SUCCESS = "extra_success"
        // Removed duplicate: const val EXTRA_AGE_OVER_21 = "extra_age_over_21"
        // Removed duplicate: const val EXTRA_ISSUER = "extra_issuer"
        // Removed duplicate: const val EXTRA_ERROR = "extra_error"
        private const val RESULT_DISPLAY_DELAY_MS = 4000L // Kept one definition
        private const val TAG = "ResultActivity" // Added for logging
    }
}
