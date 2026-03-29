package com.webitel.chat.sdk.demo_android.ui.dialog

import com.webitel.chat.sdk.Message


data class MessageUI(
    val localId: String,
    var error: String? = null,
    var progress: String? = null,
    val message: Message? = null,
    val body: String = ""
) {
    private val _createdAt = System.currentTimeMillis()

    val text: String
        get() { return message?.text ?: body }

    val senderName: String
        get() { return message?.from?.name ?: "Me" }

    val senderId: String
        get() { return message?.from?.id?.sub ?: "Me" }


    val isSent: Boolean
        get() { return message != null }

    val createdAt: Long
        get() {
            return message?.createdAt ?: _createdAt
        }

    val isOutgoing: Boolean
        get() {
            return message?.isOutgoing ?: true
        }
}
