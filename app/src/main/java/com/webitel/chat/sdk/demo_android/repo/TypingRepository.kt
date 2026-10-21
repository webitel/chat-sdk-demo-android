package com.webitel.chat.sdk.demo_android.repo

import android.util.Log
import com.webitel.chat.sdk.Participant
import com.webitel.chat.sdk.TypingRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap


/**
 * Typing indicator.
 *
 * Typing is sent at most once per [TYPING_INTERVAL_MS] while the user types.
 * The same value goes to `timeoutMs`: the other side expects the next typing event
 * within this time, otherwise it hides the indicator.
 */
class TypingRepository(
    private val dialogs: DialogsRepository,
    private val currentUserSub: () -> String?
) {

    private val _typing = MutableStateFlow<Map<String, String>>(emptyMap())
    /** dialogId → name of the participant who is typing. */
    val typing: StateFlow<Map<String, String>> = _typing

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lastSentAt = ConcurrentHashMap<String, Long>()
    private val hideJobs = ConcurrentHashMap<String, Job>()


    /** Call on every input change. Sends typing at most once per [TYPING_INTERVAL_MS]. */
    fun sendTyping(dialogId: String) {
        val now = System.currentTimeMillis()
        val last = lastSentAt[dialogId]
        if (last != null && now - last < TYPING_INTERVAL_MS) return

        val dialog = dialogs.find(dialogId) ?: return
        lastSentAt[dialogId] = now

        // SDK: send a typing indicator.
        // `previewText` can also be passed to show a preview of the draft — the demo does not share drafts.
        dialog.sendTyping(TypingRequest(timeoutMs = TYPING_INTERVAL_MS)) { result ->
            result.onFailure { Log.e(TAG, "Failed to send typing", it) }
        }
    }


    /** Called for `ActivityEvent.Typing`. */
    fun handleTyping(dialogId: String, member: Participant, timeoutMs: Long?) {
        // SDK sends our own typing back too — skip it
        if (member.contact.id.sub == currentUserSub()) return

        _typing.update { it + (dialogId to member.contact.name) }

        // Hide when the timeout passes without a new typing event
        hideJobs.remove(dialogId)?.cancel()
        hideJobs[dialogId] = scope.launch {
            delay(timeoutMs ?: TYPING_INTERVAL_MS)
            clear(dialogId)
        }
    }


    /** Hides the indicator, e.g. when a message from that participant arrives. */
    fun clear(dialogId: String) {
        hideJobs.remove(dialogId)?.cancel()
        _typing.update { it - dialogId }
    }


    fun clearAll() {
        hideJobs.values.forEach { it.cancel() }
        hideJobs.clear()
        lastSentAt.clear()
        _typing.value = emptyMap()
    }


    companion object {
        const val TYPING_INTERVAL_MS = 5_000L
        private const val TAG = "TypingRepository"
    }
}
