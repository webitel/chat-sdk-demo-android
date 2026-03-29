package com.webitel.chat.sdk.demo_android.ui.dialogs

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
import androidx.navigation.fragment.findNavController
import com.webitel.chat.sdk.ConnectionState
import com.webitel.chat.sdk.demo_android.databinding.FragmentDialogsBinding
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch


class DialogsFragment : Fragment() {
    private var _binding: FragmentDialogsBinding? = null
    lateinit var viewModel: DialogsViewModel

    private val adapter = DialogsAdapter { dialog ->
        val action = DialogsFragmentDirections
            .actionNavigationDialogsToDialogFragment(
                dialogId = dialog.id,
                dialogTitle = dialog.subject,
                contactId = null,
                contactIss = null
            )

        findNavController().navigate(action)
    }

    private val binding get() = _binding!!


    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {

        _binding = FragmentDialogsBinding.inflate(inflater, container, false)
        val root: View = binding.root
        viewModel = ViewModelProvider(this).get(DialogsViewModel::class.java)
        binding.recyclerView.adapter = adapter
        binding.recyclerView.setHasFixedSize(true)

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.dialogs.collect { list ->
                    adapter.submitList(list.toList())
                }
            }
        }
        return root
    }


    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        observeEvents()
        viewModel.load()
        observeConnectionState()
    }


    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }


    private fun observeConnectionState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.connectionState
                    .map { it is ConnectionState.Connected }
                    .distinctUntilChanged()
                    .filter { it }
                    .collect {
                        viewModel.load()
                    }
            }
        }
    }


    private fun observeEvents() {
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.events.collect { event ->
                    Toast.makeText(context, event, Toast.LENGTH_LONG)
                        .show()
                }
            }
        }
    }
}