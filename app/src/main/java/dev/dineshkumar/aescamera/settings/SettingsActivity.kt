package dev.dineshkumar.aescamera.settings

import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.MenuItem
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import dev.dineshkumar.aescamera.AESCameraApplication
import dev.dineshkumar.aescamera.ProtectedBaseActivity
import dev.dineshkumar.aescamera.R
import dev.dineshkumar.aescamera.databinding.ActivitySettingsBinding
import dev.dineshkumar.aescamera.utils.AppPrefs
import dev.dineshkumar.aescamera.utils.MigrationCancelledException
import dev.dineshkumar.aescamera.utils.ProgressDialogController
import dev.dineshkumar.aescamera.utils.VaultExporter
import dev.dineshkumar.aescamera.utils.VaultLocation
import dev.dineshkumar.aescamera.utils.VaultStorage
import dev.dineshkumar.aescamera.utils.actionBarSize
import dev.dineshkumar.aescamera.utils.applySystemBarInsetsAsPadding

/**
 * Vault location (private app storage or shared storage, moved on change), backup and
 * restore through the system file picker, and the rapid capture toggle.
 */
class SettingsActivity : ProtectedBaseActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var prefs: AppPrefs
    private var busy = false

    private val exportLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            uri?.let { runExport(it) }
        }

    private val restoreLauncher =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (!uris.isNullOrEmpty()) {
                runRestore(uris)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = AppPrefs(this)

        supportActionBar?.apply {
            title = getString(R.string.settings_title)
            setDisplayHomeAsUpEnabled(true)
        }
        binding.settingsScroll.applySystemBarInsetsAsPadding(extraTop = actionBarSize())

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
