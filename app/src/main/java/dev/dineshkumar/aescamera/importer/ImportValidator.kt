package dev.dineshkumar.aescamera.importer

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import androidx.annotation.StringRes
import dev.dineshkumar.aescamera.R
import dev.dineshkumar.aescamera.utils.CommonUtils
import dev.dineshkumar.aescamera.utils.EncryptionUtils
import dev.dineshkumar.aescamera.utils.EncvHeader
import java.io.InputStream
import java.io.OutputStream

/** What [ImportValidator.inspect] decided about a file another app handed us. */
sealed class Inspection {
    abstract val uri: Uri

    /** [name] is already sanitised; [size] is -1 when the provider did not say. */
    data class Accepted(
        override val uri: Uri,
        val name: String,
        val size: Long,
        val isVideo: Boolean
    ) : Inspection()

    data class Rejected(override val uri: Uri, @StringRes val reason: Int) : Inspection()
}

/**
 * Checks files arriving from other apps (share sheet, "open with", the restore picker)
 * before anything else happens to them. Everything about such a file — its name, size
 * and content — is attacker-controlled, so the name is rebuilt from a safe character
 * set, the size is bounded, and the first bytes must look like one of our formats
 * before the user is even asked for a password.
 */
object ImportValidator {
    /** Photos are decrypted whole in memory, so they are capped hard. */
    const val MAX_IMAGE_BYTES = 64L * 1024 * 1024

    /** Videos stream, so the cap only guards against filling the disk. */
    const val MAX_VIDEO_BYTES = 8L * 1024 * 1024 * 1024

    private const val MAX_NAME_LENGTH = 120
    private val UNSAFE_CHARS = Regex("[^A-Za-z0-9._-]")

    fun inspect(resolver: ContentResolver, uri: Uri): Inspection {
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) {
            return Inspection.Rejected(uri, R.string.import_rejected_unsupported)
        }

        var displayName: String? = null
        var size = -1L
        runCatching {
            resolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0 && !cursor.isNull(nameIndex)) {
                        displayName = cursor.getString(nameIndex)
                    }
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                        size = cursor.getLong(sizeIndex)
                    }
                }
            }
        }

        val rawName = displayName ?: uri.lastPathSegment
        val lower = rawName?.lowercase() ?: ""
        val isVideo = lower.endsWith(".encv")
        if (!isVideo && !lower.endsWith(".enc")) {
            return Inspection.Rejected(uri, R.string.import_rejected_not_encrypted)
        }

        val head = ByteArray(EncvHeader.PREFIX_SIZE)
        val headRead = try {
            resolver.openInputStream(uri)?.use { readUpTo(it, head) }
                ?: return Inspection.Rejected(uri, R.string.import_rejected_unreadable)
        } catch (e: Exception) {
            return Inspection.Rejected(uri, R.string.import_rejected_unreadable)
        }

        if (!looksEncrypted(head, headRead, size, isVideo)) {
            return Inspection.Rejected(uri, R.string.import_rejected_not_encrypted)
        }
        if (size > (if (isVideo) MAX_VIDEO_BYTES else MAX_IMAGE_BYTES)) {
            return Inspection.Rejected(uri, R.string.import_rejected_too_large)
        }

        return Inspection.Accepted(uri, sanitizeName(rawName, isVideo), size, isVideo)
    }

    /**
     * True if the first [read] bytes of [head] (and the total [size], -1 if unknown)
     * are consistent with an .encv file, an authenticated .enc photo, or a legacy
     * AES/CBC .enc photo (salt + iv + whole blocks, no magic to check).
     */
    fun looksEncrypted(head: ByteArray, read: Int, size: Long, isVideo: Boolean): Boolean {
        if (isVideo) {
            return read >= EncvHeader.SIZE &&
                    runCatching { EncvHeader.fromBytes(head.copyOf(read)) }.isSuccess
        }
        if (read >= 5 && EncryptionUtils.isAuthenticatedImage(head.copyOf(read))) {
            return true
        }
        return read >= EncvHeader.PREFIX_SIZE && (size < 0 || (size >= 48 && (size - 32) % 16 == 0L))
    }

    /**
     * Rebuilds a file name from a provider-supplied display name: only the last path
     * segment, only safe characters, no leading dot (hidden files, and the vault's own
     * ".vault" verifier), bounded length, and the right extension — or a fresh generated
     * name when nothing usable is left.
     */
    fun sanitizeName(raw: String?, isVideo: Boolean): String {
        val extension = if (isVideo) ".encv" else ".enc"
        val lastSegment = raw?.substringAfterLast('/')?.substringAfterLast('\\')?.trim() ?: ""
        var cleaned = UNSAFE_CHARS.replace(lastSegment, "_").trimStart('.')
        if (cleaned.length > MAX_NAME_LENGTH) {
            cleaned = cleaned.takeLast(MAX_NAME_LENGTH)
        }
        if (cleaned.length <= extension.length || !cleaned.lowercase().endsWith(extension)) {
            return if (isVideo) CommonUtils.getVideoFileName() else CommonUtils.getImageFileName()
        }
        return cleaned
    }

    /**
     * Copies [input] to [output], refusing (returns false) once more than [limit] bytes
     * have been read, so a provider that lies about its size cannot fill the disk or,
     * for photos, the heap.
     */
    fun copyWithLimit(input: InputStream, output: OutputStream, limit: Long): Boolean {
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) return true
            total += read
            if (total > limit) return false
            output.write(buffer, 0, read)
        }
    }

    private fun readUpTo(input: InputStream, buffer: ByteArray): Int {
        var total = 0
        while (total < buffer.size) {
            val read = input.read(buffer, total, buffer.size - total)
            if (read < 0) break
            total += read
        }
        return total
    }
}
