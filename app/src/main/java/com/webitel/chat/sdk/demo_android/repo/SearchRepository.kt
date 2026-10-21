package com.webitel.chat.sdk.demo_android.repo

import com.webitel.chat.sdk.Dialog
import com.webitel.chat.sdk.MessageSearchRequest
import com.webitel.chat.sdk.MessageSearchSlice
import com.webitel.chat.sdk.demo_android.ui.dialog.MessageUI


/**
 * Full-text search within one dialog.
 * Tapping a result opens history around it, see [MessagesRepository.jump].
 */
class SearchRepository {

    /** Returns the newest matches first. */
    suspend fun search(dialog: Dialog, query: String): List<MessageUI> {
        // SDK: search messages in the dialog.
        // The demo shows the first page; `slice.olderCursor` passed as `cursor` loads more.
        val slice = awaitResult<MessageSearchSlice> {
            dialog.searchMessages(MessageSearchRequest(query = query, limit = 50), it)
        }
        return slice.items.reversed().map { it.toUI() }
    }
}
