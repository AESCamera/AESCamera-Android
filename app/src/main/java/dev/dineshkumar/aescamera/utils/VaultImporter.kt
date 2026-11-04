package dev.dineshkumar.aescamera.utils

import android.util.Log
import dev.dineshkumar.aescamera.AESCameraApplication
import dev.dineshkumar.aescamera.importer.ImportValidator
import java.io.File

sealed class VaultImportResult {
    data class Success(val file: File) : VaultImportResult()

    /** The file does not decrypt (or verify) under the current password. */
    object WrongPassword : VaultImportResult()

    data class Failed(val error: Throwable) : VaultImportResult()
}

/**
 * Copies an already-encrypted file (a shared .enc/.encv, or one picked from a backup)
 * into the current vault and builds its thumbnail. Files are self-contained ciphertext,
 * so no re-encryption happens; a file that does not open with the current password is
 * removed again and reported, since this vault could never show it.
 *
 * Blocking (PBKDF2 + a full read): call off the main thread.
 */
object VaultImporter {
    private const val TAG = "VaultImporter"

    fun importFile(
        application: AESCameraApplication,
        source: File,
        preferredName: String
    ): VaultImportResult {
        val key = application.key
            ?: return VaultImportResult.Failed(IllegalStateException("Vault is locked"))
        val vault = application.ensureVault()
            ?: return VaultImportResult.Failed(IllegalStateException("Vault is locked"))

        val isVideo = preferredName.lowercase().endsWith(".encv")
        val name = ImportValidator.sanitizeName(preferredName, isVideo)
        val target = uniqueFile(vault, name)

        return try {
            vault.mkdirs()
            source.copyTo(target, overwrite = false)

            if (isVideo) {
                if (EncryptionUtils.verifyEncv(target, key) == EncvVerification.FAILED) {
                    target.delete()
                    return VaultImportResult.WrongPassword
                }
                runCatching { VideoThumbnailer.createThumbnail(application, target, key) }
                    .onFailure { Log.w(TAG, "No thumbnail for imported video: ${it.message}") }
            } else {
                val decrypted = try {
                    EncryptionUtils.decryptImage(target.readBytes(), key)
                } catch (e: Exception) {
                    target.delete()
                    return VaultImportResult.WrongPassword
                }
                runCatching { ThumbnailUtils.createImageThumbnail(application, target.name, decrypted) }
                    .onFailure { Log.w(TAG, "No thumbnail for imported photo: ${it.message}") }
            }
            VaultImportResult.Success(target)
        } catch (e: Exception) {
            Log.e(TAG, "Import failed: ${e.message}", e)
            runCatching { target.delete() }
            VaultImportResult.Failed(e)
        }
    }

    /** [name], or [name]-1, -2, … if the vault already has a file of that name. */
    fun uniqueFile(dir: File, name: String): File {
        var candidate = File(dir, name)
        var counter = 1
        val base = name.substringBeforeLast('.')
        val extension = name.substringAfterLast('.', "")
        while (candidate.exists()) {
            candidate = File(dir, "$base-$counter.$extension")
            counter++
        }
        return candidate
    }
}
