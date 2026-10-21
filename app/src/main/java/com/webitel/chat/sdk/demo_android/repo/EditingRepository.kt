package com.webitel.chat.sdk.demo_android.repo

import com.webitel.chat.sdk.ChatClient
import com.webitel.chat.sdk.EditMessageResult
import com.webitel.chat.sdk.Message
import com.webitel.chat.sdk.MessageContent


/**
 * Editing messages. Only the author can edit; the demo edits only text messages.
 *
 * `MessageEvent.Edited` comes for any edit (mine or by another participant)
 * and carries the full updated message.
 */
class EditingRepository(
    private val client: () -> ChatClient,
    private val messages: MessagesRepository
) {

    suspend fun editMessage(dialogId: String, messageId: String, text: String) {
        // SDK: edit message text
        val result = awaitResult<EditMessageResult> { client().editMessage(messageId, text, it) }

        // Show the change right away; the Edited event will bring the same data.
        messages.updateByMessageId(dialogId, messageId) {
            it.copy(content = MessageContent.Text(text), editedAt = result.editedAt)
        }
    }


    /** Replaces our copy with the updated message, keeping the list item id. */
    fun handleEdited(dialogId: String, message: Message) {
        messages.updateByMessageId(dialogId, message.id) { message.toUI().copy(localId = it.localId) }
    }
}
