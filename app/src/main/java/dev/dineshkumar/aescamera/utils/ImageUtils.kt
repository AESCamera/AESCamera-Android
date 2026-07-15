package dev.dineshkumar.aescamera.utils

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream

object ImageUtils {

    /**
     * Decodes an image byte array with its EXIF orientation applied.
     *
     * Camera HALs usually store portrait photos as landscape pixel data plus an EXIF
     * orientation tag, which [BitmapFactory] ignores — so the tag has to be applied
     * manually or portrait photos render sideways.
     *
     * [minDimension], when given, subsamples the decode (power-of-two [BitmapFactory
     * .Options.inSampleSize]) so that both dimensions stay >= it. Decoding a
     * multi-megapixel JPEG at 1/8–1/16 size is roughly an order of magnitude faster and
     * allocates a fraction of the memory — use it whenever a small target (thumbnail)
     * is being produced.
     */
    fun decodeOriented(bytes: ByteArray, minDimension: Int? = null): Bitmap {
        val options = BitmapFactory.Options()
        if (minDimension != null && minDimension > 0) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sampleSize = 1
            while (bounds.outWidth / (sampleSize * 2) >= minDimension
                && bounds.outHeight / (sampleSize * 2) >= minDimension
            ) {
                sampleSize *= 2
            }
            options.inSampleSize = sampleSize
        }

        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            ?: throw IllegalArgumentException("Not a decodable image")

        val orientation = try {
            ByteArrayInputStream(bytes).use {
                ExifInterface(it).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
            }
        } catch (e: Exception) {
            // Not a format ExifInterface understands (e.g. PNG): nothing to apply.
            ExifInterface.ORIENTATION_NORMAL
        }

        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.postScale(-1f, 1f)
            }
            else -> return bitmap
        }

        val oriented =
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (oriented != bitmap) {
            bitmap.recycle()
        }
        return oriented
    }
}
