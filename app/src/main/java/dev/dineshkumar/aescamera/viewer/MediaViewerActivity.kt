package dev.dineshkumar.aescamera.viewer

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.viewpager2.widget.ViewPager2
import dev.dineshkumar.aescamera.AESCameraApplication
import dev.dineshkumar.aescamera.ProtectedBaseActivity
import dev.dineshkumar.aescamera.R
import dev.dineshkumar.aescamera.databinding.ActivityMediaViewerBinding
import dev.dineshkumar.aescamera.utils.MediaNames
import dev.dineshkumar.aescamera.utils.VaultImportResult
import dev.dineshkumar.aescamera.utils.VaultImporter
import java.io.File
import dev.dineshkumar.aescamera.utils.useLightSystemBarIcons

/**
 * The vault's media viewer: one horizontal pager over every photo and video, newest
 * first, so swiping moves through the whole gallery regardless of type. Pages are
 * [ImagePageFragment] and [VideoPageFragment].
 *
 * Two modes, both fed only by this app (the activity is not exported):
 * - Vault: [EXTRA_DIR] + [EXTRA_NAME]; the activity lists the directory itself, so the
 *   Intent stays small however large the vault is.
 * - Imported: [EXTRA_IMPORTED_PATHS], files another app shared with us, already staged
 *   in our cache by ImportActivity. "Add to Vault" copies the current one into the vault.
 */
class MediaViewerActivity : ProtectedBaseActivity() {

    private lateinit var binding: ActivityMediaViewerBinding
    private var files: List<File> = emptyList()
    private var imported = false
    private var menu: Menu? = null
    private val addedToVault = HashSet<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMediaViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        useLightSystemBarIcons()
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        savedInstanceState?.getStringArrayList(STATE_ADDED)?.let { addedToVault.addAll(it) }

        val importedPaths = intent.getStringArrayListExtra(EXTRA_IMPORTED_PATHS)
        val startIndex: Int
        if (importedPaths != null) {
            imported = true
            files = importedPaths.map { File(it) }.filter { it.isFile }
            startIndex = 0
        } else {
            val dir = intent.getStringExtra(EXTRA_DIR)?.let { File(it) }
            val name = intent.getStringExtra(EXTRA_NAME)
            files = dir?.let { listMedia(it) } ?: emptyList()
            startIndex = files.indexOfFirst { it.name == name }.coerceAtLeast(0)
        }

        if (files.isEmpty()) {
            Toast.makeText(this, R.string.error_no_file_selected, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        binding.pager.adapter = MediaPagerAdapter(this, files, imported)
        binding.pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updateTitle(position)
            }
        })
        binding.pager.setCurrentItem(startIndex, false)
        updateTitle(startIndex)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putStringArrayList(STATE_ADDED, ArrayList(addedToVault))
    }

    private fun updateTitle(position: Int) {
        val file = files.getOrNull(position) ?: return
        supportActionBar?.title = MediaNames.viewerTitle(this, file)
        supportActionBar?.subtitle = if (files.size > 1) {
            getString(R.string.viewer_position, position + 1, files.size)
        } else {
            null
        }
        menu?.findItem(R.id.action_add_to_vault)?.isVisible =
            imported && file.absolutePath !in addedToVault
    }

    private fun addCurrentToVault() {
        val app = application as AESCameraApplication
        val file = files.getOrNull(binding.pager.currentItem) ?: return
        if (app.key == null) {
            return
        }
        val item = menu?.findItem(R.id.action_add_to_vault)
        item?.isEnabled = false

        Thread {
            val result = VaultImporter.importFile(app, file, file.name)
            runOnUiThread {
                if (isFinishing || isDestroyed) {
                    return@runOnUiThread
                }
                item?.isEnabled = true
                val message = when (result) {
                    is VaultImportResult.Success -> {
                        addedToVault.add(file.absolutePath)
                        updateTitle(binding.pager.currentItem)
                        R.string.file_added_to_vault_message
                    }

                    VaultImportResult.WrongPassword -> R.string.vault_import_wrong_password
                    is VaultImportResult.Failed -> R.string.vault_import_failed
                }
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
            }
        }.start()
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        super.onCreateOptionsMenu(menu)
        menuInflater.inflate(R.menu.file_viewer_menu, menu)
        this.menu = menu
        updateTitle(binding.pager.currentItem)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                onBackPressedDispatcher.onBackPressed()
                true
            }

            R.id.action_lock -> {
                (application as AESCameraApplication).key = null
                true
            }

            R.id.action_add_to_vault -> {
                addCurrentToVault()
                true
            }

            else -> super.onOptionsItemSelected(item)
        }
    }

    companion object {
        private const val STATE_ADDED = "added_to_vault"
        const val EXTRA_DIR = "dir"
        const val EXTRA_NAME = "name"

        /** Files staged by ImportActivity (absolute paths inside our cache). */
        const val EXTRA_IMPORTED_PATHS = "imported_paths"

        /** The vault's media, newest first — the same order as the gallery grid. */
        fun listMedia(dir: File): List<File> =
            dir.listFiles()
                ?.filter {
                    it.isFile && !it.name.startsWith(".") &&
                            (it.extension == "enc" || it.extension == "encv")
                }
                ?.sortedByDescending { MediaNames.captureTime(it) }
                ?: emptyList()

        fun intentFor(context: Context, file: File): Intent =
            Intent(context, MediaViewerActivity::class.java)
                .putExtra(EXTRA_DIR, file.parentFile?.absolutePath)
                .putExtra(EXTRA_NAME, file.name)

        fun intentForImported(context: Context, paths: ArrayList<String>): Intent =
            Intent(context, MediaViewerActivity::class.java)
                .putStringArrayListExtra(EXTRA_IMPORTED_PATHS, paths)
    }
}
