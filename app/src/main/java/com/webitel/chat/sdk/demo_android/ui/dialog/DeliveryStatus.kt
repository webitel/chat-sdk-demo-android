package com.webitel.chat.sdk.demo_android.ui.dialog

import com.webitel.chat.sdk.ParticipantState


/** Status of my own message: ✓ delivered, ✓✓ read. */
enum class DeliveryStatus { SENT, DELIVERED, READ }


/**
 * Receipts are horizons, not per-message flags: `readUpTo = 108` means
 * every message with `sequence <= 108` is read. One horizon per dialog gives the status of every message.
 */
data class ReceiptHorizon(
    val deliveredUpTo: Long = 0,
    val readUpTo: Long = 0
) {

    /** Null for incoming messages and for messages not confirmed by the server yet (no `sequence`). */
    fun status(message: MessageUI): DeliveryStatus? {
        if (!message.isOutgoing) return null
        val sequence = message.sequence ?: return null
        return when {
            sequence <= readUpTo -> DeliveryStatus.READ
            sequence <= deliveredUpTo -> DeliveryStatus.DELIVERED
            else -> DeliveryStatus.SENT
        }
    }


    companion object {
        /**
         * SDK: `Dialog.participantStates` holds the horizons of each participant and only moves forward.
         * Uses the other participants: a message counts as read when at least one of them read it.
         */
        fun from(states: List<ParticipantState>, currentUserSub: String?): ReceiptHorizon {
            val others = states.filter { it.member.contact.id.sub != currentUserSub }
            return ReceiptHorizon(
                deliveredUpTo = others.maxOfOrNull { it.deliveredUpToSequence } ?: 0,
                readUpTo = others.maxOfOrNull { it.readUpToSequence } ?: 0
            )
        }
    }
}
