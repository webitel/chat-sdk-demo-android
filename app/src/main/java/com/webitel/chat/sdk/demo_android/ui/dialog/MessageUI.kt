package com.webitel.chat.sdk.demo_android.ui.dialog

import com.webitel.chat.sdk.DeliveryException
import com.webitel.chat.sdk.Message
import com.webitel.chat.sdk.MessageContent
import com.webitel.chat.sdk.MessageDeletion
import com.webitel.chat.sdk.MessageReaction


/**
 * Represents the download/availability state of a single file attachment.
 *
 * Each [MessageUI] carries a map of fileId → FileState so the adapter
 * can independently render each attachment in a message.
 */
sealed class FileState {
    /** File exists on the server but has not been downloaded yet. */
    object Idle : FileState()

    /** Download is in progress. [progress] is 0–100, or null if total size is unknown. */
    data class Downloading(val progress: Int?) : FileState()

    /** Upload is in progress. [progress] is 0–100, or null if total size is unknown. */
    data class Uploading(val progress: Int?) : FileState()

    /** File was downloaded/uploaded and is available at [localPath]. */
    data class Downloaded(val localPath: String) : FileState()

    /** A terminal error occurred. [message] describes what went wrong. */
    data class Error(val message: String) : FileState()
}

/**
 * Carries local file info for an outgoing attachment before the server echo arrives.
 * Once the echo replaces the placeholder the [MessageUI.pendingAttachment] is null.
 */
data class PendingAttachment(
    val tempId: String,
    val fileName: String,
    val mimeType: String,
    val localPath: String
)

data class MessageUI(
    val localId: String,
    val content: MessageContent,
    val createdAt: Long,
    val senderName: String,
    val isOutgoing: Boolean,
    val messageId: String? = null,
    val error: String? = null,
    /** fileId → file state; one entry per attachment in this message. */
    val fileStates: Map<String, FileState> = emptyMap(),
    /** Non-null only for outgoing messages while the server echo has not yet arrived. */
    val pendingAttachment: PendingAttachment? = null,
    /** SDK: reactions on the message, one entry per emoji (emoji, count, reactedByMe). */
    val reactions: List<MessageReaction> = emptyList(),
    /** SDK: last edit time, null if never edited. */
    val editedAt: Long? = null,
    /** SDK: deleted messages stay in the history, marked as deleted. */
    val isDeleted: Boolean = false,
    /**
     * SDK: who deleted the message and when. Null if not deleted, or not known yet
     * (after our own delete request only the id is known — the Deleted event brings the rest).
     */
    val deletion: MessageDeletion? = null,
    /** SDK: quote of the message this one replies to. */
    val reply: ReplyUI? = null,
    /** SDK: position of the message in the dialog, used for read receipts. */
    val sequence: Long? = null,
    /** ✓ / ✓✓ of my message. Not from the message itself — calculated from the dialog's [ReceiptHorizon]. */
    val deliveryStatus: DeliveryStatus? = null,
    /** SDK: members the message was not delivered to, with the error. */
    val deliveryFailures: List<DeliveryException> = emptyList()
) {
    val isSent: Boolean get() = messageId != null

    /** Emoji the current user reacted with. One user has at most one reaction per message. */
    val myReaction: String? get() = reactions.firstOrNull { it.reactedByMe }?.emoji

    /** Same rule as `Message.isEdited` in the SDK: a deleted message is never "edited". */
    val isEdited: Boolean get() = !isDeleted && editedAt != null && editedAt > createdAt

    /** Only the author can edit; the demo edits only text messages. */
    val canEdit: Boolean
        get() = isOutgoing && isSent && !isDeleted && pendingAttachment == null &&
                content is MessageContent.Text

    /** Keeps a known [deletion] if the new one is null. */
    fun deleted(deletion: MessageDeletion?) =
        copy(isDeleted = true, deletion = deletion ?: this.deletion)

    companion object {
        /** Local message shown right away, before the server confirms it. */
        fun outgoing(
            localId: String,
            content: MessageContent,
            pendingAttachment: PendingAttachment? = null,
            fileStates: Map<String, FileState> = emptyMap(),
            reply: ReplyUI? = null
        ) = MessageUI(
            localId = localId,
            content = content,
            createdAt = System.currentTimeMillis(),
            senderName = "Me",
            isOutgoing = true,
            fileStates = fileStates,
            pendingAttachment = pendingAttachment,
            reply = reply
        )
    }
}


val Message.previewText: String?
    get() = if (isDeleted) "Message deleted" else when (val content = this.content) {
        is MessageContent.Text -> content.text
        is MessageContent.Attachments -> "Attachment"
        is MessageContent.KeyboardOnly -> "Buttons"
        is MessageContent.Composite -> when {
            !content.text.isNullOrBlank() -> content.text
            content.attachments.isNotEmpty() -> "Attachment"
            content.keyboard != null -> "Buttons"
            else -> ""
        }
        is MessageContent.Contact -> "Contact: ${content.name}"
        is MessageContent.Location -> "Location: ${content.name}"
        is MessageContent.System -> "System: ${content.text}"
    }
