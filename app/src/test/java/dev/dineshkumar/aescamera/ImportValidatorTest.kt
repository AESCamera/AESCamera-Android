package dev.dineshkumar.aescamera

import dev.dineshkumar.aescamera.importer.ImportValidator
import dev.dineshkumar.aescamera.utils.EncryptionUtils
import dev.dineshkumar.aescamera.utils.EncvHeader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.random.Random

class ImportValidatorTest {

    @Test
    fun displayNamesCannotEscapeTheVault() {
        // A provider-supplied DISPLAY_NAME is attacker-controlled: separators, parent
        // references and hidden-file dots must not survive.
        assertEquals("x.enc", ImportValidator.sanitizeName("../../x.enc", isVideo = false))
        assertEquals("x.enc", ImportValidator.sanitizeName("..\\..\\x.enc", isVideo = false))
        assertEquals("vault.enc", ImportValidator.sanitizeName(".vault.enc", isVideo = false))
        assertEquals("b.enc", ImportValidator.sanitizeName("IMG-2026-11-04 a/b.enc", isVideo = false))
        assertEquals(
            "IMG_2026_a.enc",
            ImportValidator.sanitizeName("IMG 2026 a.enc", isVideo = false)
        )
    }

    @Test
    fun unusableNamesAreReplacedWithGeneratedOnes() {
        assertTrue(ImportValidator.sanitizeName(null, isVideo = false).matches(Regex("IMG-.*\\.enc")))
        assertTrue(ImportValidator.sanitizeName("", isVideo = true).matches(Regex("VID-.*\\.encv")))
        assertTrue(ImportValidator.sanitizeName(".enc", isVideo = false).matches(Regex("IMG-.*\\.enc")))
        // Wrong extension for the kind: regenerated rather than mislabelled.
        assertTrue(ImportValidator.sanitizeName("clip.enc", isVideo = true).endsWith(".encv"))
        assertTrue(ImportValidator.sanitizeName("a".repeat(300) + ".enc", isVideo = false).length <= 120)
    }

    @Test
    fun magicBytesGateWhatIsOpened() {
        val photo = EncryptionUtils.encryptImage(Random.nextBytes(100), "pw")
        assertTrue(ImportValidator.looksEncrypted(photo, photo.size, photo.size.toLong(), isVideo = false))

        val legacy = EncryptionUtils.encrypt(Random.nextBytes(100), "pw")
        assertTrue(ImportValidator.looksEncrypted(legacy, 48, legacy.size.toLong(), isVideo = false))
        // Unknown size is allowed for legacy files, a non-block-aligned size is not.
        assertTrue(ImportValidator.looksEncrypted(legacy, 48, -1, isVideo = false))
        assertFalse(ImportValidator.looksEncrypted(legacy, 48, legacy.size + 1L, isVideo = false))

        val video = EncvHeader().toBytes() + Random.nextBytes(32)
        assertTrue(ImportValidator.looksEncrypted(video, 48, 10_000, isVideo = true))
        assertFalse(ImportValidator.looksEncrypted(Random.nextBytes(48), 48, 10_000, isVideo = true))
        assertFalse(ImportValidator.looksEncrypted(Random.nextBytes(48), 10, 10_000, isVideo = true))
    }

    @Test
    fun copyStopsAtTheLimit() {
        val data = Random.nextBytes(200_000)
        val out = ByteArrayOutputStream()
        assertTrue(ImportValidator.copyWithLimit(ByteArrayInputStream(data), out, 200_000))
        assertEquals(data.size, out.size())

        val capped = ByteArrayOutputStream()
        assertFalse(ImportValidator.copyWithLimit(ByteArrayInputStream(data), capped, 100_000))
    }
}
