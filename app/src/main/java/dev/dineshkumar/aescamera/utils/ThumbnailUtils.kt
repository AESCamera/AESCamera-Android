package dev.dineshkumar.aescamera.utils

import android.graphics.Bitmap
import androidx.core.graphics.scale
import dev.dineshkumar.aescamera.AESCameraApplication
import java.io.ByteArrayOutputStream
import java.io.File

class ThumbnailUtils {
    companion object {
        /** Thumbnail width in pixels: enough for a third-of-screen tile on a dense display. */
        const val THUMBNAIL_WIDTH_PX = 320

        /**
         * Encrypts a thumbnail of [inputPicture] under [fileName] in the vault's thumbnail
         * directory. An existing thumbnail is kept unless [overwrite] is set (used to
         * rebuild the tiny thumbnails made by older versions). Background jobs that
         * decrypted [inputPicture] under a particular key pass it as [expectedKey]: if
         * the vault was re-keyed meanwhile, nothing is written, so a thumbnail from one
         * vault can never land in another.
         */
        fun createImageThumbnail(
            application: AESCameraApplication,
            fileName: String,
            inputPicture: ByteArray,
            overwrite: Boolean = false,
            expectedKey: String? = null
        ) {
            val thumbnailDirectory = application.thumbnailDirectory ?: return
            val key = application.key ?: return
            if (expectedKey != null && expectedKey != key) {
                return
            }

            // Create and encrypt thumbnail. Recreate the dir in case it vanished from
            // shared storage since the key was entered.
            thumbnailDirectory.mkdirs()
            val thumbnailFile = File(thumbnailDirectory, fileName)

            if (thumbnailFile.exists() && !overwrite) {
                return
            }

            val thumbnailWidth = THUMBNAIL_WIDTH_PX

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