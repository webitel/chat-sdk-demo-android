package com.webitel.chat.sdk.demo_android.ui.dialog

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
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
import java.io.File


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


    private val openDocument =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            uri?.let {
                requireContext().contentResolver.takePersistableUriPermission(
                    it,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                val text = binding.etMessage.text.toString().trim()
                binding.etMessage.setText("")
                sendMediaMessage(it, text)
            }
        }


    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        viewModel = ViewModelProvider(this).get(DialogViewModel::class.java)
        _binding = FragmentDialogBinding.inflate(inflater, container, false)
        val root: View = _binding!!.root

        adapter = MessagesAdapter(
            onDownloadClick = { messageLocalId, fileId, fileName, fileSize ->
                viewModel.downloadFile(requireContext(), messageLocalId, fileId, fileName, fileSize)
            },
            onFileOpen = { localPath, mimeType ->
                openFileWithIntent(localPath, mimeType)
            },
            onButtonClick = { messageId, buttonId, action ->
                viewModel.onButtonClick(messageId, buttonId, action)
            }
        )
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
            val text = binding.etMessage.text.toString().trim()
            if (text.isNotBlank()) {
                viewModel.sendMessage(text)
                binding.etMessage.setText("")
            }
        }

        binding.btnAttach.setOnClickListener {
            openDocument.launch(arrayOf("*/*"))
        }

        observeEvents()
        observeButtonActions()
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


    private fun sendMediaMessage(uri: Uri, text: String = "") {
        viewModel.sendFile(requireContext(), uri, text)
    }

    private fun openFileWithIntent(localPath: String, mimeType: String) {
        val file = File(localPath)
        val uri = FileProvider.getUriForFile(
            requireContext(),
            "${requireContext().packageName}.fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Open file"))
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

    private fun observeButtonActions() {
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.buttonActionEvents.collect { event ->
                    when (event) {
                        is ButtonActionEvent.OpenUrl -> {
                            val intent = android.content.Intent(
                                android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse(event.url)
                            )
                            startActivity(intent)
                        }
                        is ButtonActionEvent.RequestData -> {
                            Toast.makeText(
                                context,
                                "RequestData: ${event.type} — is not supported in the demo app",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
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