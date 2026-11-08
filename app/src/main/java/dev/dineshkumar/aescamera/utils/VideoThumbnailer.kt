package dev.dineshkumar.aescamera.utils

import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import dev.dineshkumar.aescamera.AESCameraApplication
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Builds the encrypted gallery thumbnail of an .encv file from its first frame. Used for
 * imported and restored videos, which — unlike recordings made here — had no preview
 * snapshot taken when they were captured.
 */
object VideoThumbnailer {

    fun createThumbnail(
        application: AESCameraApplication,
        file: File,
        password: String,
        overwrite: Boolean = false,
        expectedKey: String? = password
    ) {
        val header = EncvHeader.fromFile(file)
        val retriever = MediaMetadataRetriever()
        val frame: Bitmap? = try {
            EncryptedVideoMediaDataSource(file, password).use { source ->
                retriever.setDataSource(source)
                retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            }
        } finally {
            runCatching { retriever.release() }
        }
        if (frame == null) {
            return
        }

        // MPEG-TS carries no rotation; the header does (see EncvHeader). iOS recordings
        // (fragmented MP4) keep it in the track matrix, which the retriever applies itself,
        // and their header rotation is always 0, so they aren't turned twice.
        val oriented = if (header.rotationDegrees != 0) {
            val matrix = Matrix().apply { postRotate(header.rotationDegrees.toFloat()) }
            Bitmap.createBitmap(frame, 0, 0, frame.width, frame.height, matrix, true)
        } else {
            frame
        }

        val jpeg = ByteArrayOutputStream()
        oriented.compress(Bitmap.CompressFormat.JPEG, 90, jpeg)
        ThumbnailUtils.createImageThumbnail(
            application, file.name, jpeg.toByteArray(), overwrite, expectedKey
        )
    }
}
