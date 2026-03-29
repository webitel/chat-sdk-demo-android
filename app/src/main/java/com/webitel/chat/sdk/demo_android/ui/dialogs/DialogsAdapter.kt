package com.webitel.chat.sdk.demo_android.ui.dialogs

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.webitel.chat.sdk.demo_android.R
import com.webitel.chat.sdk.demo_android.ui.common.colorFromId
import com.webitel.chat.sdk.demo_android.ui.common.isColorDark


class DialogsAdapter(
    private val onClick: (DialogUI) -> Unit
) : ListAdapter<DialogUI, DialogsAdapter.DialogVH>(Diff()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DialogVH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_dialog, parent, false)
        return DialogVH(view)
    }

    override fun onBindViewHolder(holder: DialogVH, position: Int) {
        holder.bind(getItem(position))
    }


    override fun onBindViewHolder(holder: DialogVH, position: Int, payloads: MutableList<Any>) {
        if (payloads.isEmpty()) {
            onBindViewHolder(holder, position)
        } else {
            for (payload in payloads) {
                if (payload == "PAYLOAD_MESSAGE") {
                    holder.updateMessageOnly(getItem(position))
                }
            }
        }
    }


    inner class DialogVH(view: View) : RecyclerView.ViewHolder(view) {

        private val avatarCard: MaterialCardView = view.findViewById(R.id.avatarCard)
        private val avatarText: TextView = view.findViewById(R.id.avatarText)
        private val title: TextView = view.findViewById(R.id.titleText)
        private val topMessage: TextView = view.findViewById(R.id.topMessageText)

        fun bind(dialog: DialogUI) {
            title.text = dialog.subject
            updateMessageOnly(dialog)

            val initials = dialog.subject
                .split(" ")
                .take(2)
                .mapNotNull { it.firstOrNull()?.uppercase() }
                .joinToString("")

            avatarText.text = initials

            val bg = colorFromId(dialog.id)
            avatarCard.setCardBackgroundColor(bg)
            avatarText.setTextColor(
                if (isColorDark(bg)) Color.WHITE else Color.BLACK
            )

            itemView.setOnClickListener { onClick(dialog) }
        }

        fun updateMessageOnly(dialog: DialogUI) {
            topMessage.text = dialog.lastMessageText
        }
    }


    class Diff : DiffUtil.ItemCallback<DialogUI>() {
        override fun areItemsTheSame(old: DialogUI, new: DialogUI) = old.id == new.id

        override fun areContentsTheSame(old: DialogUI, new: DialogUI) =
            old.lastMessageText == new.lastMessageText &&
                    old.lastMessageAt == new.lastMessageAt

        override fun getChangePayload(oldItem: DialogUI, newItem: DialogUI): Any? {
            if (oldItem.lastMessageText != newItem.lastMessageText) {
                return "PAYLOAD_MESSAGE"
            }
            return super.getChangePayload(oldItem, newItem)
        }
    }
}