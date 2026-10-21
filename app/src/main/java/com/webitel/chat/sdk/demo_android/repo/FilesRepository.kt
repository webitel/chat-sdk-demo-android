package com.webitel.chat.sdk.demo_android.repo

import com.webitel.chat.sdk.Cancellable
import com.webitel.chat.sdk.ChatClient
import com.webitel.chat.sdk.ChatError
import com.webitel.chat.sdk.DownloadListener
import com.webitel.chat.sdk.DownloadRequest
import com.webitel.chat.sdk.DownloadResult
import com.webitel.chat.sdk.MessageContent
import com.webitel.chat.sdk.MessageTarget
import com.webitel.chat.sdk.SendAttachment
import com.webitel.chat.sdk.SendContent
import com.webitel.chat.sdk.UploadListener
import com.webitel.chat.sdk.UploadRequest
import com.webitel.chat.sdk.UploadResult
import com.webitel.chat.sdk.demo_android.ui.dialog.FileState
import com.webitel.chat.sdk.demo_android.ui.dialog.MessageUI
import com.webitel.chat.sdk.demo_android.ui.dialog.PendingAttachment
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException


/**
 * File attachments.
 *
 * Send a file = two SDK calls:
 * 1. `client.upload(...)` — upload the file, get its `fileId`;
 * 2. `client.sendMessage(...)` with `SendAttachment.File(fileId)`.
 *
 * Download: `client.download(...)` streams the file in chunks, we write them to disk.
 * Downloaded files are remembered in [FileCache] so they are not downloaded again.
 */
class FilesRepository(
    private val client: () -> ChatClient,
    private val messages: MessagesRepository
) {

    /**
     * Uploads a file and sends it as a message (with optional [text]).
     *
     * For several files: upload each one, then send them all in one message:
     * `SendContent.Attachments(fileIds.map { SendAttachment.File(it) })`.
     */
    suspend fun sendFile(
        target: MessageTarget,
        request: UploadRequest,
        localFilePath: String,
        text: String = ""
    ): String {
        val sendId = messages.newSendId()

        // Local message with upload progress. The file id is unknown yet, so `sendId` is used.
        messages.add(
            target,
            MessageUI.outgoing(
                localId = sendId,
                content = MessageContent.Text(text.ifBlank { request.fileName }),
                pendingAttachment = PendingAttachment(
                    tempId = sendId,
                    fileName = request.fileName,
                    mimeType = request.mimeType.toString(),
                    localPath = localFilePath
                ),
                fileStates = mapOf(sendId to FileState.Uploading(null))
            )
        )

        val fileId = try {
            upload(request) { progress ->
                messages.setFileState(target, sendId, sendId, FileState.Uploading(progress))
            }
        } catch (e: Exception) {
            messages.markFailed(target, sendId, e)
            throw e
        }

        // We already have this file locally — no need to download it after the server echo.
        FileCache.shared.put(fileId, localFilePath)
        messages.setFileState(target, sendId, sendId, FileState.Downloaded(localFilePath))

        val attachments = listOf(SendAttachment.File(fileId))
        val content = if (text.isBlank()) {
            SendContent.Attachments(attachments)
        } else {
            SendContent.Composite(text, attachments)
        }
        return messages.send(target, content, sendId)
    }


    /** Downloads an attachment into [filesDir]. Progress goes to the message's [FileState]. */
    fun download(
        dialogId: String,
        messageLocalId: String,
        fileId: String,
        fileName: String,
        filesDir: File,
        fileSize: Long
    ): Cancellable {
        val target = MessageTarget.Dialog(dialogId)
        fun setState(state: FileState) = messages.setFileState(target, messageLocalId, fileId, state)

        val destFile = File(File(filesDir, DOWNLOADS_DIR).apply { mkdirs() }, "${fileId}_$fileName")
        val out = destFile.outputStream().buffered()
        var bytesReceived = 0L

        setState(FileState.Downloading(null))

        // SDK: download a file by id; chunks arrive in onChunk
        return client().download(DownloadRequest(fileId, 0L), object : DownloadListener {
            override fun onChunk(chunk: ByteArray) {
                out.write(chunk)
                bytesReceived += chunk.size
                setState(FileState.Downloading(percent(bytesReceived, fileSize)))
            }

            override fun onCompleted(result: DownloadResult) {
                out.close()
                FileCache.shared.put(fileId, destFile.absolutePath)
                setState(FileState.Downloaded(destFile.absolutePath))
            }

            override fun onError(error: ChatError) {
                out.close()
                destFile.delete()
                setState(FileState.Error(error.message ?: "Download failed"))
            }
        })
    }


    /** Returns the uploaded file id. */
    private suspend fun upload(
        request: UploadRequest,
        onProgress: (percent: Int?) -> Unit
    ): String = suspendCancellableCoroutine { cont ->
        // SDK: upload a file
        val task = client().upload(request, object : UploadListener {
            override fun onCreated(uploadId: String) {
                // `uploadId` can be passed as `UploadRequest.resumeId` to resume an interrupted upload.
            }

            override fun onProgress(uploaded: Long, total: Long?) {
                onProgress(percent(uploaded, total))
            }

            override fun onCompleted(result: UploadResult) {
                cont.resume(result.file.id)
            }

            override fun onError(error: ChatError) {
                cont.resumeWithException(error)
            }
        })
        cont.invokeOnCancellation { task.cancel() }
    }


    private fun percent(done: Long, total: Long?): Int? =
        if (total != null && total > 0) ((done * 100) / total).toInt().coerceIn(0, 100) else null


    companion object {
        private const val DOWNLOADS_DIR = "downloads"
    }
}
