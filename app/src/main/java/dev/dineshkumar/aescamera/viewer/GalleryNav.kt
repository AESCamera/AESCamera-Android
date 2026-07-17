package dev.dineshkumar.aescamera.viewer

import android.content.Context
import android.content.Intent

/**
 * Shared contract for previous/next navigation across the whole vault gallery — images
 * (`.enc`, opened in [DecryptedImageViewerActivity]) and videos (`.encv`, opened in
 * [DecryptedVideoPlayerActivity]) in one ordered list.
 *
 * Both viewers receive the same [EXTRA_PATHS] list and a starting [EXTRA_INDEX]. When the
 * user steps onto an item of the other media type, the current viewer launches the right
 * one via [intentFor] and finishes, so navigation feels continuous across the mixed list.
 */
object GalleryNav {
    const val EXTRA_PATHS = "gallery_paths"
    const val EXTRA_INDEX = "gallery_index"

    fun isVideo(path: String): Boolean = path.endsWith(".encv", ignoreCase = true)

    /** Intent that opens the correct viewer for [paths]\[index], carrying the list along. */
    fun intentFor(context: Context, paths: List<String>, index: Int): Intent {
        val target = if (isVideo(paths[index])) {
            DecryptedVideoPlayerActivity::class.java
        } else {
            DecryptedImageViewerActivity::class.java
        }
        return Intent(context, target).apply {
            putStringArrayListExtra(EXTRA_PATHS, ArrayList(paths))
            putExtra(EXTRA_INDEX, index)
        }
    }
}
