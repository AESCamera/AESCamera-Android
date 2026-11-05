package dev.dineshkumar.aescamera.list

import android.graphics.Bitmap
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import dev.dineshkumar.aescamera.AESCameraApplication
import dev.dineshkumar.aescamera.R
import dev.dineshkumar.aescamera.databinding.ItemDateHeaderBinding
import dev.dineshkumar.aescamera.databinding.ItemMediaBinding
import java.io.File

/**
 * The gallery grid: day headers spanning the row, square media tiles under them.
 * Tap opens; long-press enters selection mode, in which tap toggles. Selection is kept
 * by file path, so it survives list refreshes and is restored across rotation.
 */
class FileAdapter(
    application: AESCameraApplication,
    private val onOpen: (File) -> Unit,
    private val onSelectionChanged: (selected: Int, total: Int) -> Unit
) : ListAdapter<GalleryItem, RecyclerView.ViewHolder>(GalleryDiffCallback()) {

    private val thumbnailLoader =
        ThumbnailLoader(application, application.thumbnailDirectory, application.key)
    private val selected = LinkedHashSet<String>()

    var selectionMode: Boolean = false
        private set

    override fun getItemViewType(position: Int): Int = when (getItem(position)) {
        is GalleryItem.Header -> TYPE_HEADER
        is GalleryItem.Media -> TYPE_MEDIA
    }

    fun isHeader(position: Int): Boolean = getItem(position) is GalleryItem.Header

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HEADER) {
            HeaderViewHolder(ItemDateHeaderBinding.inflate(inflater, parent, false))
        } else {
            MediaViewHolder(ItemMediaBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is GalleryItem.Header -> (holder as HeaderViewHolder).bind(item)
            is GalleryItem.Media -> (holder as MediaViewHolder).bind(item, item.path in selected)
        }
    }

    fun destroy() {
        thumbnailLoader.cancel()
    }

    // ---- selection ----------------------------------------------------------------

    val mediaCount: Int
        get() = currentList.count { it is GalleryItem.Media }

    fun selectedFiles(): List<File> =
        currentList.filterIsInstance<GalleryItem.Media>()
            .filter { it.path in selected }
            .map { it.file }

    fun selectedPaths(): ArrayList<String> = ArrayList(selected)

    /** Restores a saved selection (rotation); paths no longer listed are dropped. */
    fun restoreSelection(paths: Collection<String>) {
        val listed = currentList.filterIsInstance<GalleryItem.Media>().map { it.path }.toSet()
        selected.clear()
        selected.addAll(paths.filter { it in listed })
        selectionMode = selected.isNotEmpty()
        notifyItemRangeChanged(0, itemCount)
        onSelectionChanged(selected.size, mediaCount)
    }

    fun clearSelection() {
        if (selected.isEmpty() && !selectionMode) {
            return
        }
        selected.clear()
        selectionMode = false
        notifyItemRangeChanged(0, itemCount)
        onSelectionChanged(0, mediaCount)
    }

    fun selectAll() {
        currentList.filterIsInstance<GalleryItem.Media>().forEach { selected.add(it.path) }
        selectionMode = true
        notifyItemRangeChanged(0, itemCount)
        onSelectionChanged(selected.size, mediaCount)
    }

    private fun toggle(item: GalleryItem.Media, position: Int) {
        if (!selected.remove(item.path)) {
            selected.add(item.path)
        }
        val wasSelectionMode = selectionMode
        selectionMode = selected.isNotEmpty()
        if (selectionMode == wasSelectionMode) {
            notifyItemChanged(position)
        } else {
            // Entering or leaving selection mode: every tile shows or drops its checkbox.
            notifyItemRangeChanged(0, itemCount)
        }
        onSelectionChanged(selected.size, mediaCount)
    }

    /** Called by the activity after a delete/refresh so the selection matches the list. */
    override fun submitList(list: List<GalleryItem>?) {
        super.submitList(list) {
            val listed = currentList.filterIsInstance<GalleryItem.Media>().map { it.path }.toSet()
            if (selected.retainAll(listed)) {
                selectionMode = selected.isNotEmpty()
                if (!selectionMode) {
                    notifyItemRangeChanged(0, itemCount)
                }
                onSelectionChanged(selected.size, mediaCount)
            }
        }
    }

    // ---- view holders -------------------------------------------------------------

    class HeaderViewHolder(private val binding: ItemDateHeaderBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(item: GalleryItem.Header) {
            binding.dateHeader.text = item.label
        }
    }

    inner class MediaViewHolder(private val binding: ItemMediaBinding) :
        RecyclerView.ViewHolder(binding.root) {

        /** The file this tile currently shows; thumbnails for any other file are dropped. */
        var boundPath: String? = null
            private set

        init {
            binding.root.setOnClickListener {
                val position = bindingAdapterPosition
                if (position == RecyclerView.NO_POSITION) return@setOnClickListener
                val item = getItem(position) as? GalleryItem.Media ?: return@setOnClickListener
                if (selectionMode) toggle(item, position) else onOpen(item.file)
            }
            binding.root.setOnLongClickListener {
                val position = bindingAdapterPosition
                if (position == RecyclerView.NO_POSITION) return@setOnLongClickListener false
                val item = getItem(position) as? GalleryItem.Media ?: return@setOnLongClickListener false
                toggle(item, position)
                true
            }
        }

        fun bind(item: GalleryItem.Media, isSelected: Boolean) {
            boundPath = item.path
            binding.playBadge.visibility = if (item.isVideo) View.VISIBLE else View.GONE
            binding.root.contentDescription = binding.root.context.getString(
                if (item.isVideo) R.string.tile_video_description else R.string.tile_photo_description,
                dev.dineshkumar.aescamera.utils.MediaNames.viewerTitle(binding.root.context, item.file)
            )
            binding.selectionCheck.visibility = if (selectionMode) View.VISIBLE else View.GONE
            binding.selectionCheck.isChecked = isSelected
            binding.selectionScrim.visibility = if (isSelected) View.VISIBLE else View.GONE
            binding.root.isSelected = isSelected
            thumbnailLoader.load(this, item)
        }

        fun showThumbnail(bitmap: Bitmap) {
            binding.thumbnail.setImageBitmap(bitmap)
            binding.placeholderIcon.visibility = View.GONE
        }

        fun showPlaceholder() {
            binding.thumbnail.setImageDrawable(null)
            binding.placeholderIcon.visibility = View.VISIBLE
        }
    }

    companion object {
        const val TYPE_HEADER = 0
        const val TYPE_MEDIA = 1
    }
}
