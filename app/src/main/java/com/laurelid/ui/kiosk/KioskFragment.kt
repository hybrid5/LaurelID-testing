package com.laurelid.ui.kiosk

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.laurelid.databinding.FragmentKioskBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect

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
        binding?.retryButton?.setOnClickListener { viewModel.startSession() }
        binding?.resetButton?.setOnClickListener { viewModel.reset() }
        lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { render(it) }
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
                binding.statusText.text = getString(com.laurelid.R.string.kiosk_idle)
                binding.progressIndicator.visibility = View.INVISIBLE
                binding.qrImage.setImageBitmap(null)
                binding.resultText.text = ""
            }
            KioskState.Engaging -> {
                binding.statusText.text = getString(com.laurelid.R.string.kiosk_engaging)
                binding.progressIndicator.visibility = View.VISIBLE
                binding.qrImage.setImageBitmap(null)
                binding.resultText.text = ""
            }
            is KioskState.WaitingApproval -> {
                binding.statusText.text = getString(com.laurelid.R.string.kiosk_waiting)
                binding.progressIndicator.visibility = View.INVISIBLE
                binding.qrImage.setImageBitmap(state.qrBitmap)
                binding.resultText.text = state.sessionId ?: ""
            }
            KioskState.Decrypting -> {
                binding.statusText.text = getString(com.laurelid.R.string.kiosk_decrypting)
                binding.progressIndicator.visibility = View.VISIBLE
                binding.resultText.text = ""
            }
            is KioskState.Verified -> {
                binding.statusText.text = getString(com.laurelid.R.string.kiosk_verified)
                binding.progressIndicator.visibility = View.INVISIBLE
                binding.resultText.text = state.result.minimalClaims.entries.joinToString { "${it.key}=${it.value}" }
            }
            is KioskState.Failed -> {
                binding.statusText.text = getString(com.laurelid.R.string.kiosk_failed)
                binding.progressIndicator.visibility = View.INVISIBLE
                binding.resultText.text = state.reason
            }
        }
    }
}

