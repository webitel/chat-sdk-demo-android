package com.webitel.chat.sdk.demo_android.ui.dialog

import android.content.DialogInterface
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
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
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.color.MaterialColors
import com.webitel.chat.sdk.ChatButtonAction
import com.webitel.chat.sdk.ChatKeyboard
import com.webitel.chat.sdk.MessageAttachment
import com.webitel.chat.sdk.MessageContent
import com.webitel.chat.sdk.demo_android.R
import com.webitel.chat.sdk.demo_android.ui.common.colorFromId
import com.webitel.chat.sdk.demo_android.ui.common.formatTime
import com.webitel.chat.sdk.demo_android.ui.common.initials
import com.webitel.chat.sdk.demo_android.ui.common.isColorDark
import java.io.File


class MessagesAdapter(
    private val onDownloadClick: (messageLocalId: String, fileId: String, fileName: String, fileSize: Long) -> Unit,
    private val onFileOpen: (localPath: String, mimeType: String) -> Unit,
    private val onButtonClick: (messageId: String?, buttonId: String, action: ChatButtonAction) -> Unit,
    private val onReactionClick: (message: MessageUI, emoji: String) -> Unit,
    private val onMessageLongClick: (message: MessageUI) -> Unit,
    private val onLocationClick: (MessageContent.Location) -> Unit,
    private val onContactClick: (MessageContent.Contact) -> Unit
) : ListAdapter<MessageUI, MessagesAdapter.BaseVH>(Diff) {

    companion object {
        private const val TYPE_MINE = 1
        private const val TYPE_THEIRS = 2
        private const val TYPE_SYSTEM = 3
        private val READ_COLOR = Color.parseColor("#17AAF0")
    }

    override fun getItemViewType(position: Int): Int {
        val message = getItem(position)
        return when {
            // System events (deleted ones too) are a centered line, not a bubble
            message.content is MessageContent.System && !message.isDeleted -> TYPE_SYSTEM
            message.isOutgoing -> TYPE_MINE
            else -> TYPE_THEIRS
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BaseVH {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_SYSTEM -> SystemVH(inflater.inflate(R.layout.item_message_system, parent, false))
            TYPE_MINE -> MineVH(inflater.inflate(R.layout.item_message_mine, parent, false))
            else -> TheirsVH(inflater.inflate(R.layout.item_message_theirs, parent, false))
        }
    }

    override fun onBindViewHolder(holder: BaseVH, position: Int) {
        holder.bind(getItem(position))
    }

    /** Payload = only download/upload progress changed: update numbers without re-binding. */
    @Suppress("UNCHECKED_CAST")
    override fun onBindViewHolder(holder: BaseVH, position: Int, payloads: MutableList<Any>) {
        val fileStates = payloads.lastOrNull() as? Map<String, FileState>
        if (fileStates != null && holder is MessageVH) holder.updateProgress(fileStates)
        else holder.bind(getItem(position))
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
                tvProgress.text = progressText(state.progress)
                cell.setOnClickListener(null)
            }
            is FileState.Downloading -> {
                Glide.with(cell.context).clear(imageView)
                progressOverlay.isVisible = true
                tvProgress.text = progressText(state.progress)
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
            is FileState.Downloading, is FileState.Uploading -> {
                btnDownload.isGone = true
                progressContainer.isVisible = true
                bindProgress(downloadProgress, tvProgressPercent, state.progress)
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
                bindProgress(downloadProgress, tvProgressPercent, state.progress)
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

    abstract inner class BaseVH(view: View) : RecyclerView.ViewHolder(view) {
        abstract fun bind(msg: MessageUI)
    }


    /**
     * SDK: `MessageContent.System` — chat events (member joined / left, Flow events).
     * Shown as a centered line: no bubble, menu, reactions or status.
     */
    inner class SystemVH(view: View) : BaseVH(view) {
        private val text: TextView = view.findViewById(R.id.systemText)

        override fun bind(msg: MessageUI) {
            val system = msg.content as MessageContent.System
            text.text = "${system.icon} ${system.text} · ${formatTime(msg.createdAt)}"
        }
    }


    /** Common part of outgoing and incoming bubbles: text, attachments, keyboard, time. */
    abstract inner class MessageVH(view: View) : BaseVH(view) {
        private val messageContainer: LinearLayout = view.findViewById(R.id.messageContainer)
        private val tv: TextView = view.findViewById(R.id.textMessage)
        protected val time: TextView = view.findViewById(R.id.time)
        private val attachmentContainer: LinearLayout = view.findViewById(R.id.attachmentContainer)
        private val replyMarkupContainer: LinearLayout = view.findViewById(R.id.replyMarkupContainer)
        private val reactionsContainer: ChipGroup = view.findViewById(R.id.reactionsContainer)
        private val deliveryFailuresText: TextView = view.findViewById(R.id.deliveryFailuresText)
        private val replyQuote: View = view.findViewById(R.id.replyQuote)
        private val quoteAuthor: TextView = view.findViewById(R.id.quoteAuthor)
        private val quoteText: TextView = view.findViewById(R.id.quoteText)

        override fun bind(msg: MessageUI) {
            setContainerWidthExpanded(msg.hasImages())
            bindQuote(msg)
            bindContent(msg)
            bindReactions(msg)
            bindDeliveryFailures(msg)
            time.text = if (msg.isEdited) "edited · ${formatTime(msg.createdAt)}" else formatTime(msg.createdAt)

            // Long press opens the message menu. Only server messages that are not deleted have one.
            val hasMenu = msg.isSent && !msg.isDeleted
            messageContainer.setOnLongClickListener {
                if (hasMenu) onMessageLongClick(msg)
                hasMenu
            }
        }

        fun updateProgress(fileStates: Map<String, FileState>) =
            updateProgressValues(attachmentContainer, fileStates)

        /** Images need the wide (percent) bubble, everything else wraps its content. */
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

        private fun bindContent(msg: MessageUI) {
            replyMarkupContainer.isGone = true
            attachmentContainer.removeAllViews()

            // Tap on the bubble: only location and contact cards have an action
            messageContainer.setOnClickListener(null)
            messageContainer.isClickable = false

            // Deleted messages stay in the history: "Message deleted by <name>"
            if (msg.isDeleted) {
                val by = msg.deletion?.let { " by ${it.deletedBy.contact.name}" }.orEmpty()
                showText("🚫 Message deleted$by")
                return
            }

            // Outgoing file before the server echo: render from local file info
            if (msg.pendingAttachment != null) {
                val text = (msg.content as? MessageContent.Text)?.text
                    ?.takeIf { it != msg.pendingAttachment.fileName }
                showText(text)
                bindPendingAttachment(msg, msg.pendingAttachment, attachmentContainer)
                return
            }

            when (val content = msg.content) {
                is MessageContent.Text -> showText(content.text)
                is MessageContent.Attachments -> {
                    showText(null)
                    bindAttachments(msg, content.attachments, attachmentContainer)
                }
                is MessageContent.Composite -> {
                    showText(content.text)
                    bindAttachments(msg, content.attachments, attachmentContainer)
                    content.keyboard?.let { bindKeyboard(it, replyMarkupContainer, msg.messageId) }
                }
                // SDK: contact card — name, phone and email. Tap offers to call / write.
                is MessageContent.Contact -> {
                    showText(listOfNotNull("👤 ${content.name}", content.phone, content.email).joinToString("\n"))
                    messageContainer.setOnClickListener { onContactClick(content) }
                }
                // SDK: location — name, address and coordinates. Tap opens a maps app.
                is MessageContent.Location -> {
                    showText(listOf("📍 ${content.name}", content.address).filter { it.isNotBlank() }.joinToString("\n"))
                    messageContainer.setOnClickListener { onLocationClick(content) }
                }
                // Not a bubble (see SystemVH); reached only for a deleted system message
                is MessageContent.System -> showText(content.text)
                is MessageContent.KeyboardOnly -> {
                    showText(null)
                    bindKeyboard(content.keyboard, replyMarkupContainer, msg.messageId)
                }
            }
        }

        /** "Not delivered to <member>: <code> <message>" for each member the message did not reach. */
        private fun bindDeliveryFailures(msg: MessageUI) {
            val failures = if (msg.isDeleted) emptyList() else msg.deliveryFailures
            deliveryFailuresText.isVisible = failures.isNotEmpty()
            deliveryFailuresText.text = failures.joinToString("\n") {
                "⚠️ Not delivered to ${it.member.contact.name}: ${it.error.code} ${it.error.message.orEmpty()}"
            }
        }

        /** Quote of the original message: author + short content, or "Message deleted". */
        private fun bindQuote(msg: MessageUI) {
            val reply = msg.reply
            replyQuote.isVisible = reply != null && !msg.isDeleted
            if (reply == null) return

            quoteAuthor.text = reply.authorName
            quoteText.text = if (reply.isDeleted) "Message deleted" else reply.text
            quoteText.setTypeface(null, if (reply.isDeleted) Typeface.ITALIC else Typeface.NORMAL)
        }

        /** "👍 2" chip per emoji. Mine has an outline; tap toggles it. */
        private fun bindReactions(msg: MessageUI) {
            reactionsContainer.removeAllViews()
            reactionsContainer.isVisible = msg.reactions.isNotEmpty() && !msg.isDeleted
            if (msg.isDeleted) return

            val density = reactionsContainer.resources.displayMetrics.density
            msg.reactions.forEach { reaction ->
                val chip = Chip(reactionsContainer.context).apply {
                    text = "${reaction.emoji} ${reaction.count}"
                    setEnsureMinTouchTargetSize(false)
                    chipMinHeight = 28 * density
                    chipStrokeWidth = if (reaction.reactedByMe) 2 * density else 0f
                    chipStrokeColor = ColorStateList.valueOf(
                        MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary)
                    )
                    setOnClickListener { onReactionClick(msg, reaction.emoji) }
                }
                reactionsContainer.addView(chip)
            }
        }

        private fun showText(text: String?) {
            tv.isVisible = !text.isNullOrEmpty()
            tv.text = text.orEmpty()
        }
    }


    /** Outgoing: shows sending / error, then ✓ delivered and ✓✓ read. */
    inner class MineVH(view: View) : MessageVH(view) {
        private val deliveryStatus: TextView = view.findViewById(R.id.deliveryStatus)
        private val errorImg: ImageView = view.findViewById(R.id.warningImageView)
        private val errorText: TextView = view.findViewById(R.id.errorText)
        private val sendingProgress: ProgressBar = view.findViewById(R.id.sendProgressBar)

        override fun bind(msg: MessageUI) {
            super.bind(msg)
            val failed = !msg.isSent && !msg.error.isNullOrEmpty()
            val sending = !msg.isSent && !failed

            deliveryStatus.isVisible = msg.isSent && !msg.isDeleted
            deliveryStatus.text = when (msg.deliveryStatus) {
                DeliveryStatus.DELIVERED -> "✓"
                DeliveryStatus.READ -> "✓✓"
                DeliveryStatus.SENT, null -> ""
            }
            deliveryStatus.setTextColor(
                if (msg.deliveryStatus == DeliveryStatus.READ) READ_COLOR else Color.GRAY
            )
            time.isVisible = msg.isSent
            sendingProgress.isVisible = sending
            errorImg.isVisible = failed
            errorText.isVisible = failed
            errorText.text = msg.error.orEmpty()
        }
    }


    /** Incoming: shows sender avatar and name. */
    inner class TheirsVH(view: View) : MessageVH(view) {
        private val avatarCard: MaterialCardView = view.findViewById(R.id.avatarCard)
        private val avatarText: TextView = view.findViewById(R.id.avatarText)
        private val author: TextView = view.findViewById(R.id.author)

        override fun bind(msg: MessageUI) {
            super.bind(msg)
            author.text = msg.senderName
            avatarText.text = initials(msg.senderName)

            val bg = colorFromId(msg.senderName)
            avatarCard.setCardBackgroundColor(bg)
            avatarText.setTextColor(if (isColorDark(bg)) Color.WHITE else Color.BLACK)
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

            val progress = state.progress ?: return

            // Image cell: has R.id.tvProgress inside a progressOverlay
            val tvProgress = view.findViewById<TextView>(R.id.tvProgress)
            if (tvProgress != null) {
                tvProgress.text = progressText(progress)
                return
            }

            // File row: has R.id.downloadProgress
            val bar = view.findViewById<ProgressBar>(R.id.downloadProgress) ?: return
            val percent = view.findViewById<TextView>(R.id.tvProgressPercent) ?: return
            bindProgress(bar, percent, progress)
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

    /** Upload / download progress in percent, or null if not in progress or the size is unknown. */
    private val FileState.progress: Int?
        get() = when (this) {
            is FileState.Downloading -> progress
            is FileState.Uploading -> progress
            else -> null
        }

    private fun progressText(progress: Int?) = if (progress != null) "$progress%" else "…"

    private fun bindProgress(bar: ProgressBar, percent: TextView, progress: Int?) {
        bar.isIndeterminate = progress == null
        percent.isVisible = progress != null
        if (progress != null) {
            bar.progress = progress
            percent.text = progressText(progress)
        }
    }

    /**
     * Icon for built-in system event types. The list is not exhaustive:
     * the backend may send custom Flow events — they get a generic icon and their `text`.
     */
    private val MessageContent.System.icon: String
        get() = when (type) {
            "member_added" -> "👤➕"
            "member_removed" -> "👤➖"
            else -> "ℹ️"
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
