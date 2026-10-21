package com.webitel.chat.sdk.demo_android.repo

import com.webitel.chat.sdk.DeliveryException


/**
 * A message may fail to reach some members (e.g. an external channel rejected it).
 *
 * Failures are part of the message (`Message.deliveryExceptions`) and come with the history.
 * In realtime they arrive one at a time as `ReceiptEvent.DeliveryFailed` — handled here.
 */
class DeliveryFailuresRepository(private val messages: MessagesRepository) {

    /** Adds the failure to the message, replacing a previous one for the same member. */
    fun handleDeliveryFailed(dialogId: String, exception: DeliveryException) {
        messages.updateByMessageId(dialogId, exception.messageId) { message ->
            val others = message.deliveryFailures.filterNot { it.member.id == exception.member.id }
            message.copy(deliveryFailures = others + exception)
        }
    }
}
