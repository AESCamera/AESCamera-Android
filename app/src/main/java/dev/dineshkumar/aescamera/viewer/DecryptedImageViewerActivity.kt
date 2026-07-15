package dev.dineshkumar.aescamera.viewer

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Build.VERSION
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import dev.dineshkumar.aescamera.AESCameraApplication
import dev.dineshkumar.aescamera.ProtectedBaseActivity
import dev.dineshkumar.aescamera.R
import dev.dineshkumar.aescamera.databinding.ActivityDecryptedImageViewerBinding
import dev.dineshkumar.aescamera.utils.CommonUtils
import dev.dineshkumar.aescamera.utils.EncryptionUtils
import dev.dineshkumar.aescamera.utils.ImageUtils
import dev.dineshkumar.aescamera.utils.ThumbnailUtils
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class DecryptedImageViewerActivity : ProtectedBaseActivity() {

    private lateinit var binding: ActivityDecryptedImageViewerBinding
    private var fileUri: Uri? = null
    private var fileName: String? = null;
    private var menu: Menu? = null
    private var uris: ArrayList<Uri>? = null
    private var currentIndex: Int = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityDecryptedImageViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.decrypted_image_viewer_title)

        fileUri = intent.data;

        if (fileUri == null) {
            fileUri = if (VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM)
            } else {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            }
        }

        val fileUri = this.fileUri

        if (fileUri != null) {
            fileName = getFileName(fileUri)
            supportActionBar?.title = fileName ?: getString(R.string.decrypted_image_viewer_title)

            uris = if (intent.hasExtra("uris")) {
                if (VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableArrayListExtra("uris")
                } else {
                    intent.getParcelableArrayListExtra("uris", Uri::class.java)
                }
            } else {
                null
            }

            if (uris != null) {
                currentIndex = uris!!.indexOf(fileUri)
            }

            loadDecryptedImage()
        } else {
            Toast.makeText(this, getString(R.string.error_no_file_selected), Toast.LENGTH_SHORT)
                .show()
            finish()
        }

        updateButtons()

        (application as AESCameraApplication).keyLiveData.observe(this) {
            loadDecryptedImage()
        }

        binding.decryptedImageViewResetKey.setOnClickListener {
            (application as AESCameraApplication).key = null
        }
        binding.decryptedImageViewNext.setOnClickListener {
            loadNextImage()
        }
        binding.decryptedImageViewPrevious.setOnClickListener {
            loadPreviousImage()
        }
    }

    private fun getFileName(uri: Uri): String? {
        var result: String? = null
        if (uri.scheme == "content") {
            val cursor = contentResolver.query(uri, null, null, null, null)
            try {
                if (cursor != null && cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex != -1) {
                        result = cursor.getString(nameIndex)
                    }
                }
            } finally {
                cursor?.close()
            }
        }
        if (result == null) {
            result = uri.path
            val cut = result?.lastIndexOf('/')
            if (cut != -1) {
                result = result?.substring(cut!! + 1)
            }
        }
        return result
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
        val uris = this.uris

        if (uris == null) {
            binding.decryptedImageViewNext.visibility = View.GONE
            binding.decryptedImageViewPrevious.visibility = View.GONE
            return
        }

        binding.decryptedImageViewNext.visibility = View.VISIBLE
        binding.decryptedImageViewPrevious.visibility = View.VISIBLE

        binding.decryptedImageViewNext.isEnabled = currentIndex + 1 < uris.size
        binding.decryptedImageViewPrevious.isEnabled = currentIndex - 1 >= 0
    }

    private fun loadNextImage() {
        val uris = this.uris ?: return

        if (currentIndex + 1 >= uris.size) {
            return
        }

        currentIndex++

        fileUri = uris[currentIndex]
        fileName = getFileName(fileUri!!)
        supportActionBar?.title = fileName ?: getString(R.string.decrypted_image_viewer_title)

        loadDecryptedImage()

        updateButtons()
    }

    private fun loadPreviousImage() {
        val uris = this.uris ?: return

        if (currentIndex - 1 < 0) {
            return
        }

        currentIndex--

        fileUri = uris[currentIndex]
        fileName = getFileName(fileUri!!)
        supportActionBar?.title = fileName ?: getString(R.string.decrypted_image_viewer_title)

        loadDecryptedImage()

        updateButtons()
    }

    @OptIn(DelicateCoroutinesApi::class)
    private fun loadDecryptedImage() {
        val fileUri = this.fileUri;

        if (fileUri == null) {
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

                val inputStream = contentResolver.openInputStream(fileUri)

                if (inputStream == null) {
                    withContext(Dispatchers.Main) {
                        showError(getString(R.string.file_open_failed_message))
                    }
                    return@launch
                }

                val encryptedBytes = inputStream.readBytes()
                inputStream.close()

                val decryptedBytes = runCatching {
                    EncryptionUtils.decrypt(encryptedBytes, key)
                }.getOrElse {
                    Log.e(TAG, "Error: ${it.message}", it)
                    withContext(Dispatchers.Main) {
                        showError(getString(R.string.image_decryption_failed_message), true)
                    }
                    return@launch
                }

                if (fileUri.authority == "${application.packageName}.provider") {
                    try {
                        ThumbnailUtils.createImageThumbnail(
                            app,
                            fileUri.lastPathSegment ?: CommonUtils.getImageFileName(),
                            decryptedBytes
                        )
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

                    if (fileUri.authority != "${application.packageName}.provider") {
                        menu?.findItem(R.id.action_add_to_vault)?.isVisible = true
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@DecryptedImageViewerActivity, "Error: ${e.message}", Toast.LENGTH_LONG
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
        val key = app.key

        if (key == null) {
            showError(getString(R.string.enter_key_message))
            return
        }

        val fileUri = this.fileUri

        if (fileUri == null) {
            showError(getString(R.string.error_no_file_selected))
            return
        }

        val outputDirectory = app.outputDirectory ?: return

        var fileName = this.fileName ?: CommonUtils.getImageFileName()

        if (!fileName.endsWith(".enc")) {
            fileName = "${fileName}.enc"
        }

        outputDirectory.mkdirs()
        val outputFile = File(outputDirectory, fileName)

        // Copy the (already encrypted) file into the vault.
        contentResolver.openInputStream(fileUri)?.use { inputStream ->
            outputFile.outputStream().use { outputStream ->
                inputStream.copyTo(outputStream)
            }
        }

        // The vault copy is ciphertext; the thumbnail needs the decrypted image.
        try {
            val decryptedBytes = EncryptionUtils.decrypt(outputFile.readBytes(), key)
            ThumbnailUtils.createImageThumbnail(app, fileName, decryptedBytes)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create thumbnail for imported file! ${e.message}", e)
        }

        Toast.makeText(this, getString(R.string.file_added_to_vault_message), Toast.LENGTH_SHORT)
            .show()
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
    }
}