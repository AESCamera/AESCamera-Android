package dev.dineshkumar.aescamera

import dev.dineshkumar.aescamera.utils.EncryptionUtils
import dev.dineshkumar.aescamera.utils.EncvHeader
import dev.dineshkumar.aescamera.utils.EncvVerification
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import kotlin.random.Random

class EncryptionUtilsTest {

    private val password = "test-password"

    @Test
    fun cbcRoundTrip() {
        val plaintext = Random.nextBytes(100_000)
        val encrypted = EncryptionUtils.encrypt(plaintext, password)
        val decrypted = EncryptionUtils.decrypt(encrypted, password)
        assertArrayEquals(plaintext, decrypted)
    }

    @Test
    fun authenticatedImageRoundTrip() {
        val plaintext = Random.nextBytes(100_000)
        val encrypted = EncryptionUtils.encryptImage(plaintext, password)

        assertTrue(EncryptionUtils.isAuthenticatedImage(encrypted))
        // Header: magic "AESP" + version 1.
        assertArrayEquals(
            byteArrayOf(0x41, 0x45, 0x53, 0x50, 1),
            encrypted.sliceArray(0 until 5)
        )

        assertArrayEquals(plaintext, EncryptionUtils.decryptImage(encrypted, password))
    }

    @Test
    fun authenticatedImageIsSelfContained() {
        // The iteration count is read from the header, not assumed by the caller —
        // a thumbnail (lower cost) decrypts through the same call as a photo.
        val plaintext = Random.nextBytes(1_000)
        val encrypted = EncryptionUtils.encryptImage(
            plaintext, password, EncryptionUtils.THUMBNAIL_ITERATIONS
        )
        assertArrayEquals(plaintext, EncryptionUtils.decryptImage(encrypted, password))
    }

    @Test
    fun authenticatedImageRejectsWrongPassword() {
        val encrypted = EncryptionUtils.encryptImage(Random.nextBytes(1_000), password)
        assertThrows(AEADBadTagException::class.java) {
            EncryptionUtils.decryptImage(encrypted, "wrong-password")
        }
    }

    @Test
    fun authenticatedImageRejectsTampering() {
        val encrypted = EncryptionUtils.encryptImage(Random.nextBytes(1_000), password)
        encrypted[encrypted.size / 2] = (encrypted[encrypted.size / 2] + 1).toByte()
        assertThrows(AEADBadTagException::class.java) {
            EncryptionUtils.decryptImage(encrypted, password)
        }
    }

    @Test
    fun authenticatedImageRejectsAbsurdIterationCount() {
        // A crafted header must not be able to stall the app with a huge PBKDF2 cost.
        val encrypted = EncryptionUtils.encryptImage(Random.nextBytes(100), password)
        for (i in 5..8) encrypted[i] = 0x7F // iterations ≈ 2^31
        assertThrows(IllegalArgumentException::class.java) {
            EncryptionUtils.decryptImage(encrypted, password)
        }
    }

    @Test
    fun legacyCbcImageDecryptsThroughDecryptImage() {
        val plaintext = Random.nextBytes(50_000)
        val legacyEncrypted = EncryptionUtils.encrypt(plaintext, password)

        assertFalse(EncryptionUtils.isAuthenticatedImage(legacyEncrypted))
        assertArrayEquals(plaintext, EncryptionUtils.decryptImage(legacyEncrypted, password))
    }

    @Test
    fun addToCounterCarriesAcrossBytes() {
        val zero = ByteArray(16)
        assertArrayEquals(zero, EncryptionUtils.addToCounter(zero, 0))

        val one = EncryptionUtils.addToCounter(zero, 1)
        assertEquals(1, one[15].toInt())

        // 0x0100 = 256 carries into byte 14
        val carried = EncryptionUtils.addToCounter(zero, 256)
        assertEquals(1, carried[14].toInt())
        assertEquals(0, carried[15].toInt())

        // All-0xFF low byte + 1 propagates the carry
        val nearOverflow = ByteArray(16) { if (it >= 8) 0xFF.toByte() else 0 }
        val wrapped = EncryptionUtils.addToCounter(nearOverflow, 1)
        assertEquals(1, wrapped[7].toInt())
        for (i in 8..15) assertEquals(0, wrapped[i].toInt())
    }

    @Test
    fun encryptedVideoStreamRoundTrip() {
        val plaintext = Random.nextBytes(200_000)
        val file = File.createTempFile("encv-test", ".encv").apply { deleteOnExit() }

        val header = EncvHeader(rotationDegrees = 90)
        EncryptionUtils.createEncryptedVideoOutputStream(file, password, header).use { out ->
            // Write in odd-sized chunks to exercise cipher block buffering
            var written = 0
            while (written < plaintext.size) {
                val chunk = minOf(7777, plaintext.size - written)
                out.write(plaintext, written, chunk)
                written += chunk
            }
        }

        val bytes = file.readBytes()
        assertEquals(EncvHeader.PREFIX_SIZE + plaintext.size, bytes.size)

        val parsed = EncvHeader.fromBytes(bytes)
        assertEquals(90, parsed.rotationDegrees)
        assertEquals(EncvHeader.CONTAINER_MPEG2_TS, parsed.container)

        val salt = bytes.sliceArray(16 until 32)
        val iv = bytes.sliceArray(32 until 48)
        val ciphertext = bytes.sliceArray(EncvHeader.PREFIX_SIZE until bytes.size)

        val cipher = EncryptionUtils.ctrCipherAt(Cipher.DECRYPT_MODE, password, salt, iv)
        assertArrayEquals(plaintext, cipher.doFinal(ciphertext))
    }

    @Test
    fun ctrSeekMatchesFullDecrypt() {
        val plaintext = Random.nextBytes(70_000)
        val file = File.createTempFile("encv-seek", ".encv").apply { deleteOnExit() }

        EncryptionUtils.createEncryptedVideoOutputStream(file, password, EncvHeader())
            .use { it.write(plaintext) }

        val bytes = file.readBytes()
        val salt = bytes.sliceArray(16 until 32)
        val iv = bytes.sliceArray(32 until 48)
        val ciphertext = bytes.sliceArray(EncvHeader.PREFIX_SIZE until bytes.size)

        // Decrypting from an arbitrary offset must equal the corresponding slice of
        // the plaintext — this is the property seekable playback depends on.
        for (offset in longArrayOf(0, 1, 15, 16, 17, 4096, 65537)) {
            val cipher =
                EncryptionUtils.ctrCipherAt(Cipher.DECRYPT_MODE, password, salt, iv, offset)
            val decrypted = cipher.doFinal(ciphertext, offset.toInt(), 1024)
            val expected = plaintext.sliceArray(offset.toInt() until offset.toInt() + 1024)
            assertArrayEquals("offset $offset", expected, decrypted)
        }
    }

    @Test
    fun headerRejectsGarbage() {
        assertThrows(IllegalArgumentException::class.java) {
            EncvHeader.fromBytes(Random.nextBytes(16))
        }
    }

    @Test
    fun finishPatchesHeaderAndAppendsTrailerOnly() {
        val plaintext = Random.nextBytes(10_000)
        val file = File.createTempFile("encv-rot", ".encv").apply { deleteOnExit() }

        val out = EncryptionUtils.createEncryptedVideoOutputStream(file, password, EncvHeader())
        out.use { it.write(plaintext) }
        val before = file.readBytes()

        out.finish(270)
        val after = file.readBytes()

        assertEquals(before.size + EncvHeader.TRAILER_SIZE, after.size)
        val header = EncvHeader.fromBytes(after)
        assertEquals(270, header.rotationDegrees)
        assertTrue(header.isAuthenticated)
        // The ciphertext (and salt/iv) is untouched — only the header changed and the
        // trailer was appended.
        assertArrayEquals(
            before.sliceArray(EncvHeader.SIZE until before.size),
            after.sliceArray(EncvHeader.SIZE until before.size)
        )
    }

    private fun writeFinishedVideo(file: File, plaintext: ByteArray, rotation: Int = 90) {
        val out = EncryptionUtils.createEncryptedVideoOutputStream(file, password, EncvHeader())
        out.use { it.write(plaintext) }
        out.finish(rotation)
    }

    @Test
    fun finishedVideoVerifies() {
        val plaintext = Random.nextBytes(100_000)
        val file = File.createTempFile("encv-verify", ".encv").apply { deleteOnExit() }
        writeFinishedVideo(file, plaintext)

        assertEquals(EncvVerification.VERIFIED, EncryptionUtils.verifyEncv(file, password))

        // The ciphertext region (trailer excluded) still decrypts to the plaintext.
        val bytes = file.readBytes()
        val salt = bytes.sliceArray(16 until 32)
        val iv = bytes.sliceArray(32 until 48)
        val ciphertext =
            bytes.sliceArray(EncvHeader.PREFIX_SIZE until bytes.size - EncvHeader.TRAILER_SIZE)
        val cipher = EncryptionUtils.ctrCipherAt(Cipher.DECRYPT_MODE, password, salt, iv)
        assertArrayEquals(plaintext, cipher.doFinal(ciphertext))
    }

    @Test
    fun verifyDetectsTamperedCiphertext() {
        val file = File.createTempFile("encv-tamper", ".encv").apply { deleteOnExit() }
        writeFinishedVideo(file, Random.nextBytes(10_000))

        val bytes = file.readBytes()
        bytes[EncvHeader.PREFIX_SIZE + 5000] = (bytes[EncvHeader.PREFIX_SIZE + 5000] + 1).toByte()
        file.writeBytes(bytes)

        assertEquals(EncvVerification.FAILED, EncryptionUtils.verifyEncv(file, password))
    }

    @Test
    fun verifyDetectsTamperedHeader() {
        val file = File.createTempFile("encv-tamper-hdr", ".encv").apply { deleteOnExit() }
        writeFinishedVideo(file, Random.nextBytes(10_000), rotation = 270)

        val bytes = file.readBytes()
        bytes[6] = 0 // flip rotation 270 -> 0: presentation metadata is covered too
        file.writeBytes(bytes)

        assertEquals(EncvVerification.FAILED, EncryptionUtils.verifyEncv(file, password))
    }

    @Test
    fun verifyDetectsStrippedTrailer() {
        val file = File.createTempFile("encv-strip", ".encv").apply { deleteOnExit() }
        writeFinishedVideo(file, Random.nextBytes(10_000))

        val bytes = file.readBytes()
        file.writeBytes(bytes.sliceArray(0 until bytes.size - EncvHeader.TRAILER_SIZE))

        // The header's authenticated flag promises a trailer; its absence is tampering.
        assertEquals(EncvVerification.FAILED, EncryptionUtils.verifyEncv(file, password))
    }

    @Test
    fun verifyRejectsWrongPassword() {
        val file = File.createTempFile("encv-wrongpw", ".encv").apply { deleteOnExit() }
        writeFinishedVideo(file, Random.nextBytes(10_000))

        assertEquals(
            EncvVerification.FAILED,
            EncryptionUtils.verifyEncv(file, "wrong-password")
        )
    }

    @Test
    fun unfinishedRecordingIsUnverified() {
        // A recording that dies mid-stream never gets finish(): flag clear, no trailer.
        val file = File.createTempFile("encv-crash", ".encv").apply { deleteOnExit() }
        EncryptionUtils.createEncryptedVideoOutputStream(file, password, EncvHeader())
            .use { it.write(Random.nextBytes(10_000)) }

        assertFalse(EncvHeader.fromFile(file).isAuthenticated)
        assertEquals(EncvVerification.UNVERIFIED, EncryptionUtils.verifyEncv(file, password))
    }

    @Test
    fun legacyV2FileIsUnverifiedAndStillDecrypts() {
        val plaintext = Random.nextBytes(10_000)
        val salt = Random.nextBytes(16)
        val iv = Random.nextBytes(16)
        val cipher = EncryptionUtils.ctrCipherAt(Cipher.ENCRYPT_MODE, password, salt, iv)
        val file = File.createTempFile("encv-v2", ".encv").apply { deleteOnExit() }
        file.writeBytes(
            EncvHeader(version = EncvHeader.VERSION_CTR).toBytes() + salt + iv +
                cipher.doFinal(plaintext)
        )

        assertEquals(EncvVerification.UNVERIFIED, EncryptionUtils.verifyEncv(file, password))

        val header = EncvHeader.fromFile(file)
        assertEquals(EncvHeader.VERSION_CTR, header.version)
        assertEquals(0, header.trailerSize)

        val bytes = file.readBytes()
        val decrypt = EncryptionUtils.ctrCipherAt(Cipher.DECRYPT_MODE, password, salt, iv)
        assertArrayEquals(
            plaintext,
            decrypt.doFinal(bytes.sliceArray(EncvHeader.PREFIX_SIZE until bytes.size))
        )
    }
}
