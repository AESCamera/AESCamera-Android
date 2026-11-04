package dev.dineshkumar.aescamera.utils

import android.content.Context
import android.util.Log
import dev.dineshkumar.aescamera.R
import java.io.File
import java.io.IOException

class MigrationCancelledException : Exception("Vault move cancelled")

/**
 * Resolves where the vault directory tree lives and moves it between locations.
 *
 * [VaultLocation.PRIVATE] is the app's internal storage, which nothing but this app (or
 * root) can read; backups go through the export feature. [VaultLocation.SHARED] is the
 * historic Android/media location, visible to file managers, USB and apps with broad
 * storage access — convenient for people who manage their own backups, at the cost of
 * exposing file names (capture times), counts and sizes.
 *
 * New installs default to private; an install that already has a shared vault stays
 * shared until the user moves it from Settings.
 */
object VaultStorage {
    private const val TAG = "VaultStorage"
    private const val PRIVATE_DIR_NAME = "vault"

    fun sharedBaseDir(context: Context): File? =
        context.externalMediaDirs.firstOrNull()?.let {
            File(it, context.resources.getString(R.string.app_name))
        }

    fun privateBaseDir(context: Context): File = File(context.filesDir, PRIVATE_DIR_NAME)

    /** The base directory for [location], without creating it. */
    fun baseDir(context: Context, location: VaultLocation): File = when (location) {
        VaultLocation.PRIVATE -> privateBaseDir(context)
        VaultLocation.SHARED -> sharedBaseDir(context) ?: privateBaseDir(context)
    }

    /** The configured location, decided and stored on first call. */
    fun location(context: Context): VaultLocation {
        val prefs = AppPrefs(context)
        prefs.vaultLocation?.let { return it }

        val shared = sharedBaseDir(context)
        val hasSharedVault = shared != null && shared.listFiles()?.any { it.isDirectory } == true
        val decided = if (hasSharedVault) VaultLocation.SHARED else VaultLocation.PRIVATE
        prefs.vaultLocation = decided
        return decided
    }

    /** The base directory currently in use, created and prepared for use. */
    fun currentBaseDir(context: Context): File {
        val location = location(context)
        val dir = baseDir(context, location).apply { mkdirs() }
        if (location == VaultLocation.SHARED) {
            // Keep media scanners out of the ciphertext tree.
            runCatching { File(dir, ".nomedia").createNewFile() }
        }
        return dir
    }

    /** Every regular file under [base] (all vaults, their verifiers and thumbnails). */
    fun filesUnder(base: File): List<File> =
        if (base.isDirectory) base.walkTopDown().filter { it.isFile }.toList() else emptyList()

    /**
     * Copies the whole tree under [from] into [to], then deletes [from]. Files are
     * ciphertext, so nothing is re-encrypted. If anything fails or [isCancelled]
     * becomes true, the files copied so far are removed again and the source is left
     * intact. Blocking — call off the main thread.
     */
    fun migrate(
        from: File,
        to: File,
        isCancelled: () -> Boolean = { false },
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }
    ) {
        if (from.canonicalPath == to.canonicalPath) {
            return
        }
        val files = filesUnder(from)
        to.mkdirs()
        val copied = ArrayList<File>()
        try {
            files.forEachIndexed { index, source ->
                if (isCancelled()) {
                    throw MigrationCancelledException()
                }
                val relative = source.relativeTo(from).path
                val target = File(to, relative)
                target.parentFile?.mkdirs()
                source.copyTo(target, overwrite = true)
                if (target.length() != source.length()) {
                    throw IOException("Short copy of $relative")
                }
                copied.add(target)
                onProgress(index + 1, files.size)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Vault move aborted: ${e.message}")
            copied.forEach { runCatching { it.delete() } }
            throw e
        }
        // Everything is in place at the destination: drop the source tree.
        if (!from.deleteRecursively()) {
            Log.w(TAG, "Could not fully remove the old vault location")
        }
    }
}
