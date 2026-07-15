package dev.dineshkumar.aescamera.utils

import java.text.SimpleDateFormat
import java.util.Locale

class CommonUtils {
    companion object {
        private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"

        fun getImageFileName(): String {
            return "IMG-${SimpleDateFormat(FILENAME_FORMAT, Locale.US).format(System.currentTimeMillis())}.enc"
        }

        fun getVideoFileName(): String {
            return "VID-${SimpleDateFormat(FILENAME_FORMAT, Locale.US).format(System.currentTimeMillis())}.encv"
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

    }
}