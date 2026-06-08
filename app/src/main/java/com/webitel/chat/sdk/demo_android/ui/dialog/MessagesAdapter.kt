package com.webitel.chat.sdk.demo_android.ui.dialog

import android.annotation.SuppressLint
import android.content.DialogInterface
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.webitel.chat.sdk.ChatButtonAction
import com.webitel.chat.sdk.ChatKeyboard
import com.webitel.chat.sdk.MessageAttachment
import com.webitel.chat.sdk.MessageContent
import com.webitel.chat.sdk.demo_android.R
import com.webitel.chat.sdk.demo_android.ui.common.colorFromId
import com.webitel.chat.sdk.demo_android.ui.common.formatTime
import com.webitel.chat.sdk.demo_android.ui.common.isColorDark
import java.io.File


class MessagesAdapter(
    private val onDownloadClick: (messageLocalId: String, fileId: String, fileName: String, fileSize: Long) -> Unit,
    private val onFileOpen: (localPath: String, mimeType: String) -> Unit,
    private val onButtonClick: (messageId: String?, buttonId: String, action: ChatButtonAction) -> Unit
) : ListAdapter<MessageUI, RecyclerView.ViewHolder>(Diff) {

    companion object {
        private const val TYPE_MINE = 1
        private const val TYPE_THEIRS = 2
    }

    override fun getItemViewType(position: Int): Int =
        if (getItem(position).isOutgoing) TYPE_MINE else TYPE_THEIRS

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_MINE) {
            MineVH(inflater.inflate(R.layout.item_message_mine, parent, false))
        } else {
            TheirsVH(inflater.inflate(R.layout.item_message_theirs, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val msg = getItem(position)
        when (holder) {
            is MineVH -> holder.bind(msg)
            is TheirsVH -> holder.bind(msg)
        }
    }

    @Suppress("UNCHECKED_CAST")
    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isEmpty()) { super.onBindViewHolder(holder, position, payloads); return }
        val fileStates = payloads.filterIsInstance<Map<String, FileState>>().lastOrNull()
            ?: run { super.onBindViewHolder(holder, position, payloads); return }
        val msg = getItem(position)
        when (holder) {
            is MineVH -> holder.updateProgress(msg, fileStates)
            is TheirsVH -> holder.updateProgress(msg, fileStates)
        }
    }

    // ─── Image grid ──────────────────────────────────────────────────────────

    /**
     * Groups [cells] into rows of two and inflates [R.layout.item_image_cell] for each.
     * A lone image in a row takes full width at 200 dp; pairs share the row at 160 dp each.
     */
    private fun bindImageGrid(
        msg: MessageUI,
        cells: List<ImageCellData>,
        container: LinearLayout
    ) {
        val inflater = LayoutInflater.from(container.context)
        val density = container.resources.displayMetrics.density
        val gap = (2 * density).toInt()

        cells.chunked(2).forEach { pair ->
            val row = LinearLayout(container.context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            val cellHeightDp = if (pair.size == 1) 200 else 160
            val cellHeightPx = (cellHeightDp * density).toInt()

            pair.forEach { cellData ->
                val cell = inflater.inflate(R.layout.item_image_cell, row, false)
                cell.layoutParams = LinearLayout.LayoutParams(0, cellHeightPx, 1f).apply {
                    setMargins(gap, gap, gap, gap)
                }
                bindImageCell(cell, msg, cellData)
                row.addView(cell)
            }
            container.addView(row)
        }
    }

    private fun bindImageCell(cell: View, msg: MessageUI, data: ImageCellData) {
        cell.tag = AttachmentTag(data.fileId, data.mimeType, data.fileSize)
        val imageView = cell.findViewById<ImageView>(R.id.imagePreview)
        val progressOverlay = cell.findViewById<View>(R.id.progressOverlay)
        val tvProgress = cell.findViewById<TextView>(R.id.tvProgress)

        when (val state = data.state) {
            is FileState.Downloaded -> {
                Glide.with(cell.context).load(File(state.localPath)).into(imageView)
                progressOverlay.isGone = true
                cell.setOnClickListener { onFileOpen(state.localPath, data.mimeType) }
            }
            is FileState.Uploading -> {
                data.localPreviewPath?.let { Glide.with(cell.context).load(File(it)).into(imageView) }
                    ?: Glide.with(cell.context).clear(imageView)
                progressOverlay.isVisible = true
                tvProgress.text = if (state.progress != null) "${state.progress}%" else "…"
                cell.setOnClickListener(null)
            }
            is FileState.Downloading -> {
                Glide.with(cell.context).clear(imageView)
                progressOverlay.isVisible = true
                tvProgress.text = if (state.progress != null) "${state.progress}%" else "…"
            }
            is FileState.Idle -> {
                Glide.with(cell.context).clear(imageView)
                progressOverlay.isVisible = true
                tvProgress.text = "⬇"
                cell.setOnClickListener {
                    onDownloadClick(msg.localId, data.fileId, data.fileName, data.fileSize)
                }
            }
            is FileState.Error -> {
                Glide.with(cell.context).clear(imageView)
                progressOverlay.isVisible = true
                tvProgress.text = "⚠"
                cell.setOnClickListener {
                    onDownloadClick(msg.localId, data.fileId, data.fileName, data.fileSize)
                }
            }
        }
    }

    // ─── File rows ────────────────────────────────────────────────────────────

    private fun bindFileRow(
        msg: MessageUI,
        attachment: MessageAttachment,
        container: LinearLayout
    ) {
        val row = LayoutInflater.from(container.context)
            .inflate(R.layout.item_attachment, container, false)
        row.tag = AttachmentTag(attachment.fileId, attachment.mimeType, attachment.size)

        val tvFileName = row.findViewById<TextView>(R.id.tvFileName)
        val progressContainer = row.findViewById<LinearLayout>(R.id.progressContainer)
        val downloadProgress = row.findViewById<ProgressBar>(R.id.downloadProgress)
        val tvProgressPercent = row.findViewById<TextView>(R.id.tvProgressPercent)
        val btnDownload = row.findViewById<ImageButton>(R.id.btnDownload)

        tvFileName.text = attachment.fileName

        when (val state = msg.fileStates[attachment.fileId] ?: FileState.Idle) {
            is FileState.Idle -> {
                progressContainer.isGone = true
                btnDownload.isVisible = true
                btnDownload.setOnClickListener {
                    onDownloadClick(msg.localId, attachment.fileId, attachment.fileName, attachment.size)
                }
            }
            is FileState.Downloading -> {
                btnDownload.isGone = true
                progressContainer.isVisible = true
                downloadProgress.isIndeterminate = state.progress == null
                if (state.progress != null) {
                    downloadProgress.progress = state.progress
                    tvProgressPercent.isVisible = true
                    tvProgressPercent.text = "${state.progress}%"
                } else {
                    tvProgressPercent.isGone = true
                }
            }
            is FileState.Uploading -> {
                btnDownload.isGone = true
                progressContainer.isVisible = true
                downloadProgress.isIndeterminate = state.progress == null
                if (state.progress != null) {
                    downloadProgress.progress = state.progress
                    tvProgressPercent.isVisible = true
                    tvProgressPercent.text = "${state.progress}%"
                } else {
                    tvProgressPercent.isGone = true
                }
            }
            is FileState.Downloaded -> {
                progressContainer.isGone = true
                btnDownload.isGone = true
                row.setOnClickListener { onFileOpen(state.localPath, attachment.mimeType) }
            }
            is FileState.Error -> {
                tvFileName.text = "${attachment.fileName} ⚠"
                progressContainer.isGone = true
                btnDownload.isVisible = true
                btnDownload.setOnClickListener {
                    onDownloadClick(msg.localId, attachment.fileId, attachment.fileName, attachment.size)
                }
            }
        }

        container.addView(row)
    }

    /**
     * Renders a non-image pending attachment row (upload in progress or done).
     */
    private fun bindPendingFileRow(
        msg: MessageUI,
        pending: PendingAttachment,
        container: LinearLayout
    ) {
        val row = LayoutInflater.from(container.context)
            .inflate(R.layout.item_attachment, container, false)
        row.tag = AttachmentTag(pending.tempId, pending.mimeType, 0L)

        val tvFileName = row.findViewById<TextView>(R.id.tvFileName)
        val progressContainer = row.findViewById<LinearLayout>(R.id.progressContainer)
        val downloadProgress = row.findViewById<ProgressBar>(R.id.downloadProgress)
        val tvProgressPercent = row.findViewById<TextView>(R.id.tvProgressPercent)
        val btnDownload = row.findViewById<ImageButton>(R.id.btnDownload)

        tvFileName.text = pending.fileName
        btnDownload.isGone = true

        when (val state = msg.fileStates[pending.tempId] ?: FileState.Uploading(null)) {
            is FileState.Uploading -> {
                progressContainer.isVisible = true
                downloadProgress.isIndeterminate = state.progress == null
                if (state.progress != null) {
                    downloadProgress.progress = state.progress
                    tvProgressPercent.isVisible = true
                    tvProgressPercent.text = "${state.progress}%"
                } else {
                    tvProgressPercent.isGone = true
                }
            }
            is FileState.Downloaded -> {
                progressContainer.isGone = true
                row.setOnClickListener { onFileOpen(pending.localPath, pending.mimeType) }
            }
            else -> {
                progressContainer.isGone = true
            }
        }

        container.addView(row)
    }

    // ─── bindAttachments / bindPendingAttachment ──────────────────────────────

    /**
     * Splits [attachments] into images (shown as a grid) and other files (shown as rows).
     */
    private fun bindAttachments(
        msg: MessageUI,
        attachments: List<MessageAttachment>,
        container: LinearLayout
    ) {
        container.removeAllViews()

        val images = attachments.filter { it.isImage }
        val files = attachments.filterNot { it.isImage }

        // Image grid needs match_parent so weight-based cells can fill the 80%-wide bubble.
        // File-only rows are wrap_content — breaking the circular match_parent chain that
        // would otherwise collapse the container to 0 width in wrap-mode messageContainer.
        container.layoutParams = LinearLayout.LayoutParams(
            if (images.isNotEmpty()) LinearLayout.LayoutParams.MATCH_PARENT
            else LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )

        if (images.isNotEmpty()) {
            val cells = images.map { a ->
                val state = msg.fileStates[a.fileId] ?: FileState.Idle
                ImageCellData(
                    fileId = a.fileId,
                    fileName = a.fileName,
                    mimeType = a.mimeType,
                    fileSize = a.size,
                    localPreviewPath = (state as? FileState.Downloaded)?.localPath,
                    state = state
                )
            }
            bindImageGrid(msg, cells, container)
        }

        files.forEach { bindFileRow(msg, it, container) }
    }

    /**
     * Renders an outgoing attachment before the server echo has arrived.
     * Images are shown inline; other file types use the standard file row.
     */
    private fun bindPendingAttachment(
        msg: MessageUI,
        pending: PendingAttachment,
        container: LinearLayout
    ) {
        container.removeAllViews()
        val isImage = pending.mimeType.startsWith("image/")
        container.layoutParams = LinearLayout.LayoutParams(
            if (isImage) LinearLayout.LayoutParams.MATCH_PARENT
            else LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        if (isImage) {
            val state = msg.fileStates[pending.tempId] ?: FileState.Uploading(null)
            val cell = ImageCellData(
                fileId = pending.tempId,
                fileName = pending.fileName,
                mimeType = pending.mimeType,
                fileSize = 0L,
                localPreviewPath = pending.localPath,
                state = state
            )
            bindImageGrid(msg, listOf(cell), container)
        } else {
            bindPendingFileRow(msg, pending, container)
        }
    }

    // ─── Keyboard ─────────────────────────────────────────────────────────────

    private fun bindKeyboard(
        keyboard: ChatKeyboard,
        container: LinearLayout,
        messageId: String?
    ) {
        container.removeAllViews()
        container.visibility = View.VISIBLE

        val ctx = container.context
        val dp4 = (4 * ctx.resources.displayMetrics.density).toInt()
        val dp8 = dp4 * 2

        when (keyboard) {
            is ChatKeyboard.Buttons -> {
                keyboard.rows.forEach { row ->
                    val rowLayout = LinearLayout(ctx).apply {
                        orientation = LinearLayout.HORIZONTAL
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).also { it.setMargins(dp8, 0, dp8, dp4) }
                    }
                    row.buttons.forEach { btn ->
                        val isDanger = btn.metadata?.get("danger") == true
                        val styleAttr = if (isDanger)
                            com.google.android.material.R.attr.materialButtonStyle
                        else
                            com.google.android.material.R.attr.materialButtonOutlinedStyle

                        val btnView = MaterialButton(ctx, null, styleAttr).apply {
                            text = btn.label
                            isAllCaps = false
                            layoutParams = LinearLayout.LayoutParams(
                                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                            ).also { it.setMargins(dp4, 0, dp4, 0) }
                            setOnClickListener { onButtonClick(messageId, btn.id, btn.action) }
                        }
                        rowLayout.addView(btnView)
                    }
                    container.addView(rowLayout)
                }
            }

            is ChatKeyboard.ListMenu -> {
                val triggerLabel = keyboard.title
                val allButtons = keyboard.sections.flatMap { it.buttons }
                val labels = keyboard.sections.flatMap { section ->
                    section.buttons.map { btn ->
                        if (section.title.isNotBlank()) "[${section.title}]  ${btn.label}" else btn.label
                    }
                }.toTypedArray()

                val triggerBtn = MaterialButton(
                    ctx, null,
                    com.google.android.material.R.attr.materialButtonOutlinedStyle
                ).apply {
                    text = triggerLabel
                    isAllCaps = false
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).also { it.setMargins(dp8, 0, dp8, dp4) }
                    setOnClickListener { view ->
                        AlertDialog.Builder(view.context)
                            .setTitle(keyboard.title.ifBlank { null })
                            .setItems(labels) { _: DialogInterface, index: Int ->
                                val btn = allButtons[index]
                                onButtonClick(messageId, btn.id, btn.action)
                            }
                            .show()
                    }
                }
                container.addView(triggerBtn)
            }
        }
    }

    // ─── ViewHolders ──────────────────────────────────────────────────────────

    inner class MineVH(view: View) : RecyclerView.ViewHolder(view) {
        private val messageContainer: LinearLayout = view.findViewById(R.id.messageContainer)
        private val tv: TextView = view.findViewById(R.id.textMessage)
        private val time: TextView = view.findViewById(R.id.time)
        private val sentImg: ImageView = view.findViewById(R.id.sentImageView)
        private val errorImg: ImageView = view.findViewById(R.id.warningImageView)
        private val errorText: TextView = view.findViewById(R.id.errorText)
        private val sendingProgress: ProgressBar = view.findViewById(R.id.sendProgressBar)
        private val attachmentContainer: LinearLayout = view.findViewById(R.id.attachmentContainer)
        private val replyMarkupContainer: LinearLayout = view.findViewById(R.id.replyMarkupContainer)

        fun bind(msg: MessageUI) {
            setContainerWidthExpanded(msg.hasImages())
            bindContent(msg)
            time.text = formatTime(msg.createdAt)
            when {
                msg.isSent -> showSent()
                !msg.error.isNullOrEmpty() -> showError(msg.error)
                else -> showSending()
            }
        }

        private fun setContainerWidthExpanded(expanded: Boolean) {
            val p = messageContainer.layoutParams as ConstraintLayout.LayoutParams
            if (expanded) {
                p.matchConstraintDefaultWidth = ConstraintLayout.LayoutParams.MATCH_CONSTRAINT_PERCENT
                p.matchConstraintMaxWidth = 0
            } else {
                p.matchConstraintDefaultWidth = 0
                p.matchConstraintMaxWidth = ConstraintLayout.LayoutParams.WRAP_CONTENT
            }
            messageContainer.layoutParams = p
        }

        @SuppressLint("SetTextI18n")
        private fun bindContent(msg: MessageUI) {
            // Outgoing before server echo: render from pending attachment info
            if (msg.pendingAttachment != null) {
                val pendingText = (msg.content as? MessageContent.Text)?.text
                    ?.takeIf { it != msg.pendingAttachment.fileName }
                tv.isVisible = !pendingText.isNullOrBlank()
                if (!pendingText.isNullOrBlank()) tv.text = pendingText
                bindPendingAttachment(msg, msg.pendingAttachment, attachmentContainer)
                replyMarkupContainer.visibility = View.GONE
                return
            }

            attachmentContainer.removeAllViews()
            when (val content = msg.content) {
                is MessageContent.Text -> {
                    tv.isVisible = true
                    tv.text = content.text
                    replyMarkupContainer.visibility = View.GONE
                }
                is MessageContent.Attachments -> {
                    tv.isGone = true
                    bindAttachments(msg, content.attachments, attachmentContainer)
                    replyMarkupContainer.visibility = View.GONE
                }
                is MessageContent.Composite -> {
                    tv.isVisible = !content.text.isNullOrEmpty()
                    tv.text = content.text.orEmpty()
                    bindAttachments(msg, content.attachments, attachmentContainer)
                    val kb = content.keyboard
                    if (kb != null) bindKeyboard(kb, replyMarkupContainer, msg.messageId)
                    else replyMarkupContainer.visibility = View.GONE
                }
                is MessageContent.Contact -> {
                    tv.isVisible = true
                    tv.text = "Contact: ${content.name}"
                    replyMarkupContainer.visibility = View.GONE
                }
                is MessageContent.Location -> {
                    tv.isVisible = true
                    tv.text = "Location: ${content.name}"
                    replyMarkupContainer.visibility = View.GONE
                }
                is MessageContent.System -> {
                    tv.isVisible = true
                    tv.text = "System: ${content.text}"
                    replyMarkupContainer.visibility = View.GONE
                }
                is MessageContent.KeyboardOnly -> {
                    tv.isGone = true
                    bindKeyboard(content.keyboard, replyMarkupContainer, msg.messageId)
                }
            }
        }

        fun updateProgress(msg: MessageUI, fileStates: Map<String, FileState>) =
            updateProgressValues(attachmentContainer, fileStates)

        private fun showSent() {
            sentImg.isVisible = true
            sendingProgress.isGone = true
            errorImg.isGone = true
            errorText.isGone = true
            time.isVisible = true
        }

        private fun showSending() {
            sendingProgress.isVisible = true
            sentImg.isGone = true
            errorImg.isGone = true
            time.isGone = true
        }

        private fun showError(error: String?) {
            errorImg.isVisible = true
            errorText.isVisible = true
            errorText.text = error.orEmpty()
            sentImg.isGone = true
            sendingProgress.isGone = true
            time.isGone = true
        }
    }


    inner class TheirsVH(view: View) : RecyclerView.ViewHolder(view) {
        private val messageContainer: LinearLayout = view.findViewById(R.id.messageContainer)
        private val avatarCard: MaterialCardView = view.findViewById(R.id.avatarCard)
        private val avatarText: TextView = view.findViewById(R.id.avatarText)
        private val author: TextView = view.findViewById(R.id.author)
        private val time: TextView = view.findViewById(R.id.time)
        private val tv: TextView = view.findViewById(R.id.textMessage)
        private val attachmentContainer: LinearLayout = view.findViewById(R.id.attachmentContainer)
        private val replyMarkupContainer: LinearLayout = view.findViewById(R.id.replyMarkupContainer)

        fun bind(msg: MessageUI) {
            setContainerWidthExpanded(msg.hasImages())
            bindContent(msg)

            val initials = msg.senderName
                .split(" ")
                .take(2)
                .mapNotNull { it.firstOrNull()?.uppercase() }
                .joinToString("")

            avatarText.text = initials
            author.text = msg.senderName
            time.text = formatTime(msg.createdAt)

            val bg = colorFromId(msg.senderName)
            avatarCard.setCardBackgroundColor(bg)
            avatarText.setTextColor(if (isColorDark(bg)) Color.WHITE else Color.BLACK)
        }

        private fun setContainerWidthExpanded(expanded: Boolean) {
            val p = messageContainer.layoutParams as ConstraintLayout.LayoutParams
            if (expanded) {
                p.matchConstraintDefaultWidth = ConstraintLayout.LayoutParams.MATCH_CONSTRAINT_PERCENT
                p.matchConstraintMaxWidth = 0
            } else {
                p.matchConstraintDefaultWidth = 0
                p.matchConstraintMaxWidth = ConstraintLayout.LayoutParams.WRAP_CONTENT
            }
            messageContainer.layoutParams = p
        }

        fun updateProgress(msg: MessageUI, fileStates: Map<String, FileState>) =
            updateProgressValues(attachmentContainer, fileStates)

        @SuppressLint("SetTextI18n")
        private fun bindContent(msg: MessageUI) {
            attachmentContainer.removeAllViews()
            when (val content = msg.content) {
                is MessageContent.Text -> {
                    tv.isVisible = true
                    tv.text = content.text
                    replyMarkupContainer.visibility = View.GONE
                }
                is MessageContent.Attachments -> {
                    tv.isGone = true
                    bindAttachments(msg, content.attachments, attachmentContainer)
                    replyMarkupContainer.visibility = View.GONE
                }
                is MessageContent.Composite -> {
                    tv.isVisible = !content.text.isNullOrEmpty()
                    tv.text = content.text.orEmpty()
                    bindAttachments(msg, content.attachments, attachmentContainer)
                    val kb = content.keyboard
                    if (kb != null) bindKeyboard(kb, replyMarkupContainer, msg.messageId)
                    else replyMarkupContainer.visibility = View.GONE
                }
                is MessageContent.Contact -> {
                    tv.isVisible = true
                    tv.text = "Contact: ${content.name}"
                    replyMarkupContainer.visibility = View.GONE
                }
                is MessageContent.Location -> {
                    tv.isVisible = true
                    tv.text = "Location: ${content.name}"
                    replyMarkupContainer.visibility = View.GONE
                }
                is MessageContent.System -> {
                    tv.isVisible = true
                    tv.text = "System: ${content.text}"
                    replyMarkupContainer.visibility = View.GONE
                }
                is MessageContent.KeyboardOnly -> {
                    tv.isGone = true
                    bindKeyboard(content.keyboard, replyMarkupContainer, msg.messageId)
                }
            }
        }
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private fun MessageUI.hasImages(): Boolean {
        if (pendingAttachment?.mimeType?.startsWith("image/") == true) return true
        return when (val c = content) {
            is MessageContent.Attachments -> c.attachments.any { it.isImage }
            is MessageContent.Composite -> c.attachments.any { it.isImage }
            else -> false
        }
    }

    /**
     * Walks [container] (and one level of nested image-pair rows) looking for views
     * tagged with [AttachmentTag]. For each match that is currently in a progress state,
     * updates only the numeric progress — without removing/recreating views.
     */
    private fun updateProgressValues(container: LinearLayout, fileStates: Map<String, FileState>) {
        fun update(view: View) {
            val tag = view.tag as? AttachmentTag ?: return
            val state = fileStates[tag.fileId] ?: return

            // Image cell: has R.id.tvProgress inside a progressOverlay
            val tvProgress = view.findViewById<TextView>(R.id.tvProgress)
            if (tvProgress != null) {
                when (state) {
                    is FileState.Downloading -> tvProgress.text = if (state.progress != null) "${state.progress}%" else "…"
                    is FileState.Uploading -> tvProgress.text = if (state.progress != null) "${state.progress}%" else "…"
                    else -> {}
                }
                return
            }

            // File row: has R.id.downloadProgress
            val dp = view.findViewById<ProgressBar>(R.id.downloadProgress) ?: return
            val tv = view.findViewById<TextView>(R.id.tvProgressPercent) ?: return
            when (state) {
                is FileState.Downloading -> {
                    dp.isIndeterminate = state.progress == null
                    if (state.progress != null) { dp.progress = state.progress; tv.isVisible = true; tv.text = "${state.progress}%" }
                }
                is FileState.Uploading -> {
                    dp.isIndeterminate = state.progress == null
                    if (state.progress != null) { dp.progress = state.progress; tv.isVisible = true; tv.text = "${state.progress}%" }
                }
                else -> {}
            }
        }

        for (i in 0 until container.childCount) {
            val child = container.getChildAt(i)
            if (child.tag is AttachmentTag) {
                update(child)
            } else if (child is LinearLayout) {
                // image-pair row wrapper has no tag — walk its children
                for (j in 0 until child.childCount) update(child.getChildAt(j))
            }
        }
    }

    private data class AttachmentTag(val fileId: String, val mimeType: String, val fileSize: Long)

    private data class ImageCellData(
        val fileId: String,
        val fileName: String,
        val mimeType: String,
        val fileSize: Long,
        val localPreviewPath: String?,
        val state: FileState
    )

    object Diff : DiffUtil.ItemCallback<MessageUI>() {
        override fun areItemsTheSame(a: MessageUI, b: MessageUI) = a.localId == b.localId
        override fun areContentsTheSame(a: MessageUI, b: MessageUI) = a == b

        override fun getChangePayload(oldItem: MessageUI, newItem: MessageUI): Any? {
            // Only file states differ — check if the state TYPES are identical (just progress numbers changed)
            if (oldItem.copy(fileStates = newItem.fileStates) != newItem) return null
            val onlyProgress = oldItem.fileStates.all { (k, v) ->
                val n = newItem.fileStates[k]
                (v is FileState.Downloading && n is FileState.Downloading) ||
                (v is FileState.Uploading   && n is FileState.Uploading)   ||
                v == n
            }
            return if (onlyProgress) newItem.fileStates else null
        }
    }
}
