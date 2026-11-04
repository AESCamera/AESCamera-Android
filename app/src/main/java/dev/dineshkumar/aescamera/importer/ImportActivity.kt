package dev.dineshkumar.aescamera.importer

import android.app.Dialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.WindowManager
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.IntentCompat
import dev.dineshkumar.aescamera.R
import dev.dineshkumar.aescamera.viewer.MediaViewerActivity
import java.io.File

/**
 * The only exported entry point for files from other apps: "Open with" (ACTION_VIEW)
 * and "Share to" (ACTION_SEND / SEND_MULTIPLE) from messengers, mail clients and file
 * managers. It validates each file ([ImportValidator]), asks the user to confirm what is
 * being opened and from where, copies the ciphertext into the app's own cache, and
 * only then hands a path inside our storage to the (non-exported) viewer or player —
 * which is where the password prompt happens. Nothing here needs the key.
 */
class ImportActivity : AppCompatActivity() {

    private var progress: Dialog? = null
    private var confirmation: Dialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )

        val uris = incomingUris()
        if (uris.isEmpty()) {
            Toast.makeText(this, R.string.error_no_file_selected, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        // Content queries and the magic-byte read go to another app's provider: keep
        // them off the main thread.
        Thread {
            val results = uris.take(MAX_FILES).map { ImportValidator.inspect(contentResolver, it) }
            runOnUiThread {
                if (!isFinishing && !isDestroyed) {
                    confirm(results)
                }
            }
        }.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        progress?.let { runCatching { it.dismiss() } }
        progress = null
        confirmation?.let { runCatching { it.dismiss() } }
        confirmation = null
    }

    private fun incomingUris(): List<Uri> = when (intent.action) {
        Intent.ACTION_SEND -> listOfNotNull(
            IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                ?: intent.data
        )

        Intent.ACTION_SEND_MULTIPLE ->
            IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                ?.filterNotNull() ?: emptyList()

        else -> listOfNotNull(intent.data)
    }

    private fun confirm(results: List<Inspection>) {
        val accepted = results.filterIsInstance<Inspection.Accepted>()
        val rejected = results.filterIsInstance<Inspection.Rejected>()

        if (accepted.isEmpty()) {
            val reason = rejected.firstOrNull()?.reason ?: R.string.import_rejected_unsupported
            Toast.makeText(this, reason, Toast.LENGTH_LONG).show()
            finish()
            return
        }

        val sender = senderLabel()
        val message = StringBuilder(
            if (accepted.size == 1) {
                getString(R.string.import_confirm_one, accepted[0].name, sender)
            } else {
                resources.getQuantityString(
                    R.plurals.import_confirm_many, accepted.size, accepted.size, sender
                )
            }
        )
        if (rejected.isNotEmpty()) {
            message.append("\n\n")
            message.append(
                resources.getQuantityString(
                    R.plurals.import_skipped, rejected.size, rejected.size
                )
            )
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.import_confirm_title)
            .setMessage(message)
            .setNegativeButton(R.string.cancel) { _, _ -> finish() }
            .setPositiveButton(R.string.import_open) { _, _ -> copyAndOpen(accepted) }
            .setOnCancelListener { finish() }
            .create()
        dialog.window?.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )
        confirmation = dialog
        dialog.show()
    }

    /** The human-readable name of the app that sent the file, if Android tells us. */
    private fun senderLabel(): String {
        val referrer = referrer
        val packageName = if (referrer?.scheme == "android-app") referrer.host else null
        return packageName?.let { pkg ->
            runCatching {
                packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0))
                    .toString()
            }.getOrNull()
        } ?: getString(R.string.import_unknown_sender)
    }

    private fun copyAndOpen(accepted: List<Inspection.Accepted>) {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_progress, null)
        view.findViewById<ProgressBar>(R.id.progressBar).isIndeterminate = true
        view.findViewById<TextView>(R.id.progressText).setText(R.string.importing_message)
        progress = AlertDialog.Builder(this).setView(view).setCancelable(false).create()
            .also { it.show() }

        Thread {
            val cacheDir = importCacheDir(this).apply {
                deleteRecursively()
                mkdirs()
            }
            val copied = ArrayList<String>()
            for (item in accepted) {
                val target = uniqueFile(cacheDir, item.name)
                val limit =
                    if (item.isVideo) ImportValidator.MAX_VIDEO_BYTES else ImportValidator.MAX_IMAGE_BYTES
                val ok = try {
                    contentResolver.openInputStream(item.uri)?.use { input ->
                        target.outputStream().use { output ->
                            ImportValidator.copyWithLimit(input, output, limit)
                        }
                    } ?: false
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to copy shared file: ${e.message}", e)
                    false
                }
                if (ok) {
                    copied.add(target.absolutePath)
                } else {
                    target.delete()
                }
            }

            runOnUiThread {
                progress?.let { runCatching { it.dismiss() } }
                progress = null
                if (isFinishing || isDestroyed) {
                    return@runOnUiThread
                }
                if (copied.isEmpty()) {
                    Toast.makeText(this, R.string.import_failed_copy, Toast.LENGTH_LONG).show()
                } else {
                    startActivity(MediaViewerActivity.intentForImported(this, copied))
                }
                finish()
            }
        }.start()
    }

    private fun uniqueFile(dir: File, name: String): File {
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

    companion object {
        private const val TAG = "ImportActivity"
        private const val MAX_FILES = 50

        /** Where shared files are staged; wiped on each import and at app start. */
        fun importCacheDir(context: android.content.Context): File =
            File(context.cacheDir, "import")
    }
}
