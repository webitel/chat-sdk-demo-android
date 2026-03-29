package com.webitel.chat.sdk.demo_android.ui.contacts

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
import com.webitel.chat.sdk.demo_android.databinding.FragmentContactsBinding
import kotlinx.coroutines.launch


class ContactsFragment : Fragment() {
    private var _binding: FragmentContactsBinding? = null

    lateinit var viewModel: ContactsViewModel

    private val adapter = ContactsAdapter { contact ->
        val action = ContactsFragmentDirections
            .actionNavigationContactsToDialogFragment(
                dialogId = null,
                dialogTitle = contact.name,
                contactId = contact.id.sub,
                contactIss = contact.id.iss
            )

        findNavController().navigate(action)
    }

    private val binding get() = _binding!!


    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        viewModel = ViewModelProvider(this)
            .get(ContactsViewModel::class.java)

        _binding = FragmentContactsBinding.inflate(inflater, container, false)
        val root: View = binding.root

        binding.recyclerView.adapter = adapter
        binding.recyclerView.setHasFixedSize(true)

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.contacts.collect { list ->
                    adapter.submitList(list.toList())
                }
            }
        }
        observeEvents()
        return root
    }


    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewModel.load()
    }


    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
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