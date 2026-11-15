package dev.dineshkumar.aescamera.settings

import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.MenuItem
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.CheckBox
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import dev.dineshkumar.aescamera.AESCameraApplication
import dev.dineshkumar.aescamera.ProtectedBaseActivity
import dev.dineshkumar.aescamera.R
import dev.dineshkumar.aescamera.databinding.ActivitySettingsBinding
import dev.dineshkumar.aescamera.utils.AppPrefs
import dev.dineshkumar.aescamera.utils.DecoyImporter
import dev.dineshkumar.aescamera.utils.MigrationCancelledException
import dev.dineshkumar.aescamera.utils.ProgressDialogController
import dev.dineshkumar.aescamera.utils.VaultExporter
import dev.dineshkumar.aescamera.utils.VaultLocation
import dev.dineshkumar.aescamera.utils.VaultStorage
import dev.dineshkumar.aescamera.utils.VaultUtils
import dev.dineshkumar.aescamera.utils.applySystemBarInsetsAsPadding
import java.io.File

/**
 * Vault location (private app storage or shared storage, moved on change), backup and
 * restore through the system file picker, the duress password, and the capture toggles
 * (rapid capture, last capture preview).
 */
class SettingsActivity : ProtectedBaseActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var prefs: AppPrefs
    private var busy = false

    // A pick made while the vault locked (more than a few seconds in the system picker
    // locks it, see AESCameraApplication). It runs once the vault is unlocked again.
    private var pendingExport: Uri? = null
    private var pendingRestore: List<Uri>? = null

    // The decoy vault (and its password) photos are being picked for, and a pick that
    // came back while the vault was locked.
    private var decoyTarget: Pair<String, File>? = null
    private var pendingDecoy: List<Uri>? = null

    private val exportLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri == null) return@registerForActivityResult
            if (isLocked()) pendingExport = uri else runExport(uri)
        }

    private val restoreLauncher =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNullOrEmpty()) return@registerForActivityResult
            if (isLocked()) pendingRestore = uris else runRestore(uris)
        }

    private val decoyPicker =
        registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
            if (decoyTarget == null || uris.isEmpty()) {
                decoyTarget = null
                return@registerForActivityResult
            }
            if (isLocked()) pendingDecoy = uris else runDecoyImport(uris)
        }

    private fun isLocked() = (application as AESCameraApplication).key == null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = AppPrefs(this)

        supportActionBar?.apply {
            title = getString(R.string.settings_title)
            setDisplayHomeAsUpEnabled(true)
        }
        // The dispatched top inset already includes the action bar; as a margin it keeps
        // scrolled content from showing through the status bar (see FilesListActivity).
        binding.settingsScroll.applySystemBarInsetsAsPadding(topAsMargin = true)

        showLocation(VaultStorage.location(this))
        binding.locationGroup.setOnCheckedChangeListener { _, checkedId ->
            val chosen = if (checkedId == R.id.locationShared) VaultLocation.SHARED
            else VaultLocation.PRIVATE
            if (!busy && chosen != VaultStorage.location(this)) {
                confirmMove(chosen)
            }
        }

        binding.rapidCaptureSwitch.isChecked = prefs.rapidCapture
        binding.rapidCaptureSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.rapidCapture = checked
        }
        binding.showLastCaptureSwitch.isChecked = prefs.showLastCapture
        binding.showLastCaptureSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.showLastCapture = checked
        }

        binding.exportButton.setOnClickListener {
            val app = application as AESCameraApplication
            val vault = app.outputDirectory
            if (vault == null || VaultExporter.backupFiles(vault).isEmpty()) {
                Toast.makeText(this, R.string.export_nothing, Toast.LENGTH_SHORT).show()
            } else {
                exportLauncher.launch(null)
            }
        }
        binding.restoreButton.setOnClickListener {
            restoreLauncher.launch(arrayOf("*/*"))
        }

        binding.duressSetupButton.setOnClickListener { showDuressSetup() }
        binding.duressDecoyButton.setOnClickListener { askDuressPassword() }
        binding.duressRemoveButton.setOnClickListener { confirmRemoveDuress() }

        (application as AESCameraApplication).keyLiveData.observe(this) { key ->
            showDuressState()
            if (key == null) return@observe
            pendingExport?.let { pendingExport = null; runExport(it) }
            pendingRestore?.let { pendingRestore = null; runRestore(it) }
            pendingDecoy?.let { pendingDecoy = null; runDecoyImport(it) }
        }
    }

    /** Only the owner fields are shown, so a decoy vault looks like any other vault. */
    private fun showDuressState() {
        val payload = (application as AESCameraApplication).vaultPayload
        val set = payload?.duressVault != null
        binding.duressStatus.setText(
            when {
                !set -> R.string.settings_duress_desc
                payload?.duressScope == VaultUtils.WipeScope.ALL_OTHERS -> R.string.settings_duress_set_all
                else -> R.string.settings_duress_set_this
            }
        )
        binding.duressSetupButton.setText(if (set) R.string.settings_duress_change else R.string.settings_duress_setup)
        binding.duressDecoyButton.isVisible = set
        binding.duressRemoveButton.isVisible = set
    }

    /** Password dialogs get their own FLAG_SECURE window (see ProtectedBaseActivity). */
    private fun AlertDialog.secure(): AlertDialog = apply {
        window?.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
    }

    private fun showDuressSetup() {
        val app = application as AESCameraApplication
        val ownerKey = app.key ?: return
        val view = layoutInflater.inflate(R.layout.dialog_duress_setup, null)
        val passwordLayout = view.findViewById<TextInputLayout>(R.id.duressPasswordLayout)
        val password = view.findViewById<TextInputEditText>(R.id.duressPassword)
        val confirmLayout = view.findViewById<TextInputLayout>(R.id.duressConfirmLayout)
        val confirm = view.findViewById<TextInputEditText>(R.id.duressConfirm)
        val scopeGroup = view.findViewById<RadioGroup>(R.id.duressScopeGroup)
        val ack = view.findViewById<CheckBox>(R.id.duressAck)
        view.findViewById<TextView>(R.id.duressChangeWarning).isVisible = app.vaultPayload?.duressVault != null

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.duress_setup_title)
            .setView(view)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.duress_set_action, null)
            .create()
            .secure()
        dialog.setOnShowListener {
            val setButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            setButton.isEnabled = false
            ack.setOnCheckedChangeListener { _, checked -> setButton.isEnabled = checked }
            setButton.setOnClickListener {
                passwordLayout.error = null
                confirmLayout.error = null
                val chosen = password.text.toString()
                when {
                    chosen.isEmpty() -> passwordLayout.error = getString(R.string.duress_error_empty)
                    chosen != confirm.text.toString() -> confirmLayout.error = getString(R.string.duress_error_mismatch)
                    chosen == ownerKey -> passwordLayout.error = getString(R.string.duress_error_same)
                    else -> {
                        val scope = if (scopeGroup.checkedRadioButtonId == R.id.duressScopeAll) {
                            VaultUtils.WipeScope.ALL_OTHERS
                        } else {
                            VaultUtils.WipeScope.THIS_VAULT
                        }
                        dialog.dismiss()
                        runDuressSetup(ownerKey, chosen, scope)
                    }
                }
            }
        }
        dialog.show()
    }

    private fun runDuressSetup(ownerKey: String, duressKey: String, scope: VaultUtils.WipeScope) {
        val app = application as AESCameraApplication
        val progress = ProgressDialogController(this, R.string.duress_setting_up, cancellable = false)
        busy = true
        progress.show()

        Thread {
            val outcome = runCatching {
                val owner = app.ensureVault() ?: error("Locked during setup")
                check(app.key == ownerKey) { "The vault changed during setup" }
                val decoy = VaultUtils.setDuress(ownerKey, owner, duressKey, scope, VaultUtils.baseDir(this))
                app.vaultPayload = (app.vaultPayload ?: VaultUtils.VerifierPayload())
                    .copy(duressVault = decoy.name, duressScope = scope)
                decoy
            }
            runOnUiThread {
                busy = false
                progress.dismiss()
                if (isFinishing || isDestroyed) {
                    return@runOnUiThread
                }
                showDuressState()
                outcome.fold(
                    onSuccess = { decoy -> offerDecoyPhotos(duressKey, decoy) },
                    onFailure = { error ->
                        if (error is VaultUtils.DuressPasswordInUseException) {
                            AlertDialog.Builder(this)
                                .setTitle(R.string.duress_setup_title)
                                .setMessage(R.string.duress_error_in_use)
                                .setPositiveButton(android.R.string.ok, null)
                                .show()
                        } else {
                            Log.e(TAG, "Duress setup failed: ${error.message}", error)
                            Toast.makeText(this, R.string.duress_failed, Toast.LENGTH_LONG).show()
                        }
                    }
                )
            }
        }.start()
    }

    private fun offerDecoyPhotos(duressKey: String, decoy: File) {
        AlertDialog.Builder(this)
            .setTitle(R.string.duress_done_title)
            .setMessage(R.string.duress_done_message)
            .setNegativeButton(R.string.duress_later, null)
            .setPositiveButton(R.string.duress_add_now) { _, _ -> pickDecoyPhotos(duressKey, decoy) }
            .show()
    }

    private fun pickDecoyPhotos(duressKey: String, decoy: File) {
        decoyTarget = duressKey to decoy
        decoyPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    /** Re-asks the duress password: it is never stored, and it is the decoy vault's key. */
    private fun askDuressPassword() {
        val app = application as AESCameraApplication
        val ownerKey = app.key ?: return
        val owner = app.outputDirectory ?: return
        val view = layoutInflater.inflate(R.layout.dialog_duress_password, null)
        val field = view.findViewById<TextInputEditText>(R.id.duressEnter)

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.duress_enter_title)
            .setView(view)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val entered = field.text.toString()
                if (entered.isNotEmpty()) checkDuressPassword(ownerKey, owner, entered)
            }
            .create()
            .secure()
        dialog.show()
        field.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                true
            } else {
                false
            }
        }
        field.requestFocus()
    }

    private fun checkDuressPassword(ownerKey: String, owner: File, duressKey: String) {
        val progress = ProgressDialogController(this, R.string.duress_checking, cancellable = false)
        progress.show()
        Thread {
            val decoy = runCatching {
                VaultUtils.findDuressVault(ownerKey, owner, duressKey, VaultUtils.baseDir(this))
            }.getOrNull()
            runOnUiThread {
                progress.dismiss()
                if (isFinishing || isDestroyed) {
                    return@runOnUiThread
                }
                if (decoy == null) {
                    Toast.makeText(this, R.string.duress_wrong, Toast.LENGTH_LONG).show()
                } else {
                    pickDecoyPhotos(duressKey, decoy)
                }
            }
        }.start()
    }

    private fun runDecoyImport(uris: List<Uri>) {
        val (duressKey, decoy) = decoyTarget ?: return
        decoyTarget = null
        val progress = ProgressDialogController(this, R.string.duress_adding, cancellable = false)
        busy = true
        progress.show()

        Thread {
            val outcome = runCatching {
                DecoyImporter.import(this, uris, duressKey, decoy) { done, total ->
                    runOnUiThread { progress.progress(done, total) }
                }
            }
            runOnUiThread {
                busy = false
                progress.dismiss()
                if (isFinishing || isDestroyed) {
                    return@runOnUiThread
                }
                val summary = outcome.getOrElse {
                    Log.e(TAG, "Decoy import failed: ${it.message}", it)
                    DecoyImporter.Summary(0, uris.size)
                }
                Toast.makeText(
                    this,
                    resources.getQuantityString(R.plurals.duress_added, summary.added, summary.added, summary.skipped),
                    Toast.LENGTH_LONG
                ).show()
            }
        }.start()
    }

    private fun confirmRemoveDuress() {
        AlertDialog.Builder(this)
            .setTitle(R.string.duress_remove_title)
            .setMessage(R.string.duress_remove_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.duress_remove_action) { _, _ -> runRemoveDuress() }
            .show()
    }

    private fun runRemoveDuress() {
        val app = application as AESCameraApplication
        val ownerKey = app.key ?: return
        val owner = app.outputDirectory ?: return
        val progress = ProgressDialogController(this, R.string.duress_setting_up, cancellable = false)
        busy = true
        progress.show()

        Thread {
            val outcome = runCatching {
                VaultUtils.removeDuress(ownerKey, owner, VaultUtils.baseDir(this))
                app.vaultPayload = app.vaultPayload?.copy(duressVault = null, duressScope = null)
            }
            runOnUiThread {
                busy = false
                progress.dismiss()
                if (isFinishing || isDestroyed) {
                    return@runOnUiThread
                }
                showDuressState()
                outcome.exceptionOrNull()?.let { Log.e(TAG, "Removing the duress password failed", it) }
                Toast.makeText(
                    this,
                    if (outcome.isSuccess) R.string.duress_removed else R.string.duress_failed,
                    Toast.LENGTH_LONG
                ).show()
            }
        }.start()
    }

    private fun showLocation(location: VaultLocation) {
        binding.locationGroup.check(
            if (location == VaultLocation.SHARED) R.id.locationShared else R.id.locationPrivate
        )
    }

    private fun confirmMove(target: VaultLocation) {
        val from = VaultStorage.baseDir(this, VaultStorage.location(this))
        val to = VaultStorage.baseDir(this, target)
        val count = VaultStorage.filesUnder(from).size
        val destination = getString(
            if (target == VaultLocation.SHARED) R.string.settings_location_shared
            else R.string.settings_location_private
        )

        AlertDialog.Builder(this)
            .setTitle(R.string.move_vault_title)
            .setMessage(resources.getQuantityString(R.plurals.move_vault_message, count, count, destination))
            .setNegativeButton(R.string.cancel) { _, _ -> showLocation(VaultStorage.location(this)) }
            .setOnCancelListener { showLocation(VaultStorage.location(this)) }
            .setPositiveButton(R.string.move_vault_action) { _, _ -> runMove(from, to, target) }
            .show()
    }

    private fun runMove(from: java.io.File, to: java.io.File, target: VaultLocation) {
        val app = application as AESCameraApplication
        val progress = ProgressDialogController(this, R.string.moving_vault, cancellable = true)
        busy = true
        progress.show()

        Thread {
            val outcome = runCatching {
                VaultStorage.migrate(from, to, { progress.cancelled }) { done, total ->
                    runOnUiThread { progress.progress(done, total) }
                }
                prefs.vaultLocation = target
                // The vault path changed: point the app at the moved directory.
                app.relocateVault()
            }
            runOnUiThread {
                busy = false
                progress.dismiss()
                if (isFinishing || isDestroyed) {
                    return@runOnUiThread
                }
                showLocation(VaultStorage.location(this))
                val message = outcome.fold(
                    onSuccess = { R.string.move_vault_done },
                    onFailure = { error ->
                        if (error is MigrationCancelledException) {
                            R.string.move_vault_cancelled
                        } else {
                            Log.e(TAG, "Vault move failed: ${error.message}", error)
                            R.string.move_vault_failed
                        }
                    }
                )
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    private fun runExport(treeUri: Uri) {
        val app = application as AESCameraApplication
        val files = app.outputDirectory?.let { VaultExporter.backupFiles(it) } ?: emptyList()
        if (files.isEmpty()) {
            Toast.makeText(this, R.string.export_nothing, Toast.LENGTH_SHORT).show()
            return
        }
        val progress = ProgressDialogController(this, R.string.exporting, cancellable = true)
        busy = true
        progress.show()

        Thread {
            val outcome = runCatching {
                VaultExporter.export(this, treeUri, files, { progress.cancelled }) { done, total ->
                    runOnUiThread { progress.progress(done, total) }
                }
            }
            runOnUiThread {
                busy = false
                progress.dismiss()
                if (isFinishing || isDestroyed) {
                    return@runOnUiThread
                }
                val message = outcome.fold(
                    onSuccess = { summary -> exportMessage(summary) },
                    onFailure = { error ->
                        if (error is MigrationCancelledException) {
                            getString(R.string.export_cancelled)
                        } else {
                            Log.e(TAG, "Export failed: ${error.message}", error)
                            getString(R.string.export_failed)
                        }
                    }
                )
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    private fun runRestore(uris: List<Uri>) {
        val app = application as AESCameraApplication
        if (app.key == null) {
            return
        }
        val progress = ProgressDialogController(this, R.string.restoring, cancellable = true)
        busy = true
        progress.show()

        Thread {
            val outcome = runCatching {
                VaultExporter.restore(app, uris, { progress.cancelled }) { done, total ->
                    runOnUiThread { progress.progress(done, total) }
                }
            }
            runOnUiThread {
                busy = false
                progress.dismiss()
                if (isFinishing || isDestroyed) {
                    return@runOnUiThread
                }
                val message = outcome.fold(
                    onSuccess = { summary ->
                        resources.getQuantityString(
                            R.plurals.restore_done, summary.restored,
                            summary.restored, summary.wrongPassword, summary.skipped
                        )
                    },
                    onFailure = { error ->
                        if (error is MigrationCancelledException) {
                            getString(R.string.restore_cancelled)
                        } else {
                            Log.e(TAG, "Restore failed: ${error.message}", error)
                            getString(R.string.restore_failed)
                        }
                    }
                )
                AlertDialog.Builder(this)
                    .setTitle(R.string.settings_restore)
                    .setMessage(message)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
        }.start()
    }

    private fun exportMessage(summary: VaultExporter.ExportSummary): String =
        if (summary.failed == 0) {
            resources.getQuantityString(R.plurals.export_done, summary.exported, summary.exported)
        } else {
            resources.getQuantityString(
                R.plurals.export_partial, summary.exported, summary.exported, summary.failed
            )
        }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return if (item.itemId == android.R.id.home) {
            onBackPressedDispatcher.onBackPressed()
            true
        } else {
            super.onOptionsItemSelected(item)
        }
    }

    companion object {
        private const val TAG = "SettingsActivity"
    }
}
