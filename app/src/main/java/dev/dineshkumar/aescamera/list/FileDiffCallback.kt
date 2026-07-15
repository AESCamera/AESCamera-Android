package dev.dineshkumar.aescamera.list

import androidx.recyclerview.widget.DiffUtil
import java.io.File

class FileDiffCallback : DiffUtil.ItemCallback<File>() {
    override fun areItemsTheSame(oldItem: File, newItem: File): Boolean {
        return oldItem.absolutePath == newItem.absolutePath
    }

    override fun areContentsTheSame(oldItem: File, newItem: File): Boolean {
        return oldItem.lastModified() == newItem.lastModified() && oldItem.length() == newItem.length()
    }
}