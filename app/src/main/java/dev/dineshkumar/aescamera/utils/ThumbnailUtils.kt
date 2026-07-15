package dev.dineshkumar.aescamera.utils

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.TypedValue
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

            // Create and encrypt thumbnail
            val thumbnailFile = File(thumbnailDirectory, fileName)

            if (thumbnailFile.exists()) {
                return
            }

            // Create thumbnail
            val fullSizeImage = BitmapFactory.decodeByteArray(inputPicture, 0, inputPicture.size)

            val thumbnailWidth: Int = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                24f,
                application.resources.displayMetrics
            ).toInt()
            val thumbnailHeight = fullSizeImage.height * thumbnailWidth / fullSizeImage.width;

            val thumbnail = Bitmap.createScaledBitmap(
                fullSizeImage,
                thumbnailWidth,
                thumbnailHeight,
                true
            )

            val thumbnailOutputStream = ByteArrayOutputStream()
            thumbnail.compress(Bitmap.CompressFormat.JPEG, 90, thumbnailOutputStream)

            // Encrypt and save thumbnail
            EncryptionUtils.encrypt(thumbnailOutputStream, key).let {
                thumbnailFile.writeBytes(it)
            }
        }
    }
}