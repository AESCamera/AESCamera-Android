package dev.dineshkumar.aescamera.viewer.extractor

import android.util.Log
import dev.dineshkumar.aescamera.utils.EncryptedVideoMediaDataSource
import java.io.File

/**
 * Builds the [Fmp4Index] of an encrypted iOS recording, decrypting only the `moov` and
 * `moof` boxes it reads (CTR random access, see EncryptedVideoMediaDataSource).
 */
object EncvFmp4Index {

    private const val TAG = "EncvFmp4Index"

    /** The index, or null when it can't be built (the file then plays unseekable). */
    fun read(file: File, password: String): Fmp4Index? = try {
        EncryptedVideoMediaDataSource(file, password).use { source ->
            Fmp4Index.build(source.size) { offset, count ->
                val buffer = ByteArray(count)
                var filled = 0
                while (filled < count) {
                    val read = source.readAt(offset + filled, buffer, filled, count - filled)
                    if (read <= 0) break
                    filled += read
                }
                if (filled == count) buffer else buffer.copyOf(filled)
            }
        }
    } catch (e: Exception) {
        Log.e(TAG, "Failed to index ${file.name}", e)
        null
    }
}
