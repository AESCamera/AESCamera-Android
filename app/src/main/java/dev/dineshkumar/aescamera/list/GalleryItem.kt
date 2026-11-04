package dev.dineshkumar.aescamera.list

import androidx.recyclerview.widget.DiffUtil
import dev.dineshkumar.aescamera.utils.MediaNames
import java.io.File

/** A row of the gallery grid: a full-width day header, or a media tile. */
sealed class GalleryItem {
    data class Header(val dayStart: Long, val label: String) : GalleryItem()

    data class Media(val file: File, val captureTime: Long, val isVideo: Boolean) : GalleryItem() {
        val path: String get() = file.absolutePath
    }

    companion object {
        /** Groups [files] (newest first) under one header per day. */
        fun build(files: List<File>, label: (Long) -> String): List<GalleryItem> {
            val items = ArrayList<GalleryItem>(files.size + 8)
            var currentDay = Long.MIN_VALUE
            for (file in files) {
                val time = MediaNames.captureTime(file)
                val day = MediaNames.dayStart(time)
                if (day != currentDay) {
                    currentDay = day
                    items.add(Header(day, label(time)))
                }
                items.add(Media(file, time, MediaNames.isVideo(file)))
            }
            return items
        }
    }
}

class GalleryDiffCallback : DiffUtil.ItemCallback<GalleryItem>() {
    override fun areItemsTheSame(oldItem: GalleryItem, newItem: GalleryItem): Boolean =
        when {
            oldItem is GalleryItem.Header && newItem is GalleryItem.Header ->
                oldItem.dayStart == newItem.dayStart

            oldItem is GalleryItem.Media && newItem is GalleryItem.Media ->
                oldItem.path == newItem.path

            else -> false
        }

    override fun areContentsTheSame(oldItem: GalleryItem, newItem: GalleryItem): Boolean =
        when {
            oldItem is GalleryItem.Header && newItem is GalleryItem.Header ->
                oldItem.label == newItem.label

            oldItem is GalleryItem.Media && newItem is GalleryItem.Media ->
                oldItem.captureTime == newItem.captureTime &&
                        oldItem.file.lastModified() == newItem.file.lastModified() &&
                        oldItem.file.length() == newItem.file.length()

            else -> false
        }
}
