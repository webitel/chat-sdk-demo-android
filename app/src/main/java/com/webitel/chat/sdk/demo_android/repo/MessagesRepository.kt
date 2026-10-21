package com.webitel.chat.sdk.demo_android.repo

import com.webitel.chat.sdk.ChatClient
import com.webitel.chat.sdk.Dialog
import com.webitel.chat.sdk.HistoryCursor
import com.webitel.chat.sdk.HistoryRequest
import com.webitel.chat.sdk.Message
import com.webitel.chat.sdk.MessageAction
import com.webitel.chat.sdk.MessageContent
import com.webitel.chat.sdk.MessageOptions
import com.webitel.chat.sdk.MessageTarget
import com.webitel.chat.sdk.MoveDirection
import com.webitel.chat.sdk.SendContent
import com.webitel.chat.sdk.demo_android.ui.dialog.FileState
import com.webitel.chat.sdk.demo_android.ui.dialog.MessageUI
import com.webitel.chat.sdk.demo_android.ui.dialog.toReplyUI
import com.webitel.chat.sdk.demo_android.ui.dialog.toUI
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap


/**
 * Messages of each chat, kept in memory.
 *
 * A chat is a [MessageTarget]:
 * - `MessageTarget.Dialog` — an existing dialog;
 * - `MessageTarget.Contact` — a contact without a dialog yet. The SDK creates the dialog
 *   on the first message and sends `DialogEvent.Created`.
 *
 * Sending is optimistic: a local message with a random `sendId` is shown right away.
 * The server echo (`MessageEvent.Received`) carries the same `sendId` and replaces it.
 *
 * History is paged: each dialog keeps one [HistoryWindow] — see the History section.
 */
class MessagesRepository(private val client: () -> ChatClient) {

    private val chats = ConcurrentHashMap<String, MutableStateFlow<List<MessageUI>>>()
    private val windows = ConcurrentHashMap<String, HistoryWindow>()


    fun messages(target: MessageTarget): StateFlow<List<MessageUI>> = flow(target)


    fun clear(target: MessageTarget) {
        chats.remove(target.key)
        windows.remove(target.key)
    }


    fun clearAll() {
        chats.values.forEach { it.value = emptyList() }
        chats.clear()
        windows.clear()
    }


    /** [replyTo] makes the message a reply to it. */
    suspend fun sendText(target: MessageTarget, text: String, replyTo: MessageUI? = null): String {
        val sendId = newSendId()
        add(target, MessageUI.outgoing(sendId, MessageContent.Text(text), reply = replyTo?.toReplyUI()))
        return send(target, SendContent.Text(text), sendId, replyTo?.messageId)
    }


    /**
     * Sends [content] for the local message [sendId] that is already in the list.
     * On success the message gets its server id (✓), on failure — an error.
     */
    suspend fun send(
        target: MessageTarget,
        content: SendContent,
        sendId: String,
        replyToMessageId: String? = null
    ): String {
        try {
            // SDK: send a message. `sendId` links the server echo with the local message,
            // `replyToMessageId` makes the message a reply.
            val messageId = awaitResult<String> {
                client().sendMessage(target, MessageOptions(content, sendId, replyToMessageId), it)
            }
            update(target, sendId) { it.copy(messageId = messageId) }
            return messageId
        } catch (e: Exception) {
            markFailed(target, sendId, e)
            throw e
        }
    }


    /** A tap on an inline keyboard button with a callback. */
    suspend fun sendButtonClick(messageId: String, buttonId: String, data: String) {
        // SDK: send the button click to the bot
        awaitResult<Unit> {
            client().sendAction(messageId, MessageAction.ButtonClick(id = buttonId, data = data), it)
        }
    }


    fun handleReceived(dialogId: String, message: Message) = upsert(dialogId, message)


    /**
     * Replaces the message with the same id (or our local copy with the same sendId), or appends a new one.
     * Used for realtime messages and for messages from a sync.
     */
    fun upsert(dialogId: String, message: Message) {
        // Chat was never opened — its history will be loaded when it is.
        val flow = chats[MessageTarget.Dialog(dialogId).key] ?: return

        flow.update { list ->
            val index = list.indexOfFirst {
                it.messageId == message.id || (message.sendId != null && it.localId == message.sendId)
            }
            val lastSequence = list.lastOrNull { it.sequence != null }?.sequence ?: 0
            val isNew = message.sequence?.let { it > lastSequence } ?: true

            when {
                // Our own message came back: replace the local one with the full
                // server message (with attachment metadata).
                index != -1 -> list.toMutableList().apply { this[index] = message.toUI() }
                // Append only new messages, and only to a window that ends with the newest message.
                window(dialogId).isLatest && isNew -> list + message.toUI()
                else -> list
            }
        }
    }


    // ─── History ─────────────────────────────────────────────────────────────

    fun window(dialogId: String): HistoryWindow = windows[dialogId] ?: HistoryWindow()


    /** Dialogs whose history is cached (opened at least once). */
    fun openedDialogIds(): List<String> = chats.keys.filterNot { it.startsWith(CONTACT_PREFIX) }


    /** On open: loads the newest messages if the dialog has no cache yet. */
    suspend fun loadIfNeeded(dialog: Dialog) {
        if (flow(MessageTarget.Dialog(dialog.id)).value.isEmpty()) loadLatest(dialog)
    }


    /** Opens the dialog at the newest messages. Replaces the cache. */
    suspend fun loadLatest(dialog: Dialog) {
        // SDK: no cursor — the newest page of history
        val slice = awaitResult { dialog.getHistory(HistoryRequest(), it) }

        windows[dialog.id] = HistoryWindow(olderCursor = slice.olderCursor, newerCursor = null)
        flow(MessageTarget.Dialog(dialog.id)).update { list ->
            val sending = list.filter { it.messageId == null }  // keep messages still being sent
            slice.items.map { it.toUI() } + sending
        }
    }


    /**
     * Opens history around a message (e.g. a search result). Replaces the cache,
     * pagination then continues from this point in both directions.
     * Returns without a request if the message is already loaded.
     */
    suspend fun jump(dialog: Dialog, messageId: String) {
        val flow = flow(MessageTarget.Dialog(dialog.id))
        if (flow.value.any { it.messageId == messageId }) return

        // SDK: AROUND loads messages before and after the given one
        val cursor = HistoryCursor(messageId = messageId, direction = MoveDirection.AROUND)
        val slice = awaitResult { dialog.getHistory(HistoryRequest(cursor = cursor), it) }

        windows[dialog.id] = HistoryWindow(slice.olderCursor, slice.newerCursor)
        flow.value = slice.items.map { it.toUI() }
    }


    /** Next page of older messages — when the user scrolls to the top. */
    suspend fun loadOlder(dialog: Dialog) {
        val cursor = window(dialog.id).olderCursor ?: return

        // SDK: older page from the cursor
        val slice = awaitResult { dialog.getHistory(HistoryRequest(cursor = cursor), it) }

        windows[dialog.id] = window(dialog.id).copy(olderCursor = slice.olderCursor)
        flow(MessageTarget.Dialog(dialog.id)).update { list -> newMessages(slice.items, list) + list }
    }


    /**
     * Next page of newer messages — when the window is not at the newest message
     * (after a jump, or after a long offline period).
     */
    suspend fun loadNewer(dialog: Dialog) {
        val cursor = window(dialog.id).newerCursor ?: return

        // SDK: newer page from the cursor
        val slice = awaitResult { dialog.getHistory(HistoryRequest(cursor = cursor), it) }

        // An empty page means the window reached the newest message
        val newerCursor = if (slice.items.isEmpty()) null else slice.newerCursor
        windows[dialog.id] = window(dialog.id).copy(newerCursor = newerCursor)
        flow(MessageTarget.Dialog(dialog.id)).update { list -> list + newMessages(slice.items, list) }
    }


    private fun newMessages(items: List<Message>, loaded: List<MessageUI>): List<MessageUI> {
        val knownIds = loaded.mapNotNull { it.messageId }.toSet()
        return items.filter { it.id !in knownIds }.map { it.toUI() }
    }


    // ─── Local list updates (also used by FilesRepository) ──────────────────

    fun newSendId(): String = UUID.randomUUID().toString()


    fun add(target: MessageTarget, message: MessageUI) {
        flow(target).update { it + message }
    }


    fun setFileState(target: MessageTarget, localId: String, fileId: String, state: FileState) {
        update(target, localId) { it.copy(fileStates = it.fileStates + (fileId to state)) }
    }


    fun markFailed(target: MessageTarget, localId: String, error: Throwable) {
        update(target, localId) { it.copy(error = error.message ?: "Failed") }
    }


    /** Updates a server message in an opened chat (used by realtime events). */
    fun updateByMessageId(dialogId: String, messageId: String, transform: (MessageUI) -> MessageUI) {
        updateEach(dialogId) { if (it.messageId == messageId) transform(it) else it }
    }


    /** Applies [transform] to every message of an opened chat. */
    fun updateEach(dialogId: String, transform: (MessageUI) -> MessageUI) {
        val flow = chats[MessageTarget.Dialog(dialogId).key] ?: return
        flow.update { list -> list.map(transform) }
    }


    private fun update(target: MessageTarget, localId: String, transform: (MessageUI) -> MessageUI) {
        flow(target).update { list ->
            list.map { if (it.localId == localId) transform(it) else it }
        }
    }


    private fun flow(target: MessageTarget) =
        chats.getOrPut(target.key) { MutableStateFlow(emptyList()) }


    private val MessageTarget.key: String
        get() = when (this) {
            is MessageTarget.Dialog -> id
            is MessageTarget.Contact -> CONTACT_PREFIX + contactId.sub
        }


    private companion object {
        const val CONTACT_PREFIX = "contact:"
    }
}


/**
 * Converts an SDK [Message] to [MessageUI].
 * Attachments that were downloaded before are marked as [FileState.Downloaded].
 */
fun Message.toUI(): MessageUI {
    val attachments = when (val c = content) {
        is MessageContent.Attachments -> c.attachments
        is MessageContent.Composite -> c.attachments
        else -> emptyList()
    }

    val fileStates = attachments.associate { attachment ->
        val cachedPath = FileCache.shared.get(attachment.fileId)
        attachment.fileId to if (cachedPath != null && File(cachedPath).exists()) {
            FileState.Downloaded(cachedPath)
        } else {
            FileState.Idle
        }
    }

    return MessageUI(
        localId = sendId ?: id,
        content = content,
        createdAt = createdAt,
        senderName = from.contact.name,
        isOutgoing = isOutgoing,
        messageId = id,
        fileStates = fileStates,
        reactions = reactions,
        editedAt = editedAt,
        isDeleted = isDeleted,
        deletion = deletion,
        reply = reply?.toUI(),
        sequence = sequence,
        deliveryFailures = deliveryExceptions
    )
}
