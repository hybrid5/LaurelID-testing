package com.laurelid.ui.verify

import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.ViewFlipper
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.laurelid.databinding.ActivityVerifyBinding
import com.laurelid.verifier.transport.NfcEngagementTransport
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@AndroidEntryPoint
class VerifyActivity : AppCompatActivity() {

    private val viewModel: VerificationViewModel by viewModels()

    @Inject lateinit var nfcEngagementTransport: NfcEngagementTransport

    private lateinit var binding: ActivityVerifyBinding
    private lateinit var flipper: ViewFlipper
    private lateinit var qrImage: ImageView
    private lateinit var badge: TextView
    private lateinit var name: TextView
    private lateinit var errorView: TextView
    private lateinit var portraitView: ImageView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVerifyBinding.inflate(layoutInflater)
        setContentView(binding.root)

        flipper = binding.verifyFlipper
        qrImage = binding.root.findViewById(com.laurelid.R.id.verify_qr_image)
        badge = binding.root.findViewById(com.laurelid.R.id.verify_result_badge)
        name = binding.root.findViewById(com.laurelid.R.id.verify_result_name)
        errorView = binding.root.findViewById(com.laurelid.R.id.verify_result_error)
        portraitView = binding.root.findViewById(com.laurelid.R.id.verify_portrait)

        nfcEngagementTransport.bind(this)

        lifecycleScope.launch {
            viewModel.state.collectLatest { render(it) }
        }
    }

    private fun render(state: VerificationUiState) {
        when (state.stage) {
            VerificationUiState.Stage.WAITING -> showWaiting(state)
            VerificationUiState.Stage.PENDING -> showPending()
            VerificationUiState.Stage.RESULT -> showResult(state)
        }
    }

    private fun showWaiting(state: VerificationUiState) {
        flipper.displayedChild = 0
        val bitmap = state.qrCode
        if (bitmap != null) {
            qrImage.setImageBitmap(bitmap)
            qrImage.visibility = View.VISIBLE
        } else {
            qrImage.setImageBitmap(null)
            qrImage.visibility = View.GONE
        }
        clearResultViews()
    }

    private fun showPending() {
        flipper.displayedChild = 1
        clearResultViews()
    }

    private fun showResult(state: VerificationUiState) {
        flipper.displayedChild = 2
        val result = state.result
        if (result?.isSuccess == true) {
            badge.visibility = View.VISIBLE
            name.visibility = View.VISIBLE
            errorView.visibility = View.GONE
            val given = result.minimalClaims["given_name"] as? String
            val family = result.minimalClaims["family_name"] as? String
            name.text = listOfNotNull(given, family).joinToString(separator = " ")
            val portrait = result.portrait
            if (portrait != null && portrait.isNotEmpty()) {
                portraitView.visibility = View.VISIBLE
                portraitView.setImageBitmap(BitmapFactory.decodeByteArray(portrait, 0, portrait.size))
            } else {
                portraitView.visibility = View.GONE
            }
        } else {
            badge.visibility = View.GONE
            name.visibility = View.GONE
            errorView.visibility = View.VISIBLE
            errorView.text = state.errorMessage ?: getString(com.laurelid.R.string.verify_result_error)
            portraitView.visibility = View.GONE
        }
    }

    private fun clearResultViews() {
        badge.visibility = View.GONE
        name.visibility = View.GONE
        errorView.visibility = View.GONE
        portraitView.visibility = View.GONE
        portraitView.setImageBitmap(null)
    }
}
