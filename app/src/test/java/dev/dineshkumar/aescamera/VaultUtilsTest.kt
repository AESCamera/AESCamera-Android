package dev.dineshkumar.aescamera

import dev.dineshkumar.aescamera.utils.CommonUtils
import dev.dineshkumar.aescamera.utils.EncryptionUtils
import dev.dineshkumar.aescamera.utils.VaultUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class VaultUtilsTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun createsVaultWithRandomNameAndFindsItAgain() {
        val base = tempFolder.root

        val vault = VaultUtils.openVault("correct horse", base)

        assertTrue(vault.isDirectory)
        assertNotEquals(CommonUtils.getKeyDigest("correct horse"), vault.name)
        assertTrue(File(vault, ".vault").isFile)

        assertEquals(vault, VaultUtils.openVault("correct horse", base))
    }

    @Test
    fun findVaultNeverCreatesOne() {
        val base = tempFolder.root

        // Unlocking with a key that has no vault must leave the directory tree
        // untouched: a typo at the password prompt creates nothing.
        assertNull(VaultUtils.findVault("typo", base))
        assertEquals(0, base.listFiles()?.size)

        val vault = VaultUtils.createVault("typo", base)
        assertTrue(File(vault, ".vault").isFile)
        assertEquals(vault, VaultUtils.findVault("typo", base))
        assertNull(VaultUtils.findVault("other", base))
    }

    @Test
    fun differentKeysGetDifferentVaults() {
        val base = tempFolder.root

        val vaultA = VaultUtils.openVault("key-a", base)
        val vaultB = VaultUtils.openVault("key-b", base)

        assertNotEquals(vaultA, vaultB)
        assertTrue(vaultA.isDirectory)
        assertTrue(vaultB.isDirectory)
    }

    @Test
    fun legacyMd5VaultIsAdoptedAndRenamed() {
        val base = tempFolder.root
        val legacyName = CommonUtils.getKeyDigest("test")
        val legacyDir = File(base, legacyName).apply { mkdirs() }
        File(legacyDir, "IMG-1.enc").writeBytes(byteArrayOf(1, 2, 3))

        val vault = VaultUtils.openVault("test", base)

        assertNotEquals(legacyName, vault.name)
        assertFalse(File(base, legacyName).exists())
        assertTrue(File(vault, "IMG-1.enc").isFile)
        assertTrue(File(vault, ".vault").isFile)

        // Reopening finds the migrated vault instead of creating a new one.
        assertEquals(vault, VaultUtils.openVault("test", base))
    }

    @Test
    fun newVerifierUsesAuthenticatedFormat() {
        val vault = VaultUtils.openVault("some key", tempFolder.root)

        assertTrue(EncryptionUtils.isAuthenticatedImage(File(vault, ".vault").readBytes()))
    }

    @Test
    fun legacyCbcVerifierIsAcceptedAndUpgraded() {
        val base = tempFolder.root
        val dir = File(base, "0123456789abcdef0123456789abcdef").apply { mkdirs() }
        val magic = "AESCameraVault:v1".toByteArray(Charsets.UTF_8)
        File(dir, ".vault").writeBytes(EncryptionUtils.encrypt(magic, "legacy key"))

        val vault = VaultUtils.openVault("legacy key", base)

        assertEquals(dir, vault)
        // The verifier was rewritten in the authenticated format and still opens.
        assertTrue(EncryptionUtils.isAuthenticatedImage(File(vault, ".vault").readBytes()))
        assertEquals(vault, VaultUtils.openVault("legacy key", base))
    }
}
