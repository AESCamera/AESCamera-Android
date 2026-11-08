package dev.dineshkumar.aescamera

import dev.dineshkumar.aescamera.utils.EncryptionUtils
import dev.dineshkumar.aescamera.utils.EncvHeader
import dev.dineshkumar.aescamera.utils.VideoKdf
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * The test vectors of the shared format spec (docs/FORMAT_SPEC.md, "Test vectors"),
 * computed independently with Python and openssl. The iOS app pins the same table in
 * AESCore's ReferenceVectorTests, so both apps are tied to one definition.
 */
class CrossPlatformVectorsTest {

    private val salt = ByteArray(16) { it.toByte() }

    private fun pbkdf2(algorithm: String, password: String, iterations: Int): ByteArray =
        SecretKeyFactory.getInstance(algorithm)
            .generateSecret(PBEKeySpec(password.toCharArray(), salt, iterations, 256))
            .encoded

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    @Test
    fun pbkdf2Vectors() {
        assertEquals(
            "0ac89cbd459b1c6b36b6d5a1e65b966122cfcd07627d57b3d3111ef5c27db611",
            hex(pbkdf2("PBKDF2WithHmacSHA256", "test-password", 1000))
        )
        assertEquals(
            "ac84284f385ee4d9df612ace65a7a2e246a2593f18afba9177d5df1239da34da",
            hex(pbkdf2("PBKDF2WithHmacSHA1", "test-password", 1000))
        )
    }

    @Test
    fun passwordsAreUtf8WithoutNormalisation() {
        // "pässwörd" precomposed (NFC) and decomposed (NFD) are different passwords.
        assertEquals(
            "2f56986c68f37683f0c4f846ad3742cc90085e0bfdf205d7f45ec47e3caadce8",
            hex(pbkdf2("PBKDF2WithHmacSHA256", "pässwörd", 1000))
        )
        assertEquals(
            "8602ef6adaae267b928f7ae96bff28e72677294eedaec3209023e3fbfc5f259a",
            hex(pbkdf2("PBKDF2WithHmacSHA256", "pässwörd", 1000))
        )
        assertEquals(
            "fc7e9a4cbc1446ab41adbb525e95afa55cc73452bf61779b7a4f1ddb074b46aa",
            hex(pbkdf2("PBKDF2WithHmacSHA256", "🔒key", 1000))
        )
    }

    @Test
    fun encvMacKey() {
        val fileKey = pbkdf2("PBKDF2WithHmacSHA256", "test-password", 1000)
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(fileKey, "HmacSHA256")) }
        assertEquals(
            "717bbdc18b42ebed9505b1ef176d65204d189fd162d3e4449ebf4e10b343f886",
            hex(mac.doFinal("AESCamera encv v3 mac".toByteArray(Charsets.US_ASCII)))
        )
    }

    @Test
    fun ctrKeystreamWithCounterCarry() {
        val kdf = VideoKdf(VideoKdf.ID_SHA256, 1000)
        // The low bytes are ff ff, so the counter carries into byte 13 after one block.
        val counter = ByteArray(16) { if (it >= 14) 0xFF.toByte() else it.toByte() }
        val plaintext = ByteArray(4096) { (it % 251).toByte() }

        val cipher = EncryptionUtils.ctrCipherAt(Cipher.ENCRYPT_MODE, "test-password", salt, counter, kdf)
        val ciphertext = cipher.update(plaintext)
        assertEquals(
            "c6239b6491f7f5cbe0ca9f4b527ca4e18d38d32c6c93278f3cddf63f1cddf3fac44ef2e4fd3117df756a21aeefe36bec",
            hex(ciphertext.copyOf(48))
        )
        assertEquals(
            "b380506722d8af1e4498383d22f7e88a1bdccd8f858f21a5823ff17881030191",
            hex(MessageDigest.getInstance("SHA-256").digest(ciphertext))
        )

        // Random access: decrypting from an unaligned offset gives the same plaintext.
        for (offset in intArrayOf(1, 15, 16, 17, 1000, 4095)) {
            val part = EncryptionUtils.ctrCipherAt(
                Cipher.DECRYPT_MODE, "test-password", salt, counter, kdf, offset.toLong()
            ).update(ciphertext, offset, ciphertext.size - offset)
            assertArrayEquals("offset $offset", plaintext.copyOfRange(offset, plaintext.size), part)
        }
    }

    @Test
    fun containersAreKnownOrRejected() {
        fun headerBytes(container: Int) = EncvHeader().toBytes().also { it[5] = container.toByte() }

        assertEquals(EncvHeader.CONTAINER_MPEG2_TS, EncvHeader.fromBytes(headerBytes(1)).container)
        // iOS recordings: fragmented MP4, rotation in the track matrix so the header says 0.
        val ios = EncvHeader.fromBytes(headerBytes(2))
        assertEquals(EncvHeader.CONTAINER_FRAGMENTED_MP4, ios.container)
        assertEquals(0, ios.rotationDegrees)

        for (unknown in intArrayOf(0, 3, 255)) {
            assertThrows(IllegalArgumentException::class.java) {
                EncvHeader.fromBytes(headerBytes(unknown))
            }
        }
    }
}
