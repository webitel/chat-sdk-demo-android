package com.webitel.chat.sdk.demo_android.repo

import com.webitel.chat.sdk.ChatClient
import com.webitel.chat.sdk.MessageDeletion
import com.webitel.chat.sdk.MessageDeletionResult
import com.webitel.chat.sdk.SkippedMessageReason


/**
 * Deleting messages.
 *
 * Deletion is soft: deleted messages stay in the history (`Message.deletion`),
 * so the demo keeps them in the list and shows a "Message deleted" placeholder.
 */
class DeletionRepository(
    private val client: () -> ChatClient,
    private val messages: MessagesRepository
) {

    /** Returns the reason if the server did not delete the message, null on success. */
    suspend fun deleteMessage(dialogId: String, messageId: String): SkippedMessageReason? {
        // SDK: delete messages. Takes several ids at once — the demo deletes one.
        // Messages that were not deleted come in `skipped` with a reason.
        val result = awaitResult<MessageDeletionResult> { client().deleteMessages(listOf(messageId), it) }

        // The result has no `deletedBy` — mark the message now, the Deleted event adds who deleted it.
        result.deletedIds.forEach { handleDeleted(dialogId, it, null) }
        return result.skipped.firstOrNull()?.reason
    }


    /** Called for `MessageEvent.Deleted` (deleted by anyone) and after our own request. Safe to call twice. */
    fun handleDeleted(dialogId: String, messageId: String, deletion: MessageDeletion?) {
        messages.updateEach(dialogId) { message ->
            when {
                message.messageId == messageId -> message.deleted(deletion)
                // Replies to the deleted message keep the quote, marked as deleted
                message.reply?.messageId == messageId ->
                    message.copy(reply = message.reply.copy(isDeleted = true))
                else -> message
            }
        }
    }
}
