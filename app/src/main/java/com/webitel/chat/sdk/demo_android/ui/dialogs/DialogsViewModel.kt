package com.webitel.chat.sdk.demo_android.ui.dialogs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.webitel.chat.sdk.ConnectionState
import com.webitel.chat.sdk.DialogRequest
import com.webitel.chat.sdk.demo_android.repo.ChatRepository
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch


class DialogsViewModel : ViewModel() {
    val dialogs = ChatRepository.shared.dialogs
    val connectionState = ChatRepository.shared.connectionState
    private val _events = MutableSharedFlow<String>()
    val events: SharedFlow<String> = _events
    private var connectedHandled = false

    init {
        viewModelScope.launch {
            connectionState.collect { state ->
                if (state !is ConnectionState.Connected) {
                    resetConnectionHandled()
                }
            }
        }
    }


    fun load() {
        viewModelScope.launch {
            try {
                if (onConnectedHandled()) {
                    ChatRepository.shared.loadDialogs(DialogRequest(size = 50))
                }
            } catch (e: Exception) {
                _events.emit(e.message.toString())
            }
        }
    }


    fun onConnectedHandled(): Boolean {
        if (connectedHandled) return false
        connectedHandled = true
        return true
    }


    private fun resetConnectionHandled() {
        connectedHandled = false
    }
}