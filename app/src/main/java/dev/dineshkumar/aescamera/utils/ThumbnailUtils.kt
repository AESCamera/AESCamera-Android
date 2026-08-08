package dev.dineshkumar.aescamera.utils

import android.graphics.Bitmap
import android.util.TypedValue
import androidx.core.graphics.scale
import dev.dineshkumar.aescamera.AESCameraApplication
import java.io.ByteArrayOutputStream
import java.io.File

class ThumbnailUtils {
    companion object {
        fun createImageThumbnail(
            application: AESCameraApplication,
            fileName: String,
            inputPicture: ByteArray
        ) {
            val thumbnailDirectory = application.thumbnailDirectory ?: return
            val key = application.key ?: return

            // Create and encrypt thumbnail. Recreate the dir in case it vanished from
            // shared storage since the key was entered.
            thumbnailDirectory.mkdirs()
            val thumbnailFile = File(thumbnailDirectory, fileName)

            if (thumbnailFile.exists()) {
                return
            }

            val thumbnailWidth: Int = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                24f,
                application.resources.displayMetrics
            ).toInt()

            // Subsampled, EXIF-oriented decode: a full-resolution decode of a
            // multi-megapixel JPEG takes hundreds of ms just to produce a tiny thumbnail.
            val sourceImage = ImageUtils.decodeOriented(inputPicture, thumbnailWidth)
            val thumbnailHeight = sourceImage.height * thumbnailWidth / sourceImage.width

            val thumbnail = sourceImage.scale(thumbnailWidth, thumbnailHeight)

            val thumbnailOutputStream = ByteArrayOutputStream()
            thumbnail.compress(Bitmap.CompressFormat.JPEG, 90, thumbnailOutputStream)

            // Encrypt and save the thumbnail. Thumbnails use a lower PBKDF2 cost than
            // photos: the gallery grid pays one derivation per thumbnail while scrolling.
            EncryptionUtils.encryptImage(
                thumbnailOutputStream.toByteArray(),
                key,
                EncryptionUtils.THUMBNAIL_ITERATIONS
            ).let {
                thumbnailFile.writeBytes(it)
            }
        }
    }
}