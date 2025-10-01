package com.laurelid.ui

import android.os.Bundle
import android.text.InputFilter
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import com.laurelid.R
import com.laurelid.config.AdminConfig
import com.laurelid.config.AdminPinManager
import com.laurelid.config.ConfigManager
import com.laurelid.config.EncryptedAdminPinStorage
import com.laurelid.util.KioskUtil

class AdminActivity : AppCompatActivity() {

    private lateinit var configManager: ConfigManager
    private lateinit var adminPinManager: AdminPinManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_admin)
        KioskUtil.applyKioskDecor(window)
        configManager = ConfigManager(applicationContext)
        adminPinManager = AdminPinManager(EncryptedAdminPinStorage(applicationContext))
        bindConfig()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            KioskUtil.setImmersiveMode(window)
        }
    }

    private fun bindConfig() {
        val config = configManager.getConfig()
        val venueInput: EditText = findViewById(R.id.venueIdInput)
        val refreshInput: EditText = findViewById(R.id.trustRefreshInput)
        val apiInput: EditText = findViewById(R.id.apiEndpointInput)
        val demoSwitch: SwitchCompat = findViewById(R.id.demoModeSwitch)
        val pinStatus: TextView = findViewById(R.id.pinStatusText)
        val currentPinInput: EditText = findViewById(R.id.currentPinInput)
        val newPinInput: EditText = findViewById(R.id.newPinInput)
        val confirmPinInput: EditText = findViewById(R.id.confirmPinInput)
        val saveButton: Button = findViewById(R.id.saveButton)

        venueInput.setText(config.venueId)
        refreshInput.setText(config.trustRefreshIntervalMinutes.toString())
        apiInput.setText(config.apiEndpointOverride)
        demoSwitch.isChecked = config.demoMode

        val lengthFilter = InputFilter.LengthFilter(AdminPinManager.MAX_PIN_LENGTH)
        currentPinInput.filters = arrayOf(lengthFilter)
        newPinInput.filters = arrayOf(lengthFilter)
        confirmPinInput.filters = arrayOf(lengthFilter)

        val pinSet = adminPinManager.isPinSet()
        pinStatus.text = if (pinSet) {
            getString(R.string.admin_pin_status_set)
        } else {
            getString(R.string.admin_pin_status_not_set)
        }
        currentPinInput.visibility = if (pinSet) View.VISIBLE else View.GONE

        saveButton.setOnClickListener {
            currentPinInput.error = null
            newPinInput.error = null
            confirmPinInput.error = null

            val venueId = venueInput.text.toString().trim()
            val refreshMinutes = refreshInput.text.toString().toIntOrNull()
                ?: AdminConfig.DEFAULT_TRUST_REFRESH_MINUTES
            val endpoint = apiInput.text.toString().trim()
            val currentPin = currentPinInput.text.toString()
            val newPin = newPinInput.text.toString().trim()
            val confirmPin = confirmPinInput.text.toString().trim()
            val pinCurrentlySet = adminPinManager.isPinSet()

            if (!pinCurrentlySet && newPin.isBlank()) {
                newPinInput.error = getString(R.string.admin_pin_required)
                return@setOnClickListener
            }

            if ((newPin.isNotBlank() && confirmPin.isBlank()) || (confirmPin.isNotBlank() && newPin.isBlank())) {
                confirmPinInput.error = getString(R.string.admin_pin_mismatch)
                return@setOnClickListener
            }

            var pinUpdated = false

            if (newPin.isNotBlank() || confirmPin.isNotBlank() || !pinCurrentlySet) {
                if (newPin != confirmPin) {
                    confirmPinInput.error = getString(R.string.admin_pin_mismatch)
                    return@setOnClickListener
                }

                if (!adminPinManager.isPinFormatValid(newPin)) {
                    newPinInput.error = getString(
                        R.string.admin_pin_requirements,
                        AdminPinManager.MIN_PIN_LENGTH,
                        AdminPinManager.MAX_PIN_LENGTH
                    )
                    return@setOnClickListener
                }

                if (pinCurrentlySet && currentPin.isBlank()) {
                    currentPinInput.error = getString(R.string.admin_pin_error)
                    return@setOnClickListener
                }

                if (pinCurrentlySet) {
                    when (val verification = adminPinManager.verifyPin(currentPin)) {
                        AdminPinManager.VerificationResult.Success -> {
                            adminPinManager.updatePin(newPin)
                            pinUpdated = true
                        }
                        is AdminPinManager.VerificationResult.Failure -> {
                            currentPinInput.error = getString(R.string.admin_pin_error)
                            if (verification.remainingAttempts > 0) {
                                Toast.makeText(
                                    this,
                                    getString(R.string.admin_pin_attempts_remaining, verification.remainingAttempts),
                                    Toast.LENGTH_SHORT
                                ).show()
                            } else {
                                Toast.makeText(this, R.string.admin_pin_error, Toast.LENGTH_SHORT).show()
                            }
                            return@setOnClickListener
                        }
                        is AdminPinManager.VerificationResult.Locked -> {
                            val message = getString(
                                R.string.admin_pin_locked,
                                formatLockoutDuration(verification.remainingMillis)
                            )
                            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                            return@setOnClickListener
                        }
                        AdminPinManager.VerificationResult.PinNotSet -> {
                            adminPinManager.updatePin(newPin)
                            pinUpdated = true
                        }
                    }
                } else {
                    adminPinManager.updatePin(newPin)
                    pinUpdated = true
                }
            }

            val updatedConfig = AdminConfig(
                venueId = venueId,
                trustRefreshIntervalMinutes = refreshMinutes,
                apiEndpointOverride = endpoint,
                demoMode = demoSwitch.isChecked,
            )
            configManager.saveConfig(updatedConfig)
            val message = if (pinUpdated) {
                getString(R.string.admin_pin_updated)
            } else {
                getString(R.string.admin_saved)
            }
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun formatLockoutDuration(remainingMillis: Long): String {
        val totalSeconds = (remainingMillis.coerceAtLeast(0) + 999) / 1000
        val minutes = (totalSeconds / 60).toInt()
        val seconds = (totalSeconds % 60).toInt()
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
}
