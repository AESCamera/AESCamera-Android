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

    // Verifier v2 and the duress password.

    private val v2Size = 37 + VaultUtils.VerifierPayload.SIZE + 16

    private fun withFile(vault: File, name: String = "IMG-1.enc") =
        File(vault, name).apply { writeBytes(ByteArray(8000) { it.toByte() }) }

    @Test
    fun v1VerifierIsUpgradedToV2() {
        val base = tempFolder.root
        val dir = File(base, "fedcba9876543210fedcba9876543210").apply { mkdirs() }
        val v1 = "AESCameraVault:v1".toByteArray(Charsets.UTF_8)
        File(dir, ".vault").writeBytes(EncryptionUtils.encryptImage(v1, "old key", 210_000))

        assertEquals(dir, VaultUtils.findVault("old key", base))

        assertEquals(v2Size, File(dir, ".vault").length().toInt())
        assertEquals(VaultUtils.VerifierPayload(), VaultUtils.readPayload(dir, "old key"))
        assertNull(VaultUtils.findVault("another key", base))
    }

    @Test
    fun payloadRoundTripsAndRejectsOtherPlaintexts() {
        val payload = VaultUtils.VerifierPayload(
            armed = true,
            wipeScope = VaultUtils.WipeScope.THIS_VAULT,
            wipeTarget = "0123456789abcdef0123456789abcdef",
            duressVault = "fedcba9876543210fedcba9876543210",
            duressScope = VaultUtils.WipeScope.ALL_OTHERS,
        )
        val encoded = payload.encode()

        assertEquals(VaultUtils.VerifierPayload.SIZE, encoded.size)
        assertEquals(payload, VaultUtils.VerifierPayload.decode(encoded))
        assertEquals(VaultUtils.VerifierPayload.SIZE, VaultUtils.VerifierPayload().encode().size)
        assertNull(VaultUtils.VerifierPayload.decode("AESCameraVault:v1".toByteArray()))
        assertNull(VaultUtils.VerifierPayload.decode(encoded.copyOf(127)))
    }

    @Test
    fun everyV2VerifierHasTheSameSize() {
        val base = tempFolder.root
        val owner = VaultUtils.openVault("real", base)
        val plain = VaultUtils.openVault("plain", base)
        val duress = VaultUtils.setDuress("real", owner, "duress", VaultUtils.WipeScope.THIS_VAULT, base)

        listOf(owner, plain, duress).forEach {
            assertEquals(v2Size, File(it, ".vault").length().toInt())
        }
    }

    @Test
    fun duressPasswordWipesItsOwnerAndOpensTheDecoy() {
        val base = tempFolder.root
        val owner = VaultUtils.openVault("real", base)
        withFile(owner)
        File(owner, ".thumbs").mkdirs()
        withFile(File(owner, ".thumbs"))
        val other = VaultUtils.openVault("other", base)
        withFile(other)
        val duress = VaultUtils.setDuress("real", owner, "duress", VaultUtils.WipeScope.THIS_VAULT, base)
        withFile(duress, "IMG-decoy.enc")

        assertEquals(duress, VaultUtils.unlockVault("duress", base))

        assertFalse(owner.exists())
        assertNull(VaultUtils.findVault("real", base))
        assertTrue(File(other, "IMG-1.enc").isFile)
        assertTrue(File(duress, "IMG-decoy.enc").isFile)
        // Unlocking with it again is harmless.
        assertEquals(duress, VaultUtils.unlockVault("duress", base))
        assertEquals(other, VaultUtils.findVault("other", base))
    }

    @Test
    fun duressPasswordCanWipeEveryOtherVault() {
        val base = tempFolder.root
        val owner = VaultUtils.openVault("real", base)
        withFile(owner)
        val other = VaultUtils.openVault("other", base)
        withFile(other)
        val duress = VaultUtils.setDuress("real", owner, "duress", VaultUtils.WipeScope.ALL_OTHERS, base)

        assertEquals(duress, VaultUtils.unlockVault("duress", base))

        assertEquals(listOf(duress), base.listFiles()!!.filter { it.isDirectory })
        assertNull(VaultUtils.findVault("real", base))
        assertNull(VaultUtils.findVault("other", base))
    }

    @Test
    fun lookupsNeverTriggerTheWipe() {
        val base = tempFolder.root
        val owner = VaultUtils.openVault("real", base)
        withFile(owner)
        val duress = VaultUtils.setDuress("real", owner, "duress", VaultUtils.WipeScope.ALL_OTHERS, base)

        assertEquals(duress, VaultUtils.findVault("duress", base))
        assertEquals(duress, VaultUtils.findDuressVault("real", owner, "duress", base))
        assertNull(VaultUtils.findDuressVault("real", owner, "wrong", base))

        assertTrue(File(owner, "IMG-1.enc").isFile)
        assertEquals(owner, VaultUtils.unlockVault("real", base))
    }

    @Test
    fun duressPasswordMustNotOpenAnExistingVault() {
        val base = tempFolder.root
        val owner = VaultUtils.openVault("real", base)
        VaultUtils.openVault("taken", base)

        for (candidate in listOf("real", "taken")) {
            try {
                VaultUtils.setDuress("real", owner, candidate, VaultUtils.WipeScope.THIS_VAULT, base)
                throw AssertionError("Accepted a password that opens a vault: $candidate")
            } catch (expected: VaultUtils.DuressPasswordInUseException) {
            }
        }
        assertNull(VaultUtils.readPayload(owner, "real")!!.duressVault)
    }

    @Test
    fun ownerRecordsItsDuressVaultAndCanRemoveIt() {
        val base = tempFolder.root
        val owner = VaultUtils.openVault("real", base)
        val first = VaultUtils.setDuress("real", owner, "duress-1", VaultUtils.WipeScope.THIS_VAULT, base)

        val recorded = VaultUtils.readPayload(owner, "real")!!
        assertEquals(first.name, recorded.duressVault)
        assertEquals(VaultUtils.WipeScope.THIS_VAULT, recorded.duressScope)
        assertFalse(recorded.armed)

        // Replacing it wipes the previous decoy vault.
        val second = VaultUtils.setDuress("real", owner, "duress-2", VaultUtils.WipeScope.ALL_OTHERS, base)
        assertFalse(first.exists())
        assertEquals(second.name, VaultUtils.readPayload(owner, "real")!!.duressVault)

        VaultUtils.removeDuress("real", owner, base)
        assertFalse(second.exists())
        assertNull(VaultUtils.readPayload(owner, "real")!!.duressVault)
        assertNull(VaultUtils.findVault("duress-2", base))
    }

    @Test
    fun interruptedWipeIsFinishedByTheNextDuressUnlock() {
        val base = tempFolder.root
        val owner = VaultUtils.openVault("real", base)
        withFile(owner)
        val duress = VaultUtils.setDuress("real", owner, "duress", VaultUtils.WipeScope.THIS_VAULT, base)
        // A wipe that died after removing the verifier: the media is still there.
        File(owner, ".vault").delete()

        assertEquals(duress, VaultUtils.unlockVault("duress", base))
        assertFalse(owner.exists())
    }

    @Test
    fun wipeOverwritesHeadersBeforeDeleting() {
        val vault = tempFolder.newFolder("0123456789abcdef0123456789abcdef")
        val file = withFile(vault)
        val original = file.readBytes()
        // A second hard link to the same data shows what the wipe wrote before deleting.
        val witness = File(tempFolder.root, "witness")
        java.nio.file.Files.createLink(witness.toPath(), file.toPath())

        VaultUtils.wipeVault(vault)

        assertFalse(vault.exists())
        val after = witness.readBytes()
        assertEquals(original.size, after.size)
        assertFalse(after.copyOfRange(0, 4096).contentEquals(original.copyOfRange(0, 4096)))
        assertTrue(after.copyOfRange(4096, after.size).contentEquals(original.copyOfRange(4096, original.size)))
    }
}
