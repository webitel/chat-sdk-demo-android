package com.webitel.chat.sdk.demo_android.ui.dialog

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
import androidx.recyclerview.widget.LinearLayoutManager
import com.webitel.chat.sdk.ContactId
import com.webitel.chat.sdk.demo_android.R
import com.webitel.chat.sdk.demo_android.databinding.FragmentDialogBinding
import kotlinx.coroutines.launch


class DialogFragment : Fragment(R.layout.fragment_dialog) {

    private var _binding: FragmentDialogBinding? = null
    lateinit var viewModel: DialogViewModel
    private lateinit var adapter: MessagesAdapter
    private var lastSize = 0

    private val dialogId: String? by lazy {
        requireArguments().getString(DIALOG_ID)
    }

    private val contactId: String? by lazy {
        requireArguments().getString(CONTACT_ID)
    }

    private val contactIss: String? by lazy {
        requireArguments().getString(CONTACT_ISS)
    }

    private val binding get() = _binding!!


    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        viewModel = ViewModelProvider(this).get(DialogViewModel::class.java)
        _binding = FragmentDialogBinding.inflate(inflater, container, false)
        val root: View = _binding!!.root

        adapter = MessagesAdapter()
        val llm = MessagesLinearLayoutManager(
            requireContext()
        )
        llm.stackFromEnd = true

        binding.rvMessages.layoutManager = llm
        binding.rvMessages.adapter = adapter
        binding.rvMessages.setItemViewCacheSize(100)

        return root
    }


    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewModel.initiate(dialogId, getContactId())
        observeMessages()
        observeConnectionState()

        binding.btnSend.setOnClickListener {
            val text = binding.etMessage.text.toString()
            if (text.isNotBlank()) {
                viewModel.sendMessage(text)
                binding.etMessage.setText("")
            }
        }
        observeEvents()
    }

    override fun onResume() {
        super.onResume()
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.checkUpdatesForDialog()
        }
    }


    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }


    private fun getContactId(): ContactId? {
        val cId = contactId
        val cIss = contactIss
        return if (!cId.isNullOrEmpty() && !cIss.isNullOrEmpty()) {
            ContactId(cId, cIss)
        } else null
    }


    private fun isNearBottom(threshold: Int = 2): Boolean {
        val lm = binding.rvMessages.layoutManager as? LinearLayoutManager ?: return true
        val lastVisible = lm.findLastVisibleItemPosition()
        val total = lm.itemCount
        return lastVisible >= total - 1 - threshold
    }


    private fun observeMessages() {
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.messages.collect { list ->
                    val isNewItem = list.size > lastSize

                    val shouldScroll = (isNewItem && (isNearBottom()
                            || !list.lastOrNull()?.localId.isNullOrEmpty()))
                    adapter.submitList(list) {
                        if (shouldScroll && list.isNotEmpty()) {
                            binding.rvMessages.scrollToPosition(list.size - 1)
                        }
                    }

                    lastSize = list.size
                }
            }
        }
    }

    private fun observeConnectionState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.reload.collect {
                    viewModel.checkUpdatesForDialog()
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


    companion object {
        const val DIALOG_ID = "dialog_id"
        const val CONTACT_ID = "contact_id"
        const val CONTACT_ISS = "contact_iss"
    }
}