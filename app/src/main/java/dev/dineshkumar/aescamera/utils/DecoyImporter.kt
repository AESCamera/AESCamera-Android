package dev.dineshkumar.aescamera.utils

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Adds photos from the device to a duress password's decoy vault, so that vault doesn't
 * look suspiciously empty when it opens. Each photo is stored like a camera capture: a
 * JPEG sealed with the duress password, named after when it was taken, with a thumbnail.
 */
object DecoyImporter {
    private const val TAG = "DecoyImporter"

    // Photos bigger than this are skipped rather than loaded into memory whole.
    private const val MAX_BYTES = 64L * 1024 * 1024

    data class Summary(val added: Int, val skipped: Int)

    fun import(
        context: Context,
        uris: List<Uri>,
        duressKey: String,
        vault: File,
        onProgress: (done: Int, total: Int) -> Unit,
    ): Summary {
        val thumbnails = File(vault, ".thumbs").apply { mkdirs() }
        var added = 0
        var skipped = 0
        uris.forEachIndexed { index, uri ->
            try {
                val bytes = readBounded(context, uri) ?: throw IllegalArgumentException("Too large or unreadable")
                val jpeg = asJpeg(bytes)
                val file = freeName(vault, takenAt(bytes))
                file.writeBytes(EncryptionUtils.encryptImage(jpeg, duressKey))
                runCatching { ThumbnailUtils.writeThumbnail(File(thumbnails, file.name), duressKey, jpeg) }
                    .onFailure { Log.w(TAG, "No thumbnail for a decoy photo", it) }
                added++
            } catch (e: Exception) {
                Log.w(TAG, "Skipped a decoy photo: ${e.message}")
                skipped++
            }
            onProgress(index + 1, uris.size)
        }
        return Summary(added, skipped)
    }

    private fun readBounded(context: Context, uri: Uri): ByteArray? =
        context.contentResolver.openInputStream(uri)?.use { input ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > MAX_BYTES) return null
                out.write(buffer, 0, read)
            }
            out.toByteArray()
        }

    /** JPEG as-is (it keeps its EXIF); anything else decoded upright and re-encoded. */
    private fun asJpeg(bytes: ByteArray): ByteArray {
        if (bytes.size > 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()) {
            return bytes
        }
        val bitmap = ImageUtils.decodeOriented(bytes)
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }.toByteArray()
    }

    /** When the photo was taken (EXIF), else now. Decoy photos then sort like real ones. */
    private fun takenAt(bytes: ByteArray): Long {
        val stamp = runCatching {
            ExifInterface(ByteArrayInputStream(bytes)).getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
        }.getOrNull()
        val parsed = stamp?.let {
            runCatching { SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).parse(it)?.time }.getOrNull()
        }
        return parsed ?: System.currentTimeMillis()
    }

    /** A camera-style name for [time]; later milliseconds if that name is taken. */
    private fun freeName(vault: File, time: Long): File {
        val format = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss-SSS", Locale.US)
        var candidate = time
        while (true) {
            val file = File(vault, "IMG-${format.format(candidate)}.enc")
            if (!file.exists()) return file
            candidate++
        }
    }
}
