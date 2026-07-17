package dev.dineshkumar.aescamera.utils

import java.io.DataInputStream
import java.io.File
import java.io.RandomAccessFile

/**
 * Plaintext header of .encv (encrypted video) files:
 *
 * ```
 * [ magic "AESV": 4 ][ version: 1 ][ container: 1 ][ rotation/90: 1 ][ flags: 1 ][ reserved: 8 ]
 * [ salt: 16 ][ iv: 16 ]
 * [ AES-256/CTR/NoPadding ciphertext ... ]
 * [ v3, finalized only: trailer "AESM": 4 ][ HMAC-SHA256: 32 ]
 * ```
 *
 * The header carries only non-sensitive presentation metadata. Rotation is stored here
 * because the MPEG-2 TS container (unlike MP4) has no rotation metadata; the player must
 * apply it. Ciphertext starts at byte [PREFIX_SIZE].
 *
 * Version 3 adds the authentication trailer (encrypt-then-MAC over
 * ciphertext‖header‖salt‖iv, see EncryptionUtils.verifyEncv). [FLAG_AUTHENTICATED] is set
 * only when recording finalizes successfully — a recording that died mid-stream has the
 * flag clear and no trailer, and stays playable as far as it got (MPEG-TS survives
 * truncation). Version 2 files (no trailer, ever) remain fully readable.
 */
data class EncvHeader(
    val container: Int = CONTAINER_MPEG2_TS,
    val rotationDegrees: Int = 0,
    val flags: Int = 0,
    val version: Int = VERSION_AUTHENTICATED
) {
    init {
        require(rotationDegrees in intArrayOf(0, 90, 180, 270)) {
            "Rotation must be one of 0/90/180/270, got $rotationDegrees"
        }
        require(version in VERSION_CTR..VERSION_AUTHENTICATED) {
            "Unsupported .encv version $version"
        }
    }

    /** True if the file was finalized with the v3 authentication trailer. */
    val isAuthenticated: Boolean
        get() = version >= VERSION_AUTHENTICATED && (flags and FLAG_AUTHENTICATED) != 0

    /** Bytes after the ciphertext (the trailer), 0 for v2 or unfinalized v3 files. */
    val trailerSize: Int
        get() = if (isAuthenticated) TRAILER_SIZE else 0

    fun toBytes(): ByteArray {
        val bytes = ByteArray(SIZE)
        MAGIC.copyInto(bytes)
        bytes[4] = version.toByte()
        bytes[5] = container.toByte()
        bytes[6] = (rotationDegrees / 90).toByte()
        bytes[7] = flags.toByte()
        // bytes 8..15 reserved, zero
        return bytes
    }

    companion object {
        val MAGIC = byteArrayOf(0x41, 0x45, 0x53, 0x56) // "AESV"

        /** v2: AES/CTR, no integrity (legacy recordings). */
        const val VERSION_CTR = 2

        /** v3: AES/CTR + HMAC-SHA256 trailer when finalized. */
        const val VERSION_AUTHENTICATED = 3

        const val SIZE = 16
        const val SALT_SIZE = 16
        const val IV_SIZE = 16

        /** Offset where the AES-CTR ciphertext begins. */
        const val PREFIX_SIZE = SIZE + SALT_SIZE + IV_SIZE // 48

        const val CONTAINER_MPEG2_TS = 1

        /** Set at finalize time; a clear flag means "no trailer follows". */
        const val FLAG_AUTHENTICATED = 0x01

        val TRAILER_MAGIC = byteArrayOf(0x41, 0x45, 0x53, 0x4D) // "AESM"
        const val TRAILER_SIZE = 4 + 32 // magic + HMAC-SHA256

        fun fromBytes(bytes: ByteArray): EncvHeader {
            require(bytes.size >= SIZE) { "Header requires $SIZE bytes, got ${bytes.size}" }
            require(bytes.sliceArray(0 until 4).contentEquals(MAGIC)) { "Bad magic, not an .encv file" }
            val version = bytes[4].toInt()
            require(version in VERSION_CTR..VERSION_AUTHENTICATED) {
                "Unsupported .encv version $version"
            }
            return EncvHeader(
                container = bytes[5].toInt(),
                rotationDegrees = bytes[6].toInt() * 90,
                flags = bytes[7].toInt(),
                version = version
            )
        }

        /** Reads the plaintext header from the start of an .encv file. */
        fun fromFile(file: File): EncvHeader {
            val bytes = ByteArray(SIZE)
            DataInputStream(file.inputStream()).use { it.readFully(bytes) }
            return fromBytes(bytes)
        }

        /**
         * Rewrites the plaintext header block of an existing .encv file. Used at
         * finalize time: the final rotation is only known from CameraView's VideoResult
         * when recording ends, and the authenticated flag must not be set until the
         * trailer is about to be written — so recording starts with rotation 0 / flags 0
         * and the finished header is patched in here. (The header is plaintext, so this
         * needs no re-encryption.)
         */
        fun patchHeader(file: File, header: EncvHeader) {
            RandomAccessFile(file, "rw").use { raf ->
                raf.seek(0)
                raf.write(header.toBytes())
            }
        }
    }
}
