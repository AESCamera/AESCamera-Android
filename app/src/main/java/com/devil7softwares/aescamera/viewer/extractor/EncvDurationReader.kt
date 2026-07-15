package com.devil7softwares.aescamera.viewer.extractor

import androidx.media3.common.C
import com.devil7softwares.aescamera.utils.EncryptionUtils
import com.devil7softwares.aescamera.utils.EncvHeader
import java.io.File
import java.io.RandomAccessFile
import javax.crypto.Cipher

/**
 * Measures the duration of an .encv recording by decrypting a small chunk at each end of
 * the file (CTR random access makes the tail cheap) and reading the first and last PES
 * PTS. Needed because MediaRecorder's TS output has no PCR, which is the only thing
 * ExoPlayer derives TS duration from.
 */
object EncvDurationReader {

    /** How many bytes to scan at each end of the stream. PTS occur once per frame. */
    private const val SEARCH_BYTES = 256 * 1024

    /** Returns the stream duration in microseconds, or [C.TIME_UNSET] if unreadable. */
    fun readDurationUs(file: File, password: String): Long {
        try {
            RandomAccessFile(file, "r").use { raf ->
                val header = ByteArray(EncvHeader.SIZE)
                val salt = ByteArray(EncvHeader.SALT_SIZE)
                val iv = ByteArray(EncvHeader.IV_SIZE)
                raf.readFully(header)
                raf.readFully(salt)
                raf.readFully(iv)
                EncvHeader.fromBytes(header) // validates magic/version

                val totalPlaintext = raf.length() - EncvHeader.PREFIX_SIZE
                if (totalPlaintext < TsPtsParser.TS_PACKET_SIZE) return C.TIME_UNSET

                val headLength = minOf(totalPlaintext, SEARCH_BYTES.toLong()).toInt()
                val head = decryptRange(raf, password, salt, iv, 0L, headLength)
                val firstPts = TsPtsParser.findFirstPts(head) ?: return C.TIME_UNSET

                // Keep the tail chunk packet-aligned relative to the stream start.
                var tailStart = maxOf(0L, totalPlaintext - SEARCH_BYTES)
                tailStart -= tailStart % TsPtsParser.TS_PACKET_SIZE
                val tail =
                    decryptRange(raf, password, salt, iv, tailStart, (totalPlaintext - tailStart).toInt())
                val lastPts = TsPtsParser.findLastPts(tail) ?: return C.TIME_UNSET

                if (lastPts <= firstPts) return C.TIME_UNSET
                return (lastPts - firstPts) * 1_000_000L / 90_000L
            }
        } catch (e: Exception) {
            return C.TIME_UNSET
        }
    }

    private fun decryptRange(
        raf: RandomAccessFile,
        password: String,
        salt: ByteArray,
        iv: ByteArray,
        plaintextOffset: Long,
        length: Int
    ): ByteArray {
        val cipher =
            EncryptionUtils.ctrCipherAt(Cipher.DECRYPT_MODE, password, salt, iv, plaintextOffset)
        raf.seek(EncvHeader.PREFIX_SIZE + plaintextOffset)
        val buffer = ByteArray(length)
        raf.readFully(buffer)
        return cipher.update(buffer) ?: ByteArray(0)
    }
}
