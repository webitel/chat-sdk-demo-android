package com.webitel.chat.sdk.demo_android.ui.contacts

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.webitel.chat.sdk.Contact
import com.webitel.chat.sdk.demo_android.R
import com.webitel.chat.sdk.demo_android.ui.common.colorFromId
import com.webitel.chat.sdk.demo_android.ui.common.isColorDark


class ContactsAdapter(
    private val onClick: (Contact) -> Unit
) : ListAdapter<Contact, ContactsAdapter.ContactVH>(Diff()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ContactVH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_contact, parent, false)
        return ContactVH(view)
    }

    override fun onBindViewHolder(holder: ContactVH, position: Int) {
        holder.bind(getItem(position))
    }


    inner class ContactVH(view: View) : RecyclerView.ViewHolder(view) {

        private val avatarCard: MaterialCardView = view.findViewById(R.id.avatarCard)
        private val avatarText: TextView = view.findViewById(R.id.avatarText)
        private val title: TextView = view.findViewById(R.id.titleText)

        fun bind(contact: Contact) {
            title.text = contact.name

            val initials = contact.name
                .split(" ")
                .take(2)
                .mapNotNull { it.firstOrNull()?.uppercase() }
                .joinToString("")

            avatarText.text = initials

            val bg = colorFromId(contact.id.sub)
            avatarCard.setCardBackgroundColor(bg)
            avatarText.setTextColor(
                if (isColorDark(bg)) Color.WHITE else Color.BLACK
            )

            itemView.setOnClickListener { onClick(contact) }
        }
    }

    class Diff : DiffUtil.ItemCallback<Contact>() {
        override fun areItemsTheSame(old: Contact, new: Contact) = old.id == new.id
        override fun areContentsTheSame(old: Contact, new: Contact) =
            old.id == new.id
    }
}