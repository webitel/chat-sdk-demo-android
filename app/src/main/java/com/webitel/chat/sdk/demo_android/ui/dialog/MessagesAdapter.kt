package com.webitel.chat.sdk.demo_android.ui.dialog

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.webitel.chat.sdk.demo_android.R
import com.webitel.chat.sdk.demo_android.ui.common.colorFromId
import com.webitel.chat.sdk.demo_android.ui.common.formatTime
import com.webitel.chat.sdk.demo_android.ui.common.isColorDark


class MessagesAdapter() : ListAdapter<MessageUI, RecyclerView.ViewHolder>(Diff) {

    companion object {
        private const val TYPE_MINE = 1
        private const val TYPE_THEIRS = 2
    }

    override fun getItemViewType(position: Int): Int {
        val msg = getItem(position)
        return if (msg.isOutgoing) TYPE_MINE else TYPE_THEIRS
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)

        return if (viewType == TYPE_MINE) {
            val v = inflater.inflate(R.layout.item_message_mine, parent, false)
            MineVH(v)
        } else {
            val v = inflater.inflate(R.layout.item_message_theirs, parent, false)
            TheirsVH(v)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val msg = getItem(position)
        when (holder) {
            is MineVH -> holder.bind(msg)
            is TheirsVH -> holder.bind(msg)
        }
    }

    class MineVH(view: View) : RecyclerView.ViewHolder(view) {
        private val tv = view.findViewById<TextView>(R.id.textMessage)
        private val time: TextView = view.findViewById(R.id.time)
        private val sentImg: ImageView = itemView.findViewById(R.id.sentImageView)
        private val errorImg: ImageView = itemView.findViewById(R.id.warningImageView)
        private val errorText: TextView = itemView.findViewById(R.id.errorText)
        private val sendingProgress: ProgressBar = itemView.findViewById(R.id.sendProgressBar)

        fun bind(msg: MessageUI) {
            tv.text = msg.text
            time.text = formatTime(msg.createdAt)

            if (msg.isSent) {
                showSent()
            } else if (!msg.error.isNullOrEmpty()) {
                showError(msg.error)
            } else {
                showSending()
            }
        }

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

    class TheirsVH(view: View) : RecyclerView.ViewHolder(view) {
        private val avatarCard: MaterialCardView = view.findViewById(R.id.avatarCard)
        private val avatarText: TextView = view.findViewById(R.id.avatarText)
        private val author: TextView = view.findViewById(R.id.author)
        private val time: TextView = view.findViewById(R.id.time)
        private val tv = view.findViewById<TextView>(R.id.textMessage)
        fun bind(msg: MessageUI) {
            tv.text = msg.text
            val initials = msg.senderName
                .split(" ")
                .take(2)
                .mapNotNull { it.firstOrNull()?.uppercase() }
                .joinToString("")

            avatarText.text = initials
            author.text = msg.senderName
            time.text = formatTime(msg.createdAt)

            val bg = colorFromId(msg.senderId)
            avatarCard.setCardBackgroundColor(bg)
            avatarText.setTextColor(
                if (isColorDark(bg)) Color.WHITE else Color.BLACK
            )
        }

    }

    object Diff : DiffUtil.ItemCallback<MessageUI>() {
        override fun areItemsTheSame(a: MessageUI, b: MessageUI) = a.localId == b.localId
        override fun areContentsTheSame(a: MessageUI, b: MessageUI) = a == b
    }
}