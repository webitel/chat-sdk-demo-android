package com.webitel.chat.sdk.demo_android.ui.dialog

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.webitel.chat.sdk.Cancellable
import com.webitel.chat.sdk.ChatButtonAction
import com.webitel.chat.sdk.ContactId
import com.webitel.chat.sdk.Dialog
import com.webitel.chat.sdk.FileSource
import com.webitel.chat.sdk.MessageContent
import com.webitel.chat.sdk.MessageTarget
import com.webitel.chat.sdk.Participant
import com.webitel.chat.sdk.SkippedMessageReason
import com.webitel.chat.sdk.UploadRequest
import com.webitel.chat.sdk.demo_android.repo.ChatRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File


/** Where to put a message on screen after a history page is loaded. */
data class ScrollRequest(val localId: String, val anchor: Anchor) {
    enum class Anchor { TOP, CENTER, BOTTOM }
}


sealed class ButtonActionEvent {
    data class OpenUrl(val url: String) : ButtonActionEvent()
    data class RequestData(val type: String) : ButtonActionEvent()
}


/**
 * Chat screen. Opened either for an existing dialog ([dialogId])
 * or for a contact without a dialog yet ([contactId]).
 */
class DialogViewModel : ViewModel() {
    private val repo = ChatRepository.shared

    private val _events = MutableSharedFlow<String>()
    val events: SharedFlow<String> = _events

    /** Dialog members. Empty for a contact chat without a dialog yet. */
    private val _members = MutableStateFlow<List<Participant>>(emptyList())
    val members: StateFlow<List<Participant>> = _members

    /** False when the current user has left the dialog (e.g. removed while offline, see SyncRepository). */
    private val _isMember = MutableStateFlow(true)
    val isMember: StateFlow<Boolean> = _isMember
    private var membersJob: Job? = null

    /** "X is typing…" or null. */
    private val _typingText = MutableStateFlow<String?>(null)
    val typingText: StateFlow<String?> = _typingText
    private var typingJob: Job? = null

    /** The message we are replying to, shown above the input. */
    private val _replyingTo = MutableStateFlow<MessageUI?>(null)
    val replyingTo: StateFlow<MessageUI?> = _replyingTo

    private val _buttonActionEvents = MutableSharedFlow<ButtonActionEvent>()
    val buttonActionEvents: SharedFlow<ButtonActionEvent> = _buttonActionEvents.asSharedFlow()

    private val _messages = MutableStateFlow<List<MessageUI>>(emptyList())
    val messages: StateFlow<List<MessageUI>> = _messages

    /** False after a jump to an older message — newer messages are loaded by scrolling down. */
    private val _isAtLatest = MutableStateFlow(true)
    val isAtLatest: StateFlow<Boolean> = _isAtLatest
    private var hasOlder = false
    private var isLoadingPage = false

    private val _scrollRequests = MutableSharedFlow<ScrollRequest>(extraBufferCapacity = 1)
    val scrollRequests: SharedFlow<ScrollRequest> = _scrollRequests

    private val _searchResults = MutableSharedFlow<List<MessageUI>>()
    val searchResults: SharedFlow<List<MessageUI>> = _searchResults

    private var dialogId: String? = null
    private var contactId: ContactId? = null
    private var messagesJob: Job? = null

    private val target: MessageTarget
        get() = dialogId?.let { MessageTarget.Dialog(it) }
            ?: MessageTarget.Contact(requireNotNull(contactId))

    private val activeDownloads = mutableMapOf<String, Cancellable>()


    fun initiate(dialogId: String?, contactId: ContactId?) {
        if (this.dialogId != null || this.contactId != null) return  // e.g. screen rotation

        this.contactId = contactId
        this.dialogId = dialogId ?: contactId?.let { repo.dialogs.findByContact(it.sub)?.id }

        observeMessages()
        if (this.dialogId == null) observeDialogCreation()

        // Only the first open loads history: after a reconnect the SDK syncs missed changes itself
        loadPage({ repo.messages.loadIfNeeded(it) })
    }


    fun sendMessage(text: String) {
        val replyTo = _replyingTo.value
        _replyingTo.value = null
        viewModelScope.launch {
            try {
                repo.messages.sendText(target, text, replyTo)
            } catch (e: Exception) {
                _events.emit(e.message.orEmpty())
            }
        }
    }


    /**
     * Called with the messages that are on screen now. `markAsRead` covers all older
     * messages too, so only the newest incoming one is passed on.
     */
    fun onMessagesVisible(visible: List<MessageUI>) {
        val dialogId = dialogId ?: return
        val newest = visible
            .filter { !it.isOutgoing && it.isSent && !it.isDeleted }
            .maxByOrNull { it.sequence ?: 0 }
            ?: return
        repo.read.markAsRead(dialogId, newest)
    }


    /** Called on every input change: tells others that we are typing. */
    fun onInputChanged() {
        dialogId?.let { repo.typing.sendTyping(it) }
    }


    fun startReply(message: MessageUI) {
        _replyingTo.value = message
    }


    fun cancelReply() {
        _replyingTo.value = null
    }


    fun sendFile(context: Context, uri: Uri, text: String = "") {
        viewModelScope.launch {
            try {
                // Keep a local copy: after upload the sender sees the file as already downloaded.
                val localFile = copyToLocalFile(context, uri)
                val request = UploadRequest(
                    FileSource.Stream(localFile.inputStream()),
                    localFile.name,
                    context.contentResolver.getType(uri).orEmpty(),
                    localFile.length()
                )
                repo.files.sendFile(target, request, localFile.absolutePath, text)
            } catch (e: Exception) {
                _events.emit(e.message.orEmpty())
            }
        }
    }


    fun downloadFile(context: Context, messageLocalId: String, fileId: String, fileName: String, fileSize: Long) {
        val dId = dialogId ?: return
        activeDownloads[fileId] = repo.files.download(
            dialogId = dId,
            messageLocalId = messageLocalId,
            fileId = fileId,
            fileName = fileName,
            filesDir = context.filesDir,
            fileSize = fileSize
        )
    }


    fun onButtonClick(messageId: String?, buttonId: String, action: ChatButtonAction) {
        viewModelScope.launch {
            when (action) {
                is ChatButtonAction.SendCallback -> try {
                    if (messageId != null) repo.messages.sendButtonClick(messageId, buttonId, action.data)
                } catch (e: Exception) {
                    _events.emit(e.message.orEmpty())
                }
                is ChatButtonAction.OpenUrl ->
                    _buttonActionEvents.emit(ButtonActionEvent.OpenUrl(action.url))
                is ChatButtonAction.RequestData ->
                    _buttonActionEvents.emit(ButtonActionEvent.RequestData(action.type))
            }
        }
    }


    /** Tap on my own reaction removes it, any other emoji sets (or replaces) it. */
    fun toggleReaction(message: MessageUI, emoji: String) {
        if (message.myReaction == emoji) {
            removeReaction(message)
            return
        }
        val messageId = message.messageId ?: return
        viewModelScope.launch {
            try {
                repo.reactions.setReaction(messageId, emoji)
            } catch (e: Exception) {
                _events.emit("Reaction: " + e.message.orEmpty())
            }
        }
    }


    fun removeReaction(message: MessageUI) {
        val messageId = message.messageId ?: return
        viewModelScope.launch {
            try {
                repo.reactions.removeReaction(messageId)
            } catch (e: Exception) {
                _events.emit("Reaction: " + e.message.orEmpty())
            }
        }
    }


    fun editMessage(message: MessageUI, newText: String) {
        val text = newText.trim()
        val oldText = (message.content as? MessageContent.Text)?.text
        val dialogId = dialogId ?: return
        val messageId = message.messageId ?: return
        if (text.isEmpty() || text == oldText) return

        viewModelScope.launch {
            try {
                repo.editing.editMessage(dialogId, messageId, text)
            } catch (e: Exception) {
                _events.emit("Message not edited: " + e.message.orEmpty())
            }
        }
    }


    fun deleteMessage(message: MessageUI) {
        val dialogId = dialogId ?: return
        val messageId = message.messageId ?: return
        viewModelScope.launch {
            try {
                val reason = repo.deletion.deleteMessage(dialogId, messageId)
                if (reason != null) _events.emit("Message not deleted: ${reason.text}")
            } catch (e: Exception) {
                _events.emit("Message not deleted: " + e.message.orEmpty())
            }
        }
    }


    // ─── History and search ──────────────────────────────────────────────────

    /** Called when the user scrolled to the top. Keeps the same message on screen after older ones were added above it. */
    fun loadOlder() {
        if (!hasOlder) return
        val firstId = _messages.value.firstOrNull()?.localId
        loadPage({ repo.messages.loadOlder(it) }) {
            firstId?.let { scrollTo(it, ScrollRequest.Anchor.TOP) }
        }
    }


    /** Called when the user scrolled to the bottom while the window is not at the newest message. */
    fun loadNewer() {
        if (_isAtLatest.value) return
        loadPage({ repo.messages.loadNewer(it) })
    }


    fun jumpToLatest() {
        loadPage({ repo.messages.loadLatest(it) }) {
            repo.messages.messages(target).value.lastOrNull()
                ?.let { scrollTo(it.localId, ScrollRequest.Anchor.BOTTOM) }
        }
    }


    /** Opens the chat at a message (search result). */
    fun jump(message: MessageUI) {
        val messageId = message.messageId ?: return
        loadPage({ repo.messages.jump(it, messageId) }) {
            scrollTo(message.localId, ScrollRequest.Anchor.CENTER)
        }
    }


    fun search(query: String) {
        val text = query.trim()
        val dialog = dialogId?.let { repo.dialogs.find(it) } ?: return
        if (text.isEmpty()) return
        viewModelScope.launch {
            try {
                val results = repo.search.search(dialog, text)
                if (results.isEmpty()) _events.emit("Nothing found") else _searchResults.emit(results)
            } catch (e: Exception) {
                _events.emit("Search: " + e.message.orEmpty())
            }
        }
    }


    /** Runs one history request at a time, then refreshes the window state. */
    private fun loadPage(load: suspend (Dialog) -> Unit, then: () -> Unit = {}) {
        val dialog = dialogId?.let { repo.dialogs.find(it) } ?: return
        if (isLoadingPage) return
        isLoadingPage = true

        viewModelScope.launch {
            try {
                load(dialog)
                updateWindow()
                then()
            } catch (e: Exception) {
                _events.emit("Load messages: " + e.message.orEmpty())
            } finally {
                isLoadingPage = false
            }
        }
    }


    private fun updateWindow() {
        val window = repo.messages.window(dialogId ?: return)
        _isAtLatest.value = window.isLatest
        hasOlder = window.hasOlder
    }


    private fun scrollTo(localId: String, anchor: ScrollRequest.Anchor) {
        _scrollRequests.tryEmit(ScrollRequest(localId, anchor))
    }


    override fun onCleared() {
        super.onCleared()
        activeDownloads.values.forEach { it.cancel() }
    }


    /** Messages of this chat with ✓ / ✓✓ status from the dialog's receipt horizon. */
    private fun observeMessages() {
        messagesJob?.cancel()
        messagesJob = viewModelScope.launch {
            combine(repo.messages.messages(target), receiptHorizon()) { list, horizon ->
                list.map { it.copy(deliveryStatus = horizon.status(it)) }
            }.collect {
                _messages.value = it
                // The window can change without our request (e.g. full resync)
                updateWindow()
            }
        }
        observeTyping()
        observeMembers()
    }


    /** Recalculated on every receipt: the dialog is refreshed with new participantStates. */
    private fun receiptHorizon(): Flow<ReceiptHorizon> {
        val dialogId = dialogId ?: return flowOf(ReceiptHorizon())
        return repo.dialogs.items
            .mapNotNull { list -> list.firstOrNull { it.id == dialogId } }
            .map { ReceiptHorizon.from(it.participantStates, repo.currentUserSub) }
            .onStart { emit(ReceiptHorizon()) }
            .distinctUntilChanged()
    }


    /** Typing works only for an existing dialog (`Dialog.sendTyping`). */
    private fun observeTyping() {
        val dialogId = dialogId ?: return
        typingJob?.cancel()
        typingJob = viewModelScope.launch {
            repo.typing.typing.collect { typing ->
                _typingText.value = typing[dialogId]?.let { "$it is typing…" }
            }
        }
    }


    /** SDK keeps `dialog.members` current; the dialog snapshot is refreshed on events. */
    private fun observeMembers() {
        val dialogId = dialogId ?: return
        membersJob?.cancel()
        membersJob = viewModelScope.launch {
            repo.dialogs.items
                .map { list -> list.firstOrNull { it.id == dialogId } }
                .distinctUntilChanged()
                .collect { dialog ->
                    _isMember.value = dialog != null
                    _members.value = dialog?.members.orEmpty()
                }
        }
    }


    /** The first message to a contact creates a dialog — switch the screen to it. */
    private fun observeDialogCreation() {
        val contact = contactId ?: return
        viewModelScope.launch {
            repo.dialogs.items.collect {
                if (dialogId != null) return@collect
                val dialog = repo.dialogs.findByContact(contact.sub) ?: return@collect

                repo.messages.clear(MessageTarget.Contact(contact))
                dialogId = dialog.id
                observeMessages()
                loadPage({ repo.messages.loadLatest(it) })
            }
        }
    }


    /** Why the server did not delete a message — human readable. */
    private val SkippedMessageReason.text: String
        get() = when (this) {
            SkippedMessageReason.NOT_FOUND -> "Message not found"
            SkippedMessageReason.NOT_AUTHOR -> "You are not the author"
            SkippedMessageReason.ALREADY_DELETED -> "Message is already deleted"
            SkippedMessageReason.CHAT_CLOSED -> "Chat is closed"
            SkippedMessageReason.NOT_ALLOWED -> "Not allowed"
            SkippedMessageReason.UNKNOWN -> "Unknown reason"
        }


    companion object {
        val QUICK_REACTIONS = listOf("👍", "❤️", "😂", "😮", "😢", "🙏")
    }


    private suspend fun copyToLocalFile(context: Context, uri: Uri): File = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }
            ?: "file"

        val dir = File(context.filesDir, "uploads").apply { mkdirs() }
        val file = File(dir, name)
        val input = resolver.openInputStream(uri) ?: throw IllegalStateException("Can't read $uri")
        input.use { inp -> file.outputStream().use { inp.copyTo(it) } }
        file
    }
}
