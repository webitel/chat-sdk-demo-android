package com.webitel.chat.sdk.demo_android.repo

import com.webitel.chat.sdk.ChatClient
import com.webitel.chat.sdk.MessageReaction
import com.webitel.chat.sdk.ReactionResult
import com.webitel.chat.sdk.removeReaction


/**
 * Message reactions. One user has at most one reaction per message —
 * a new emoji replaces the previous one.
 *
 * UI is updated only from `MessageEvent.ReactionsChanged`: it comes for my own
 * reactions too and carries the full list, so there is no local state to keep in sync.
 */
class ReactionsRepository(
    private val client: () -> ChatClient,
    private val messages: MessagesRepository
) {

    suspend fun setReaction(messageId: String, emoji: String) {
        // SDK: set (or replace) my reaction
        awaitResult<ReactionResult> { client().setReaction(messageId, emoji, null, it) }
    }


    suspend fun removeReaction(messageId: String) {
        // SDK: remove my reaction (same as setReaction with an empty emoji)
        awaitResult<ReactionResult> { client().removeReaction(messageId, it) }
    }


    /** `reactions` is the full new list for the message. */
    fun handleReactionsChanged(dialogId: String, messageId: String, reactions: List<MessageReaction>) {
        messages.updateByMessageId(dialogId, messageId) { it.copy(reactions = reactions) }
    }
}
