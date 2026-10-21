package com.webitel.chat.sdk.demo_android.repo

import android.util.Log
import com.webitel.chat.sdk.DialogRequest
import com.webitel.chat.sdk.DialogSyncChanges
import com.webitel.chat.sdk.MemberChangeAction
import com.webitel.chat.sdk.MessageTarget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch


/**
 * Synchronization is done by the SDK. The app only:
 * - loads dialogs once, after the first connect (cold start);
 * - applies `DialogEvent.Synchronized` — changes missed while offline;
 * - reloads everything on `onResyncRequired`.
 *
 * After a reconnect the app does not reload dialogs or history itself.
 */
class SyncRepository(
    private val dialogs: DialogsRepository,
    private val messages: MessagesRepository,
    private val deletion: DeletionRepository,
    private val typing: TypingRepository
) {

    /** SDK events come on its own thread — requests are started here so it is never blocked. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var didInitialLoad = false


    /**
     * SDK: the sync position starts with the first connection, so the initial load
     * is done after `Connected` — changes made after it are recovered by the SDK.
     */
    fun loadInitialStateIfNeeded() {
        if (didInitialLoad) return
        didInitialLoad = true
        scope.launch {
            try {
                dialogs.loadDialogs(DIALOGS_REQUEST)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load dialogs", e)
                didInitialLoad = false  // retry on the next connect
            }
        }
    }


    /**
     * Called for `DialogEvent.Synchronized` after a reconnect, one event per changed dialog.
     * SDK already updated the dialog's `lastMessage`, `unreadCount`, `participantStates` and `members`.
     */
    fun handleSynchronized(dialogId: String, changes: DialogSyncChanges) {
        // SDK: the current user has left the dialog
        if (changes.hasLeft) {
            removeDialog(dialogId)
            return
        }

        // A dialog the app does not know yet — fetch it first
        if (dialogs.find(dialogId) == null) {
            scope.launch {
                try {
                    dialogs.fetchDialog(dialogId)
                    apply(dialogId, changes)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to load dialog $dialogId", e)
                }
            }
            return
        }

        apply(dialogId, changes)
    }


    /**
     * Too many changes were missed — incremental sync is not possible.
     * Reload the dialog list and every opened history.
     */
    fun onResyncRequired() {
        Log.d(TAG, "Resync required — reloading dialogs and histories")
        typing.clearAll()

        scope.launch {
            try {
                dialogs.loadDialogs(DIALOGS_REQUEST)
                for (dialogId in messages.openedDialogIds()) {
                    val dialog = dialogs.find(dialogId)
                    if (dialog == null) {
                        // The user is no longer in this dialog
                        messages.clear(MessageTarget.Dialog(dialogId))
                    } else {
                        messages.loadLatest(dialog)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Resync failed", e)
            }
        }
    }


    /** After `endSession`: the next connect is a cold start again. */
    fun reset() {
        didInitialLoad = false
    }


    private fun apply(dialogId: String, changes: DialogSyncChanges) {
        // New and changed messages come whole (edits, reactions, delivery failures) — upsert by id
        changes.messages.forEach { messages.upsert(dialogId, it) }
        // Deleted messages stay in the list, marked with who deleted them and when
        changes.deletedMessages.forEach { (messageId, info) -> deletion.handleDeleted(dialogId, messageId, info) }

        // `dialog.members` is already updated, the change itself is only logged
        changes.memberChanges.forEach { change ->
            val action = when (val a = change.action) {
                MemberChangeAction.Added -> "added"
                MemberChangeAction.Removed -> "removed"
                is MemberChangeAction.Unknown -> a.raw
            }
            val by = change.by?.let { " by ${it.contact.name}" }.orEmpty()
            Log.d(TAG, "Dialog $dialogId: ${change.member.contact.name} $action$by")
        }

        // Unread badge, ✓ / ✓✓ and members come from the dialog — refresh its snapshot
        dialogs.refresh(dialogId)
    }


    private fun removeDialog(dialogId: String) {
        dialogs.remove(dialogId)
        messages.clear(MessageTarget.Dialog(dialogId))
        typing.clear(dialogId)
    }


    companion object {
        private const val TAG = "SyncRepository"
        private val DIALOGS_REQUEST = DialogRequest(size = 50)
    }
}
