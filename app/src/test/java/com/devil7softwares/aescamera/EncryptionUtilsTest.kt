package com.devil7softwares.aescamera

import com.devil7softwares.aescamera.utils.EncryptionUtils
import com.devil7softwares.aescamera.utils.EncvHeader
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File
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
    fun patchRotationRewritesHeaderOnly() {
        val plaintext = Random.nextBytes(10_000)
        val file = File.createTempFile("encv-rot", ".encv").apply { deleteOnExit() }

        EncryptionUtils.createEncryptedVideoOutputStream(file, password, EncvHeader())
            .use { it.write(plaintext) }
        val before = file.readBytes()

        EncvHeader.patchRotation(file, 270)
        val after = file.readBytes()

        assertEquals(270, EncvHeader.fromBytes(after).rotationDegrees)
        // Everything except the rotation byte is untouched
        assertArrayEquals(
            before.filterIndexed { i, _ -> i != 6 }.toByteArray(),
            after.filterIndexed { i, _ -> i != 6 }.toByteArray()
        )
    }
}
