package dev.dineshkumar.aescamera.viewer

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import dev.dineshkumar.aescamera.utils.MediaNames
import java.io.File

/** One page per vault item: an image page or a video page, by file extension. */
class MediaPagerAdapter(
    activity: FragmentActivity,
    private val files: List<File>,
    private val imported: Boolean
) : FragmentStateAdapter(activity) {

    override fun getItemCount(): Int = files.size

    override fun getItemId(position: Int): Long = files[position].absolutePath.hashCode().toLong()

    override fun containsItem(itemId: Long): Boolean =
        files.any { it.absolutePath.hashCode().toLong() == itemId }

    override fun createFragment(position: Int): Fragment {
        val file = files[position]
        return if (MediaNames.isVideo(file)) {
            VideoPageFragment.newInstance(file, imported)
        } else {
            ImagePageFragment.newInstance(file, imported)
        }
    }
}
