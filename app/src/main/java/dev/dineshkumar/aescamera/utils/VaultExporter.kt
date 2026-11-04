package dev.dineshkumar.aescamera.utils

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import dev.dineshkumar.aescamera.AESCameraApplication
import dev.dineshkumar.aescamera.importer.ImportActivity
import dev.dineshkumar.aescamera.importer.ImportValidator
import dev.dineshkumar.aescamera.importer.Inspection
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Backup and restore through the system file picker (Storage Access Framework), so the
 * destination can be an SD card, a USB drive or any documents provider such as a cloud
 * drive. Every vault file is self-contained ciphertext, so copies need no wrapping and
 * are useless without the password. Both directions are blocking: call off the main
 * thread.
 */
object VaultExporter {
    private const val TAG = "VaultExporter"

    // A MIME type no provider maps to an extension, so the file keeps its .enc/.encv name
    // instead of gaining ".bin" (which application/octet-stream causes on some providers).
    private const val EXPORT_MIME = "application/x-aescamera"

    data class ExportSummary(val exported: Int, val failed: Int)

    data class RestoreSummary(val restored: Int, val wrongPassword: Int, val skipped: Int)

    /** The files worth backing up from [vault]: the verifier and the media, not thumbnails. */
    fun backupFiles(vault: File): List<File> {
        val verifier = File(vault, ".vault").takeIf { it.isFile }
        val media = vault.listFiles()
            ?.filter {
                it.isFile && !it.name.startsWith(".") &&
                        (it.extension == "enc" || it.extension == "encv")
            }
            ?.sortedBy { it.name }
            ?: emptyList()
        return listOfNotNull(verifier) + media
    }

    /** Copies [files] into a new dated folder under the picked [treeUri]. */
    fun export(
        context: Context,
        treeUri: Uri,
        files: List<File>,
        isCancelled: () -> Boolean = { false },
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }
    ): ExportSummary {
        val tree = DocumentFile.fromTreeUri(context, treeUri)
            ?: throw IOException("Backup folder is not accessible")
        val folderName =
            "AESCamera-" + SimpleDateFormat("yyyy-MM-dd-HH-mm", Locale.US).format(Date())
        val folder = tree.findFile(folderName)?.takeIf { it.isDirectory }
            ?: tree.createDirectory(folderName)
            ?: throw IOException("Could not create the backup folder")

        var exported = 0
        var failed = 0
        files.forEachIndexed { index, file ->
            if (isCancelled()) {
                throw MigrationCancelledException()
            }
            try {
                val document = folder.createFile(EXPORT_MIME, file.name)
                    ?: throw IOException("Could not create ${file.name}")
                context.contentResolver.openOutputStream(document.uri)?.use { output ->
                    file.inputStream().use { it.copyTo(output) }
                } ?: throw IOException("Could not open ${file.name} for writing")
                exported++
            } catch (e: Exception) {
                Log.e(TAG, "Export of ${file.name} failed: ${e.message}", e)
                failed++
            }
            onProgress(index + 1, files.size)
        }
        return ExportSummary(exported, failed)
    }

    private fun isVerifier(application: AESCameraApplication, uri: Uri): Boolean {
        val name = runCatching {
            application.contentResolver.query(
                uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment
        return name?.substringAfterLast('/') == ".vault"
    }

    /**
     * Brings picked backup files into the current vault: each one is validated like a
     * shared file, staged in the cache, then imported (which also rejects files sealed
     * with a different password and builds thumbnails).
     */
    fun restore(
        application: AESCameraApplication,
        uris: List<Uri>,
        isCancelled: () -> Boolean = { false },
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }
    ): RestoreSummary {
        val stagingDir = File(ImportActivity.importCacheDir(application), "restore").apply {
            deleteRecursively()
            mkdirs()
        }
        var restored = 0
        var wrongPassword = 0
        var skipped = 0
        try {
            uris.forEachIndexed { index, uri ->
                if (isCancelled()) {
                    throw MigrationCancelledException()
                }
                if (isVerifier(application, uri)) {
                    // The exported .vault verifier belongs to the old vault; this vault
                    // has its own. Not a file to restore, and not worth reporting.
                    onProgress(index + 1, uris.size)
                    return@forEachIndexed
                }
                val inspection = ImportValidator.inspect(application.contentResolver, uri)
                if (inspection !is Inspection.Accepted) {
                    skipped++
                    onProgress(index + 1, uris.size)
                    return@forEachIndexed
                }
                val staged = File(stagingDir, inspection.name)
                val limit = if (inspection.isVideo) ImportValidator.MAX_VIDEO_BYTES
                else ImportValidator.MAX_IMAGE_BYTES
                val copied = try {
                    application.contentResolver.openInputStream(uri)?.use { input ->
                        staged.outputStream().use { ImportValidator.copyWithLimit(input, it, limit) }
                    } ?: false
                } catch (e: Exception) {
                    Log.e(TAG, "Could not read ${inspection.name}: ${e.message}", e)
                    false
                }
                if (!copied) {
                    staged.delete()
                    skipped++
                } else {
                    when (VaultImporter.importFile(application, staged, inspection.name)) {
                        is VaultImportResult.Success -> restored++
                        VaultImportResult.WrongPassword -> wrongPassword++
                        is VaultImportResult.Failed -> skipped++
                    }
                    staged.delete()
                }
                onProgress(index + 1, uris.size)
            }
        } finally {
            stagingDir.deleteRecursively()
        }
        return RestoreSummary(restored, wrongPassword, skipped)
    }
}
