package dev.dineshkumar.aescamera.utils

import android.app.Activity
import android.view.LayoutInflater
import android.widget.ProgressBar
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import dev.dineshkumar.aescamera.R

/**
 * A modal "working…" dialog with a determinate bar for the long file operations (vault
 * move, export, restore). [cancelled] is polled by the worker thread; the dialog itself
 * cannot be dismissed by Back or a tap outside.
 */
class ProgressDialogController(
    private val activity: Activity,
    @StringRes private val title: Int,
    cancellable: Boolean
) {
    @Volatile
    var cancelled = false
        private set

    private val view = LayoutInflater.from(activity).inflate(R.layout.dialog_progress, null)
    private val bar = view.findViewById<ProgressBar>(R.id.progressBar)
    private val text = view.findViewById<TextView>(R.id.progressText)
    private val dialog: AlertDialog = AlertDialog.Builder(activity)
        .setView(view)
        .setCancelable(false)
        .apply {
            if (cancellable) {
                setNegativeButton(R.string.cancel) { _, _ -> cancelled = true }
            }
        }
        .create()

    fun show() {
        text.setText(title)
        bar.isIndeterminate = true
        dialog.show()
    }

    /** Call on the main thread. */
    fun progress(done: Int, total: Int) {
        bar.isIndeterminate = false
        bar.max = total.coerceAtLeast(1)
        bar.progress = done
        text.text = activity.getString(R.string.progress_count, activity.getString(title), done, total)
    }

    fun dismiss() {
        runCatching { dialog.dismiss() }
    }
}
