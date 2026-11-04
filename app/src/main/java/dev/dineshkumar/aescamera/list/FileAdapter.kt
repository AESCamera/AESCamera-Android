package dev.dineshkumar.aescamera.list

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import dev.dineshkumar.aescamera.AESCameraApplication
import dev.dineshkumar.aescamera.ProtectedBaseActivity
import dev.dineshkumar.aescamera.R
import dev.dineshkumar.aescamera.databinding.ActivityFilesListBinding
import java.util.Locale
import dev.dineshkumar.aescamera.databinding.ItemFileBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class FileAdapter(
    private val context: Context,
    private val thumbnailDirectory: File?,
    var key: String?,
    private val onItemClick: (File, ArrayList<File>) -> Unit,
    private val onSelectionChanged: (Int) -> Unit
) : ListAdapter<File, FileAdapter.FileViewHolder>(FileDiffCallback()) {

    private val selectedItems = mutableSetOf<Int>()
    private val thumbnailLoader = ThumbnailLoader(context, thumbnailDirectory, key)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FileViewHolder {
        val binding = ItemFileBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return FileViewHolder(binding)
    }

    fun onPause() {
        thumbnailLoader.pauseLoading()
    }

    fun onResume() {
        thumbnailLoader.resumeLoading()
    }

    fun onDestroy() {
        thumbnailLoader.cancelLoading()
    }

    override fun onBindViewHolder(holder: FileViewHolder, position: Int) {
        holder.bind(getItem(position), position in selectedItems)
    }

    inner class FileViewHolder(private val binding: ItemFileBinding) :
        RecyclerView.ViewHolder(binding.root) {
        private var isVideo = false

        init {
            binding.root.setOnClickListener {
                val files = ArrayList<File>()

                for (i in 0 until itemCount) {
                    files.add(getItem(i))
                }

                onItemClick(getItem(bindingAdapterPosition), files)
            }
            binding.fileCheckBox.setOnClickListener {
                toggleSelection(bindingAdapterPosition)
            }
        }

        fun bind(file: File, isSelected: Boolean) {
            binding.fileName.text = file.name
            binding.fileSize.text = formatFileSize(file.length())
            binding.fileCheckBox.isChecked = isSelected
            isVideo = file.extension == "encv"

            showIcon(
                context.getString(
                    if (isVideo) R.string.fa_file_video
                    else R.string.fa_file_image
                )
            )
            thumbnailLoader.loadThumbnail(this, file)
        }

        fun showThumbnail(bitmap: Bitmap) {
            binding.fileThumbnail.setImageBitmap(bitmap)
            binding.fileIcon.visibility = View.GONE
            binding.fileThumbnail.visibility = View.VISIBLE
            // The generic icon already flags a video; badge the decrypted thumbnail so a
            // video stays distinguishable from a photo once its preview is showing.
            binding.playBadge.visibility = if (isVideo) View.VISIBLE else View.GONE
        }

        fun showIcon(icon: String) {
            binding.fileIcon.setIcon(icon)
            binding.fileIcon.visibility = View.VISIBLE
            binding.fileThumbnail.visibility = View.GONE
            binding.playBadge.visibility = View.GONE
        }

        private fun formatFileSize(size: Long): String {
            val kb = size / 1024.0
            val mb = kb / 1024.0
            return when {
                mb >= 1 -> String.format(Locale.getDefault(), "%.2f MB", mb)
                kb >= 1 -> String.format(Locale.getDefault(), "%.2f KB", kb)
                else -> String.format(Locale.getDefault(), "%d bytes", size)
            }
        }
    }

    private fun toggleSelection(position: Int) {
        if (selectedItems.contains(position)) {
            selectedItems.remove(position)
        } else {
            selectedItems.add(position)
        }
        notifyItemChanged(position)
        onSelectionChanged(selectedItems.size)
    }

    @SuppressLint("NotifyDataSetChanged")
    fun clearSelection() {
        selectedItems.clear()
        notifyDataSetChanged()
        onSelectionChanged(0)
    }

    @SuppressLint("NotifyDataSetChanged")
    fun selectAll() {
        for (i in 0 until itemCount) {
            selectedItems.add(i)
        }
        notifyDataSetChanged()
        onSelectionChanged(selectedItems.size)
    }

    fun getSelectedItems(): List<File> {
        return selectedItems.map { getItem(it) }
    }

    fun getSelectedItemsCount(): Int {
        return selectedItems.size
    }
}