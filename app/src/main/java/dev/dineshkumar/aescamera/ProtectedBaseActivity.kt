package dev.dineshkumar.aescamera

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity


abstract class ProtectedBaseActivity : AppCompatActivity() {
    private var dialogOpen: Boolean = false
    private var passwordDialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )

        checkPassword()

        (application as AESCameraApplication).keyLiveData.observe(this) {
            checkPassword()
        }
    }

    override fun onResume() {
        super.onResume()

        checkPassword()
    }

    override fun onDestroy() {
        super.onDestroy()
        // A dialog left open across a rotation leaks its window; the recreated
        // activity shows a fresh one from onCreate.
        passwordDialog?.let { runCatching { it.dismiss() } }
        passwordDialog = null
        dialogOpen = false
    }

    private fun checkPassword() {
        val app = application as AESCameraApplication

        if (app.key == null) {
            showPasswordDialog()
        }
    }

    private fun showPasswordDialog() {
        if (dialogOpen) {
            return
        }

        val builder = AlertDialog.Builder(this)
        val inflater = LayoutInflater.from(this)
        val dialogView = inflater.inflate(R.layout.dialog_key, null)
        val passwordEditText = dialogView.findViewById<EditText>(R.id.keyText)

        builder.setView(dialogView)
            .setTitle(getString(R.string.enter_encryption_key_title))
            .setCancelable(false)
            .setPositiveButton("OK") { _, _ ->
                dialogOpen = false
                passwordDialog = null

                val password = passwordEditText.text.toString()
                if (password.isNotEmpty()) {
                    unlock(password)
                } else {
                    showPasswordDialog()
                }
            }

        val dialog = builder.create()
        // A dialog has its own window, which does not inherit the activity's
        // FLAG_SECURE: without this, screenshots and screen recordings blank the
        // activity but capture the dialog — including a password shown with the eye
        // toggle.
        dialog.window?.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )

        dialogOpen = true
        passwordDialog = dialog
        dialog.show()

        // The keyboard's Done action unlocks, like tapping OK.
        passwordEditText.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                true
            } else {
                false
            }
        }
        passwordEditText.requestFocus()
    }

    /**
     * Derives the vault master key (expensive PBKDF2) off the main thread behind a
     * non-cancelable spinner, then sets the key on the application.
     */
    private fun unlock(password: String) {
        val progress = AlertDialog.Builder(this)
            .setView(LayoutInflater.from(this).inflate(R.layout.dialog_unlocking, null))
            .setCancelable(false)
            .create()
        progress.show()

        Thread {
            try {
                (application as AESCameraApplication).key = password
            } catch (e: Exception) {
                Log.e(TAG, "Unlock failed: ${e.message}", e)
            }
            runOnUiThread {
                runCatching { progress.dismiss() }
                if (isFinishing || isDestroyed) {
                    // Rotated away during unlock: the recreated activity prompts itself.
                    return@runOnUiThread
                }
                // If the key didn't take (an error), re-prompt.
                if ((application as AESCameraApplication).key == null) {
                    showPasswordDialog()
                }
            }
        }.start()
    }

    companion object {
        private const val TAG = "ProtectedBaseActivity"
    }
}
