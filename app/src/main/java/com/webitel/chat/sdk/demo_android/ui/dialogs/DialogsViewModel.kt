package com.webitel.chat.sdk.demo_android.ui.dialogs

import androidx.lifecycle.ViewModel
import com.webitel.chat.sdk.demo_android.repo.ChatRepository
import kotlinx.coroutines.flow.combine


/**
 * Dialogs list. Nothing to load here: [com.webitel.chat.sdk.demo_android.repo.SyncRepository]
 * loads dialogs after the first connect, and SDK events keep them current.
 */
class DialogsViewModel : ViewModel() {
    /** Dialogs with "X is typing…" shown instead of the last message. */
    val dialogs = combine(
        ChatRepository.shared.dialogs.items,
        ChatRepository.shared.typing.typing
    ) { list, typing ->
        list.map { it.copy(typingName = typing[it.id]) }
    }
}
