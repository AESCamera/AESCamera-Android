package dev.dineshkumar.aescamera

import android.os.Bundle
import android.text.InputType
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
                    (application as AESCameraApplication).key = password
                } else {
                    showPasswordDialog()
                }
            }

        dialogOpen = true
        builder.create().show()
    }
}
