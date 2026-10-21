package com.webitel.chat.sdk.demo_android.repo

import com.webitel.chat.sdk.ChatClient
import com.webitel.chat.sdk.Dialog
import com.webitel.chat.sdk.DialogFilter
import com.webitel.chat.sdk.DialogRequest
import com.webitel.chat.sdk.DialogType
import com.webitel.chat.sdk.demo_android.ui.dialogs.DialogUI
import com.webitel.chat.sdk.demo_android.ui.dialogs.toUI
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap


/**
 * Dialogs list, sorted by the last message.
 *
 * SDK: `client.getDialogs(...)` loads dialogs, `DialogEvent.Created` adds a new one
 * (e.g. after the first message to a contact). Loaded by [SyncRepository].
 */
class DialogsRepository(
    private val client: () -> ChatClient,
    private val currentUserSub: () -> String?
) {

    private val _items = MutableStateFlow<List<DialogUI>>(emptyList())
    val items: StateFlow<List<DialogUI>> = _items

    /** contact sub → DIRECT dialog with this contact. */
    private val dialogsByContact = ConcurrentHashMap<String, Dialog>()


    fun find(dialogId: String): Dialog? =
        _items.value.firstOrNull { it.id == dialogId }?.dialog


    fun findByContact(contactSub: String): Dialog? =
        dialogsByContact[contactSub]


    /** Replaces the list (cold start, full resync). */
    suspend fun loadDialogs(request: DialogRequest) {
        // SDK: load a page of dialogs
        val page = awaitResult { client().getDialogs(request, it) }

        // Replace in one step: an open chat must not see an empty list in between
        dialogsByContact.clear()
        page.items.forEach(::rememberContact)
        _items.value = page.items.map { it.toUI() }.sortedByDescending { it.lastMessageAt }
    }


    /** A dialog the app does not have yet, e.g. from a sync. */
    suspend fun fetchDialog(dialogId: String) {
        // SDK: the dialog with this id
        val request = DialogRequest(filter = DialogFilter(ids = listOf(dialogId)))
        val page = awaitResult { client().getDialogs(request, it) }
        page.items.firstOrNull()?.let(::upsert)
    }


    /** The current user has left the dialog. */
    fun remove(dialogId: String) {
        dialogsByContact.values.removeAll { it.id == dialogId }
        _items.update { list -> list.filterNot { it.id == dialogId } }
    }


    fun handleCreated(dialog: Dialog) = upsert(dialog)


    /**
     * After any event for the dialog: SDK already updated its `lastMessage`, `unreadCount`,
     * `participantStates` and `members` — just refresh the snapshot and re-sort.
     */
    fun refresh(dialogId: String) {
        find(dialogId)?.let(::upsert)
    }


    fun clear() {
        dialogsByContact.clear()
        _items.value = emptyList()
    }


    private fun upsert(dialog: Dialog) {
        rememberContact(dialog)
        _items.update { list ->
            (list.filterNot { it.id == dialog.id } + dialog.toUI())
                .sortedByDescending { it.lastMessageAt }
        }
    }


    private fun rememberContact(dialog: Dialog) {
        if (dialog.type != DialogType.DIRECT) return
        dialog.members
            .firstOrNull { it.contact.id.sub != currentUserSub() }
            ?.let { dialogsByContact[it.contact.id.sub] = dialog }
    }
}
