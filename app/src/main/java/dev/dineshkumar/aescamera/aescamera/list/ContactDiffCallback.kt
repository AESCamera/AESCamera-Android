package dev.dineshkumar.aescamera.list

import androidx.recyclerview.widget.DiffUtil

class ContactDiffCallback: DiffUtil.ItemCallback<ContactItem>() {
    override fun areItemsTheSame(oldItem: ContactItem, newItem: ContactItem): Boolean {
        return oldItem.contactNo == newItem.contactNo
    }

    override fun areContentsTheSame(oldItem: ContactItem, newItem: ContactItem): Boolean {
        return oldItem.name == newItem.name && oldItem.photoUri == newItem.photoUri
    }
}