package dev.dineshkumar.aescamera.utils

import android.content.Context
import android.util.Log
import dev.dineshkumar.aescamera.R
import java.io.File
import java.security.SecureRandom

/**
 * Locates or creates the vault directory for a key.
 *
 * Vault directories have random names that carry no information about the key. Each
 * vault contains a hidden verifier file ([VERIFIER_FILE_NAME]) — a fixed magic string
 * sealed with the key in the authenticated image format (PBKDF2-SHA256 + AES-GCM,
 * [VERIFIER_ITERATIONS]). The vault for a key is found by test-opening verifiers, so
 * guessing a key from the directory tree costs a full PBKDF2 derivation per guess, the
 * same as attacking an encrypted media file.
 *
 * Older layouts are upgraded on first unlock: a pre-authentication AES/CBC verifier is
 * rewritten in the sealed format, and legacy MD5(key)-named directories (GPU
 * brute-forceable) are adopted and renamed.
 */
object VaultUtils {
    private const val TAG = "VaultUtils"
    private const val VERIFIER_FILE_NAME = ".vault"
    private val VERIFIER_MAGIC = "AESCameraVault:v1".toByteArray(Charsets.UTF_8)

    // PBKDF2 cost of the verifier — the gate an attacker must pass per password guess.
    // Paid once per vault scanned at unlock, off the main thread (dialog_unlocking).
    private const val VERIFIER_ITERATIONS = 210_000

    /** Returns the vault directory for [key], creating it if none exists yet. */
    fun openVault(key: String, context: Context): File {
        val mediaDir = context.externalMediaDirs.firstOrNull()?.let {
            File(it, context.resources.getString(R.string.app_name)).apply { mkdirs() }
        }
        if (mediaDir == null || !mediaDir.exists()) {
            // No shared storage available; keep the historic internal-storage fallback.
            return context.filesDir
        }
        return openVault(key, mediaDir)
    }

    /** [openVault] core, separated from [Context] for unit testing. */
    fun openVault(key: String, baseDir: File): File {
        // An existing vault whose verifier this key opens.
        baseDir.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name }?.forEach { dir ->
            if (verifierMatchesOrUpgrades(File(dir, VERIFIER_FILE_NAME), dir, key)) {
                return renameIfLegacy(dir, key)
            }
        }

        // A legacy vault named MD5(key) with no verifier at all: adopt it.
        val legacyDir = File(baseDir, CommonUtils.getKeyDigest(key))
        if (legacyDir.isDirectory) {
            Log.i(TAG, "Adopting legacy MD5-named vault")
            writeVerifier(legacyDir, key)
            return renameIfLegacy(legacyDir, key)
        }

        // No vault for this key yet: create one under a random name.
        val newDir = File(baseDir, randomVaultName()).apply { mkdirs() }
        writeVerifier(newDir, key)
        return newDir
    }

    /**
     * True if [verifier] belongs to [key]. A matching legacy AES/CBC verifier is
     * rewritten in the sealed format in place so later unlocks authenticate properly.
     */
    private fun verifierMatchesOrUpgrades(verifier: File, dir: File, key: String): Boolean {
        if (!verifier.isFile) {
            return false
        }
        val bytes = try {
            verifier.readBytes()
        } catch (e: Exception) {
            Log.e(TAG, "Unreadable verifier in ${dir.name}", e)
            return false
        }

        val isLegacy = !EncryptionUtils.isAuthenticatedImage(bytes)
        val matches = try {
            EncryptionUtils.decryptImage(bytes, key).contentEquals(VERIFIER_MAGIC)
        } catch (e: Exception) {
            false // wrong key (bad tag / bad padding) or corrupt verifier
        }
        if (matches && isLegacy) {
            Log.i(TAG, "Upgrading legacy CBC verifier to the authenticated format")
            writeVerifier(dir, key)
        }
        return matches
    }

    private fun writeVerifier(dir: File, key: String) {
        val sealed = EncryptionUtils.encryptImage(VERIFIER_MAGIC, key, VERIFIER_ITERATIONS)
        File(dir, VERIFIER_FILE_NAME).writeBytes(sealed)
    }

    /**
     * Renames an MD5(key)-named vault to a random name — the old name is offline
     * brute-forceable. If the rename fails the vault keeps working under its old name
     * (found via its verifier) and the rename is retried on the next unlock.
     */
    private fun renameIfLegacy(dir: File, key: String): File {
        if (dir.name != CommonUtils.getKeyDigest(key)) {
            return dir
        }
        val renamed = File(dir.parentFile, randomVaultName())
        return if (dir.renameTo(renamed)) {
            Log.i(TAG, "Renamed legacy vault to a random name")
            renamed
        } else {
            Log.w(TAG, "Could not rename legacy vault; keeping MD5 name for now")
            dir
        }
    }

    private fun randomVaultName(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
