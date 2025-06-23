package com.devil7softwares.aescamera.utils

import android.content.Context
import com.devil7softwares.aescamera.R
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

class CommonUtils {
    companion object {
        private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"

        fun getImageFileName(): String {
            return "IMG-${SimpleDateFormat(FILENAME_FORMAT, Locale.US).format(System.currentTimeMillis())}.enc"
        }

        fun getKeyDigest(key: String): String {
            // Generate MD5 digest of the key
            val md = java.security.MessageDigest.getInstance("MD5")
            val digest = md.digest(key.toByteArray())
            val sb = StringBuilder()
            for (b in digest) {
                sb.append(String.format("%02x", b))
            }

            return sb.toString()
        }

        fun getOutputDirectory(keyDigest: String?, context: Context): File {
            val mediaDir = context.externalMediaDirs.firstOrNull()?.let {
                File(it, context.resources.getString(R.string.app_name)).apply { mkdirs() }
            }

            if (keyDigest != null && mediaDir != null && mediaDir.exists()) {
                val keyDir = File(mediaDir, keyDigest).apply { mkdirs() }

                if (keyDir.exists()) {
                    return keyDir
                } else {
                    return mediaDir
                }
            } else {
                return context.filesDir
            }
        }
    }
}