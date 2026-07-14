package com.devil7softwares.aescamera.viewer.datasource

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import com.devil7softwares.aescamera.utils.EncryptionUtils
import com.devil7softwares.aescamera.utils.EncvHeader
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import javax.crypto.Cipher
import kotlin.math.min

/**
 * Media3 DataSource that decrypts .encv v2 files (AES/CTR/NoPadding) on the fly.
 *
 * The file layout is [EncvHeader.SIZE header][salt][iv][ciphertext] (see [EncvHeader]).
 * Positions in the [DataSpec] refer to plaintext offsets; because CTR allows O(1) random
 * access (see EncryptionUtils.ctrCipherAt), seeking never re-reads or re-decrypts
 * earlier data. Decrypted bytes only ever exist in memory.
 */
@UnstableApi
class EncryptedCTRDataSource(private val password: String) : BaseDataSource(false) {

    private var file: RandomAccessFile? = null
    private var uri: Uri? = null
    private var cipher: Cipher? = null
    private var bytesRemaining: Long = 0
    private var opened = false
    private var scratch: ByteArray? = null

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)

        val path = dataSpec.uri.path
            ?: throw IOException("Unsupported uri (no file path): ${dataSpec.uri}")

        val raf = RandomAccessFile(File(path), "r")
        file = raf

        try {
            // Plaintext prefix: header + salt + iv
            val headerBytes = ByteArray(EncvHeader.SIZE)
            val salt = ByteArray(EncvHeader.SALT_SIZE)
            val iv = ByteArray(EncvHeader.IV_SIZE)
            raf.seek(0)
            raf.readFully(headerBytes)
            raf.readFully(salt)
            raf.readFully(iv)
            EncvHeader.fromBytes(headerBytes) // validates magic/version

            val totalPlaintext = raf.length() - EncvHeader.PREFIX_SIZE
            if (dataSpec.position > totalPlaintext) {
                throw DataSourceException(
                    androidx.media3.common.PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE
                )
            }

            // Jump the CTR counter straight to the requested plaintext offset.
            cipher = EncryptionUtils.ctrCipherAt(
                Cipher.DECRYPT_MODE, password, salt, iv, dataSpec.position
            )
            raf.seek(EncvHeader.PREFIX_SIZE + dataSpec.position)

            val available = totalPlaintext - dataSpec.position
            bytesRemaining = if (dataSpec.length == C.LENGTH_UNSET.toLong()) {
                available
            } else {
                min(dataSpec.length, available)
            }
        } catch (e: Exception) {
            runCatching { raf.close() }
            file = null
            throw e
        }

        opened = true
        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) {
            return 0
        }
        if (bytesRemaining <= 0) {
            return C.RESULT_END_OF_INPUT
        }

        val raf = file ?: return C.RESULT_END_OF_INPUT
        val activeCipher = cipher ?: return C.RESULT_END_OF_INPUT

        val toRead = min(length.toLong(), min(bytesRemaining, SCRATCH_SIZE.toLong())).toInt()
        val scratchBuffer = scratch ?: ByteArray(SCRATCH_SIZE).also { scratch = it }

        val read = raf.read(scratchBuffer, 0, toRead)
        if (read == -1) {
            return C.RESULT_END_OF_INPUT
        }

        // CTR is a stream cipher: update() always produces exactly `read` bytes.
        val produced = activeCipher.update(scratchBuffer, 0, read, buffer, offset)

        bytesRemaining -= read
        bytesTransferred(read)
        return produced
    }

    override fun getUri(): Uri? {
        return uri
    }

    override fun close() {
        if (opened) {
            opened = false
            transferEnded()
        }
        runCatching { file?.close() }
        file = null
        cipher = null
        uri = null
    }

    companion object {
        private const val SCRATCH_SIZE = 64 * 1024
    }
}
