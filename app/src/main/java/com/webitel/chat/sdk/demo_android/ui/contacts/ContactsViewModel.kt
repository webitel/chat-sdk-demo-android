package com.webitel.chat.sdk.demo_android.ui.contacts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.webitel.chat.sdk.ContactRequest
import com.webitel.chat.sdk.demo_android.repo.ChatRepository
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch


class ContactsViewModel : ViewModel() {
    val contacts = ChatRepository.shared.contacts.items

    private val _events = MutableSharedFlow<String>()
    val events: SharedFlow<String> = _events

    fun load() {
        viewModelScope.launch {
            try {
                ChatRepository.shared.contacts.loadContacts(ContactRequest(size = 500))
            } catch (e: Exception) {
                _events.emit(e.message.orEmpty())
            }
        }
    }
}
