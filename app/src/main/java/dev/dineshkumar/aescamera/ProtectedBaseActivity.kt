package dev.dineshkumar.aescamera

import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.view.LayoutInflater
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.CheckBox
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity


abstract class ProtectedBaseActivity : AppCompatActivity() {
    private var dialogOpen: Boolean = false

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
        val showPasswordCheckBox = dialogView.findViewById<CheckBox>(R.id.showKey)

        showPasswordCheckBox.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                passwordEditText.inputType = EditorInfo.TYPE_CLASS_TEXT
            } else {
                passwordEditText.inputType =
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
        }

        builder.setView(dialogView)
            .setTitle(getString(R.string.enter_encryption_key_title))
            .setCancelable(false)
            .setPositiveButton("OK") { _, _ ->
                dialogOpen = false

                val password = passwordEditText.text.toString()
                if (password.isNotEmpty()) {
                    unlock(password)
                } else {
                    showPasswordDialog()
                }
            }

        dialogOpen = true
        builder.create().show()
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
                // If the key didn't take (error/empty vault), re-prompt.
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
