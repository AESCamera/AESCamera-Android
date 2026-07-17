package dev.dineshkumar.aescamera.utils

import java.io.File
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.OutputStream
import javax.crypto.Mac

/**
 * Write side of an .encv v3 recording: an [OutputStream] that encrypts plaintext into
 * [file] (AES/CTR) while an HMAC accumulates over the ciphertext as it is written
 * (encrypt-then-MAC, so verification never decrypts unauthenticated data).
 *
 * After the stream is closed, call [finish] with the final rotation to patch the header
 * and append the authentication trailer. A stream that is closed but never finished
 * leaves a playable-but-unverified file (the authenticated flag stays clear) — the same
 * state a mid-recording crash produces.
 */
class EncryptedVideoOutput internal constructor(
    private val file: File,
    private val header: EncvHeader,
    private val salt: ByteArray,
    private val iv: ByteArray,
    private val mac: Mac,
    cipherStream: OutputStream
) : FilterOutputStream(cipherStream) {

    // FilterOutputStream forwards array writes one byte at a time; delegate wholesale.
    override fun write(b: ByteArray, off: Int, len: Int) {
        out.write(b, off, len)
    }

    /**
     * Finalizes the file: patches the header (final rotation + authenticated flag) and
     * appends the trailer. The MAC input is ciphertext ‖ final header ‖ salt ‖ iv — the
     * header goes last because its final contents are only known when recording ends.
     * Call after [close], from the thread that owns the recording teardown.
     */
    fun finish(rotationDegrees: Int) {
        val finalHeader = header.copy(
            rotationDegrees = rotationDegrees,
            flags = header.flags or EncvHeader.FLAG_AUTHENTICATED,
            version = EncvHeader.VERSION_AUTHENTICATED
        )
        val headerBytes = finalHeader.toBytes()
        EncvHeader.patchHeader(file, finalHeader)

        mac.update(headerBytes)
        mac.update(salt)
        mac.update(iv)
        FileOutputStream(file, true).use { stream ->
            stream.write(EncvHeader.TRAILER_MAGIC)
            stream.write(mac.doFinal())
        }
    }
}

/** Tees every written byte into [mac]. Sits below the cipher, so it sees ciphertext. */
internal class MacOutputStream(out: OutputStream, private val mac: Mac) : FilterOutputStream(out) {
    override fun write(b: Int) {
        mac.update(b.toByte())
        out.write(b)
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        mac.update(b, off, len)
        out.write(b, off, len)
    }
}
