package com.webitel.chat.sdk.demo_android.repo

import android.app.Application
import android.util.Log
import com.google.firebase.messaging.FirebaseMessaging
import com.webitel.chat.sdk.ActivityEvent
import com.webitel.chat.sdk.AuthMethod
import com.webitel.chat.sdk.ChatClient
import com.webitel.chat.sdk.ChatClientListener
import com.webitel.chat.sdk.ChatEvent
import com.webitel.chat.sdk.ChatEventListener
import com.webitel.chat.sdk.ConnectionListener
import com.webitel.chat.sdk.ConnectionState
import com.webitel.chat.sdk.ContactIdentity
import com.webitel.chat.sdk.DialogEvent
import com.webitel.chat.sdk.LogLevel
import com.webitel.chat.sdk.MessageEvent
import com.webitel.chat.sdk.ReceiptEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow


/**
 * The only place where the demo talks to Chat SDK.
 *
 * ChatRepository creates the [ChatClient], connects and routes SDK events to features:
 * - [DialogsRepository] — dialogs list;
 * - [ContactsRepository] — contacts;
 * - [MessagesRepository] — send text, incoming messages, paged history;
 * - [SearchRepository] — search messages in a dialog;
 * - [FilesRepository] — upload / download attachments;
 * - [ReactionsRepository] — message reactions;
 * - [EditingRepository] — editing messages;
 * - [DeletionRepository] — deleting messages;
 * - [TypingRepository] — typing indicator;
 * - [ReadRepository] — marking messages as read;
 * - [DeliveryFailuresRepository] — members a message was not delivered to;
 * - [SyncRepository] — initial load, changes missed while offline, full resync.
 *
 * The SDK is callback-based; see [awaitResult] for how calls are turned into suspend functions.
 *
 * Simple singleton for demo purposes. In production consider DI (Hilt/Koin).
 */
class ChatRepository private constructor() : ChatEventListener, ConnectionListener, ChatClientListener {

    private var client: ChatClient? = null
    private var jwtGenerator: JwtGenerator? = null
    private var currentHost: String? = null
    private var currentUser: ContactIdentity? = null

    /** Current user's contact id, e.g. to tell my receipts from others'. */
    val currentUserSub: String? get() = currentUser?.sub

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState

    val dialogs = DialogsRepository(::requireClient) { currentUser?.sub }
    val contacts = ContactsRepository(::requireClient) { currentUser?.sub }
    val messages = MessagesRepository(::requireClient)
    val search = SearchRepository()
    val files = FilesRepository(::requireClient, messages)
    val reactions = ReactionsRepository(::requireClient, messages)
    val editing = EditingRepository(::requireClient, messages)
    val deletion = DeletionRepository(::requireClient, messages)
    val typing = TypingRepository(dialogs) { currentUser?.sub }
    val read = ReadRepository(dialogs) { currentUser?.sub }
    val deliveryFailures = DeliveryFailuresRepository(messages)
    val sync = SyncRepository(dialogs, messages, deletion, typing)


    /** Creates the client from saved settings on app start. */
    fun setupChatClient(
        application: Application,
        host: String,
        clientToken: String,
        user: ContactIdentity
    ) {
        if (client != null) return
        createClient(application, host, clientToken, user)
    }


    /** Called from Settings. Recreates the client if the host or the user changed. */
    suspend fun updateConnectInfo(
        application: Application,
        host: String,
        clientToken: String,
        user: ContactIdentity
    ) {
        val isSameAccount = client != null &&
                currentHost == host &&
                currentUser?.sub == user.sub &&
                currentUser?.iss == user.iss

        if (!isSameAccount) {
            if (client != null) endSession()
            createClient(application, host, clientToken, user)
        }

        connect()
    }


    fun connect() {
        // SDK: open the connection. State changes come to onStateChanged.
        client?.connect()
    }


    fun disconnect() {
        // SDK: close the connection (e.g. app goes to background)
        client?.disconnect()
    }


    /** Push token from FCM. The server uses it to send notifications to this device. */
    fun registerPushToken(token: String) {
        // SDK: register the device for push notifications
        client?.registerDevice(token) { result ->
            result
                .onSuccess { Log.d(TAG, "Device registered") }
                .onFailure { Log.e(TAG, "Device registration failed", it) }
        }
    }


    // ─── SDK listeners ───────────────────────────────────────────────────────

    override fun onEvent(event: ChatEvent) {
        when (event) {
            is MessageEvent.Received -> {
                // The participant has sent what they were typing
                if (!event.message.isOutgoing) typing.clear(event.dialogId)
                messages.handleReceived(event.dialogId, event.message)
                dialogs.refresh(event.dialogId)
            }
            is MessageEvent.Edited -> {
                editing.handleEdited(event.dialogId, event.message)
                dialogs.refresh(event.dialogId)
            }
            is MessageEvent.Deleted -> {
                deletion.handleDeleted(event.dialogId, event.messageId, event.deletion)
                dialogs.refresh(event.dialogId)
            }
            is MessageEvent.ReactionsChanged ->
                reactions.handleReactionsChanged(event.dialogId, event.messageId, event.reactions)
            is ActivityEvent.Typing ->
                typing.handleTyping(event.dialogId, event.member, event.timeoutMs)
            is DialogEvent.Created -> dialogs.handleCreated(event.dialog)
            is DialogEvent.Synchronized -> sync.handleSynchronized(event.dialogId, event.changes)
            // SDK already updated `participantStates` and `unreadCount` —
            // refresh the dialog to update ✓ / ✓✓ statuses and the unread badge
            is ReceiptEvent.Delivered -> dialogs.refresh(event.dialogId)
            is ReceiptEvent.Read -> dialogs.refresh(event.dialogId)
            is ReceiptEvent.DeliveryFailed ->
                deliveryFailures.handleDeliveryFailed(event.dialogId, event.exception)
            else -> Log.d(TAG, "Event not handled in demo: $event")
        }
    }


    override fun onStateChanged(from: ConnectionState, to: ConnectionState) {
        _connectionState.value = to
        if (to is ConnectionState.Connected) sync.loadInitialStateIfNeeded()
    }


    /** SDK: client-level event, not bound to a dialog. */
    override fun onResyncRequired() = sync.onResyncRequired()


    // ─── Client lifecycle ────────────────────────────────────────────────────

    private fun createClient(
        application: Application,
        host: String,
        clientToken: String,
        user: ContactIdentity
    ) {
        val jwt = jwtGenerator ?: JwtGenerator(application).also { jwtGenerator = it }

        // SDK: create the client.
        // AuthMethod.Token — the SDK calls the provider every time it needs a fresh token.
        client = ChatClient.builder(application, host, clientToken)
            .auth(AuthMethod.Token { jwt.generate(user) })
            .logLevel(LogLevel.DEBUG)
            .build()
            .also {
                it.addEventListener(this)
                it.addConnectionListener(this)
                it.addClientListener(this)
            }

        currentHost = host
        currentUser = user

        FirebaseMessaging.getInstance().token
            .addOnSuccessListener(::registerPushToken)
            .addOnFailureListener { Log.e(TAG, "Fetching FCM token failed", it) }
    }


    /** Logs out on the server and clears all local data of the previous user. */
    private suspend fun endSession() {
        try {
            // SDK: log out — the server forgets this device/session
            awaitResult<Unit> { requireClient().endSession(it) }
        } catch (e: Exception) {
            Log.w(TAG, "endSession failed", e)
        }

        dialogs.clear()
        contacts.clear()
        messages.clearAll()
        typing.clearAll()
        read.clearAll()
        sync.reset()
    }


    private fun requireClient(): ChatClient =
        client ?: throw IllegalStateException(
            "ChatClient not initialized. Set connection config on the Settings tab"
        )


    companion object {
        val shared = ChatRepository()
        private const val TAG = "ChatRepository"
    }
}
