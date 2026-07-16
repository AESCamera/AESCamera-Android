package dev.dineshkumar.aescamera.list

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.database.getStringOrNull
import androidx.recyclerview.widget.GridLayoutManager
import dev.dineshkumar.aescamera.AESCameraApplication
import dev.dineshkumar.aescamera.ProtectedBaseActivity
import dev.dineshkumar.aescamera.R
import dev.dineshkumar.aescamera.databinding.ActivityFilesListBinding
import dev.dineshkumar.aescamera.viewer.DecryptedImageViewerActivity
import dev.dineshkumar.aescamera.viewer.DecryptedVideoPlayerActivity
import java.io.File


class FilesListActivity : ProtectedBaseActivity() {
    private lateinit var binding: ActivityFilesListBinding
    private lateinit var adapter: FileAdapter
    private var optionsMenu: Menu? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityFilesListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupActionBar()
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
            layoutManager = GridLayoutManager(this@FilesListActivity, 3)
            adapter = this@FilesListActivity.adapter
        }
    }

    private fun onItemClick(file: File, files: ArrayList<File>) {
        if (file.extension == "encv") {
            val intent = Intent(this, DecryptedVideoPlayerActivity::class.java)
            intent.putExtra(DecryptedVideoPlayerActivity.EXTRA_PATH, file.absolutePath)
            startActivity(intent)
            return
        }

        val intent = Intent(this, DecryptedImageViewerActivity::class.java)
        val uri = FileProvider.getUriForFile(this, application.packageName + ".provider", file)
        // Previous/next navigation in the image viewer only makes sense across images.
        val uris = ArrayList<Uri>(files.filter { it.extension == "enc" }.map { imageFile ->
            FileProvider.getUriForFile(
                this,
                application.packageName + ".provider",
                imageFile
            )
        })
        intent.data = uri
        intent.putParcelableArrayListExtra("uris", uris)
        startActivity(intent)
    }

    private fun onSelectionChanged(selectedCount: Int) {
        optionsMenu?.findItem(R.id.action_select_all)?.title =
            if (selectedCount == adapter.itemCount) getString(R.string.deselect_all) else
                getString(R.string.select_all)

        if (selectedCount > 0) {
            optionsMenu?.findItem(R.id.action_delete)?.isVisible = true
            optionsMenu?.findItem(R.id.action_share)?.isVisible = true
            optionsMenu?.findItem(R.id.action_share_whatsapp)?.isVisible = true
            supportActionBar?.title = getString(R.string.selected_count, selectedCount)
        } else {
            optionsMenu?.findItem(R.id.action_delete)?.isVisible = false
            optionsMenu?.findItem(R.id.action_share)?.isVisible = false
            optionsMenu?.findItem(R.id.action_share_whatsapp)?.isVisible = false
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

        adapter.key = app.key
        adapter.clearSelection()
        adapter.submitList(files)
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

    private fun getWhatsappContacts(): List<ContactItem> {
        val contacts = mutableListOf<ContactItem>()


        val sharedPref = getSharedPreferences("selected_contact", Context.MODE_PRIVATE)
        val lastSelectedContact = sharedPref.getString("number", null)

        val contentResolver = this.contentResolver
        val cursor = contentResolver.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(
                ContactsContract.Data.DISPLAY_NAME,
                ContactsContract.Data.DATA1,
                ContactsContract.CommonDataKinds.Phone.PHOTO_URI
            ),
            "mimetype=?",
            arrayOf("vnd.android.cursor.item/vnd.com.whatsapp.profile"),
            ContactsContract.Data.DISPLAY_NAME
        )

        if (cursor != null && cursor.count > 0) {
            while (cursor.moveToNext()) {
                val name = cursor.getString(0)
                val number = cursor.getString(1)
                val photoUri = cursor.getStringOrNull(2)
                val previouslySelected = number == lastSelectedContact

                contacts.add(
                    ContactItem(
                        name,
                        number,
                        photoUri,
                        previouslySelected
                    )
                )
            }
        }

        cursor?.close()

        return if (lastSelectedContact != null) {
            contacts.sortedBy {
                if (it.contactNo.equals(lastSelectedContact)) {
                    0
                } else {
                    1
                }
            }
        } else {
            contacts
        }
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
                deleteSelectedItems()
                true
            }

            R.id.action_share -> {
                shareSelectedItems()
                true
            }

            R.id.action_share_whatsapp -> {
                showSelectContactDialog()
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

    private fun showSelectContactDialog() {
        if (ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.READ_CONTACTS
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(this, "Please grant the permission to read contacts", Toast.LENGTH_SHORT)
                .show()
            requestPermissions(arrayOf(android.Manifest.permission.READ_CONTACTS), 1)
            return
        }


        val contacts = getWhatsappContacts()

        val dialog = object : ContactPickerDialog(
            this,
            contacts,
            onContactSelected = { contact ->
                shareSelectedItemsOnWhatsApp(contact)

                // Save the selected contact to the shared preferences
                val sharedPref = getSharedPreferences("selected_contact", Context.MODE_PRIVATE)
                with(sharedPref.edit()) {
                    putString("number", contact.contactNo)
                    apply()
                }
            }
        ) {}

        dialog.show()
    }

    private fun shareSelectedItemsOnWhatsApp(contact: ContactItem) {
        val selectedFiles = adapter.getSelectedItems()

        val intent = Intent()
        intent.setAction(Intent.ACTION_SEND_MULTIPLE)
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        intent.setType("application/octet-stream")
        intent.setComponent(
            ComponentName(
                "com.whatsapp",
                "com.whatsapp.contact.picker.ContactPicker"
            )
        )
        intent.putExtra(
            "jid",
            contact.contactNo
        );

        val files = ArrayList<Uri>()

        for (file in selectedFiles) {
            val uri = FileProvider.getUriForFile(this, application.packageName + ".provider", file)
            files.add(uri)
        }

        intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, files)
        startActivity(intent)

        adapter.clearSelection()
    }
}