package dev.dineshkumar.aescamera.list

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageDecoder
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.net.toUri
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import dev.dineshkumar.aescamera.R

class ContactAdapter(
    private val context: Context,
    private var list: List<ContactItem>,
    private val onContactSelected: (ContactItem) -> Unit
) : ListAdapter<ContactItem, ContactAdapter.ContactViewHolder>(ContactDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ContactViewHolder {
        return ContactViewHolder(
            LayoutInflater.from(context).inflate(R.layout.item_contact, parent, false)
        )
    }

    override fun onBindViewHolder(holder: ContactViewHolder, position: Int) {
        holder.bind(list[position])
    }

    override fun getItemCount(): Int {
        return list.size
    }

    @SuppressLint("NotifyDataSetChanged")
    fun updateList(newList: List<ContactItem>) {
        list = newList
        notifyDataSetChanged()
    }

    inner class ContactViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val name: TextView = itemView.findViewById(R.id.tvName)
        private val contactNo: TextView = itemView.findViewById(R.id.tvPhone)
        private val photo: ImageView = itemView.findViewById(R.id.ivContact)
        private val previouslySelected: ImageView = itemView.findViewById(R.id.ivPreviouslySelected)

        fun bind(item: ContactItem) {
            name.text = item.name
            contactNo.text = item.contactNo
            previouslySelected.visibility =
                if (item.previouslySelected) View.VISIBLE else View.INVISIBLE

            try {
                val photoUri = item.photoUri

                if (!photoUri.isNullOrEmpty()) {
                    val uri = photoUri.toUri()

                    val bitmap =
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                            val source = ImageDecoder.createSource(context.contentResolver, uri)
                            ImageDecoder.decodeBitmap(source)
                        } else {
                            MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
                        }

                    photo.setImageBitmap(bitmap)
                } else {
                    photo.setImageResource(R.drawable.ic_user)
                }
            } catch (e: Exception) {
                e.printStackTrace()

                photo.setImageResource(R.drawable.ic_user)
            }

            itemView.setOnClickListener {
                onContactSelected(item)
            }
        }
    }
}