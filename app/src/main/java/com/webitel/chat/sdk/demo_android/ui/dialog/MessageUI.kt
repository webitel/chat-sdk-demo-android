package com.webitel.chat.sdk.demo_android.ui.dialog

import com.webitel.chat.sdk.Message
import com.webitel.chat.sdk.MessageContent


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
    val pendingAttachment: PendingAttachment? = null
) {
    val isSent: Boolean get() = messageId != null
}


val Message.previewText: String?
    get() = when (val content = this.content) {
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
