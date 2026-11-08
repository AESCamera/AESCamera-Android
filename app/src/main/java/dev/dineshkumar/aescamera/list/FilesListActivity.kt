package dev.dineshkumar.aescamera.list

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.GridLayoutManager
import dev.dineshkumar.aescamera.AESCameraApplication
import dev.dineshkumar.aescamera.ProtectedBaseActivity
import dev.dineshkumar.aescamera.R
import dev.dineshkumar.aescamera.databinding.ActivityFilesListBinding
import dev.dineshkumar.aescamera.settings.SettingsActivity
import dev.dineshkumar.aescamera.utils.MediaNames
import dev.dineshkumar.aescamera.utils.MigrationCancelledException
import dev.dineshkumar.aescamera.utils.ProgressDialogController
import dev.dineshkumar.aescamera.utils.VaultExporter
import dev.dineshkumar.aescamera.utils.applySystemBarInsetsAsPadding
import dev.dineshkumar.aescamera.viewer.MediaViewerActivity
import java.io.File

/**
 * The vault gallery: a grid of square thumbnails grouped by day, newest first. Tap
 * opens the viewer; long-press starts a selection, which share, export and delete act
 * on. Back leaves selection mode before leaving the screen.
 */
class FilesListActivity : ProtectedBaseActivity() {
    private lateinit var binding: ActivityFilesListBinding
    private lateinit var adapter: FileAdapter
    private var optionsMenu: Menu? = null
    private var pendingSelection: ArrayList<String>? = null

    // The selection being exported, taken when the picker opens: more than a few seconds
    // in the picker locks the vault (see AESCameraApplication), which clears the grid.
    // The export then waits for the vault to be unlocked again.
    private var exportFiles: List<File> = emptyList()
    private var pendingExport: Uri? = null

    private val exportLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri == null) return@registerForActivityResult
            if ((application as AESCameraApplication).key == null) pendingExport = uri
            else exportSelectedItems(uri)
        }

    private val leaveSelectionOnBack = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            adapter.clearSelection()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityFilesListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupActionBar()
        // Edge-to-edge (targetSdk 36): the grid is laid out behind the (opaque) action
        // bar and under the navigation bar. AppCompat already adds the action bar's
        // height to the top inset it dispatches. The top inset is a margin so rows are
        // clipped at the action bar (not drawn under the transparent status bar), while
        // clipToPadding=false lets them scroll behind the navigation bar.
        binding.fileListRecyclerView.applySystemBarInsetsAsPadding(topAsMargin = true)
        binding.fileListRecyclerView.setHasFixedSize(true)
        onBackPressedDispatcher.addCallback(this, leaveSelectionOnBack)

        pendingSelection = savedInstanceState?.getStringArrayList(STATE_SELECTION)
        setupRecyclerView()

        val app = application as AESCameraApplication
        app.keyLiveData.observe(this) {
            // The vault directory follows the key, and the adapter's thumbnail loader
            // captures directory + key at construction — rebuild it on key changes.
            adapter.destroy()
            setupRecyclerView()
            refreshFileList()

            optionsMenu?.findItem(R.id.action_lock)?.isVisible = it != null
            if (it != null) pendingExport?.let { uri -> pendingExport = null; exportSelectedItems(uri) }
        }
        refreshFileList()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putStringArrayList(STATE_SELECTION, adapter.selectedPaths())
    }

    private fun setupActionBar() {
        supportActionBar?.apply {
            title = getString(R.string.files_list_title)
            setDisplayHomeAsUpEnabled(true)
        }
    }

    private fun setupRecyclerView() {
        val app = application as AESCameraApplication
        adapter = FileAdapter(app, onOpen = { file -> openFile(file) }) { selected, total ->
            onSelectionChanged(selected, total)
        }
        val spanCount = resources.getInteger(R.integer.file_grid_columns)
        val layoutManager = GridLayoutManager(this, spanCount)
        layoutManager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int =
                if (adapter.isHeader(position)) spanCount else 1
        }
        binding.fileListRecyclerView.layoutManager = layoutManager
        binding.fileListRecyclerView.adapter = adapter
    }

    private fun openFile(file: File) {
        // The viewer lists the vault itself and pages through photos and videos in one
        // swipeable list; only the tapped file travels in the Intent.
        startActivity(MediaViewerActivity.intentFor(this, file))
    }

    private fun onSelectionChanged(selectedCount: Int, total: Int) {
        val menu = optionsMenu
        val inSelection = selectedCount > 0
        leaveSelectionOnBack.isEnabled = inSelection

        menu?.findItem(R.id.action_select_all)?.title =
            if (selectedCount == total && total > 0) getString(R.string.deselect_all)
            else getString(R.string.select_all)
        menu?.findItem(R.id.action_delete)?.isVisible = inSelection
        menu?.findItem(R.id.action_share)?.isVisible = inSelection
        menu?.findItem(R.id.action_export)?.isVisible = inSelection
        menu?.findItem(R.id.action_lock)?.isVisible = !inSelection
        menu?.findItem(R.id.action_settings)?.isVisible = !inSelection

        supportActionBar?.title = if (inSelection) {
            resources.getQuantityString(R.plurals.selected_count, selectedCount, selectedCount)
        } else {
            getString(R.string.files_list_title)
        }
    }

    private fun refreshFileList() {
        val app = application as AESCameraApplication
        val files = app.outputDirectory?.let { MediaViewerActivity.listMedia(it) } ?: emptyList()

        optionsMenu?.findItem(R.id.action_select_all)?.isVisible = files.isNotEmpty()
        binding.emptyView.visibility = if (files.isEmpty()) View.VISIBLE else View.GONE

        adapter.submitList(GalleryItem.build(files) { MediaNames.dayLabel(this, it) })
        pendingSelection?.let { saved ->
            pendingSelection = null
            if (saved.isNotEmpty()) {
                binding.fileListRecyclerView.post { adapter.restoreSelection(saved) }
            }
        }
    }

    private fun confirmDeleteSelectedItems() {
        val count = adapter.selectedFiles().size
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
        val selectedFiles = adapter.selectedFiles()
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

        adapter.clearSelection()
        refreshFileList()
    }

    private fun shareSelectedItems() {
        val selectedFiles = adapter.selectedFiles()
        if (selectedFiles.isEmpty()) {
            return
        }

        val intent = Intent(Intent.ACTION_SEND_MULTIPLE)
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        intent.type = "application/octet-stream"

        val files = ArrayList<Uri>()
        for (file in selectedFiles) {
            files.add(FileProvider.getUriForFile(this, application.packageName + ".provider", file))
        }
        intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, files)

        // A chooser every time: the recipient is a decision, not a remembered default.
        startActivity(Intent.createChooser(intent, getString(R.string.share_encrypted_files)))

        adapter.clearSelection()
    }

    private fun exportSelectedItems(treeUri: Uri) {
        // Only files of the vault that is open now: another password may have unlocked.
        val vault = (application as AESCameraApplication).outputDirectory
        val files = exportFiles.filter { it.parentFile == vault && it.exists() }
        exportFiles = emptyList()
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

        optionsMenu?.findItem(R.id.action_select_all)?.isVisible = adapter.mediaCount != 0
        onSelectionChanged(adapter.selectedFiles().size, adapter.mediaCount)

        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                onBackPressedDispatcher.onBackPressed()
                true
            }

            R.id.action_select_all -> {
                if (adapter.selectedFiles().size == adapter.mediaCount) {
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
                exportFiles = adapter.selectedFiles()
                if (exportFiles.isNotEmpty()) {
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

    override fun onDestroy() {
        super.onDestroy()
        adapter.destroy()
    }

    companion object {
        private const val STATE_SELECTION = "selection"
    }
}
