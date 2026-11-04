package dev.dineshkumar.aescamera.list

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.GridLayoutManager
import dev.dineshkumar.aescamera.AESCameraApplication
import dev.dineshkumar.aescamera.ProtectedBaseActivity
import dev.dineshkumar.aescamera.R
import dev.dineshkumar.aescamera.databinding.ActivityFilesListBinding
import dev.dineshkumar.aescamera.settings.SettingsActivity
import dev.dineshkumar.aescamera.utils.MigrationCancelledException
import dev.dineshkumar.aescamera.utils.ProgressDialogController
import dev.dineshkumar.aescamera.utils.VaultExporter
import dev.dineshkumar.aescamera.utils.actionBarSize
import dev.dineshkumar.aescamera.utils.applySystemBarInsetsAsPadding
import dev.dineshkumar.aescamera.viewer.MediaViewerActivity
import java.io.File


class FilesListActivity : ProtectedBaseActivity() {
    private lateinit var binding: ActivityFilesListBinding
    private lateinit var adapter: FileAdapter
    private var optionsMenu: Menu? = null

    // Export the selected files (ciphertext) into a folder the user picks.
    private val exportLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            uri?.let { exportSelectedItems(it) }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityFilesListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupActionBar()
        // Edge-to-edge (targetSdk 36): the grid is laid out behind the (opaque) action
        // bar and under the navigation bar, so inset it past both. clipToPadding=false
        // lets rows scroll through the padded strips.
        binding.fileListRecyclerView.applySystemBarInsetsAsPadding(extraTop = actionBarSize())
        setupRecyclerView()

        val app = application as AESCameraApplication
        app.keyLiveData.observe(this) {
            // The vault directory follows the key, and the adapter's thumbnail loader
            // captures directory + key at construction — rebuild it on key changes.
            adapter.onDestroy()
            setupRecyclerView()
            refreshFileList()

            optionsMenu?.findItem(R.id.action_lock)?.isVisible = it != null
        }
        refreshFileList()
    }

    private fun setupActionBar() {
        supportActionBar?.apply {
            title = getString(R.string.files_list_title)
            setDisplayHomeAsUpEnabled(true)
        }
    }

    private fun setupRecyclerView() {
        val app = application as AESCameraApplication
        adapter = FileAdapter(this, app.thumbnailDirectory, app.key, onItemClick = { file, files ->
            run {
                onItemClick(file, files)
            }
        }, onSelectionChanged = { selectedCount ->
            onSelectionChanged(selectedCount)
        })
        binding.fileListRecyclerView.apply {
            layoutManager = GridLayoutManager(
                this@FilesListActivity,
                resources.getInteger(R.integer.file_grid_columns)
            )
            adapter = this@FilesListActivity.adapter
        }
    }

    private fun onItemClick(file: File, files: ArrayList<File>) {
        // The viewer lists the vault itself and pages through photos and videos in one
        // swipeable list; only the tapped file travels in the Intent.
        startActivity(MediaViewerActivity.intentFor(this, file))
    }

    private fun onSelectionChanged(selectedCount: Int) {
        optionsMenu?.findItem(R.id.action_select_all)?.title =
            if (selectedCount == adapter.itemCount) getString(R.string.deselect_all) else
                getString(R.string.select_all)

        if (selectedCount > 0) {
            optionsMenu?.findItem(R.id.action_delete)?.isVisible = true
            optionsMenu?.findItem(R.id.action_share)?.isVisible = true
            optionsMenu?.findItem(R.id.action_export)?.isVisible = true
            supportActionBar?.title =
                resources.getQuantityString(R.plurals.selected_count, selectedCount, selectedCount)
        } else {
            optionsMenu?.findItem(R.id.action_delete)?.isVisible = false
            optionsMenu?.findItem(R.id.action_share)?.isVisible = false
            optionsMenu?.findItem(R.id.action_export)?.isVisible = false
            supportActionBar?.title = getString(R.string.files_list_title)
        }
    }

    private fun refreshFileList() {
        val app = application as AESCameraApplication
        val files =
            (app.outputDirectory?.listFiles()
                ?.filter {
                    it.isFile && !it.name.startsWith(".")
                            && (it.extension == "enc" || it.extension == "encv")
                }
                ?: emptyList()).let { list ->
                list.sortedByDescending { it.lastModified() }
            }

        optionsMenu?.findItem(R.id.action_select_all)?.isVisible = files.isNotEmpty()

        binding.emptyView.visibility = if (files.isEmpty()) View.VISIBLE else View.GONE

        adapter.key = app.key
        adapter.clearSelection()
        adapter.submitList(files)
    }

    private fun confirmDeleteSelectedItems() {
        val count = adapter.getSelectedItemsCount()
        if (count == 0) {
            return
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.delete_confirm_title)
            .setMessage(resources.getQuantityString(R.plurals.delete_confirm_message, count, count))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ -> deleteSelectedItems() }
            .show()
    }

    private fun deleteSelectedItems() {
        val selectedFiles = adapter.getSelectedItems()
        val app = application as AESCameraApplication

        for (file in selectedFiles) {
            val thumbnailFile = File(app.thumbnailDirectory, file.name)
            if (thumbnailFile.exists()) {
                thumbnailFile.delete()
            }

            if (file.exists()) {
                file.delete()
            }
        }

        refreshFileList()
    }

    private fun shareSelectedItems() {
        val selectedFiles = adapter.getSelectedItems()

        val intent = Intent()
        intent.setAction(Intent.ACTION_SEND_MULTIPLE)
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        intent.setType("application/octet-stream")

        val files = ArrayList<Uri>()

        for (file in selectedFiles) {
            val uri = FileProvider.getUriForFile(this, application.packageName + ".provider", file)
            files.add(uri)
        }

        intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, files)
        startActivity(intent)

        adapter.clearSelection()
    }

    private fun exportSelectedItems(treeUri: Uri) {
        val files = adapter.getSelectedItems()
        if (files.isEmpty()) {
            return
        }
        val progress = ProgressDialogController(this, R.string.exporting, cancellable = true)
        progress.show()

        Thread {
            val outcome = runCatching {
                VaultExporter.export(this, treeUri, files, { progress.cancelled }) { done, total ->
                    runOnUiThread { progress.progress(done, total) }
                }
            }
            runOnUiThread {
                progress.dismiss()
                if (isFinishing || isDestroyed) {
                    return@runOnUiThread
                }
                val message = outcome.fold(
                    onSuccess = { summary ->
                        if (summary.failed == 0) {
                            resources.getQuantityString(
                                R.plurals.export_done, summary.exported, summary.exported
                            )
                        } else {
                            resources.getQuantityString(
                                R.plurals.export_partial, summary.exported,
                                summary.exported, summary.failed
                            )
                        }
                    },
                    onFailure = { error ->
                        if (error is MigrationCancelledException) getString(R.string.export_cancelled)
                        else getString(R.string.export_failed)
                    }
                )
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                adapter.clearSelection()
            }
        }.start()
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.file_list_menu, menu)
        optionsMenu = menu

        optionsMenu?.findItem(R.id.action_select_all)?.isVisible = adapter.itemCount != 0

        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                onBackPressedDispatcher.onBackPressed()
                true
            }

            R.id.action_select_all -> {
                if (adapter.getSelectedItems().size == adapter.itemCount) {
                    adapter.clearSelection()
                } else {
                    adapter.selectAll()
                }

                true
            }

            R.id.action_lock -> {
                val app = application as AESCameraApplication
                app.key = null
                true
            }

            R.id.action_delete -> {
                confirmDeleteSelectedItems()
                true
            }

            R.id.action_share -> {
                shareSelectedItems()
                true
            }

            R.id.action_export -> {
                if (adapter.getSelectedItemsCount() > 0) {
                    exportLauncher.launch(null)
                }
                true
            }

            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }

            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onPause() {
        super.onPause()
        adapter.onPause()
    }

    override fun onResume() {
        super.onResume()
        adapter.onResume()
    }

    override fun onDestroy() {
        super.onDestroy()
        adapter.onDestroy()
    }
}