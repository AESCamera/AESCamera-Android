package dev.dineshkumar.aescamera.list

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.LruCache
import dev.dineshkumar.aescamera.AESCameraApplication
import dev.dineshkumar.aescamera.utils.EncryptionUtils
import dev.dineshkumar.aescamera.utils.MediaNames
import dev.dineshkumar.aescamera.utils.ThumbnailUtils
import dev.dineshkumar.aescamera.utils.VideoThumbnailer
import java.io.File
import java.util.Collections
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * Decrypts gallery thumbnails off the main thread, a few at a time, into a memory cache
 * so scrolling back never re-derives a key. A result only lands on a tile that is still
 * bound to the same file, so fast scrolling cannot put a thumbnail on the wrong cell.
 *
 * Thumbnails made by older versions were 24 dp wide; one that decodes smaller than
 * [REGENERATE_BELOW_PX] is rebuilt once from the full file in the background (a full
 * decrypt, so bounded by the pool) and reloaded.
 *
 * The cache holds plaintext bitmaps: it lives and dies with the gallery screen and is
 * dropped when the key changes (the adapter is rebuilt) and on [cancel].
 */
class ThumbnailLoader(
    private val application: AESCameraApplication,
    private val thumbnailDirectory: File?,
    private val key: String?
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor: ExecutorService = Executors.newFixedThreadPool(POOL_SIZE)
    private val regenerating = Collections.synchronizedSet(HashSet<String>())

    private val cache = object : LruCache<String, Bitmap>(CACHE_KB) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    /** Shows the cached thumbnail at once, or decrypts it in the background. */
    fun load(holder: FileAdapter.MediaViewHolder, item: GalleryItem.Media) {
        val path = item.path
        cache.get(path)?.let {
            holder.showThumbnail(it)
            return
        }
        holder.showPlaceholder()

        val thumbnailFile = thumbnailDirectory?.let { File(it, item.file.name) } ?: return
        val currentKey = key ?: return
        if (!thumbnailFile.isFile) {
            return
        }

        if (executor.isShutdown) {
            return
        }
        executor.execute {
            val bitmap = try {
                val decrypted = EncryptionUtils.decryptImage(thumbnailFile.readBytes(), currentKey)
                BitmapFactory.decodeByteArray(decrypted, 0, decrypted.size)
            } catch (e: Exception) {
                Log.w(TAG, "No thumbnail for ${item.file.name}: ${e.message}")
                null
            }
            if (bitmap == null) {
                return@execute
            }
            cache.put(path, bitmap)
            mainHandler.post {
                if (holder.boundPath == path) {
                    holder.showThumbnail(bitmap)
                }
            }
            if (bitmap.width < REGENERATE_BELOW_PX && bitmap.height < REGENERATE_BELOW_PX) {
                regenerate(item, currentKey, holder)
            }
        }
    }

    private fun regenerate(item: GalleryItem.Media, currentKey: String, holder: FileAdapter.MediaViewHolder) {
        if (!regenerating.add(item.path) || executor.isShutdown) {
            return
        }
        val task = Runnable {
            try {
                if (item.isVideo) {
                    VideoThumbnailer.createThumbnail(
                        application, item.file, currentKey, overwrite = true, expectedKey = currentKey
                    )
                } else {
                    val decrypted = EncryptionUtils.decryptImage(item.file.readBytes(), currentKey)
                    ThumbnailUtils.createImageThumbnail(
                        application, item.file.name, decrypted, overwrite = true, expectedKey = currentKey
                    )
                }
                cache.remove(item.path)
                mainHandler.post {
                    if (holder.boundPath == item.path) {
                        load(holder, item)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not rebuild thumbnail for ${item.file.name}: ${e.message}")
            }
        }
        try {
            // This runs on a pool thread; cancel() may have shut the pool down meanwhile.
            executor.execute(task)
        } catch (e: RejectedExecutionException) {
            regenerating.remove(item.path)
        }
    }

    /** Stops pending work and drops the plaintext cache. */
    fun cancel() {
        executor.shutdownNow()
        cache.evictAll()
    }

    companion object {
        private const val TAG = "ThumbnailLoader"
        private const val POOL_SIZE = 3
        private const val CACHE_KB = 24 * 1024
        private const val REGENERATE_BELOW_PX = 96
    }
}
