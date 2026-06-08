package com.webitel.chat.sdk.demo_android.repo

import android.app.Application
import android.util.Log
import com.google.firebase.messaging.FirebaseMessaging
import com.webitel.chat.sdk.AuthMethod
import com.webitel.chat.sdk.Cancellable
import com.webitel.chat.sdk.ChatClient
import com.webitel.chat.sdk.ChatError
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
import com.webitel.chat.sdk.DownloadListener
import com.webitel.chat.sdk.DownloadRequest
import com.webitel.chat.sdk.DownloadResult
import com.webitel.chat.sdk.HistoryCursor
import com.webitel.chat.sdk.HistoryRequest
import com.webitel.chat.sdk.LogLevel
import com.webitel.chat.sdk.Message
import com.webitel.chat.sdk.MessageAction
import com.webitel.chat.sdk.MessageAttachment
import com.webitel.chat.sdk.MessageContent
import com.webitel.chat.sdk.MessageEvent
import com.webitel.chat.sdk.MessageOptions
import com.webitel.chat.sdk.MessageTarget
import com.webitel.chat.sdk.MoveDirection
import com.webitel.chat.sdk.Page
import com.webitel.chat.sdk.SendAttachment
import com.webitel.chat.sdk.SendContent
import com.webitel.chat.sdk.StateEvent
import com.webitel.chat.sdk.UploadListener
import com.webitel.chat.sdk.UploadRequest
import com.webitel.chat.sdk.UploadResult
import com.webitel.chat.sdk.demo_android.ui.dialog.FileState
import com.webitel.chat.sdk.demo_android.ui.dialog.MessageUI
import com.webitel.chat.sdk.demo_android.ui.dialog.PendingAttachment
import com.webitel.chat.sdk.demo_android.ui.dialog.previewText
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
import java.io.File
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
 * - file download with progress and SharedPreferences caching
 * - FCM registration
 *
 * Simplified for demo purposes.
 */
class ChatRepository private constructor() : ChatEventListener, ConnectionListener {

    private var currentUser = ContactIdentity(
        "qwer-qwer-wqre",
        "https://demo.webitel.com/portal",
        "John Doe"
    )
    private var currentHost: String = "https://demo.webitel.com"

    private var chatClient: ChatClient? = null
    private var jwtGenerator: JwtGenerator? = null

    private val useJWT: Boolean = true

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState
    private val repoScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val dialogsByContact = ConcurrentHashMap<String, Dialog>()
    private val _dialogs = MutableStateFlow<Map<String, DialogUI>>(emptyMap())
    val dialogs: StateFlow<List<DialogUI>> =
        _dialogs
            .map { map ->
                map.values
                    .sortedByDescending { it.lastMessageAt }
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


    suspend fun sendAction(
        messageId: String,
        buttonId: String,
        data: String
    ) = suspendCancellableCoroutine { continuation ->
        requireChatClient.sendAction(
            messageId,
            MessageAction.ButtonClick(id = buttonId, data = data)
        ) { result ->
            result.fold(
                onSuccess = {
                    continuation.resume(Unit)
                },
                onFailure = {
                    continuation.resumeWithException(it)
                }
            )
        }
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
                dialog.members.firstOrNull { it.contact.id.sub != currentUser.sub }?.let {
                    dialogsByContact[it.contact.id.sub] = dialog
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
                        lastMessageText = it.lastMessage?.previewText ?: ""
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


    /**
     * Downloads a file attachment and saves it to the app's files directory.
     *
     * Demonstrates:
     * - checking SharedPreferences cache before hitting the network
     * - streaming chunks to disk
     * - updating FileState in the message list via StateFlow
     * - returning Cancellable for user-initiated cancellation
     */
    fun downloadFile(
        dialogId: String,
        messageLocalId: String,
        fileId: String,
        fileName: String,
        filesDir: File,
        fileSize: Long = 0L
    ): Cancellable {
        val cached = FileCache.shared.get(fileId)
        if (cached != null && File(cached).exists()) {
            updateFileState(dialogId, messageLocalId, fileId, FileState.Downloaded(cached))
            return object : Cancellable { override fun cancel() {} }
        }

        updateFileState(dialogId, messageLocalId, fileId, FileState.Downloading(null))

        val destFile = File(filesDir, "${fileId}_${fileName}")
        val out = destFile.outputStream().buffered()
        var bytesReceived = 0L

        return requireChatClient.download(DownloadRequest(fileId, 0L), object : DownloadListener {
            override fun onChunk(chunk: ByteArray) {
                out.write(chunk)
                bytesReceived += chunk.size
                val progress = if (fileSize > 0) {
                    ((bytesReceived * 100) / fileSize).toInt().coerceIn(0, 100)
                } else null
                updateFileState(dialogId, messageLocalId, fileId, FileState.Downloading(progress))
            }

            override fun onCompleted(result: DownloadResult) {
                out.flush()
                out.close()
                FileCache.shared.put(fileId, destFile.absolutePath)
                updateFileState(dialogId, messageLocalId, fileId, FileState.Downloaded(destFile.absolutePath))
            }

            override fun onError(error: ChatError) {
                out.close()
                destFile.delete()
                updateFileState(dialogId, messageLocalId, fileId,
                    FileState.Error(error.message ?: "Download failed"))
            }
        })
    }


    /**
     * Uploads a file and sends it as a chat message attachment.
     *
     * Demonstrates the upload → send flow:
     * 1. Show an optimistic local placeholder message (spinner while sending)
     * 2. Upload the file via the SDK
     * 3. On upload success, send the message with the SDK-assigned file ID
     * 4. On server echo (MessageEvent.Received), the placeholder is replaced
     *    with the actual message content including attachment metadata
     *
     *
     * Example for multiple files:
     *
     *  val fileIds = uploads.awaitAll()
     *  chatClient.sendMessage(
     *     target,
     *     MessageOptions(
     *         SendContent.Attachments(
     *             fileIds.map { SendAttachment.File(it) }
     *         )
     *     )
     * )
     */
    suspend fun sendFileMessage(
        target: MessageTarget,
        request: UploadRequest,
        localFilePath: String,
        text: String = ""
    ): String = withContext(Dispatchers.IO) {
        suspendCancellableCoroutine { cont ->
            val sendId = UUID.randomUUID().toString()
            val tempFileId = sendId

            val localMessage = MessageUI(
                localId = sendId,
                content = MessageContent.Text(text.ifBlank { request.fileName }),
                createdAt = System.currentTimeMillis(),
                senderName = "Me",
                isOutgoing = true,
                pendingAttachment = PendingAttachment(
                    tempId = tempFileId,
                    fileName = request.fileName,
                    mimeType = request.mimeType.toString(),
                    localPath = localFilePath
                ),
                fileStates = mapOf(tempFileId to FileState.Uploading(null))
            )

            val key = when (target) {
                is MessageTarget.Dialog -> target.id
                is MessageTarget.Contact -> target.contactId.sub
            }
            val cache = when (target) {
                is MessageTarget.Dialog -> messagesCache
                is MessageTarget.Contact -> temporaryMessagesCache
            }

            cache[key]?.update { it + localMessage }

            requireChatClient.upload(request, object : UploadListener {
                override fun onCreated(uploadId: String) {}

                override fun onProgress(uploaded: Long, total: Long?) {
                    val progress = if (total != null && total > 0) {
                        ((uploaded * 100) / total).toInt()
                    } else null
                    updateFileState(key, sendId, tempFileId, FileState.Uploading(progress), cache)
                }

                override fun onCompleted(result: UploadResult) {
                    val fileId = result.file.id
                    // Cache the local copy so this file appears as Downloaded
                    // on the sender's side after the server echo arrives.
                    FileCache.shared.put(fileId, localFilePath)
                    updateFileState(key, sendId, tempFileId, FileState.Downloaded(localFilePath), cache)

                    val sendContent = if (text.isBlank()) {
                        SendContent.Attachments(listOf(SendAttachment.File(fileId)))
                    } else {
                        SendContent.Composite(text, listOf(SendAttachment.File(fileId)))
                    }
                    requireChatClient.sendMessage(
                        target,
                        MessageOptions(sendContent, sendId)
                    ) { sendResult ->
                        sendResult
                            .onSuccess { messageId ->
                                updateMessageSent(cache, key, sendId, messageId)
                                cont.resume(messageId)
                            }
                            .onFailure { throwable ->
                                updateMessageError(cache, key, sendId, throwable)
                                cont.resumeWithException(throwable)
                            }
                    }
                }

                override fun onError(error: ChatError) {
                    val throwable = Exception(error.message ?: "Upload failed")
                    updateMessageError(cache, key, sendId, throwable)
                    cont.resumeWithException(throwable)
                }
            })
        }
    }


    suspend fun sendTextMessage(
        target: MessageTarget,
        text: String
    ): String = withContext(Dispatchers.IO) {

        suspendCancellableCoroutine { cont ->
            val sendId = UUID.randomUUID().toString()
            val localMessage = MessageUI(
                localId = sendId,
                content = MessageContent.Text(text),
                createdAt = System.currentTimeMillis(),
                senderName = "Me",
                isOutgoing = true
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

            requireChatClient.sendMessage(
                target,
                MessageOptions(SendContent.Text(text), sendId)
            ) { result ->
                result
                    .onSuccess { messageId ->
                        updateMessageSent(
                            cache = cache,
                            key = key,
                            localId = sendId,
                            messageId = messageId
                        )
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

            val lastMessageId = flow.value.lastOrNull()?.messageId

            val cursor = lastMessageId?.let {
                HistoryCursor(
                    messageId = it,
                    direction = MoveDirection.NEWER
                )
            }

            val request = HistoryRequest(cursor = cursor)

            dialogUI.dialog.getHistory(request) { result ->
                result
                    .onSuccess { slice ->
                        if (slice.items.isNotEmpty()) {
                            flow.update { current ->
                                val existingIds = current.mapNotNull { it.messageId }.toSet()

                                val newMessages = slice.items
                                    .filter { it.id !in existingIds }
                                    .map { it.toUI() }

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
                .onFailure { Log.e(TAG, it.message.toString()) }
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


    private fun updateFileState(
        key: String,
        messageLocalId: String,
        fileId: String,
        state: FileState,
        cache: MutableMap<String, MutableStateFlow<List<MessageUI>>> = messagesCache
    ) {
        val flow = cache[key] ?: return
        flow.update { list ->
            list.map { msg ->
                if (msg.localId == messageLocalId)
                    msg.copy(fileStates = msg.fileStates + (fileId to state))
                else msg
            }
        }
    }


    private fun updateMessageSent(
        cache: MutableMap<String, MutableStateFlow<List<MessageUI>>>,
        key: String,
        localId: String,
        messageId: String
    ) {
        val flow = cache[key] ?: return

        flow.update { list ->
            list.map { msg ->
                if (msg.localId == localId) {
                    msg.copy(messageId = messageId)
                } else msg
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
                    msg.copy(error = throwable.message)
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
        when (event) {
            is MessageEvent.Received -> {
                val dialogId = event.dialogId
                val message = event.message
                val sendId = message.sendId

                val flow = messagesCache[dialogId]
                flow?.update { list ->
                    if (sendId != null) {
                        val index = list.indexOfFirst { it.localId == sendId }
                        if (index != -1) {
                            // Replace the local placeholder with the full server message
                            // so that file attachments and content are properly populated.
                            return@update list.toMutableList().apply {
                                this[index] = message.toUI()
                            }
                        }
                    }
                    list + message.toUI()
                }
                _dialogs.update {
                    val currentUI = it[dialogId] ?: return@update it
                    val updatedUI = DialogUI(
                        dialog = currentUI.dialog,
                        lastMessageAt = currentUI.dialog.lastMessage?.createdAt ?: System.currentTimeMillis(),
                        lastMessageText = currentUI.dialog.lastMessage?.previewText ?: ""
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
                        lastMessageText = event.dialog.lastMessage?.previewText ?: ""
                    )
                    map + (event.dialog.id to dialogUI)
                }

                if (event.dialog.type == DialogType.DIRECT) {
                    event.dialog.members.firstOrNull { it.contact.id.sub != currentUser.sub }?.let {
                        dialogsByContact[it.contact.id.sub] = event.dialog
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


/**
 * Converts a SDK [Message] to a [MessageUI], pre-populating [MessageUI.fileStates]
 * from [FileCache] so previously downloaded attachments appear as [FileState.Downloaded]
 * immediately — no re-download needed after an app restart.
 */
fun Message.toUI(): MessageUI {
    val attachments: List<MessageAttachment> = when (val c = content) {
        is MessageContent.Attachments -> c.attachments
        is MessageContent.Composite -> c.attachments
        else -> emptyList()
    }

    val fileStates = attachments.associate { attachment ->
        val cachedPath = FileCache.shared.get(attachment.fileId)
        attachment.fileId to if (cachedPath != null && File(cachedPath).exists()) {
            FileState.Downloaded(cachedPath)
        } else {
            FileState.Idle
        }
    }

    return MessageUI(
        localId = sendId ?: id,
        content = content,
        createdAt = createdAt,
        senderName = from.contact.name,
        isOutgoing = isOutgoing,
        messageId = id,
        fileStates = fileStates
    )
}
