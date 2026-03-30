package com.webitel.chat.sdk.demo_android.repo

import android.app.Application
import android.util.Log
import com.google.firebase.messaging.FirebaseMessaging
import com.webitel.chat.sdk.AuthMethod
import com.webitel.chat.sdk.ChatClient
import com.webitel.chat.sdk.ChatEvent
import com.webitel.chat.sdk.ChatEventListener
import com.webitel.chat.sdk.ConnectionListener
import com.webitel.chat.sdk.ConnectionState
import com.webitel.chat.sdk.Contact
import com.webitel.chat.sdk.ContactIdentity
import com.webitel.chat.sdk.ContactRequest
import com.webitel.chat.sdk.Dialog
import com.webitel.chat.sdk.DialogEvent
import com.webitel.chat.sdk.DialogRequest
import com.webitel.chat.sdk.DialogType
import com.webitel.chat.sdk.HistoryCursor
import com.webitel.chat.sdk.HistoryRequest
import com.webitel.chat.sdk.LogLevel
import com.webitel.chat.sdk.MessageEvent
import com.webitel.chat.sdk.MessageOptions
import com.webitel.chat.sdk.MessageTarget
import com.webitel.chat.sdk.MoveDirection
import com.webitel.chat.sdk.Page
import com.webitel.chat.sdk.StateEvent
import com.webitel.chat.sdk.demo_android.ui.dialog.MessageUI
import com.webitel.chat.sdk.demo_android.ui.dialogs.DialogUI
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException


/**
 * Demo repository that shows how to integrate Chat SDK with Kotlin Flow.
 *
 * The SDK itself is callback-based to support both Java and Kotlin projects.
 * This class demonstrates:
 *
 * - converting callbacks to suspend functions
 * - exposing data via StateFlow
 * - optimistic UI updates for messages
 * - simple in-memory caching
 * - FCM registration
 *
 * Simplified for demo purposes.
 */
class ChatRepository private constructor(): ChatEventListener, ConnectionListener {

    private var currentUser = ContactIdentity(
        "qwer-qwer-wqre",
        "https://demo.webitel.com/portal",
        "John Doe"
    )
    private var currentHost: String = "https://demo.webitel.com"

    private var chatClient: ChatClient? = null
    private var jwtGenerator: JwtGenerator? = null

    private val useJWT: Boolean = true

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Connecting)
    val connectionState: StateFlow<ConnectionState> = _connectionState
    private val repoScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val dialogsByContact = ConcurrentHashMap<String, Dialog>()
    private val _dialogs = MutableStateFlow<Map<String, DialogUI>>(emptyMap())
    val dialogs: StateFlow<List<DialogUI>> =
        _dialogs
            .map { map ->
                map.values
                    .sortedByDescending { it.lastMessageAt}
            }
            .stateIn(repoScope, SharingStarted.Eagerly, emptyList())

    private val _contacts = MutableStateFlow<List<Contact>>(emptyList())
    val contacts: StateFlow<List<Contact>> = _contacts

    private val messagesCache =
        mutableMapOf<String, MutableStateFlow<List<MessageUI>>>()

    private val temporaryMessagesCache =
        mutableMapOf<String, MutableStateFlow<List<MessageUI>>>()

    private val requireChatClient: ChatClient
        get() = chatClient
            ?: throw IllegalStateException(
                "ChatClient not initialized. Set connection config on setting tab"
            )


    companion object {
        /**
         * Simple singleton for demo purposes.
         * In production consider using DI (Hilt/Koin).
         */
        val shared = ChatRepository()
        private const val TAG = "ChatRepository"
    }


    fun setupChatClient(
        application: Application,
        endpoint: String,
        clientToken: String,
        user: ContactIdentity
    ) {
        if (chatClient != null) return

        createJwtGenerator(application)

        chatClient = createClient(application, endpoint, clientToken, user)

        currentHost = endpoint
        currentUser = user
        getAndRegisterPush()
    }


    fun messages(dialogId: String): StateFlow<List<MessageUI>> =
        messagesCache.getOrPut(dialogId) { MutableStateFlow(emptyList()) }


    fun temporaryMessages(contactId: String): StateFlow<List<MessageUI>> =
        temporaryMessagesCache.getOrPut(contactId) {
            MutableStateFlow(emptyList())
        }


    fun deleteTemporaryMessages(contactId: String) {
        temporaryMessagesCache.remove(contactId)
    }


    fun findDialogByContact(id: String): Dialog? {
        return dialogsByContact[id]
    }


    fun openConnect() {
        requireChatClient.connect()
    }


    fun closeConnect() {
        requireChatClient.disconnect()
    }


    suspend fun loadDialogs(request: DialogRequest) {
        val page = getDialogs(request)

        dialogsByContact.clear()

        page.items.forEach { dialog ->
            if (dialog.type == DialogType.DIRECT) {
                dialog.members.firstOrNull { it.id.sub != currentUser.sub }?.let {
                    dialogsByContact[it.id.sub] = dialog
                }
            }
        }

        _dialogs.update { current ->
            buildMap {
                putAll(current)
                page.items.forEach {
                    val dialogUI = DialogUI(
                        dialog = it,
                        lastMessageAt = it.lastMessage?.createdAt ?: 0,
                        lastMessageText = it.lastMessage?.text ?: ""
                    )
                    put(it.id, dialogUI)
                }
            }
        }
    }


    suspend fun loadContacts(request: ContactRequest) {
        val page = getContacts(request)

        val filteredItems = page.items.filter {
            it.id.sub != currentUser.sub
        }
        _contacts.value = filteredItems
    }


    suspend fun sendTextMessage(
        target: MessageTarget,
        text: String
    ): String = withContext(Dispatchers.IO) {

        suspendCancellableCoroutine { cont ->
            val sendId = UUID.randomUUID().toString()
            val localMessage = MessageUI(
                localId = sendId,
                body = text,
            )

            val key = when (target) {
                is MessageTarget.Dialog -> target.id
                is MessageTarget.Contact -> target.contactId.sub
            }

            val cache = when (target) {
                is MessageTarget.Dialog -> {
                    messagesCache
                }
                is MessageTarget.Contact -> temporaryMessagesCache
            }

            cache[key]?.update { it + localMessage }

            requireChatClient.sendMessage(target, MessageOptions(text, sendId = sendId)) { result ->
                result
                    .onSuccess { messageId ->
                        cont.resume(messageId)
                    }
                    .onFailure { throwable ->
                        updateMessageError(
                            cache = cache,
                            key = key,
                            localId = sendId,
                            throwable = throwable
                        )
                        cont.resumeWithException(throwable)
                    }
            }
        }
    }


    suspend fun checkUpdatesForDialog(id: String) = withContext(Dispatchers.IO) {
        suspendCancellableCoroutine { cont ->

            val dialogUI = _dialogs.value[id]
                ?: return@suspendCancellableCoroutine cont.resumeWithException(
                    IllegalStateException("Dialog not found with id=$id")
                )

            val flow = messagesCache.getOrPut(id) { MutableStateFlow(emptyList()) }

            val lastMessage = flow.value.lastOrNull()?.message

            val cursor = lastMessage?.let {
                HistoryCursor(
                    messageId = it.id,
                    direction = MoveDirection.NEWER
                )
            }

            val request = HistoryRequest(cursor = cursor)

            dialogUI.dialog.getHistory(request) { result ->
                result
                    .onSuccess { slice ->

                        if (slice.items.isNotEmpty()) {
                            flow.update { current ->
                                val existingIds = current.mapNotNull { it.message?.id }.toSet()

                                val newMessages = slice.items
                                    .filter { it.id !in existingIds }
                                    .map { MessageUI(localId = it.id, message = it) }

                                current + newMessages
                            }
                        }

                        cont.resume(Unit)
                    }
                    .onFailure { cont.resumeWithException(it) }
            }
        }
    }


    suspend fun updateConnectInfo(
        application: Application,
        endpoint: String,
        clientToken: String,
        user: ContactIdentity
    ) {
        if (chatClient == null) {
            setupChatClient(application, endpoint, clientToken, user)
            chatClient?.connect()
            return
        }

        if (shouldRecreateClient(endpoint, user)) {
            recreateClient(application, endpoint, clientToken, user)
            return
        }

        reconnectIfNeeded()
    }


    private suspend fun getContacts(request: ContactRequest): Page<Contact> =
        withContext(Dispatchers.IO) {
            suspendCancellableCoroutine { cont ->
                requireChatClient.getContacts(request) { result ->
                    result
                        .onSuccess { cont.resume(it) }
                        .onFailure { cont.resumeWithException(it) }
                }
            }
        }


    private suspend fun getDialogs(request: DialogRequest): Page<Dialog> =
        withContext(Dispatchers.IO) {
            suspendCancellableCoroutine { cont ->
                requireChatClient.getDialogs(request) { result ->
                    result
                        .onSuccess { cont.resume(it) }
                        .onFailure { cont.resumeWithException(it) }
                }
            }
        }


    private fun createClient(
        application: Application,
        endpoint: String,
        clientToken: String,
        user: ContactIdentity
    ): ChatClient {

        return ChatClient.builder(application, endpoint, clientToken)
            .auth(authMethod(user))
            .logLevel(LogLevel.DEBUG)
            .build()
            .also {
                it.addEventListener(this)
                it.addConnectionListener(this)
            }
    }


    private fun shouldRecreateClient(
        endpoint: String,
        user: ContactIdentity
    ): Boolean {
        return currentHost != endpoint ||
                currentUser.sub != user.sub ||
                currentUser.iss != user.iss
    }


    private suspend fun recreateClient(
        application: Application,
        endpoint: String,
        clientToken: String,
        user: ContactIdentity
    ) {
        try {
            endSession()
        } catch (e: Throwable) {
            Log.w(TAG, "Logout failed", e)
        }

        clearAllCache()

        chatClient = createClient(application, endpoint, clientToken, user)

        currentHost = endpoint
        currentUser = user

        getAndRegisterPush()

        chatClient?.connect()
    }


    private fun reconnectIfNeeded() {
        if (_connectionState.value is ConnectionState.Failed ||
            _connectionState.value is ConnectionState.Disconnected
        ) {
            chatClient?.connect()
        }
    }


    private fun getAndRegisterPush() {
        FirebaseMessaging.getInstance().token
            .addOnCompleteListener { task ->
                if (!task.isSuccessful) {
                    Log.e(TAG, "Fetching token failed", task.exception)
                    return@addOnCompleteListener
                }

                val token = task.result
                Log.d(TAG, "FCM Token: $token")

                sendTokenToServer(token)
            }
    }


    private fun sendTokenToServer(token: String) =
        requireChatClient.registerDevice(token) { result ->
            result
                .onSuccess { Log.d(TAG, "Device registered") }
                .onFailure { Log.e(TAG, it.message.toString())}
        }




    private suspend fun endSession() =
        withContext(Dispatchers.IO) {
            suspendCancellableCoroutine { cont ->
                requireChatClient.endSession { result ->
                    result
                        .onSuccess { cont.resume(it) }
                        .onFailure { cont.resumeWithException(it) }
                }
            }
        }


    private fun updateMessageError(
        cache: MutableMap<String, MutableStateFlow<List<MessageUI>>>,
        key: String,
        localId: String,
        throwable: Throwable
    ) {
        val flow = cache[key] ?: return

        flow.update { list ->
            list.map { msg ->
                if (msg.localId == localId) {
                    msg.copy(
                        error = throwable.message
                    )
                } else msg
            }
        }
    }


    private fun authMethod(user: ContactIdentity): AuthMethod {
        return if (useJWT)
            AuthMethod.Token { createNewJWT(user) }
        else
            AuthMethod.Contact(user)
    }


    private fun createNewJWT(user: ContactIdentity): String {
        return jwtGenerator!!.generate(
            subject = user.name,
            claims = mapOf(
                "iss" to user.iss,
                "sub" to user.sub,
                "name" to user.name
            ),
            ttlSeconds = 60000000
        )
    }


    private fun createJwtGenerator(application: Application) {
        if (jwtGenerator != null) return
        jwtGenerator = JwtGenerator(application)
    }


    private fun clearAllCache() {
        _contacts.value = emptyList()

        dialogsByContact.clear()
        _dialogs.value = emptyMap()

        messagesCache.values.forEach { it.value = emptyList() }
        messagesCache.clear()

        temporaryMessagesCache.values.forEach { it.value = emptyList() }
        temporaryMessagesCache.clear()
    }


    override fun onEvent(event: ChatEvent) {
        when(event) {
            is MessageEvent.Received -> {
                val dialogId = event.dialogId
                val message = event.message
                val sendId = message.sendId

                val flow = messagesCache[dialogId]
                flow?.update { list ->

                    if (sendId != null) {

                        val index = list.indexOfFirst { it.localId == sendId }

                        if (index != -1) {
                            return@update list.toMutableList().apply {
                                this[index] = this[index].copy(
                                    message = message,
                                    error = null
                                )
                            }
                        }
                    }
                    list + MessageUI(
                        localId = message.id,
                        message = message
                    )
                }
                _dialogs.update {
                    val currentUI = it[dialogId] ?: return@update it

                    val updatedUI = DialogUI(
                        dialog = currentUI.dialog,
                        lastMessageAt = currentUI.dialog.lastMessage?.createdAt ?: System.currentTimeMillis(),
                        lastMessageText = currentUI.dialog.lastMessage?.text ?: ""
                    )

                    it + (dialogId to updatedUI)
                }
            }
            is MessageEvent.Deleted -> TODO()
            is MessageEvent.Edited -> TODO()
            is StateEvent.Read -> TODO()
            is StateEvent.Typing -> TODO()
            is DialogEvent.Created -> {
                _dialogs.update { map ->
                    val dialogUI = DialogUI(
                        event.dialog,
                        lastMessageAt = event.dialog.lastMessage?.createdAt ?: 0,
                        lastMessageText = event.dialog.lastMessage?.text ?: ""
                    )
                    map + (event.dialog.id to dialogUI)
                }

                if (event.dialog.type == DialogType.DIRECT) {
                    event.dialog.members.firstOrNull { it.id.sub != currentUser.sub }?.let {
                        dialogsByContact[it.id.sub] = event.dialog
                    }
                }
            }
        }
    }


    override fun onStateChanged(state: ConnectionState) {
        repoScope.launch {
            _connectionState.emit(state)
        }
    }
}