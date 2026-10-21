package com.webitel.chat.sdk.demo_android.repo

import android.util.Log
import com.webitel.chat.sdk.Dialog
import com.webitel.chat.sdk.demo_android.ui.dialog.MessageUI
import java.util.concurrent.ConcurrentHashMap


/**
 * Read receipts.
 *
 * `markAsRead` marks everything up to and including the given message,
 * so it is enough to send it once for the newest visible incoming message.
 * The SDK does not skip duplicates, so the demo sends only when the message is newer than
 * - my read horizon from `Dialog.participantStates` (already confirmed by the server), and
 * - the last sequence we already sent (the request may still be in flight).
 */
class ReadRepository(
    private val dialogs: DialogsRepository,
    private val currentUserSub: () -> String?
) {

    /** dialogId → last sequence sent to markAsRead. */
    private val lastSentSequence = ConcurrentHashMap<String, Long>()
    /** dialogId → last messageId sent (fallback when there is no sequence). */
    private val lastSentMessageId = ConcurrentHashMap<String, String>()


    fun markAsRead(dialogId: String, upTo: MessageUI) {
        if (upTo.isOutgoing) return
        val messageId = upTo.messageId ?: return
        val dialog = dialogs.find(dialogId) ?: return

        // `sequence` is optional — fall back to `messageId`
        val sequence = upTo.sequence
        if (sequence == null) {
            if (dialog.unreadCount == 0 || lastSentMessageId[dialogId] == messageId) return
            lastSentMessageId[dialogId] = messageId

            // SDK: mark as read by message id
            dialog.markAsRead(messageId) { result ->
                result.onFailure {
                    Log.e(TAG, "Failed to mark as read", it)
                    lastSentMessageId.remove(dialogId)
                }
            }
            return
        }

        val alreadyRead = maxOf(myReadSequence(dialog), lastSentSequence[dialogId] ?: 0)
        if (sequence <= alreadyRead) return
        lastSentSequence[dialogId] = sequence
        Log.d(TAG, "markAsRead: dialog=$dialogId sequence=$sequence")

        // SDK: mark as read by sequence
        dialog.markAsRead(sequence) { result ->
            result.onFailure {
                Log.e(TAG, "Failed to mark as read", it)
                // Allow a retry next time the message appears
                lastSentSequence.remove(dialogId)
            }
        }
    }


    fun clearAll() {
        lastSentSequence.clear()
        lastSentMessageId.clear()
    }


    /** SDK: how far the current user has read, from the dialog's participant states. */
    private fun myReadSequence(dialog: Dialog): Long =
        dialog.participantStates
            .firstOrNull { it.member.contact.id.sub == currentUserSub() }
            ?.readUpToSequence ?: 0


    companion object {
        private const val TAG = "ReadRepository"
    }
}
