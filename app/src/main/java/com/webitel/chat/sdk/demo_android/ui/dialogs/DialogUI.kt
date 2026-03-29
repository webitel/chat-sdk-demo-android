package com.webitel.chat.sdk.demo_android.ui.dialogs

import com.webitel.chat.sdk.Dialog

data class DialogUI(
    val dialog: Dialog,
    val lastMessageAt: Long,
    val lastMessageText: String
) {
    val id: String get() = dialog.id
    val subject: String get() = dialog.subject
}