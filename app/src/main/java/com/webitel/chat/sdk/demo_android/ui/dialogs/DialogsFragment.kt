package com.webitel.chat.sdk.demo_android.ui.dialogs

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.webitel.chat.sdk.demo_android.databinding.FragmentDialogsBinding
import kotlinx.coroutines.launch


class DialogsFragment : Fragment() {
    private var _binding: FragmentDialogsBinding? = null
    private val viewModel: DialogsViewModel by viewModels()

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


    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}