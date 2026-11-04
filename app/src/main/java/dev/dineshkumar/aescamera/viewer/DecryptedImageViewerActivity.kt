package dev.dineshkumar.aescamera.viewer

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import dev.dineshkumar.aescamera.AESCameraApplication
import dev.dineshkumar.aescamera.ProtectedBaseActivity
import dev.dineshkumar.aescamera.R
import dev.dineshkumar.aescamera.databinding.ActivityDecryptedImageViewerBinding
import dev.dineshkumar.aescamera.utils.EncryptionUtils
import dev.dineshkumar.aescamera.utils.ImageUtils
import dev.dineshkumar.aescamera.utils.ThumbnailUtils
import dev.dineshkumar.aescamera.utils.VaultImportResult
import dev.dineshkumar.aescamera.utils.VaultImporter
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Shows a decrypted photo. Two modes, both fed only by this app (the activity is not
 * exported):
 *
 * - Gallery: [GalleryNav.EXTRA_PATHS] + [GalleryNav.EXTRA_INDEX], the whole vault in
 *   order, previous/next handing off to the player at video items.
 * - Imported: [EXTRA_IMPORTED_PATHS], files another app shared with us, already staged in
 *   our cache by ImportActivity. "Add to Vault" copies the ciphertext into the vault.
 */
class DecryptedImageViewerActivity : ProtectedBaseActivity() {

    private lateinit var binding: ActivityDecryptedImageViewerBinding
    private var menu: Menu? = null

    private var paths: List<String> = emptyList()
    private var isImported = false
    private var currentIndex: Int = 0

    private val currentFile: File?
        get() = paths.getOrNull(currentIndex)?.let { File(it) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityDecryptedImageViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.decrypted_image_viewer_title)

        val galleryPaths = intent.getStringArrayListExtra(GalleryNav.EXTRA_PATHS)
        val importedPaths = intent.getStringArrayListExtra(EXTRA_IMPORTED_PATHS)
        val list = galleryPaths ?: importedPaths
        if (list.isNullOrEmpty()) {
            Toast.makeText(this, getString(R.string.error_no_file_selected), Toast.LENGTH_SHORT)
                .show()
            finish()
            return
        }
        paths = list
        isImported = galleryPaths == null
        currentIndex = intent.getIntExtra(GalleryNav.EXTRA_INDEX, 0).coerceIn(paths.indices)

        supportActionBar?.title = currentFile?.name
        loadDecryptedImage()
        updateButtons()

        (application as AESCameraApplication).keyLiveData.observe(this) {
            loadDecryptedImage()
        }

        binding.decryptedImageViewResetKey.setOnClickListener {
            (application as AESCameraApplication).key = null
        }
        binding.decryptedImageViewNext.setOnClickListener {
            navigate(1)
        }
        binding.decryptedImageViewPrevious.setOnClickListener {
            navigate(-1)
        }
    }

    private fun clearError() {
        binding.decryptedImageViewError.text = ""
        binding.decryptedImageViewError.visibility = View.GONE
        binding.decryptedImageViewResetKey.visibility = View.GONE

        menu?.findItem(R.id.action_add_to_vault)?.isVisible = false
    }

    private fun showError(message: String, showResetKey: Boolean = false) {
        binding.decryptedImageViewError.text = message
        binding.decryptedImageViewError.visibility = View.VISIBLE
        binding.decryptedImageViewResetKey.visibility =
            if (showResetKey) View.VISIBLE else View.GONE
        binding.decryptedImageView.setImageResource(R.drawable.ic_blank)

        menu?.findItem(R.id.action_add_to_vault)?.isVisible = false
    }

    private fun setLoading(loading: Boolean) {
        binding.decryptedImageViewProgress.visibility = if (loading) View.VISIBLE else View.GONE

        if (!loading) {
            binding.decryptedImageView.zoomTo(1f, false)
            binding.decryptedImageView.panTo(0f, 0f, false)
        }
    }

    private fun updateButtons() {
        if (paths.size <= 1) {
            binding.decryptedImageViewNext.visibility = View.GONE
            binding.decryptedImageViewPrevious.visibility = View.GONE
            return
        }

        setNavButton(binding.decryptedImageViewPrevious, currentIndex > 0)
        setNavButton(binding.decryptedImageViewNext, currentIndex < paths.size - 1)
    }

    /** Dim-and-disable at the ends of the list, matching the player's skip controls. */
    private fun setNavButton(button: View, enabled: Boolean) {
        button.visibility = View.VISIBLE
        button.isEnabled = enabled
        button.alpha = if (enabled) 1f else 0.3f
    }

    /**
     * Steps [delta] items through the list. An image loads here; a video hands off to
     * the player at that position, so previous/next feels continuous across both types.
     */
    private fun navigate(delta: Int) {
        val target = currentIndex + delta
        if (target !in paths.indices) {
            return
        }

        if (GalleryNav.isVideo(paths[target])) {
            if (isImported) {
                // Imported videos have no shared list; the player stays single-file and
                // Back returns here.
                startActivity(
                    Intent(this, DecryptedVideoPlayerActivity::class.java)
                        .putExtra(DecryptedVideoPlayerActivity.EXTRA_PATH, paths[target])
                        .putExtra(DecryptedVideoPlayerActivity.EXTRA_IMPORTED, true)
                )
            } else {
                startActivity(GalleryNav.intentFor(this, paths, target))
                finish()
            }
            return
        }

        currentIndex = target
        supportActionBar?.title = currentFile?.name
        loadDecryptedImage()
        updateButtons()
    }

    @OptIn(DelicateCoroutinesApi::class)
    private fun loadDecryptedImage() {
        val file = currentFile

        if (file == null) {
            showError(getString(R.string.error_no_file_selected))
            return
        }

        clearError()

        setLoading(true)

        GlobalScope.launch(Dispatchers.IO) {
            try {
                val app = application as AESCameraApplication
                val key = app.key

                if (key == null) {
                    withContext(Dispatchers.Main) {
                        showError(getString(R.string.enter_key_message))
                    }
                    return@launch
                }

                if (!file.isFile) {
                    withContext(Dispatchers.Main) {
                        showError(getString(R.string.file_open_failed_message))
                    }
                    return@launch
                }

                val encryptedBytes = file.readBytes()

                val decryptedBytes = runCatching {
                    EncryptionUtils.decryptImage(encryptedBytes, key)
                }.getOrElse {
                    Log.e(TAG, "Error: ${it.message}", it)
                    withContext(Dispatchers.Main) {
                        showError(getString(R.string.image_decryption_failed_message), true)
                    }
                    return@launch
                }

                // Vault files without a thumbnail (older captures) get one on first view.
                if (!isImported) {
                    try {
                        ThumbnailUtils.createImageThumbnail(app, file.name, decryptedBytes)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to create thumbnail for opened file! ${e.message}", e)
                    }
                }

                val bitmap = runCatching {
                    ImageUtils.decodeOriented(decryptedBytes)
                }.getOrElse {
                    Log.e(TAG, "Error: ${it.message}", it)
                    withContext(Dispatchers.Main) {
                        showError(getString(R.string.image_decode_failed_message))
                    }
                    return@launch
                }

                withContext(Dispatchers.Main) {
                    binding.decryptedImageView.setImageBitmap(bitmap)

                    if (isImported) {
                        menu?.findItem(R.id.action_add_to_vault)?.isVisible = true
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@DecryptedImageViewerActivity,
                        getString(R.string.file_open_failed_message),
                        Toast.LENGTH_LONG
                    ).show()
                    finish()
                }
            } finally {
                withContext(Dispatchers.Main) {
                    setLoading(false)
                }
            }
        }
    }

    private fun addToVault() {
        val app = application as AESCameraApplication
        val file = currentFile ?: return

        if (app.key == null) {
            showError(getString(R.string.enter_key_message))
            return
        }

        menu?.findItem(R.id.action_add_to_vault)?.isEnabled = false

        // Copy + decrypt-for-thumbnail: off the main thread.
        Thread {
            val result = VaultImporter.importFile(app, file, file.name)
            runOnUiThread {
                if (isFinishing || isDestroyed) {
                    return@runOnUiThread
                }
                val item = menu?.findItem(R.id.action_add_to_vault)
                item?.isEnabled = true
                val message = when (result) {
                    is VaultImportResult.Success -> {
                        item?.isVisible = false
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

        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                onBackPressedDispatcher.onBackPressed()
                true
            }

            R.id.action_lock -> {
                val app = application as AESCameraApplication
                app.key = null
                true
            }

            R.id.action_add_to_vault -> {
                addToVault()
                true
            }

            else -> super.onOptionsItemSelected(item)
        }
    }

    companion object {
        private const val TAG = "DecryptedImageViewer"

        /** Files staged by ImportActivity (absolute paths inside our cache). */
        const val EXTRA_IMPORTED_PATHS = "imported_paths"
    }
}
