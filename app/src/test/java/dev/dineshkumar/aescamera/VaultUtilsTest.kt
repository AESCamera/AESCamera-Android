package dev.dineshkumar.aescamera

import dev.dineshkumar.aescamera.utils.CommonUtils
import dev.dineshkumar.aescamera.utils.VaultUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
}
