package com.webitel.chat.sdk.demo_android.ui.dialog

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import androidx.core.view.MenuProvider
import androidx.core.view.WindowCompat
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.webitel.chat.sdk.ContactId
import com.webitel.chat.sdk.MessageContent
import com.webitel.chat.sdk.demo_android.R
import com.webitel.chat.sdk.demo_android.databinding.FragmentDialogBinding
import com.webitel.chat.sdk.demo_android.ui.common.collectToasts
import com.webitel.chat.sdk.demo_android.ui.common.formatDateTime
import kotlinx.coroutines.launch
import java.io.File


class DialogFragment : Fragment(R.layout.fragment_dialog) {

    private var _binding: FragmentDialogBinding? = null
    private val viewModel: DialogViewModel by viewModels()
    private lateinit var adapter: MessagesAdapter
    /** Last message in the list — to scroll down only when a new one appears at the bottom. */
    private var lastItemId: String? = null
    /** Scroll request waiting until its message is in the adapter. */
    private var pendingScroll: ScrollRequest? = null

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
            },
            onReactionClick = { message, emoji ->
                viewModel.toggleReaction(message, emoji)
            },
            onMessageLongClick = { message ->
                showMessageActions(message)
            },
            onLocationClick = ::openLocation,
            onContactClick = ::showContactActions
        )
        val llm = MessagesLinearLayoutManager(
            requireContext()
        )
        llm.stackFromEnd = true

        binding.rvMessages.layoutManager = llm
        binding.rvMessages.adapter = adapter
        binding.rvMessages.setItemViewCacheSize(100)
        binding.rvMessages.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) = onVisibleItemsChanged()
        })

        return root
    }


    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewModel.initiate(dialogId, getContactId())
        observeMessages()
        observeHistory()
        setupToolbarMenu()

        binding.btnSend.setOnClickListener {
            val text = binding.etMessage.text.toString().trim()
            if (text.isNotBlank()) {
                viewModel.sendMessage(text)
                binding.etMessage.setText("")
            }
        }

        binding.btnCancelReply.setOnClickListener { viewModel.cancelReply() }
        binding.fabLatest.setOnClickListener { viewModel.jumpToLatest() }

        // Clearing the field after sending is not typing
        binding.etMessage.doAfterTextChanged { if (!it.isNullOrEmpty()) viewModel.onInputChanged() }

        binding.btnAttach.setOnClickListener {
            openDocument.launch(arrayOf("*/*"))
        }

        collectToasts(viewModel.events)
        observeButtonActions()
        observeReply()
        observeTyping()
    }


    /**
     * Toolbar: search and members. Only for an existing dialog
     * (members are known only then).
     */
    private fun setupToolbarMenu() {
        requireActivity().addMenuProvider(object : MenuProvider {
            override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
                val members = viewModel.members.value
                if (members.isEmpty()) return
                menu.add(Menu.NONE, MENU_SEARCH, Menu.NONE, "Search")
                    .setIcon(R.drawable.ic_search)
                    .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
                menu.add(Menu.NONE, MENU_MEMBERS, Menu.NONE, "Members")
                    .setIcon(R.drawable.ic_members)
                    .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            }

            // Only our items: the toolbar "Up" arrow comes here too and must reach navigation
            override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
                when (menuItem.itemId) {
                    MENU_SEARCH -> showSearchDialog()
                    MENU_MEMBERS -> showMembers()
                    else -> return false
                }
                return true
            }
        }, viewLifecycleOwner)

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.members.collect { requireActivity().invalidateMenu() }
            }
        }
    }


    private fun showMembers() {
        val items = viewModel.members.value.map { member ->
            val role = member.role.name.lowercase().replaceFirstChar { it.uppercase() }
            "${member.contact.name} — $role"
        }
        AlertDialog.Builder(requireContext())
            .setTitle("Members (${items.size})")
            .setItems(items.toTypedArray(), null)
            .setPositiveButton("Close", null)
            .show()
    }


    private fun showSearchDialog() {
        showInputDialog(title = "Search", text = "", positive = "Search") { query ->
            viewModel.search(query)
        }
    }


    /** Search results, newest first. Tap opens the chat at that message. */
    private fun showSearchResults(results: List<MessageUI>) {
        val items = results.map { "${it.senderName} · ${formatDateTime(it.createdAt)}\n${it.toReplyUI().text}" }
        AlertDialog.Builder(requireContext())
            .setTitle("Found: ${results.size}")
            .setItems(items.toTypedArray()) { _, index -> viewModel.jump(results[index]) }
            .setNegativeButton("Close", null)
            .show()
    }


    /** Pagination, the "jump to latest" button and scroll requests after a page is loaded. */
    private fun observeHistory() {
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.isAtLatest.collect { binding.fabLatest.isVisible = !it }
                }
                // Removed from the dialog (e.g. while offline): no input, just a note
                launch {
                    viewModel.isMember.collect { isMember ->
                        binding.inputBar.isVisible = isMember
                        binding.tvNotMember.isVisible = !isMember
                        if (!isMember) viewModel.cancelReply()
                    }
                }
                launch {
                    viewModel.scrollRequests.collect {
                        pendingScroll = it
                        applyPendingScroll()
                    }
                }
                launch {
                    viewModel.searchResults.collect { showSearchResults(it) }
                }
            }
        }
    }


    /** Scrolls to the requested message once it is in the adapter. */
    private fun applyPendingScroll() {
        val request = pendingScroll ?: return
        val index = adapter.currentList.indexOfFirst { it.localId == request.localId }
        if (index == -1) return
        pendingScroll = null

        val lm = binding.rvMessages.layoutManager as LinearLayoutManager
        when (request.anchor) {
            ScrollRequest.Anchor.TOP -> lm.scrollToPositionWithOffset(index, 0)
            ScrollRequest.Anchor.CENTER -> lm.scrollToPositionWithOffset(index, binding.rvMessages.height / 3)
            ScrollRequest.Anchor.BOTTOM -> lm.scrollToPosition(index)
        }
    }


    /** Called on scroll and after the list changed. */
    private fun onVisibleItemsChanged() {
        markVisibleAsRead()
        loadPageIfNeeded()
    }


    /** Top reached → older page; bottom reached while not at the newest message → newer page. */
    private fun loadPageIfNeeded() {
        val lm = _binding?.rvMessages?.layoutManager as? LinearLayoutManager ?: return
        val count = adapter.itemCount
        if (count == 0) return
        if (lm.findFirstVisibleItemPosition() in 0..2) viewModel.loadOlder()
        if (lm.findLastVisibleItemPosition() >= count - 3) viewModel.loadNewer()
    }


    /** "X is typing…" above the input. */
    private fun observeTyping() {
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.typingText.collect { text ->
                    binding.tvTyping.isVisible = text != null
                    binding.tvTyping.text = text
                }
            }
        }
    }


    /** "Replying to X" banner above the input. */
    private fun observeReply() {
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.replyingTo.collect { message ->
                    binding.replyBanner.isVisible = message != null
                    if (message != null) {
                        val reply = message.toReplyUI()
                        binding.tvReplyTitle.text = "Replying to ${reply.authorName}"
                        binding.tvReplyText.text = reply.text
                    }
                }
            }
        }
    }


    override fun onResume() {
        super.onResume()
        markVisibleAsRead()
    }


    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }


    /** Incoming messages that are on screen now are marked as read. Not in background. */
    private fun markVisibleAsRead() {
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        val lm = _binding?.rvMessages?.layoutManager as? LinearLayoutManager ?: return
        val first = lm.findFirstVisibleItemPosition()
        val last = lm.findLastVisibleItemPosition()
        if (first == RecyclerView.NO_POSITION || last == RecyclerView.NO_POSITION) return

        val list = adapter.currentList
        if (first >= list.size) return
        viewModel.onMessagesVisible(list.subList(first, minOf(last + 1, list.size)))
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

    /** Long press menu: quick reactions, "Remove reaction", "Reply", "Edit" and "Delete". */
    private fun showMessageActions(message: MessageUI) {
        val actions = DialogViewModel.QUICK_REACTIONS.map { emoji ->
            emoji to { viewModel.toggleReaction(message, emoji) }
        }.toMutableList()
        if (message.myReaction != null) {
            actions += "Remove reaction" to { viewModel.removeReaction(message) }
        }
        actions += "Reply" to { viewModel.startReply(message) }
        if (message.canEdit) {
            actions += "Edit" to { showEditDialog(message) }
        }
        // Shown for any message: the server decides and reports why it was not deleted.
        actions += "Delete" to { viewModel.deleteMessage(message) }

        AlertDialog.Builder(requireContext())
            .setItems(actions.map { it.first }.toTypedArray()) { _, index -> actions[index].second() }
            .show()
    }


    private fun showEditDialog(message: MessageUI) {
        val text = (message.content as? MessageContent.Text)?.text.orEmpty()
        showInputDialog(title = "Edit message", text = text, positive = "Save") { newText ->
            viewModel.editMessage(message, newText)
        }
    }


    /** Dialog with one text field (edit, search). */
    private fun showInputDialog(title: String, text: String, positive: String, onSubmit: (String) -> Unit) {
        val input = EditText(requireContext()).apply {
            setText(text)
            setSelection(text.length)
        }

        AlertDialog.Builder(requireContext())
            .setTitle(title)
            .setView(input)
            .setPositiveButton(positive) { _, _ ->
                hideKeyboard(input)
                onSubmit(input.text.toString())
            }
            .setNegativeButton("Cancel") { _, _ -> hideKeyboard(input) }
            // Closed with Back / tap outside: the dialog window is gone, hide IME for the activity window.
            .setOnDismissListener {
                val root = _binding?.root ?: return@setOnDismissListener
                WindowCompat.getInsetsController(requireActivity().window, root)
                    .hide(WindowInsetsCompat.Type.ime())
            }
            .show()
    }


    /**
     * The keyboard belongs to the dialog's window. If the dialog closes with the keyboard open,
     * the chat screen stays shrunk by the keyboard height — so hide it before closing.
     */
    private fun hideKeyboard(view: View) {
        val imm = requireContext().getSystemService(InputMethodManager::class.java)
        imm.hideSoftInputFromWindow(view.windowToken, 0)
    }


    /** Opens a maps app at the location. */
    private fun openLocation(location: MessageContent.Location) {
        val point = "${location.latitude},${location.longitude}"
        val uri = Uri.parse("geo:$point?q=$point(${Uri.encode(location.name)})")
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
            .onFailure { Toast.makeText(context, "No maps app", Toast.LENGTH_SHORT).show() }
    }


    /** "Call" / "Email" for a contact card — only for the fields it has. */
    private fun showContactActions(contact: MessageContent.Contact) {
        val actions = buildList {
            contact.phone?.let { phone ->
                add("Call $phone" to Intent(Intent.ACTION_DIAL, Uri.parse("tel:${phone.filterNot { it.isWhitespace() }}")))
            }
            contact.email?.let { email ->
                add("Email $email" to Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$email")))
            }
        }
        if (actions.isEmpty()) return

        AlertDialog.Builder(requireContext())
            .setTitle(contact.name)
            .setItems(actions.map { it.first }.toTypedArray()) { _, index ->
                runCatching { startActivity(actions[index].second) }
                    .onFailure { Toast.makeText(context, "No app to open it", Toast.LENGTH_SHORT).show() }
            }
            .show()
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
                    // Follow a new message at the bottom: only when the newest messages are loaded,
                    // and the user is at the bottom or it's our own message. Uses the last id, not the
                    // count: loading older pages changes the count too.
                    val last = list.lastOrNull()
                    val lastChanged = last?.localId != lastItemId
                    val shouldScroll = last != null && lastChanged && viewModel.isAtLatest.value &&
                            (lastItemId == null || isNearBottom() || last.isOutgoing)
                    lastItemId = last?.localId

                    adapter.submitList(list) {
                        if (shouldScroll) binding.rvMessages.scrollToPosition(list.size - 1)
                        applyPendingScroll()
                        // After layout: new messages may now be on screen
                        binding.rvMessages.post { if (_binding != null) onVisibleItemsChanged() }
                    }
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
                            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(event.url)))
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
        private const val MENU_MEMBERS = 1
        private const val MENU_SEARCH = 2
    }
}