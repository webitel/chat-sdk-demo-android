package com.webitel.chat.sdk.demo_android.ui.dialog

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.webitel.chat.sdk.ConnectionState
import com.webitel.chat.sdk.ContactId
import com.webitel.chat.sdk.DialogType
import com.webitel.chat.sdk.MessageTarget
import com.webitel.chat.sdk.demo_android.repo.ChatRepository
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch


class DialogViewModel : ViewModel() {
    private val _events = MutableSharedFlow<String>()
    val events: SharedFlow<String> = _events

    private val _reload = MutableSharedFlow<Unit>()
    val reload = _reload.asSharedFlow()

    private val _messages = MutableStateFlow<List<MessageUI>>(emptyList())
    val messages: StateFlow<List<MessageUI>> = _messages

    private var dialogId: String? = null
    private var contactId: ContactId? = null

    val connectionState = ChatRepository.shared.connectionState

    init {
        viewModelScope.launch {
            connectionState
                .map { it is ConnectionState.Connected }
                .distinctUntilChanged()
                .drop(1)
                .filter { it }
                .collect {
                    _reload.emit(Unit)
                }
        }
    }


    fun initiate(dialogId: String?, contactId: ContactId?) {
        val dId = dialogId
            ?: ChatRepository.shared
                .findDialogByContact(contactId!!.sub)?.id
        this.contactId = contactId
        this.dialogId = dId

        if (dId == null) {
            observeDialogCreationForContact(contactId!!.sub)
            observeTemporaryMessages(contactId.sub)
        } else {
            observeDialog(dId, emptyList())
        }
    }


    fun sendMessage(text: String) {
        viewModelScope.launch {
            try {
                val target = dialogId?.let {
                    MessageTarget.Dialog(it)
                } ?: MessageTarget.Contact(contactId!!)

                ChatRepository.shared.sendTextMessage(target, text)

            } catch (e: Exception) {
                _events.emit(e.message.orEmpty())
            }
        }
    }


    suspend fun checkUpdatesForDialog() {
        dialogId?.let {
            try {
                ChatRepository.shared.checkUpdatesForDialog(it)
            } catch (e: Exception) {
                _events.emit("checkUpdates: " + e.message.orEmpty())
            }
        }
    }


    private fun observeDialog(id: String, temp: List<MessageUI>) {
        viewModelScope.launch {
            ChatRepository.shared.messages(id).collect {
                _messages.value = it.toList()
            }
        }
    }


    private fun observeTemporaryMessages(id: String) {
        viewModelScope.launch {
            ChatRepository.shared.temporaryMessages(id).collect {
                _messages.value = it
            }
        }
    }


    private fun observeDialogCreationForContact(contactId: String) {
        viewModelScope.launch {
            ChatRepository.shared.dialogs.collect { dialogs ->
                if (dialogId != null) return@collect
                val dialog = dialogs.firstOrNull { d ->
                    d.dialog.members.any { it.contact.id.sub == contactId && d.dialog.type == DialogType.DIRECT }
                } ?: return@collect

                dialogId = dialog.id

                val tempMessages = ChatRepository.shared.temporaryMessages(contactId)

                observeDialog(dialog.id, tempMessages.value)

                ChatRepository.shared.deleteTemporaryMessages(contactId)
            }
        }
    }
}