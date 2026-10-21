package com.webitel.chat.sdk.demo_android.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.webitel.chat.sdk.demo_android.BuildConfig
import com.webitel.chat.sdk.demo_android.databinding.FragmentSettingsBinding
import com.webitel.chat.sdk.demo_android.repo.AuthInfo
import com.webitel.chat.sdk.demo_android.ui.common.collectToasts
import kotlinx.coroutines.launch


class SettingsFragment : Fragment() {
    private var _binding: FragmentSettingsBinding? = null
    private val settingsViewModel: SettingsViewModel by viewModels()

    private val binding get() = _binding!!


    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        val root: View = binding.root

        return root
    }


    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.versionText.text =
            "Demo ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · SDK ${BuildConfig.SDK_VERSION}"

        binding.connectButton.setOnClickListener {
            val host = binding.endpointInput.validateNotBlank("Endpoint required")
            val token = binding.tokenInput.validateNotBlank("Token required")
            val usrId = binding.userIdInput.validateNotBlank("User ID required")
            val iss = binding.issInput.validateNotBlank("Issuer required")
            val name = binding.nameInput.validateNotBlank("Name required")

            if (host == null || token == null || usrId == null || iss == null || name == null) {
                return@setOnClickListener
            }

            val auth = AuthInfo(host, token, usrId, iss, name)
            settingsViewModel.save(auth)
        }

        observeAuthInfo()
        collectToasts(settingsViewModel.events)
        observeProgress()
    }


    override fun onResume() {
        super.onResume()
        settingsViewModel.findAuth(requireActivity())
    }


    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }


    private fun observeAuthInfo() {
        viewLifecycleOwner.lifecycleScope.launch {
            settingsViewModel.auth.collect { auth ->
                binding.endpointInput.setText(auth.host)
                binding.tokenInput.setText(auth.token)
                binding.userIdInput.setText(auth.userId)
                binding.issInput.setText(auth.issuer)
                binding.nameInput.setText(auth.userName)
            }
        }
    }


    private fun observeProgress() {
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                settingsViewModel.inProgress.collect { inProgress ->
                    binding.connectButton.isEnabled = !inProgress
                }
            }
        }
    }
}

private fun EditText.validateNotBlank(errorMessage: String): String? {
    val text = this.text.toString().trim()
    this.error = if (text.isEmpty()) errorMessage else null
    return text.takeIf { it.isNotEmpty() }
}