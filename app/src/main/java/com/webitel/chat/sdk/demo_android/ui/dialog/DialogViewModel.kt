package com.webitel.chat.sdk.demo_android.ui.dialog

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.webitel.chat.sdk.Cancellable
import com.webitel.chat.sdk.ChatButtonAction
import com.webitel.chat.sdk.ConnectionState
import com.webitel.chat.sdk.ContactId
import com.webitel.chat.sdk.DialogType
import com.webitel.chat.sdk.FileSource
import com.webitel.chat.sdk.MessageTarget
import com.webitel.chat.sdk.UploadRequest
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
import java.io.File
import java.io.InputStream


sealed class ButtonActionEvent {
    data class OpenUrl(val url: String) : ButtonActionEvent()
    data class RequestData(val type: String) : ButtonActionEvent()
}

class DialogViewModel : ViewModel() {
    private val _events = MutableSharedFlow<String>()
    val events: SharedFlow<String> = _events

    private val _buttonActionEvents = MutableSharedFlow<ButtonActionEvent>()
    val buttonActionEvents: SharedFlow<ButtonActionEvent> = _buttonActionEvents.asSharedFlow()

    private val _reload = MutableSharedFlow<Unit>()
    val reload = _reload.asSharedFlow()

    private val _messages = MutableStateFlow<List<MessageUI>>(emptyList())
    val messages: StateFlow<List<MessageUI>> = _messages

    private var dialogId: String? = null
    private var contactId: ContactId? = null

    val connectionState = ChatRepository.shared.connectionState

    private val activeDownloads = mutableMapOf<String, Cancellable>()

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
            observeDialog(dId)
        }
    }


    fun downloadFile(context: Context, messageLocalId: String, fileId: String, fileName: String, fileSize: Long = 0L) {
        val dId = dialogId ?: return
        val cancellable = ChatRepository.shared.downloadFile(
            dialogId = dId,
            messageLocalId = messageLocalId,
            fileId = fileId,
            fileName = fileName,
            filesDir = context.filesDir,
            fileSize = fileSize
        )
        activeDownloads[fileId] = cancellable
    }

    override fun onCleared() {
        super.onCleared()
        activeDownloads.values.forEach { it.cancel() }
        activeDownloads.clear()
    }

    fun sendFile(context: Context, uri: Uri, text: String = "") {
        viewModelScope.launch {
            try {
                val target = dialogId?.let {
                    MessageTarget.Dialog(it)
                } ?: MessageTarget.Contact(contactId!!)

                val attachment = getAttachmentInfo(context, uri)

                // Copy the file to local storage before uploading.
                // The local path is passed to sendFileMessage and cached in FileCache
                // after the upload completes, so the sender sees the file as already
                // downloaded when the server echo arrives — no Download button shown.
                val downloadsDir = File(context.filesDir, "downloads").also { it.mkdirs() }
                val localFile = File(downloadsDir, attachment.title)
                localFile.outputStream().use { out -> attachment.stream.copyTo(out) }

                val req = UploadRequest(
                    FileSource.Stream(localFile.inputStream()),
                    attachment.title,
                    attachment.type,
                    attachment.size
                )
                ChatRepository.shared.sendFileMessage(target, req, localFile.absolutePath, text)
            } catch (e: Exception) {
                _events.emit(e.message.orEmpty())
            }
        }
    }

    @SuppressLint("Range")
    private fun getAttachmentInfo(context: Context, attachmentUri: Uri): Attachment {

        val contentResolver = context.contentResolver
        val inputStream = contentResolver.openInputStream(attachmentUri)
            ?: throw Exception("inputStream is null")

        val cursor = context.contentResolver.query(
            attachmentUri, null, null, null, null
        )
        cursor?.moveToFirst()

        val name =
            cursor?.getString(cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)) ?: "unknown"
        val size =
            cursor?.getLong(cursor.getColumnIndex(OpenableColumns.SIZE))
                ?: inputStream.available().toLong()
        val type = contentResolver.getType(attachmentUri) ?: ""

        cursor?.close()

        return Attachment(
            inputStream,
            title = name,
            type = type,
            path = attachmentUri.toString(),
            size
        )
    }

    inner class Attachment(
        val stream: InputStream,
        val title: String,
        val type: String,
        val path: String,
        val size: Long
    )


    fun onButtonClick(messageId: String?, buttonId: String, action: ChatButtonAction) {
        viewModelScope.launch {
            when (action) {
                is ChatButtonAction.SendCallback ->
                    messageId?.let {
                        try {
                            ChatRepository.shared.sendAction(it, buttonId, action.data)
                        } catch (e: Exception) {
                            _events.emit(e.message.orEmpty())
                        }
                    }
                is ChatButtonAction.OpenUrl ->
                    viewModelScope.launch { _buttonActionEvents.emit(ButtonActionEvent.OpenUrl(action.url)) }
                is ChatButtonAction.RequestData ->
                    viewModelScope.launch { _buttonActionEvents.emit(ButtonActionEvent.RequestData(action.type)) }
            }
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


    private fun observeDialog(id: String) {
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

                observeDialog(dialog.id)

                ChatRepository.shared.deleteTemporaryMessages(contactId)
            }
        }
    }
}