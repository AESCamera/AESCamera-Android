package dev.dineshkumar.aescamera.viewer

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import dev.dineshkumar.aescamera.AESCameraApplication
import dev.dineshkumar.aescamera.R
import dev.dineshkumar.aescamera.databinding.FragmentImagePageBinding
import dev.dineshkumar.aescamera.utils.EncryptionUtils
import dev.dineshkumar.aescamera.utils.ImageUtils
import dev.dineshkumar.aescamera.utils.ThumbnailUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.lifecycle.lifecycleScope
import java.io.File

/** A photo page of [MediaViewerActivity]: decrypts into memory, shows it zoomable. */
class ImagePageFragment : Fragment() {

    private var _binding: FragmentImagePageBinding? = null
    private val binding get() = _binding!!

    private lateinit var file: File
    private var imported = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        file = File(requireArguments().getString(ARG_PATH)!!)
        imported = requireArguments().getBoolean(ARG_IMPORTED)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentImagePageBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.zoomHost.isZoomed = { binding.image.zoom > 1.01f }
        binding.resetKey.setOnClickListener {
            (requireActivity().application as AESCameraApplication).key = null
        }
        binding.image.contentDescription = getString(R.string.photo_content_description)

        (requireActivity().application as AESCameraApplication).keyLiveData
            .observe(viewLifecycleOwner) { load() }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun showError(message: String, showResetKey: Boolean = false) {
        val b = _binding ?: return
        b.error.text = message
        b.error.visibility = View.VISIBLE
        b.resetKey.visibility = if (showResetKey) View.VISIBLE else View.GONE
        b.image.setImageResource(R.drawable.ic_blank)
        b.progress.visibility = View.GONE
    }

    private fun load() {
        val b = _binding ?: return
        val app = requireActivity().application as AESCameraApplication
        val key = app.key
        if (key == null) {
            // The activity is showing the password prompt; the image stays blank.
            b.image.setImageResource(R.drawable.ic_blank)
            b.error.visibility = View.GONE
            b.resetKey.visibility = View.GONE
            return
        }

        b.error.visibility = View.GONE
        b.resetKey.visibility = View.GONE
        b.progress.visibility = View.VISIBLE

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val result = runCatching {
                val encrypted = file.readBytes()
                val decrypted = EncryptionUtils.decryptImage(encrypted, key)
                if (!imported) {
                    // Vault photos without a thumbnail (older captures) get one on first view.
                    runCatching {
                        ThumbnailUtils.createImageThumbnail(app, file.name, decrypted, expectedKey = key)
                    }
                }
                // Bound the decode: a full-resolution 48 MP photo is ~190 MB of
                // ARGB, and neighbouring pages decode too. Twice the screen's long
                // side keeps zoom crisp without that.
                val metrics = resources.displayMetrics
                val longSide = maxOf(metrics.widthPixels, metrics.heightPixels)
                ImageUtils.decodeOriented(decrypted, 2 * longSide)
            }
            withContext(Dispatchers.Main) {
                val bound = _binding ?: return@withContext
                bound.progress.visibility = View.GONE
                result.fold(
                    onSuccess = { bitmap ->
                        bound.image.setImageBitmap(bitmap)
                        bound.image.zoomTo(1f, false)
                    },
                    onFailure = { error ->
                        Log.e(TAG, "Could not show ${file.name}: ${error.message}")
                        if (error is javax.crypto.AEADBadTagException ||
                            error is javax.crypto.BadPaddingException
                        ) {
                            showError(getString(R.string.image_decryption_failed_message), true)
                        } else {
                            showError(getString(R.string.image_decode_failed_message))
                        }
                    }
                )
            }
        }
    }

    companion object {
        private const val TAG = "ImagePage"
        private const val ARG_PATH = "path"
        private const val ARG_IMPORTED = "imported"

        fun newInstance(file: File, imported: Boolean) = ImagePageFragment().apply {
            arguments = Bundle().apply {
                putString(ARG_PATH, file.absolutePath)
                putBoolean(ARG_IMPORTED, imported)
            }
        }
    }
}
