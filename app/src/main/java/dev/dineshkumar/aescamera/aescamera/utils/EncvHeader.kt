package dev.dineshkumar.aescamera.utils

import java.io.File
import java.io.RandomAccessFile

/**
 * Plaintext header of .encv (encrypted video) files, format v2:
 *
 * ```
 * [ magic "AESV": 4 ][ version: 1 ][ container: 1 ][ rotation/90: 1 ][ flags: 1 ][ reserved: 8 ]
 * [ salt: 16 ][ iv: 16 ]
 * [ AES-256/CTR/NoPadding ciphertext ... ]
 * ```
 *
 * The header carries only non-sensitive presentation metadata. Rotation is stored here
 * because the MPEG-2 TS container (unlike MP4) has no rotation metadata; the player must
 * apply it. Ciphertext starts at byte [PREFIX_SIZE].
 */
data class EncvHeader(
    val container: Int = CONTAINER_MPEG2_TS,
    val rotationDegrees: Int = 0,
    val flags: Int = 0
) {
    init {
        require(rotationDegrees in intArrayOf(0, 90, 180, 270)) {
            "Rotation must be one of 0/90/180/270, got $rotationDegrees"
        }
    }

    fun toBytes(): ByteArray {
        val bytes = ByteArray(SIZE)
        MAGIC.copyInto(bytes)
        bytes[4] = VERSION.toByte()
        bytes[5] = container.toByte()
        bytes[6] = (rotationDegrees / 90).toByte()
        bytes[7] = flags.toByte()
        // bytes 8..15 reserved, zero
        return bytes
    }

    companion object {
        val MAGIC = byteArrayOf(0x41, 0x45, 0x53, 0x56) // "AESV"
        const val VERSION = 2
        const val SIZE = 16
        const val SALT_SIZE = 16
        const val IV_SIZE = 16

        /** Offset where the AES-CTR ciphertext begins. */
        const val PREFIX_SIZE = SIZE + SALT_SIZE + IV_SIZE // 48

        const val CONTAINER_MPEG2_TS = 1

        private const val ROTATION_OFFSET = 6L

        fun fromBytes(bytes: ByteArray): EncvHeader {
            require(bytes.size >= SIZE) { "Header requires $SIZE bytes, got ${bytes.size}" }
            require(bytes.sliceArray(0 until 4).contentEquals(MAGIC)) { "Bad magic, not an .encv file" }
            val version = bytes[4].toInt()
            require(version == VERSION) { "Unsupported .encv version $version" }
            return EncvHeader(
                container = bytes[5].toInt(),
                rotationDegrees = bytes[6].toInt() * 90,
                flags = bytes[7].toInt()
            )
        }

        /**
         * Rewrites the rotation byte of an existing .encv file. The final rotation is only
         * known from CameraView's VideoResult when recording ends, while the header is
         * written when the stream opens — so recording writes rotation 0 and patches it
         * here on completion. (The header is plaintext, so this needs no re-encryption.)
         */
        fun patchRotation(file: File, rotationDegrees: Int) {
            require(rotationDegrees in intArrayOf(0, 90, 180, 270)) {
                "Rotation must be one of 0/90/180/270, got $rotationDegrees"
            }
            RandomAccessFile(file, "rw").use { raf ->
                raf.seek(ROTATION_OFFSET)
                raf.write(rotationDegrees / 90)
            }
        }
    }
}
