package com.webitel.chat.sdk.demo_android.ui.dialog

import com.webitel.chat.sdk.MessageContent
import com.webitel.chat.sdk.MessageReply
import com.webitel.chat.sdk.MessageReplyContent


/** Quote of the original message shown inside a reply. */
data class ReplyUI(
    val messageId: String,
    val authorName: String,
    val text: String,
    /** The original message was deleted. */
    val isDeleted: Boolean
)


/** SDK: `Message.reply` is a short summary of the quoted message. */
fun MessageReply.toUI() = ReplyUI(
    messageId = messageId,
    authorName = from.contact.name,
    text = content.previewText,
    isDeleted = isDeleted
)


/** For our own reply, so the quote is shown before the server echoes the message back. */
fun MessageUI.toReplyUI() = ReplyUI(
    messageId = messageId.orEmpty(),
    authorName = senderName,
    text = when (val c = content) {
        is MessageContent.Text -> c.text
        is MessageContent.Composite -> c.text?.takeIf { it.isNotBlank() } ?: "Attachment"
        is MessageContent.Attachments -> "Attachment"
        else -> ""
    },
    isDeleted = false
)


val MessageReplyContent.previewText: String
    get() = when (this) {
        is MessageReplyContent.Text -> text.orEmpty()
        is MessageReplyContent.Image -> caption?.let { "📷 $it" } ?: "📷 Photo"
        is MessageReplyContent.Document -> "📎 ${caption ?: name ?: "File"}"
        is MessageReplyContent.Location -> "Location: ${name ?: address.orEmpty()}"
        is MessageReplyContent.Contact -> "Contact: ${displayValue.orEmpty()}"
        is MessageReplyContent.System -> text
        is MessageReplyContent.Interactive -> text.orEmpty()
        is MessageReplyContent.Unsupported -> text ?: "Message"
    }
