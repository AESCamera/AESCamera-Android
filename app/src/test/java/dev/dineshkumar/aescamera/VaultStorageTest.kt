package dev.dineshkumar.aescamera

import dev.dineshkumar.aescamera.utils.MigrationCancelledException
import dev.dineshkumar.aescamera.utils.VaultStorage
import dev.dineshkumar.aescamera.utils.VaultUtils
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class VaultStorageTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun populate(base: File): File {
        val vault = VaultUtils.createVault("pw", base)
        File(vault, "IMG-1.enc").writeBytes(byteArrayOf(1, 2, 3))
        File(vault, "VID-1.encv").writeBytes(ByteArray(1000) { it.toByte() })
        File(vault, ".thumbs").mkdirs()
        File(vault, ".thumbs/IMG-1.enc").writeBytes(byteArrayOf(9))
        return vault
    }

    @Test
    fun migrateMovesTheWholeTreeAndFindsTheVaultAgain() {
        val from = tempFolder.newFolder("shared")
        val to = File(tempFolder.root, "private")
        val vault = populate(from)
        val expected = VaultStorage.filesUnder(from).associate {
            it.relativeTo(from).path to it.readBytes()
        }

        val progress = ArrayList<Pair<Int, Int>>()
        VaultStorage.migrate(from, to) { done, total -> progress.add(done to total) }

        assertFalse(from.exists())
        assertEquals(expected.keys, VaultStorage.filesUnder(to).map { it.relativeTo(to).path }.toSet())
        for ((path, bytes) in expected) {
            assertArrayEquals(path, bytes, File(to, path).readBytes())
        }
        assertEquals(expected.size, progress.size)
        assertEquals(expected.size to expected.size, progress.last())
        // The moved vault still opens with the same password under its old name.
        assertEquals(vault.name, VaultUtils.findVault("pw", to)?.name)
    }

    @Test
    fun cancelledMigrationLeavesTheSourceIntact() {
        val from = tempFolder.newFolder("shared")
        val to = File(tempFolder.root, "private")
        populate(from)
        val before = VaultStorage.filesUnder(from).size

        var copied = 0
        assertThrows(MigrationCancelledException::class.java) {
            VaultStorage.migrate(from, to, { copied >= 2 }) { done, _ -> copied = done }
        }

        assertEquals(before, VaultStorage.filesUnder(from).size)
        assertTrue(VaultStorage.filesUnder(to).isEmpty())
    }

    @Test
    fun migrateToTheSamePlaceIsANoOp() {
        val from = tempFolder.newFolder("same")
        populate(from)
        val before = VaultStorage.filesUnder(from).size
        VaultStorage.migrate(from, File(from.path))
        assertEquals(before, VaultStorage.filesUnder(from).size)
    }
}
