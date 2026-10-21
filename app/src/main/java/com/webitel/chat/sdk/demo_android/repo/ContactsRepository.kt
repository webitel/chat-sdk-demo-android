package com.webitel.chat.sdk.demo_android.repo

import com.webitel.chat.sdk.ChatClient
import com.webitel.chat.sdk.Contact
import com.webitel.chat.sdk.ContactRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow


/**
 * Contacts the current user can write to.
 *
 * SDK: `client.getContacts(...)`.
 */
class ContactsRepository(
    private val client: () -> ChatClient,
    private val currentUserSub: () -> String?
) {

    private val _items = MutableStateFlow<List<Contact>>(emptyList())
    val items: StateFlow<List<Contact>> = _items


    suspend fun loadContacts(request: ContactRequest) {
        // SDK: load a page of contacts
        val page = awaitResult { client().getContacts(request, it) }
        // The current user is in the list too — hide it.
        _items.value = page.items.filter { it.id.sub != currentUserSub() }
    }


    fun clear() {
        _items.value = emptyList()
    }
}
