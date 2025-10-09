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
import com.laurelid.databinding.FragmentKioskBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Fragment hosting the kiosk Verify with Wallet experience. 【ISO18013-7§7】
 */
@AndroidEntryPoint
class KioskFragment : Fragment() {

    private val viewModel: KioskViewModel by viewModels()
    private var _binding: FragmentKioskBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentKioskBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
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
        _binding = null
    }

    private fun render(state: KioskViewModel.UiState) {
        val kioskBinding = binding

        kioskBinding.statusText.text = state.status
        kioskBinding.progressIndicator.isVisible = state.loading
        kioskBinding.progressIndicator.isGone = !state.loading

        val qr = state.qr
        kioskBinding.qrPanel.qrImage.setImageBitmap(qr?.bitmap)
        kioskBinding.qrPanel.sessionIdText.text = qr?.sessionId.orEmpty()

        val hasResult = !state.resultText.isNullOrBlank()
        kioskBinding.resultContainer.root.isVisible = hasResult
        kioskBinding.resultContainer.root.isGone = !hasResult
        kioskBinding.resultContainer.resultText.text = state.resultText.orEmpty()
    }
}
