package com.laurelid.ui.kiosk

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.laurelid.R
import com.laurelid.databinding.FragmentKioskBinding
import com.laurelid.mdoc.PresentationRequestBuilder
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Fragment hosting the kiosk Verify with Wallet experience. 【ISO18013-7§7】
 */
@AndroidEntryPoint
class KioskFragment : Fragment() {

    private val viewModel: KioskViewModel by viewModels()
    private var binding: FragmentKioskBinding? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val inflate = FragmentKioskBinding.inflate(inflater, container, false)
        binding = inflate
        return inflate.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val binding = binding ?: return
        binding.retryButton.setOnClickListener { viewModel.startSession() }
        binding.resetButton.setOnClickListener { viewModel.reset() }
        lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collectLatest { render(it) }
            }
        }
        viewModel.startSession()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding = null
    }

    private fun render(state: KioskState) {
        val binding = binding ?: return
        when (state) {
            KioskState.Idle -> {
                binding.statusText.setText(R.string.kiosk_idle)
                binding.progressIndicator.isGone = true
                binding.qrImage.setImageBitmap(null)
                binding.sessionIdText.text = ""
                binding.resultContainer.isGone = true
            }
            KioskState.Engaging -> {
                binding.statusText.setText(R.string.kiosk_engaging)
                binding.progressIndicator.isVisible = true
                binding.resultContainer.isGone = true
            }
            is KioskState.WaitingApproval -> {
                binding.statusText.setText(R.string.kiosk_waiting)
                binding.progressIndicator.isGone = true
                binding.qrImage.setImageBitmap(state.qrBitmap)
                binding.sessionIdText.text = state.sessionId
                binding.resultContainer.isGone = true
            }
            KioskState.Decrypting -> {
                binding.statusText.setText(R.string.kiosk_decrypting)
                binding.progressIndicator.isVisible = true
            }
            is KioskState.Verified -> {
                binding.statusText.setText(R.string.kiosk_verified)
                binding.progressIndicator.isGone = true
                binding.resultContainer.isVisible = true
                binding.resultText.text = buildResultSummary(state.result)
            }
            is KioskState.Denied -> {
                binding.statusText.setText(R.string.kiosk_failed)
                binding.progressIndicator.isGone = true
                binding.resultContainer.isVisible = true
                binding.resultText.text = state.reason
            }
            is KioskState.Failed -> {
                binding.statusText.setText(R.string.kiosk_failed)
                binding.progressIndicator.isGone = true
                binding.resultContainer.isVisible = true
                binding.resultText.text = state.reason
            }
        }
    }

    private fun buildResultSummary(result: com.laurelid.mdoc.VerificationResult): String {
        val claims = result.minimalClaims
        val age = claims[PresentationRequestBuilder.AGE_OVER_21]?.toString() ?: "?"
        val given = claims[PresentationRequestBuilder.GIVEN_NAME]?.toString() ?: ""
        val family = claims[PresentationRequestBuilder.FAMILY_NAME]?.toString() ?: ""
        return getString(R.string.kiosk_verified) + "\n" + listOf(given, family, "21+=$age").joinToString(" ")
    }
}
