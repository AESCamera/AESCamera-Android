package dev.dineshkumar.aescamera.utils

import android.media.MediaDataSource
import java.io.File
import java.io.RandomAccessFile
import javax.crypto.Cipher
import kotlin.math.min

/**
 * [MediaDataSource] over an .encv file for the platform's MediaMetadataRetriever,
 * decrypting on the fly the same way the player's data source does (CTR random access,
 * see EncryptionUtils.ctrCipherAt). Plaintext exists only in the caller's buffer.
 */
class EncryptedVideoMediaDataSource(file: File, private val password: String) : MediaDataSource() {

    private val raf = RandomAccessFile(file, "r")
    private val header: EncvHeader
    private val salt = ByteArray(EncvHeader.SALT_SIZE)
    private val iv = ByteArray(EncvHeader.IV_SIZE)
    private val plaintextSize: Long

    init {
        val headerBytes = ByteArray(EncvHeader.SIZE)
        raf.readFully(headerBytes)
        raf.readFully(salt)
        raf.readFully(iv)
        header = EncvHeader.fromBytes(headerBytes)
        plaintextSize = raf.length() - EncvHeader.PREFIX_SIZE - header.trailerSize
    }

    @Synchronized
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (position >= plaintextSize) {
            return -1
        }
        val toRead = min(size.toLong(), plaintextSize - position).toInt()
        if (toRead <= 0) {
            return 0
        }
        val scratch = ByteArray(toRead)
        raf.seek(EncvHeader.PREFIX_SIZE + position)
        raf.readFully(scratch)
        val cipher = EncryptionUtils.ctrCipherAt(
            Cipher.DECRYPT_MODE, password, salt, iv, header.kdf, position
        )
        // CTR is a stream cipher: update() produces exactly toRead bytes.
        return cipher.update(scratch, 0, toRead, buffer, offset)
    }

    override fun getSize(): Long = plaintextSize

    @Synchronized
    override fun close() {
        raf.close()
    }
}
