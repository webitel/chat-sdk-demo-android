package com.webitel.chat.sdk.demo_android.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.webitel.chat.sdk.demo_android.databinding.FragmentSettingsBinding
import com.webitel.chat.sdk.demo_android.repo.AuthInfo
import kotlinx.coroutines.launch


class SettingsFragment : Fragment() {
    private var _binding: FragmentSettingsBinding? = null
    lateinit var settingsViewModel: SettingsViewModel

    private val binding get() = _binding!!


    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        settingsViewModel =
            ViewModelProvider(this).get(SettingsViewModel::class.java)

        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        val root: View = binding.root

        return root
    }


    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.connectButton.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    val host = binding.endpointInput.text.toString()
                    val token = binding.tokenInput.text.toString()
                    val usrId = binding.userIdInput.text.toString()
                    val iss = binding.issInput.text.toString()
                    val name = binding.nameInput.text.toString()

                    if (host.isEmpty() || token.isEmpty() || usrId.isEmpty()
                        || iss.isEmpty() || name.isEmpty()
                    ) {
                        Toast.makeText(context, "all must be filled", Toast.LENGTH_LONG)
                            .show()
                    } else {
                        val auth = AuthInfo(host, token, usrId, iss, name)
                        settingsViewModel.save(requireActivity().application, auth)
                    }
                }
            }
        }
        observeAuthInfo()
        observeEvents()
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

    private fun observeEvents() {
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                settingsViewModel.events.collect { event ->
                    Toast.makeText(context, event, Toast.LENGTH_LONG)
                        .show()
                }
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