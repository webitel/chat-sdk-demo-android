package com.webitel.chat.sdk.demo_android.ui.dialogs

import com.webitel.chat.sdk.Dialog
import com.webitel.chat.sdk.Participant
import com.webitel.chat.sdk.ParticipantState
import com.webitel.chat.sdk.demo_android.ui.dialog.previewText

/**
 * Snapshot of an SDK [Dialog] for the list.
 *
 * The SDK updates `dialog.lastMessage` in place, so we copy the fields
 * the list shows — otherwise DiffUtil would not notice the change.
 */
data class DialogUI(
    val dialog: Dialog,
    val lastMessageAt: Long,
    val lastMessageText: String,
    /** SDK: number of unread messages. */
    val unreadCount: Int = 0,
    /** SDK: delivered / read horizons of each participant, used for ✓ / ✓✓. */
    val participantStates: List<ParticipantState> = emptyList(),
    /** SDK: dialog members, kept current by the SDK. */
    val members: List<Participant> = emptyList(),
    /** Name of who is typing in this dialog, null if nobody. */
    val typingName: String? = null
) {
    val id: String get() = dialog.id
    val subject: String get() = dialog.subject
}


fun Dialog.toUI() = DialogUI(
    dialog = this,
    lastMessageAt = lastMessage?.createdAt ?: 0,
    lastMessageText = lastMessage?.previewText.orEmpty(),
    unreadCount = unreadCount,
    participantStates = participantStates,
    members = members
)
