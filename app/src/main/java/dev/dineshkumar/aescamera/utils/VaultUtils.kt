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
 * encrypted with the vault's key by the normal PBKDF2+AES scheme. The vault for a key is
 * found by test-decrypting verifiers, so guessing a key from the directory tree costs a
 * full PBKDF2 derivation per guess, the same as attacking an encrypted media file.
 *
 * Vault names used to be MD5(key), which a GPU can brute-force offline at billions of
 * guesses per second; legacy vaults are adopted (verifier written, directory renamed)
 * the first time their key is entered.
 */
object VaultUtils {
    private const val TAG = "VaultUtils"
    private const val VERIFIER_FILE_NAME = ".vault"
    private val VERIFIER_MAGIC = "AESCameraVault:v1".toByteArray(Charsets.UTF_8)

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
        // An existing vault whose verifier this key decrypts.
        baseDir.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name }?.forEach { dir ->
            if (verifierMatches(File(dir, VERIFIER_FILE_NAME), key)) {
                return renameIfLegacy(dir, key)
            }
        }

        // A legacy vault named MD5(key): adopt it.
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

    private fun verifierMatches(verifier: File, key: String): Boolean {
        if (!verifier.isFile) {
            return false
        }
        return try {
            EncryptionUtils.decrypt(verifier.readBytes(), key).contentEquals(VERIFIER_MAGIC)
        } catch (e: Exception) {
            false // wrong key (bad padding) or unreadable/corrupt verifier
        }
    }

    private fun writeVerifier(dir: File, key: String) {
        val verifier = File(dir, VERIFIER_FILE_NAME)
        if (!verifier.exists()) {
            verifier.writeBytes(EncryptionUtils.encrypt(VERIFIER_MAGIC, key))
        }
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
